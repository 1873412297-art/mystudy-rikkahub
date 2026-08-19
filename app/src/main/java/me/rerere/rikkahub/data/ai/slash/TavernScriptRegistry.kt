package me.rerere.rikkahub.data.ai.slash

import com.whl.quickjs.wrapper.QuickJSContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 宏展开上下文（注入 QuickJS 的数据面） */
data class MacroExpandContext(
    val userName: String = "",
    val charName: String = "",
    val conversationId: String? = null,
)

data class SlashCommandInfo(
    val name: String,
    val aliases: List<String>,
    val helpString: String,
)

data class SlashCommandRegistration(
    val source: String,
    val aliases: List<String> = emptyList(),
    val helpString: String = "",
)

/** 宏/命令执行结果（与 SlashScriptEngine 的 Result 语义对齐） */
data class SlashCommandResult(
    val text: String? = null,
    val html: String? = null,
    val error: String? = null,
)

/** 宏展开能力抽象（生产实现为 [TavernScriptRegistry]，测试可注入假实现观察调用） */
fun interface MacroExpander {
    fun expandMacros(text: String, context: MacroExpandContext): String
}

/** 受 allowScripts 总开关保护的宏展开：脚本禁用时跳过展开，原文直出 */
fun expandMacrosIfAllowed(
    expander: MacroExpander,
    text: String,
    context: MacroExpandContext,
    allowScripts: Boolean,
): String = if (allowScripts) expander.expandMacros(text, context) else text

/** 宏源码体积上限（UTF-8 字节） */
private const val MAX_SOURCE_BYTES = 64 * 1024

/** 注册上限 */
private const val MAX_REGISTRATIONS = 64

/** 宏展开单次执行超时 */
private const val MACRO_EXECUTION_TIMEOUT_MS = 2_000L

/**
 * 宿主侧酒馆脚本注册表（应用级，WebView 重载不丢）。
 * 宏与斜杠命令源码在独立 QuickJS 单线程 executor 中执行（与 SlashScriptEngine 隔离）。
 *
 * QuickJS 原生库不可用时（如 JVM 单测环境）自动降级为无引擎模式：
 * 注册/列表/配额照常工作，展开返回原文，执行返回 error 兜底。
 */
class TavernScriptRegistry : MacroExpander {

    private class MacroEntry(val name: String, val source: String)

    private class SlashEntry(val info: SlashCommandInfo, val source: String)

