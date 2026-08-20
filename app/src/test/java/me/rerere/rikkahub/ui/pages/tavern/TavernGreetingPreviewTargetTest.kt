package me.rerere.rikkahub.ui.pages.tavern

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.applyTavernPreviewMessagePatch
import me.rerere.rikkahub.data.model.tavernPreviewTargetLabel
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.uuid.Uuid

class TavernGreetingPreviewTargetTest {
    private val assistantId = Uuid.parse("10000000-0000-4000-8000-000000000001")
    private val firstConversation = Uuid.parse("20000000-0000-4000-8000-000000000001")
    private val secondConversation = Uuid.parse("20000000-0000-4000-8000-000000000002")

    @Test
    fun `full preview has no implicit target and requires explicit selection`() {
        val selection = TavernGreetingPreviewTargetSelection(assistantId)

        assertNull(selection.selected.value)
        assertThrows(IllegalStateException::class.java) {
            selection.routeMessageWrite(firstConversation, JsonPrimitive("changed")) { _, _ -> }
        }
    }

    @Test
    fun `selection rejects conversations belonging to another assistant`() {
        val selection = TavernGreetingPreviewTargetSelection(assistantId)

        assertThrows(IllegalArgumentException::class.java) {
            selection.select(
                TavernGreetingPreviewTarget(
                    conversationId = firstConversation,
                    assistantId = Uuid.parse("10000000-0000-4000-8000-000000000002"),
                    title = "wrong assistant",
                ),
            )
        }
        assertNull(selection.selected.value)
    }

    @Test
    fun `preview writes route only to the manually selected real conversation`() {
        val selection = TavernGreetingPreviewTargetSelection(assistantId)
        val writes = mutableListOf<Pair<Uuid, String>>()
        selection.select(TavernGreetingPreviewTarget(firstConversation, assistantId, "First"))
        assertThrows(IllegalStateException::class.java) {
            selection.routeMessageWrite(firstConversation, JsonPrimitive("too early")) { _, _ -> }
        }
        selection.markReady(firstConversation)

        selection.routeMessageWrite(firstConversation, JsonPrimitive("one")) { id, patch ->
            writes += id to patch.toString()
        }
        selection.select(TavernGreetingPreviewTarget(secondConversation, assistantId, "Second"))
        selection.markReady(secondConversation)
        assertThrows(IllegalStateException::class.java) {
            selection.routeMessageWrite(firstConversation, JsonPrimitive("stale")) { _, _ -> }
        }
        selection.routeMessageWrite(secondConversation, JsonPrimitive("two")) { id, patch ->
            writes += id to patch.toString()
        }

        assertEquals(
            listOf(firstConversation to "\"one\"", secondConversation to "\"two\""),
            writes,
        )
    }

    @Test
    fun `only one greeting preview can own the editor runtime`() {
        val owner = TavernGreetingPreviewOwner()

        owner.show("first_mes")
        assertEquals("first_mes", owner.active.value)
        owner.show("alternate_2")
        assertEquals("alternate_2", owner.active.value)
        owner.showSource("first_mes")
        assertEquals("alternate_2", owner.active.value)
        owner.showSource("alternate_2")
        assertNull(owner.active.value)
    }

    @Test
    fun `conversation lease releases old targets and the final editor target exactly once`() {
        val events = mutableListOf<String>()
        val lease = TavernPreviewConversationLease(
            acquire = { events += "acquire:$it" },
            release = { events += "release:$it" },
        )

        lease.switchTo(firstConversation)
        lease.switchTo(firstConversation)
        lease.switchTo(secondConversation)
        lease.clear()
        lease.clear()

        assertEquals(
            listOf(
                "acquire:$firstConversation",
                "release:$firstConversation",
                "acquire:$secondConversation",
                "release:$secondConversation",
            ),
            events,
        )
    }

    @Test
    fun `preview side effects execute in callback order even when persistence suspends`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val queue = TavernPreviewSideEffectQueue(scope)
        val firstStarted = CompletableDeferred<Unit>()
        val firstMayFinish = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        try {
            queue.submit {
                order += "first-start"
                firstStarted.complete(Unit)
                firstMayFinish.await()
                order += "first-end"
            }
            queue.submit {
                order += "second"
                finished.complete(Unit)
            }
            firstStarted.await()
            assertEquals(listOf("first-start"), order)
            firstMayFinish.complete(Unit)
            finished.await()
            assertEquals(listOf("first-start", "first-end", "second"), order)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `destructive preview labels distinguish duplicate conversation titles`() {
        val first = Conversation(
            id = firstConversation,
            assistantId = assistantId,
            title = "Same",
            messageNodes = emptyList(),
        )
        val second = first.copy(id = secondConversation)

        org.junit.Assert.assertNotEquals(first.tavernPreviewTargetLabel(), second.tavernPreviewTargetLabel())
        org.junit.Assert.assertTrue(first.tavernPreviewTargetLabel().contains(firstConversation.toString().take(8)))
    }

    @Test
    fun `message patch updates the selected branch current message without changing its identity`() {
        val message = UIMessage.assistant("before")
        val conversation = Conversation(
            id = firstConversation,
            assistantId = assistantId,
            messageNodes = listOf(message.toMessageNode()),
        )

        val updated = applyTavernPreviewMessagePatch(
            conversation,
            buildJsonObject { put("text", "after") },
        )

        assertEquals(message.id, updated.currentMessages.single().id)
        assertEquals(
            "after",
            (updated.currentMessages.single().parts.single() as UIMessagePart.Text).text,
        )
    }
}
