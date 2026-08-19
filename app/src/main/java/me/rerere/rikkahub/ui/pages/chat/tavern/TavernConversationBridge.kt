package me.rerere.rikkahub.ui.pages.chat.tavern

import android.webkit.JavascriptInterface
import java.net.URI
import kotlin.uuid.Uuid

interface TavernConversationActions {
    fun onMessageLongPress(messageId: Uuid)
    fun onSelectBranch(nodeId: Uuid, index: Int)
    fun onOpenHtml(messageId: Uuid)
    fun onFallbackRequested()
}

/** Narrow, validating bridge exposed only to the app-owned conversation document. */
class TavernConversationBridge(
    private val actions: TavernConversationActions,
    private val onOpenLink: (String) -> Unit = {},
    private val onDocumentReady: () -> Unit = {},
    private val dispatch: (() -> Unit) -> Unit = { it() },
) {
    @JavascriptInterface
    fun ready() {
        dispatch(onDocumentReady)
    }

    @JavascriptInterface
    fun longPress(messageId: String): Boolean = validUuid(messageId)?.let { id ->
        dispatch { actions.onMessageLongPress(id) }
        true
    } ?: false

    @JavascriptInterface
    fun selectBranch(nodeId: String, index: Int): Boolean {
        val id = validUuid(nodeId) ?: return false
        if (index !in 0..MAX_BRANCH_INDEX) return false
        dispatch { actions.onSelectBranch(id, index) }
        return true
    }

    @JavascriptInterface
    fun openHtml(messageId: String): Boolean = validUuid(messageId)?.let { id ->
        dispatch { actions.onOpenHtml(id) }
        true
    } ?: false

    @JavascriptInterface
    fun requestFallback(): Boolean {
        dispatch(actions::onFallbackRequested)
        return true
    }

    @JavascriptInterface
    fun openLink(rawUrl: String): Boolean {
        val url = rawUrl.trim()
        if (!isAllowedTavernConversationLink(url)) return false
        dispatch { onOpenLink(url) }
        return true
    }

    private fun validUuid(value: String): Uuid? {
        if (value.length !in 1..MAX_IDENTIFIER_LENGTH) return null
        return runCatching { Uuid.parse(value) }.getOrNull()
    }

    companion object {
        const val MAX_BRANCH_INDEX = 4096
        private const val MAX_IDENTIFIER_LENGTH = 64
    }
}

internal fun isAllowedTavernConversationLink(rawUrl: String): Boolean {
    if (rawUrl.isBlank() || rawUrl.length > 4096) return false
    val scheme = runCatching { URI(rawUrl).scheme?.lowercase() }.getOrNull() ?: return false
    return scheme in setOf("http", "https", "mailto", "tel")
}

enum class TavernConversationRenderStatus { LOADING, READY, FAILED }

data class TavernConversationRenderState(
    val generation: Int,
    val status: TavernConversationRenderStatus,
    val reason: String? = null,
) {
    fun onReady(generation: Int): TavernConversationRenderState =
        if (generation == this.generation) copy(status = TavernConversationRenderStatus.READY, reason = null) else this

    fun onFailure(reason: String): TavernConversationRenderState =
        copy(status = TavernConversationRenderStatus.FAILED, reason = reason)

    fun retry(): TavernConversationRenderState = TavernConversationRenderState(
        generation = generation + 1,
        status = TavernConversationRenderStatus.LOADING,
    )

    companion object {
        fun initial() = TavernConversationRenderState(0, TavernConversationRenderStatus.LOADING)
    }
}
