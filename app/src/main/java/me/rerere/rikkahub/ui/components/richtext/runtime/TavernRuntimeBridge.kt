package me.rerere.rikkahub.ui.components.richtext.runtime

import android.webkit.JavascriptInterface
import kotlinx.serialization.json.Json

private val RUNTIME_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

private val SAFE_CALLBACK_NAME = Regex("[A-Za-z0-9_.$]+")
private const val MAX_REQUEST_CHARS = 256_000
private const val MAX_RESPONSE_BYTES = 1_000_000

internal class TavernRuntimeBridge(
    private val controller: TavernRuntimeController,
    private val scriptId: String? = null,
    private val diagnostics: TavernScriptDiagnosticsStore = tavernScriptDiagnostics,
    private val emitResult: (callbackName: String, responseJson: String) -> Unit,
) {
    @JavascriptInterface
    fun call(requestJson: String, callbackName: String) {
        val safeCallback = callbackName.takeIf {
            it.length <= 128 && it.matches(SAFE_CALLBACK_NAME)
        } ?: return

        val startedAt = System.nanoTime()
        var method = "unknown"

        fun deliver(response: TavernRuntimeResponse) {
            val responseJson = encodeBoundedResponse(response)
            scriptId?.let { id ->
                val error = response.error
                diagnostics.record(
                    scriptId = id,
                    level = if (error == null) TavernScriptDiagnosticLevel.INFO else TavernScriptDiagnosticLevel.ERROR,
                    category = "rpc",
                    message = if (error == null) "RPC $method completed" else "RPC $method failed",
                    rpcMethod = redactScriptDiagnostic(method),
                    durationMs = (System.nanoTime() - startedAt) / 1_000_000,
                    error = error?.message,
                )
            }
            emitResult(safeCallback, responseJson)
        }

        try {
            if (requestJson.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_CHARS) {
                deliver(
                    TavernRuntimeResponse.error(
                        "unknown", "REQUEST_TOO_LARGE", "Runtime request exceeds the 256KB limit"
                    )
                )
            } else {
                val request = RUNTIME_JSON.decodeFromString(TavernRuntimeRequest.serializer(), requestJson)
                method = request.method
                if (method.startsWith("generation.")) {
                    // 生成类 RPC 走异步通道：结果由 controller 回调送达，不阻塞 JavaBridge 线程
                    controller.dispatchGeneration(request) { response -> deliver(response) }
                } else {
                    deliver(controller.dispatch(request))
                }
            }
        } catch (e: Exception) {
            deliver(
                TavernRuntimeResponse.error("unknown", "BAD_REQUEST", e.message ?: "Invalid runtime request")
            )
        }
    }

    private fun encodeBoundedResponse(response: TavernRuntimeResponse): String {
        val encoded = RUNTIME_JSON.encodeToString(TavernRuntimeResponse.serializer(), response)
        if (encoded.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES) return encoded
        return RUNTIME_JSON.encodeToString(
            TavernRuntimeResponse.serializer(),
            TavernRuntimeResponse.error(
                id = response.id,
                code = "RESPONSE_TOO_LARGE",
                message = "Runtime response exceeds the 1MB limit",
            ),
        )
    }
}
