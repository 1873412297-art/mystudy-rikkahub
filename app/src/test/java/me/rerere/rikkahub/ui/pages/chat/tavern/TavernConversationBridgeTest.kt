package me.rerere.rikkahub.ui.pages.chat.tavern

import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeController
import me.rerere.rikkahub.ui.components.richtext.runtime.TavernSendHookStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class TavernConversationBridgeTest {
    private val actionToken = "trusted-parent-token"
    private val messageId = Uuid.parse("00000000-0000-4000-8000-000000000101")
    private val nodeId = Uuid.parse("00000000-0000-4000-8000-000000000102")

    @Test
    fun `dispatches validated message and branch actions`() {
        val actions = RecordingActions()
        val bridge = TavernConversationBridge(actionToken = actionToken, actions = actions)

        assertTrue(bridge.longPress(actionToken, messageId.toString()))
        assertTrue(bridge.selectBranch(actionToken, nodeId.toString(), 2))
        assertTrue(bridge.openHtml(actionToken, messageId.toString()))
        assertTrue(bridge.requestFallback(actionToken))

        assertEquals(
            listOf("long:$messageId", "branch:$nodeId:2", "html:$messageId", "fallback"),
            actions.events,
        )
    }

    @Test
    fun `rejects malformed UUIDs and out of range branch indexes without dispatch`() {
        val actions = RecordingActions()
        val bridge = TavernConversationBridge(actionToken = actionToken, actions = actions)

        assertFalse(bridge.longPress(actionToken, "not-a-uuid"))
        assertFalse(bridge.openHtml(actionToken, ""))
        assertFalse(bridge.selectBranch(actionToken, "not-a-uuid", 0))
        assertFalse(bridge.selectBranch(actionToken, nodeId.toString(), -1))
        assertFalse(
            bridge.selectBranch(
                actionToken,
                nodeId.toString(),
                TavernConversationBridge.MAX_BRANCH_INDEX + 1,
            ),
        )

        assertTrue(actions.events.isEmpty())
    }

    @Test
    fun `opens only protocol whitelisted links`() {
        val opened = mutableListOf<String>()
        val bridge = TavernConversationBridge(
            actionToken = actionToken,
            actions = RecordingActions(),
            onOpenLink = opened::add,
        )

        assertTrue(bridge.openLink(actionToken, " HTTPS://example.com/path ", true))
        assertTrue(bridge.openLink(actionToken, "mailto:user@example.com", true))
        assertTrue(bridge.openLink(actionToken, "tel:+8612345", true))
        assertFalse(bridge.openLink(actionToken, "javascript:alert(1)", true))
        assertFalse(bridge.openLink(actionToken, "file:///sdcard/secret", true))
        assertFalse(bridge.openLink(actionToken, "content://provider/item", true))
        assertFalse(bridge.openLink(actionToken, "intent://scan/#Intent;scheme=zxing;end", true))
        assertFalse(bridge.openLink(actionToken, "httpsx://example.com", true))
        assertFalse(bridge.openLink(actionToken, "https://example.com/" + "x".repeat(4096), true))

        assertEquals(
            listOf("HTTPS://example.com/path", "mailto:user@example.com", "tel:+8612345"),
            opened,
        )
    }

    @Test
    fun `rejects child frame calls without the trusted parent token`() {
        val actions = RecordingActions()
        val opened = mutableListOf<String>()
        var readyCount = 0
        val bridge = TavernConversationBridge(
            actionToken = actionToken,
            actions = actions,
            onOpenLink = opened::add,
            onDocumentReady = { readyCount += 1 },
        )

        assertFalse(bridge.longPress("wrong-token", messageId.toString()))
        assertFalse(bridge.selectBranch("wrong-token", nodeId.toString(), 0))
        assertFalse(bridge.openHtml("wrong-token", messageId.toString()))
        assertFalse(bridge.requestFallback("wrong-token"))
        assertFalse(bridge.openLink("wrong-token", "https://example.com", true))
        assertFalse(bridge.ready("wrong-token"))

        assertTrue(actions.events.isEmpty())
        assertTrue(opened.isEmpty())
        assertEquals(0, readyCount)
    }

    @Test
    fun `external link action requires a trusted user gesture`() {
        val opened = mutableListOf<String>()
        val bridge = TavernConversationBridge(
            actionToken = actionToken,
            actions = RecordingActions(),
            onOpenLink = opened::add,
        )

        assertFalse(bridge.openLink(actionToken, "https://example.com/redirect", false))
        assertTrue(bridge.openLink(actionToken, "https://example.com/tap", true))

        assertEquals(listOf("https://example.com/tap"), opened)
    }

    @Test
    fun `runtime native bridge only accepts calls authenticated by trusted parent`() {
        val results = mutableListOf<Pair<String, String>>()
        val bridge = TavernConversationRuntimeBridge(
            actionToken = actionToken,
            controller = TavernRuntimeController(),
            emitResult = { callback, response -> results += callback to response },
        )
        val request = """{"id":"1","method":"runtime.ping","params":{}}"""

        bridge.call(request, "child_callback", "wrong-token")
        assertTrue(results.isEmpty())

        bridge.call(request, "parent_callback", actionToken)
        assertEquals(1, results.size)
        assertEquals("parent_callback", results.single().first)
        assertTrue(results.single().second.contains("\"result\":\"pong\""))
    }

    @Test
    fun `signals every document ready event without deduplication`() {
        var readyCount = 0
        val bridge = TavernConversationBridge(
            actionToken = actionToken,
            actions = RecordingActions(),
            onDocumentReady = { readyCount += 1 },
        )

        bridge.ready(actionToken)
        bridge.ready(actionToken)

        assertEquals(2, readyCount)
    }

    @Test
    fun `renderer failure state preserves retry generations and ignores stale ready events`() {
        val initial = TavernConversationRenderState.initial()
        val failed = initial.onFailure(generation = 0, reason = "Initial render timed out")
        val retrying = failed.retry()

        assertEquals(TavernConversationRenderStatus.FAILED, failed.status)
        assertEquals("Initial render timed out", failed.reason)
        assertEquals(1, retrying.generation)
        assertEquals(TavernConversationRenderStatus.LOADING, retrying.status)
        assertEquals(retrying, retrying.onReady(generation = 0))
        assertEquals(TavernConversationRenderStatus.READY, retrying.onReady(generation = 1).status)
    }

    @Test
    fun `renderer ignores stale failure callback after retry starts`() {
        val retrying = TavernConversationRenderState.initial()
            .onFailure(generation = 0, reason = "old crash")
            .retry()

        assertEquals(
            retrying,
            retrying.onFailure(generation = 0, reason = "late old renderer callback"),
        )
    }

    @Test
    fun `renderer accepts ready only while expected generation is loading`() {
        val failed = TavernConversationRenderState.initial()
            .onFailure(generation = 0, reason = "crashed")
        val ready = TavernConversationRenderState.initial().onReady(generation = 0)

        assertEquals(failed, failed.onReady(generation = 0))
        assertEquals(
            TavernConversationRenderStatus.FAILED,
            ready.onFailure(generation = 0, reason = "renderer exited").status,
        )
    }

    @Test
    fun `navigation only opens allowlisted links from a user gesture`() {
        assertTrue(shouldOpenTavernNavigation("https://example.com", hasGesture = true))
        assertFalse(shouldOpenTavernNavigation("https://example.com", hasGesture = false))
        assertFalse(shouldOpenTavernNavigation("javascript:alert(1)", hasGesture = true))
    }

    @Test
    fun `network denial blocks every http target including old synthetic origin and redirects`() {
        assertFalse(shouldAllowTavernSubresource("https://rikkahub.local/", networkAllowed = false))
        assertFalse(shouldAllowTavernSubresource("http://example.com/start", networkAllowed = false))
        assertFalse(shouldAllowTavernSubresource("https://redirect.example/target", networkAllowed = false))
        assertTrue(shouldAllowTavernSubresource("https://example.com", networkAllowed = true))
        assertFalse(shouldAllowTavernSubresource("file:///sdcard/secret", networkAllowed = true))
        assertFalse(shouldAllowTavernSubresource("content://provider/item", networkAllowed = true))
        assertTrue(TAVERN_CONVERSATION_BASE_URL.substringBefore(':') !in setOf("http", "https"))
    }

    @Test
    fun `fullscreen viewer does not replace or clear conversation send hook owner`() {
        val store = TavernSendHookStore()
        val conversationId = kotlin.uuid.Uuid.random()
        val conversationController = TavernRuntimeController(conversationId = conversationId)
        val viewerController = TavernRuntimeController()
        val conversationBinding = TavernSendHookControllerBinding(
            store, conversationController, enabled = true, conversationId = conversationId,
        )
        val viewerBinding = TavernSendHookControllerBinding(store, viewerController, enabled = false)

        conversationBinding.attach()
        viewerBinding.attach()
        assertTrue(store.controllerFor(conversationId) === conversationController)

        viewerBinding.detach()
        assertTrue(store.controllerFor(conversationId) === conversationController)

        conversationBinding.detach()
        assertEquals(null, store.controllerFor(conversationId))
    }

    @Test
    fun `only user message regeneration requires destructive confirmation`() {
        assertTrue(requiresTavernRegenerateConfirmation(MessageRole.USER))
        assertFalse(requiresTavernRegenerateConfirmation(MessageRole.ASSISTANT))
        assertFalse(requiresTavernRegenerateConfirmation(MessageRole.SYSTEM))
    }

    private class RecordingActions : TavernConversationActions {
        val events = mutableListOf<String>()

        override fun onMessageLongPress(messageId: Uuid) { events += "long:$messageId" }
        override fun onSelectBranch(nodeId: Uuid, index: Int) { events += "branch:$nodeId:$index" }
        override fun onOpenHtml(messageId: Uuid) { events += "html:$messageId" }
        override fun onFallbackRequested() { events += "fallback" }
    }
}