    private val macros = ConcurrentHashMap<String, MacroEntry>()
    private val slashCommands = ConcurrentHashMap<String, SlashEntry>()
    private val registrationLock = Any()

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TavernScriptRegistry").apply { isDaemon = true }
    }

    private val contextRef = AtomicReference<QuickJSContext?>()

    @Volatile
    private var engineAvailable = true

    /** 已求值进共享 JS 上下文的宏/命令名集合（重注册时移除以重新求值） */
    private val loadedMacros = ConcurrentHashMap<String, Boolean>()
    private val loadedSlashCommands = ConcurrentHashMap<String, Boolean>()

    private fun getOrCreateContext(): QuickJSContext? {
        contextRef.get()?.let { return it }
        if (!engineAvailable) return null
        return try {
            val context = executor.submit<QuickJSContext> {
                QuickJSContext.create()
            }.get(MACRO_EXECUTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            contextRef.set(context)
            context
        } catch (e: Throwable) {
            // 原生库不可用（如 JVM 单测环境）→ 永久降级为无引擎模式
            engineAvailable = false
            contextRef.set(null)
            null
        }
    }

    private fun macroGlobalName(name: String): String {
        val sanitized = name.replace(Regex("[^A-Za-z0-9_]"), "_")
        return "__rikkahub_macro_${sanitized}_${Integer.toHexString(name.hashCode())}"
    }

    private fun slashGlobalName(name: String): String {
        val sanitized = name.replace(Regex("[^A-Za-z0-9_]"), "_")
        return "__rikkahub_slash_${sanitized}_${Integer.toHexString(name.hashCode())}"
    }

    /** 把宏源码求值成共享上下文中的全局函数（缓存；源码变化时由注册方移除缓存） */
    private fun ensureMacroLoaded(name: String, source: String): Boolean {
        if (loadedMacros.containsKey(name)) return true
        val context = getOrCreateContext() ?: return false
        val script = "var ${macroGlobalName(name)} = ($source);"
        val loaded = runOnExecutor<Boolean> {
            try {
                context.evaluate(script)
                true
            } catch (e: Exception) {
                false
            }
        } ?: false
        if (loaded) loadedMacros[name] = true
        return loaded
    }

    private fun ensureSlashLoaded(name: String, source: String): Boolean {
        if (loadedSlashCommands.containsKey(name)) return true
        val context = getOrCreateContext() ?: return false
        val script = "var ${slashGlobalName(name)} = ($source);"
        val loaded = runOnExecutor<Boolean> {
            try {
                context.evaluate(script)
                true
            } catch (e: Exception) {
                false
            }
        } ?: false
        if (loaded) loadedSlashCommands[name] = true
        return loaded
    }

    fun registerMacro(name: String, source: String): Boolean = synchronized(registrationLock) {
        if (source.toByteArray(Charsets.UTF_8).size > MAX_SOURCE_BYTES) return false
        if (macros.size >= MAX_REGISTRATIONS && !macros.containsKey(name)) return false
        macros[name] = MacroEntry(name, source)
        loadedMacros.remove(name) // 重新求值
        return true
    }

    fun removeMacro(name: String) = synchronized(registrationLock) {
        macros.remove(name)
        loadedMacros.remove(name)
    }

    fun listMacros(): List<String> = macros.keys.toList()

    fun registerSlashCommand(
        name: String,
        callbackSource: String,
        aliases: List<String>,
        helpString: String,
    ): Boolean = synchronized(registrationLock) {
        if (callbackSource.toByteArray(Charsets.UTF_8).size > MAX_SOURCE_BYTES) return false
        if (slashCommands.size >= MAX_REGISTRATIONS && !slashCommands.containsKey(name)) return false
        slashCommands[name] = SlashEntry(SlashCommandInfo(name, aliases, helpString), callbackSource)
        loadedSlashCommands.remove(name) // 重新求值
        return true
    }

    fun removeSlashCommand(name: String) = synchronized(registrationLock) {
        slashCommands.remove(name)
        loadedSlashCommands.remove(name)
    }

    /** Validates and applies a selected opening's registrations as one indivisible registry update. */
    fun registerBatch(
        macros: Map<String, String>,
        slashCommands: Map<String, SlashCommandRegistration>,
    ): Boolean = synchronized(registrationLock) {
        if (macros.values.any { it.toByteArray(Charsets.UTF_8).size > MAX_SOURCE_BYTES }) return false
        if (slashCommands.values.any { it.source.toByteArray(Charsets.UTF_8).size > MAX_SOURCE_BYTES }) return false
        if ((this.macros.keys + macros.keys).size > MAX_REGISTRATIONS) return false
        if ((this.slashCommands.keys + slashCommands.keys).size > MAX_REGISTRATIONS) return false

        macros.forEach { (name, source) ->
            this.macros[name] = MacroEntry(name, source)
            loadedMacros.remove(name)
        }
        slashCommands.forEach { (name, registration) ->
            this.slashCommands[name] = SlashEntry(
                SlashCommandInfo(name, registration.aliases, registration.helpString),
                registration.source,
            )
            loadedSlashCommands.remove(name)
        }
        true
    }

    fun listSlashCommands(): List<SlashCommandInfo> = slashCommands.values.map { it.info }

    /**
     * 同步展开注册宏：`{{name::args}}` 形态。
     * 无注册宏/无可用引擎/执行失败时保留原文。
     */
    override fun expandMacros(text: String, context: MacroExpandContext): String {
        if (macros.isEmpty()) return text
        // 注意：Android 的 ICU 正则拒绝未转义的结尾 `}}`（JVM 可编译但设备抛 PatternSyntaxException），
        // 结尾花括号必须转义为 \}\}（2026-08-14 模拟器冒烟发现）。
        val macroRegex = Regex("\\{\\{([A-Za-z_][A-Za-z0-9_]*)(?:::([^}]*))?\\}\\}")
        return macroRegex.replace(text) { match ->
            val name = match.groupValues[1]
            val args = match.groupValues[2]
            expandMacro(name, args, context) ?: match.value
        }
    }

    /**
     * 单宏直调展开：不经 {{}} 全文正则语法，直接以 args 为参数调用注册宏。
     * args 任意文本安全（含 }}、{{、引号、换行——经 JSON 转义注入调用表达式）。
     * 未注册/无可用引擎/执行失败 → null（调用方兜底原样）。
     */
    fun expandMacro(name: String, args: String, context: MacroExpandContext): String? {
        val entry = macros[name] ?: return null
        if (!ensureMacroLoaded(name, entry.source)) return null
        return callGlobal(macroGlobalName(name), args)
    }

    fun executeSlashCommand(name: String, args: String, context: MacroExpandContext): SlashCommandResult? {
        val entry = slashCommands[name] ?: return null
        if (!ensureSlashLoaded(name, entry.source)) {
            return SlashCommandResult(error = "callback evaluation failed")
        }
        val result = callGlobal(slashGlobalName(name), args)
        if (result == null) return SlashCommandResult(error = "callback execution failed")
        return SlashCommandResult(text = result)
    }

    private fun callGlobal(globalName: String, args: String): String? {
        val context = contextRef.get() ?: return null
        val script = "$globalName(\"${escapeJson(args)}\")"
        return runOnExecutor {
            try {
                context.evaluate(script)?.toString()
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun <T> runOnExecutor(block: () -> T): T? {
        val future = executor.submit(block)
        return try {
            future.get(MACRO_EXECUTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            future.cancel(true)
            null
        }
    }

    fun clear() {
        macros.clear()
        slashCommands.clear()
        loadedMacros.clear()
        loadedSlashCommands.clear()
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
