package me.rerere.rikkahub.ui.components.richtext.runtime

import android.webkit.JavascriptInterface
import kotlinx.serialization.json.Json

private val RUNTIME_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

private val SAFE_CALLBACK_NAME = Regex("[A-Za-z0-9_.$]+")

internal class TavernRuntimeBridge(
    private val controller: TavernRuntimeController,
    private val emitResult: (callbackName: String, responseJson: String) -> Unit,
) {
    @JavascriptInterface
    fun call(requestJson: String, callbackName: String) {
        if (requestJson.length > 256_000 || callbackName.length > 128) return

        val safeCallback = callbackName.takeIf {
            it.matches(SAFE_CALLBACK_NAME)
        } ?: return

        val responseJson = try {
            val request = RUNTIME_JSON.decodeFromString(TavernRuntimeRequest.serializer(), requestJson)
            val response = controller.dispatch(request)
            RUNTIME_JSON.encodeToString(TavernRuntimeResponse.serializer(), response)
        } catch (e: Exception) {
            RUNTIME_JSON.encodeToString(
                TavernRuntimeResponse.serializer(),
                TavernRuntimeResponse.error("unknown", "BAD_REQUEST", e.message ?: "Invalid runtime request")
            )
        }

        emitResult(safeCallback, responseJson)
    }
}
