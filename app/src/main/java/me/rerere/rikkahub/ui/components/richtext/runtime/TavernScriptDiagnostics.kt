package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal enum class TavernScriptRuntimeStatus {
    DISABLED,
    WAITING_PERMISSION,
    RUNNING,
    PAUSED,
    LOAD_FAILED,
    RUNTIME_CRASH,
    OVER_LIMIT,
}

internal enum class TavernScriptDiagnosticLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

internal data class TavernScriptDiagnosticEntry(
    val timestamp: Long,
    val level: TavernScriptDiagnosticLevel,
    val category: String,
    val message: String,
    val rpcMethod: String? = null,
    val durationMs: Long? = null,
    val error: String? = null,
)

/** Process-local, bounded runtime telemetry for individual browser scripts. */
internal class TavernScriptDiagnosticsStore(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val entries = mutableMapOf<String, ArrayDeque<TavernScriptDiagnosticEntry>>()
    private val _statuses = MutableStateFlow<Map<String, TavernScriptRuntimeStatus>>(emptyMap())
    val statuses = _statuses.asStateFlow()
    private val _revision = MutableStateFlow(0)
    val revision = _revision.asStateFlow()

    fun status(scriptId: String): TavernScriptRuntimeStatus? = _statuses.value[scriptId]

    fun statusFor(scriptEnabled: Boolean, scriptId: String): TavernScriptRuntimeStatus =
        if (!scriptEnabled) TavernScriptRuntimeStatus.DISABLED else status(scriptId) ?: TavernScriptRuntimeStatus.PAUSED

    fun entries(scriptId: String): List<TavernScriptDiagnosticEntry> = synchronized(lock) {
        entries[scriptId]?.toList().orEmpty()
    }

    fun record(
        scriptId: String,
        level: TavernScriptDiagnosticLevel,
        category: String,
        message: String,
        rpcMethod: String? = null,
        durationMs: Long? = null,
        error: String? = null,
    ) {
        val entry = TavernScriptDiagnosticEntry(
            timestamp = clock(),
            level = level,
            category = category,
            message = redactScriptDiagnostic(message),
            rpcMethod = rpcMethod,
            durationMs = durationMs,
            error = error?.let(::redactScriptDiagnostic),
        )
        synchronized(lock) {
            val scriptEntries = entries.getOrPut(scriptId) { ArrayDeque() }
            scriptEntries.addLast(entry)
            while (scriptEntries.size > MAX_ENTRIES_PER_SCRIPT) scriptEntries.removeFirst()
        }
        _revision.update { it + 1 }
    }

    fun setStatus(scriptId: String, status: TavernScriptRuntimeStatus) {
        _statuses.update { it + (scriptId to status) }
    }

    fun applySelection(activeIds: Set<String>, overLimitIds: Set<String>) {
        _statuses.update { old -> old.toMutableMap().apply {
            overLimitIds.forEach { put(it, TavernScriptRuntimeStatus.OVER_LIMIT) }
            activeIds.forEach { id -> if (this[id] == TavernScriptRuntimeStatus.OVER_LIMIT) put(id, TavernScriptRuntimeStatus.PAUSED) }
        } }
    }

    fun clear(scriptId: String) {
        synchronized(lock) { entries.remove(scriptId) }
        _revision.update { it + 1 }
    }

    companion object {
        const val MAX_ENTRIES_PER_SCRIPT = 500
    }
}

internal val tavernScriptDiagnostics = TavernScriptDiagnosticsStore()

internal fun tavernScriptStatusLabel(status: TavernScriptRuntimeStatus): String = when (status) {
    TavernScriptRuntimeStatus.DISABLED -> "已禁用"
    TavernScriptRuntimeStatus.WAITING_PERMISSION -> "等待权限"
    TavernScriptRuntimeStatus.RUNNING -> "运行中"
    TavernScriptRuntimeStatus.PAUSED -> "已暂停"
    TavernScriptRuntimeStatus.LOAD_FAILED -> "加载失败"
    TavernScriptRuntimeStatus.RUNTIME_CRASH -> "运行崩溃"
    TavernScriptRuntimeStatus.OVER_LIMIT -> "超出运行上限"
}

