package me.rerere.rikkahub.ui.components.richtext.runtime

import android.webkit.JavascriptInterface
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperScriptRepository

internal class TavernBrowserScriptBridge(
    private val scriptId: String,
    private val repository: TavernHelperScriptRepository,
) {
    @JavascriptInterface
    fun replaceData(rawJson: String): Boolean = runBlocking {
        runCatching { repository.replaceRuntimeData(scriptId, rawJson) }.isSuccess
    }

    @JavascriptInterface
    fun replaceButtons(rawJson: String): Boolean = runBlocking {
        runCatching { repository.replaceRuntimeButtons(scriptId, rawJson) }.isSuccess
    }
}
