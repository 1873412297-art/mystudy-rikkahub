package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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
        _revision.value += 1
    }

    fun setStatus(scriptId: String, status: TavernScriptRuntimeStatus) {
        _statuses.value = _statuses.value + (scriptId to status)
    }

    fun applySelection(activeIds: Set<String>, overLimitIds: Set<String>) {
        _statuses.value = _statuses.value.toMutableMap().apply {
            overLimitIds.forEach { put(it, TavernScriptRuntimeStatus.OVER_LIMIT) }
            activeIds.forEach { id -> if (this[id] == TavernScriptRuntimeStatus.OVER_LIMIT) put(id, TavernScriptRuntimeStatus.PAUSED) }
        }
    }

    fun clear(scriptId: String) {
        synchronized(lock) { entries.remove(scriptId) }
        _revision.value += 1
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

internal fun redactScriptDiagnostic(value: String): String = value
    .replace(Regex("(?i)(authorization\\s*[:=]\\s*(?:bearer\\s+)?)[^\\s,;]+"), "$1[已隐藏]")
    .replace(Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+"), "$1[已隐藏]")
    .replace(Regex("(?i)(cookie\\s*[:=]\\s*)[^\\r\\n]+"), "$1[已隐藏]")
    .replace(Regex("(?i)((?:x-api-key|api[_-]?key|token|secret|password)\\s*[:=]\\s*)[^\\s,;]+"), "$1[已隐藏]")
