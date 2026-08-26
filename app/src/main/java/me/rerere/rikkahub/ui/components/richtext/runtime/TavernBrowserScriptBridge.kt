package me.rerere.rikkahub.ui.components.richtext.runtime

import android.webkit.JavascriptInterface
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperScriptRepository

internal class TavernBrowserScriptBridge(
    private val scriptId: String,
    private val repository: TavernHelperScriptRepository,
    private val diagnostics: TavernScriptDiagnosticsStore = tavernScriptDiagnostics,
) {
    @JavascriptInterface
    fun replaceData(rawJson: String): Boolean = runBlocking {
        runCatching { repository.replaceRuntimeData(scriptId, rawJson) }.isSuccess
    }

    @JavascriptInterface
    fun replaceButtons(rawJson: String): Boolean = runBlocking {
        runCatching { repository.replaceRuntimeButtons(scriptId, rawJson) }.isSuccess
    }

    @JavascriptInterface
    fun log(level: String, message: String) {
        val diagnosticLevel = when (level.lowercase()) {
            "debug" -> TavernScriptDiagnosticLevel.DEBUG
            "warn", "warning" -> TavernScriptDiagnosticLevel.WARN
            "error" -> TavernScriptDiagnosticLevel.ERROR
            else -> TavernScriptDiagnosticLevel.INFO
        }
        diagnostics.record(scriptId, diagnosticLevel, "console", message)
    }

    @JavascriptInterface
    fun lifecycle(name: String, detail: String = "") {
        val normalized = name.lowercase()
        val status = when (normalized) {
            "loading" -> TavernScriptRuntimeStatus.WAITING_PERMISSION
            "running", "loaded" -> TavernScriptRuntimeStatus.RUNNING
            "paused", "unloaded" -> TavernScriptRuntimeStatus.PAUSED
            "load_failed" -> TavernScriptRuntimeStatus.LOAD_FAILED
            "runtime_crash" -> TavernScriptRuntimeStatus.RUNTIME_CRASH
            else -> return
        }
        diagnostics.setStatus(scriptId, status)
        diagnostics.record(
            scriptId = scriptId,
            level = if (status == TavernScriptRuntimeStatus.RUNTIME_CRASH || status == TavernScriptRuntimeStatus.LOAD_FAILED) {
                TavernScriptDiagnosticLevel.ERROR
            } else {
                TavernScriptDiagnosticLevel.INFO
            },
            category = "lifecycle",
            message = if (detail.isBlank()) normalized else detail,
            error = detail.takeIf { status == TavernScriptRuntimeStatus.RUNTIME_CRASH || status == TavernScriptRuntimeStatus.LOAD_FAILED },
        )
    }
}
