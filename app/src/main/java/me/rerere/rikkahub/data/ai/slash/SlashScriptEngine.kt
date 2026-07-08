package me.rerere.rikkahub.data.ai.slash

import android.content.Context
import android.util.Log
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * QuickJS-based execution engine for slash command scripts.
 *
 * Each script must define:
 *   function handleSlash(args, context) { return { result: string, ... }; }
 *
 * The `context` object provides a TavernHelper-compatible API bridge:
 *   - context.chat.currentMessages   → List<UIMessage summary>
 *   - context.chat.send(message)     → queues a message to be sent
 *   - context.char.name              → current character name
 *   - context.user.name              → current user nickname
 *   - context.variables.get(path)    → get variable
 *   - context.variables.set(path, val) → set variable
 */
class SlashScriptEngine(
    private val settingsStore: SettingsStore,
    private val variableStore: ScriptVariableStore? = null,
) {
    companion object {
        private const val TAG = "SlashScriptEngine"
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SlashScriptEngine").apply { isDaemon = true }
    }

    private val cachedScriptRef = AtomicReference<String?>(null)
    @Volatile private var jsContext: QuickJSContext? = null

    /**
     * Execute a slash command against a script.
     *
     * @param source The JavaScript source (must define handleSlash(args, context))
     * @param args The arguments after the /command (as a single string)
     * @param context Bridge providing chat/character/variable access
     * @return Result with a map of { result, error, ... } or failure
     */
    suspend fun execute(
        source: String,
        args: String,
        context: SlashContext,
    ): Result<Map<String, String>> = withContext(Dispatchers.IO) {
        try {
            val ctx = getOrCreateContext(source)
            val contextJson = buildContextJson(context)
            val argsQuoted = escapeJson(args)

            // Inject values directly — no JSON.parse needed
            // __args is a plain string, __context is a JS object (JSON is valid JS)
            val script = """
                var __args = "$argsQuoted";
                var __context = $contextJson;
                var __result = { result: '', error: null };
                try {
                    if (typeof handleSlash !== 'function') {
                        __result.error = 'handleSlash(args, context) function not found';
                    } else {
                        var output = handleSlash(__args, __context);
                        if (typeof output === 'string') {
                            __result.result = output;
                        } else if (output && typeof output === 'object') {
                            __result = output;
                            if (typeof __result.result !== 'string') __result.result = String(__result.result || '');
                        }
                    }
                } catch(e) {
                    __result.error = e.message || String(e);
                }
                JSON.stringify(__result);
            """.trimIndent()

            val result = executor.submit<String> {
                try {
                    val jsResult = ctx.evaluate(script) as? String ?: "{}"
                    jsResult
                } catch (e: Exception) {
                    Log.e(TAG, "JS execution error", e)
                    """{"error":"${escapeJson(e.message ?: "Unknown error")}"}"""
                }
            }.get()

            parseExecutionResult(result)
        } catch (e: Exception) {
            Log.e(TAG, "execute failed", e)
            Result.failure(e)
        }
    }

    /**
     * Parse available commands from a script source.
     * Expects: function registerCommands() { return [{ command: 'name', description: '...' }]; }
     */
    fun extractCommands(source: String): List<SlashCommand> {
        val scriptName = "script_" + source.hashCode().toString(16).take(8)
        return try {
            val commandsJson = executor.submit<String> {
                try {
                    val ctx = QuickJSContext.create()
                    ctx.evaluate(source)
                    val result = ctx.evaluate("""
                        (function() {
                            if (typeof registerCommands !== 'function') return '[]';
                            var cmds = registerCommands();
                            return JSON.stringify(cmds || []);
                        })()
                    """.trimIndent())
                    val json = result as? String ?: "[]"
                    ctx.destroy()
                    json
                } catch (e: Exception) {
                    Log.w(TAG, "extractCommands failed", e)
                    "[]"
                }
            }.get()

            parseCommandList(commandsJson, scriptName)
        } catch (e: Exception) {
            Log.e(TAG, "extractCommands outer error", e)
            emptyList()
        }
    }

    fun destroy() {
        try { jsContext?.destroy() } catch (_: Exception) {}
        jsContext = null
        cachedScriptRef.set(null)
        executor.shutdown()
    }

    // region Internals

    private suspend fun getOrCreateContext(source: String): QuickJSContext = withContext(Dispatchers.IO) {
        val needReload = cachedScriptRef.get() != source
        if (needReload) {
            try { jsContext?.destroy() } catch (_: Exception) {}
            // Create AND use on the dedicated executor thread
            val ctx = executor.submit<QuickJSContext> {
                val c = QuickJSContext.create()
                c.setConsole(object : QuickJSContext.Console {
                    override fun log(msg: String) { Log.d(TAG, "[script] $msg") }
                    override fun info(msg: String) { Log.i(TAG, "[script] $msg") }
                    override fun warn(msg: String) { Log.w(TAG, "[script] $msg") }
                    override fun error(msg: String) { Log.e(TAG, "[script] $msg") }
                })
                c.evaluate(source)
                c
            }.get()
            jsContext = ctx
            cachedScriptRef.set(source)
        }
        jsContext!!
    }

    private fun buildContextJson(ctx: SlashContext): String {
        val vars = ctx.variables?.all() ?: emptyMap()
        val varsJson = vars.entries.joinToString(",") { (k, v) ->
            """"${escapeJson(k)}":"${escapeJson(v)}""""
        }
        return """{"char":{"name":"${escapeJson(ctx.charName)}"},"user":{"name":"${escapeJson(ctx.userName)}"},"chat":{"messageCount":${ctx.chatMessageCount}},"variables":{$varsJson},"conversationId":"${ctx.conversationId ?: ""}"}"""
    }

    private fun parseExecutionResult(json: String): Result<Map<String, String>> {
        return try {
            val result = mutableMapOf<String, String>()
            // Simple JSON parsing for the result object
            val trimmed = json.trim()
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                val inner = trimmed.substring(1, trimmed.length - 1)
                // Parse key-value pairs (simple approach, handles strings and basic values)
                val pairs = parseJsonPairs(inner)
                result.putAll(pairs)
            }
            Result.success(result)
        } catch (e: Exception) {
            Result.success(mapOf("result" to json))
        }
    }

    private fun parseJsonPairs(input: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var i = 0
        while (i < input.length) {
            // Skip whitespace and commas
            while (i < input.length && (input[i] == ' ' || input[i] == '\n' || input[i] == '\r' || input[i] == '\t' || input[i] == ',')) i++
            if (i >= input.length) break
            // Read key
            if (input[i] != '"') break
            i++ // skip opening quote
            val keyStart = i
            while (i < input.length && input[i] != '"') {
                if (input[i] == '\\') i++ // skip escaped char
                i++
            }
            val key = input.substring(keyStart, i).replace("\\\"", "\"").replace("\\\\", "\\")
            i++ // skip closing quote
            // Skip colon
            while (i < input.length && (input[i] == ' ' || input[i] == ':')) i++
            // Read value
            if (i >= input.length) break
            val value = when (input[i]) {
                '"' -> {
                    i++
                    val valStart = i
                    while (i < input.length && input[i] != '"') {
                        if (input[i] == '\\') i++
                        i++
                    }
                    val v = input.substring(valStart, i).replace("\\\"", "\"").replace("\\\\", "\\")
                    i++
                    v
                }
                'n' -> { i += 4; "" } // null
                't' -> { i += 4; "true" }
                'f' -> { i += 5; "false" }
                else -> {
                    val valStart = i
                    while (i < input.length && input[i] != ',' && input[i] != '}' && input[i] != ' ' && input[i] != '\n') i++
                    input.substring(valStart, i)
                }
            }
            result[key] = value
        }
        return result
    }

    private fun parseCommandList(json: String, scriptName: String): List<SlashCommand> {
        // Simple JSON array parsing
        val commands = mutableListOf<SlashCommand>()
        try {
            val trimmed = json.trim()
            if (!trimmed.startsWith("[")) return commands
            // Simple approach: find each { ... } object
            var depth = 0; var start = -1
            for (i in trimmed.indices) {
                when (trimmed[i]) {
                    '{' -> { if (depth == 0) start = i; depth++ }
                    '}' -> {
                        depth--
                        if (depth == 0 && start >= 0) {
                            val obj = trimmed.substring(start, i + 1)
                            val pairs = parseJsonPairs(obj.substring(1, obj.length - 1))
                            val cmd = pairs["command"] ?: continue
                            commands.add(SlashCommand(
                                command = cmd,
                                scriptName = scriptName,
                                description = pairs["description"] ?: "",
                                argsHint = pairs["argsHint"] ?: "",
                            ))
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return commands
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    // endregion
}

/**
 * Context passed to slash command scripts, providing a TavernHelper-like API.
 */
data class SlashContext(
    val charName: String = "",
    val userName: String = "",
    val conversationId: String? = null,
    val chatMessageCount: Int = 0,
    val recentMessages: List<UIMessage> = emptyList(),
    /** Script-scoped variable store */
    val variables: ScriptVariableAccessor? = null,
)

/** Variable API exposed to JS: c.variables.get(key), c.variables.set(key, val), c.variables.delete(key) */
data class ScriptVariableAccessor(
    val scriptName: String,
    private val store: ScriptVariableStore,
) {
    fun get(key: String): String? = store.get(scriptName, key)
    fun set(key: String, value: String) = store.set(scriptName, key, value)
    fun delete(key: String) = store.delete(scriptName, key)
    fun all(): Map<String, String> = store.getAll(scriptName)
}
