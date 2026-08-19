package me.rerere.rikkahub.ui.pages.chat.tavern

import android.webkit.JavascriptInterface
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeController
import me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeBridge
import me.rerere.rikkahub.ui.components.richtext.runtime.TavernSendHookStore
import kotlin.uuid.Uuid

interface TavernConversationActions {
    fun onMessageLongPress(messageId: Uuid)
    fun onSelectBranch(nodeId: Uuid, index: Int)
    fun onOpenHtml(messageId: Uuid)
    fun onFallbackRequested()
}

/** Narrow, validating bridge exposed only to the app-owned conversation document. */
class TavernConversationBridge(
    actionToken: String,
    private val actions: TavernConversationActions,
    private val onOpenLink: (String) -> Unit = {},
    private val onDocumentReady: () -> Unit = {},
    private val dispatch: (() -> Unit) -> Unit = { it() },
) {
    private val trustedToken = actionToken.toByteArray(StandardCharsets.UTF_8)

    init {
        require(actionToken.isNotBlank()) { "A non-empty action token is required" }
    }

    @JavascriptInterface
    fun ready(actionToken: String): Boolean {
        if (!isTrusted(actionToken)) return false
        dispatch(onDocumentReady)
        return true
    }

    @JavascriptInterface
    fun longPress(actionToken: String, messageId: String): Boolean {
        if (!isTrusted(actionToken)) return false
        return validUuid(messageId)?.let { id ->
        dispatch { actions.onMessageLongPress(id) }
        true
        } ?: false
    }

    @JavascriptInterface
    fun selectBranch(actionToken: String, nodeId: String, index: Int): Boolean {
        if (!isTrusted(actionToken)) return false
        val id = validUuid(nodeId) ?: return false
        if (index !in 0..MAX_BRANCH_INDEX) return false
        dispatch { actions.onSelectBranch(id, index) }
        return true
    }

    @JavascriptInterface
    fun openHtml(actionToken: String, messageId: String): Boolean {
        if (!isTrusted(actionToken)) return false
        return validUuid(messageId)?.let { id ->
            dispatch { actions.onOpenHtml(id) }
            true
        } ?: false
    }

    @JavascriptInterface
    fun requestFallback(actionToken: String): Boolean {
        if (!isTrusted(actionToken)) return false
        dispatch(actions::onFallbackRequested)
        return true
    }

    @JavascriptInterface
    fun openLink(actionToken: String, rawUrl: String, userGesture: Boolean): Boolean {
        if (!isTrusted(actionToken) || !userGesture) return false
        val url = rawUrl.trim()
        if (!isAllowedTavernConversationLink(url)) return false
        dispatch { onOpenLink(url) }
        return true
    }

    private fun isTrusted(candidate: String): Boolean = MessageDigest.isEqual(
        trustedToken,
        candidate.toByteArray(StandardCharsets.UTF_8),
    )

    private fun validUuid(value: String): Uuid? {
        if (value.length !in 1..MAX_IDENTIFIER_LENGTH) return null
        return runCatching { Uuid.parse(value) }.getOrNull()
    }

    companion object {
        const val MAX_BRANCH_INDEX = 4096
        private const val MAX_IDENTIFIER_LENGTH = 64
    }
}

/** Runtime entry point that only the trusted parent document can invoke. */
internal class TavernConversationRuntimeBridge(
    actionToken: String,
    controller: TavernRuntimeController,
    emitResult: (callbackName: String, responseJson: String) -> Unit,
) {
    private val trustedToken = actionToken.toByteArray(StandardCharsets.UTF_8)
    private val delegate = TavernRuntimeBridge(controller, emitResult)

    init {
        require(actionToken.isNotBlank()) { "A non-empty action token is required" }
    }

    @JavascriptInterface
    fun call(requestJson: String, callbackName: String, actionToken: String) {
        if (!MessageDigest.isEqual(trustedToken, actionToken.toByteArray(StandardCharsets.UTF_8))) return
        delegate.call(requestJson, callbackName)
    }
}

internal fun isAllowedTavernConversationLink(rawUrl: String): Boolean {
    if (rawUrl.isBlank() || rawUrl.length > 4096) return false
    val scheme = runCatching { URI(rawUrl).scheme?.lowercase() }.getOrNull() ?: return false
    return scheme in setOf("http", "https", "mailto", "tel")
}

internal fun shouldOpenTavernNavigation(rawUrl: String, hasGesture: Boolean): Boolean =
    hasGesture && isAllowedTavernConversationLink(rawUrl)

internal const val TAVERN_CONVERSATION_BASE_URL = "about:blank"

internal fun shouldAllowTavernSubresource(rawUrl: String, networkAllowed: Boolean): Boolean {
    val scheme = runCatching { URI(rawUrl).scheme?.lowercase() }.getOrNull() ?: return false
    return when (scheme) {
        "file", "content" -> false
        "http", "https" -> networkAllowed
        "about", "data", "blob" -> true
        else -> false
    }
}

internal class TavernSendHookControllerBinding(
    private val store: TavernSendHookStore,
    private val controller: TavernRuntimeController,
    private val enabled: Boolean,
    private val conversationId: Uuid? = null,
) {
    fun attach() {
        if (!enabled) return
        if (conversationId == null) store.activeController = controller else store.attach(conversationId, controller)
    }

    fun detach() {
        if (!enabled) return
        if (conversationId == null) {
            if (store.activeController === controller) store.activeController = null
        } else {
            store.detach(conversationId, controller)
        }
    }
}

internal fun requiresTavernRegenerateConfirmation(role: MessageRole): Boolean = role == MessageRole.USER

enum class TavernConversationRenderStatus { LOADING, READY, FAILED }

data class TavernConversationRenderState(
    val generation: Int,
    val status: TavernConversationRenderStatus,
    val reason: String? = null,
) {
    fun onReady(generation: Int): TavernConversationRenderState =
        if (generation == this.generation && status == TavernConversationRenderStatus.LOADING) {
            copy(status = TavernConversationRenderStatus.READY, reason = null)
        } else {
            this
        }

    fun onFailure(generation: Int, reason: String): TavernConversationRenderState =
        if (generation == this.generation) {
            copy(status = TavernConversationRenderStatus.FAILED, reason = reason)
        } else {
            this
        }

    fun retry(): TavernConversationRenderState = TavernConversationRenderState(
        generation = generation + 1,
        status = TavernConversationRenderStatus.LOADING,
    )

    companion object {
        fun initial() = TavernConversationRenderState(0, TavernConversationRenderStatus.LOADING)
    }
}
