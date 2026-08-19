package me.rerere.rikkahub.ui.pages.chat.tavern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class TavernConversationBridgeTest {
    private val messageId = Uuid.parse("00000000-0000-4000-8000-000000000101")
    private val nodeId = Uuid.parse("00000000-0000-4000-8000-000000000102")

    @Test
    fun `dispatches validated message and branch actions`() {
        val actions = RecordingActions()
        val bridge = TavernConversationBridge(actions = actions)

        assertTrue(bridge.longPress(messageId.toString()))
        assertTrue(bridge.selectBranch(nodeId.toString(), 2))
        assertTrue(bridge.openHtml(messageId.toString()))
        assertTrue(bridge.requestFallback())

        assertEquals(
            listOf("long:$messageId", "branch:$nodeId:2", "html:$messageId", "fallback"),
            actions.events,
        )
    }

    @Test
    fun `rejects malformed UUIDs and out of range branch indexes without dispatch`() {
        val actions = RecordingActions()
        val bridge = TavernConversationBridge(actions = actions)

        assertFalse(bridge.longPress("not-a-uuid"))
        assertFalse(bridge.openHtml(""))
        assertFalse(bridge.selectBranch("not-a-uuid", 0))
        assertFalse(bridge.selectBranch(nodeId.toString(), -1))
        assertFalse(bridge.selectBranch(nodeId.toString(), TavernConversationBridge.MAX_BRANCH_INDEX + 1))

        assertTrue(actions.events.isEmpty())
    }

    @Test
    fun `opens only protocol whitelisted links`() {
        val opened = mutableListOf<String>()
        val bridge = TavernConversationBridge(actions = RecordingActions(), onOpenLink = opened::add)

        assertTrue(bridge.openLink(" HTTPS://example.com/path "))
        assertTrue(bridge.openLink("mailto:user@example.com"))
        assertTrue(bridge.openLink("tel:+8612345"))
        assertFalse(bridge.openLink("javascript:alert(1)"))
        assertFalse(bridge.openLink("file:///sdcard/secret"))
        assertFalse(bridge.openLink("content://provider/item"))
        assertFalse(bridge.openLink("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(bridge.openLink("httpsx://example.com"))
        assertFalse(bridge.openLink("https://example.com/" + "x".repeat(4096)))

        assertEquals(
            listOf("HTTPS://example.com/path", "mailto:user@example.com", "tel:+8612345"),
            opened,
        )
    }

    @Test
    fun `signals every document ready event without deduplication`() {
        var readyCount = 0
        val bridge = TavernConversationBridge(
            actions = RecordingActions(),
            onDocumentReady = { readyCount += 1 },
        )

        bridge.ready()
        bridge.ready()

        assertEquals(2, readyCount)
    }

    @Test
    fun `renderer failure state preserves retry generations and ignores stale ready events`() {
        val initial = TavernConversationRenderState.initial()
        val failed = initial.onFailure("Initial render timed out")
        val retrying = failed.retry()

        assertEquals(TavernConversationRenderStatus.FAILED, failed.status)
        assertEquals("Initial render timed out", failed.reason)
        assertEquals(1, retrying.generation)
        assertEquals(TavernConversationRenderStatus.LOADING, retrying.status)
        assertEquals(retrying, retrying.onReady(generation = 0))
        assertEquals(TavernConversationRenderStatus.READY, retrying.onReady(generation = 1).status)
    }

    private class RecordingActions : TavernConversationActions {
        val events = mutableListOf<String>()

        override fun onMessageLongPress(messageId: Uuid) { events += "long:$messageId" }
        override fun onSelectBranch(nodeId: Uuid, index: Int) { events += "branch:$nodeId:$index" }
        override fun onOpenHtml(messageId: Uuid) { events += "html:$messageId" }
        override fun onFallbackRequested() { events += "fallback" }
    }
}