internal fun effectiveTavernScriptStatus(
    scriptEnabled: Boolean,
    folderEnabled: Boolean = true,
    runtimeStatus: TavernScriptRuntimeStatus? = null,
): TavernScriptRuntimeStatus = when {
    !folderEnabled || !scriptEnabled -> TavernScriptRuntimeStatus.DISABLED
    else -> runtimeStatus ?: TavernScriptRuntimeStatus.PAUSED
}

internal fun redactScriptDiagnostic(value: String): String = redactEmbeddedJsonDiagnostics(value)
    .replace(
        Regex("(?i)(\"(?:authorization|cookie|x-[a-z0-9-]+|api[_ -]?key|token|secret|password)\"\\s*:\\s*\")(?:(?:\\\\.)|[^\"])*(\")"),
        "$1[已隐藏]$2",
    )
    .replace(Regex("(?i)(authorization\\s*[:=]\\s*)(?:basic|bearer)\\s+[^\\s,;]+"), "$1[已隐藏]")
    .replace(Regex("(?i)(authorization\\s*[:=]\\s*)[^\\s,;]+"), "$1[已隐藏]")
    .replace(Regex("(?i)(cookie\\s*[:=]\\s*)[^\\r\\n]+"), "$1[已隐藏]")
    .replace(Regex("(?i)((?:x-[a-z0-9-]+|x-api-key|api[_ -]?key|token|secret|password)\\s*[:=]\\s*)[^\\s,;]+"), "$1[已隐藏]")
    .replace(Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+"), "$1[已隐藏]")

private fun redactJsonDiagnostic(value: String): String? = runCatching {
    Json.parseToJsonElement(value)
}.getOrNull()?.takeIf { it is JsonObject || it is JsonArray }?.let {
    redactJsonElement(it).toString()
}

private fun redactEmbeddedJsonDiagnostics(value: String): String = buildString {
    var index = 0
    while (index < value.length) {
        val end = value.findJsonEnd(index)
        if (end != null) {
            val rawJson = value.substring(index, end + 1)
            val redacted = redactJsonDiagnostic(rawJson)
            if (redacted != null) {
                append(redacted)
                index = end + 1
                continue
            }
        }
        append(value[index])
        index++
    }
}

private fun String.findJsonEnd(start: Int): Int? {
    if (getOrNull(start) !in setOf('{', '[')) return null
    val expectedClosers = ArrayDeque<Char>()
    var inString = false
    var escaped = false
    for (index in start until length) {
        val char = this[index]
        if (inString) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
            continue
        }
        when (char) {
            '"' -> inString = true
            '{' -> expectedClosers.addLast('}')
            '[' -> expectedClosers.addLast(']')
            '}', ']' -> {
                if (expectedClosers.removeLastOrNull() != char) return null
                if (expectedClosers.isEmpty()) return index
            }
        }
    }
    return null
}

private fun redactJsonElement(element: JsonElement, redactAllValues: Boolean = false): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.mapValues { (key, child) ->
        val headerContainer = key.contains("header", ignoreCase = true)
        when {
            redactAllValues || key.isSensitiveDiagnosticKey() -> JsonPrimitive("[已隐藏]")
            else -> redactJsonElement(child, headerContainer)
        }
    })
    is JsonArray -> JsonArray(element.map { redactJsonElement(it, redactAllValues) })
    else -> if (redactAllValues) JsonPrimitive("[已隐藏]") else element
}

private fun String.isSensitiveDiagnosticKey(): Boolean = lowercase().let { key ->
    key == "authorization" || key == "cookie" ||
        key.startsWith("x-") ||
        key.contains("api_key") || key.contains("api-key") || key.contains("api key") ||
        key.contains("token") || key.contains("secret") || key.contains("password")
}
