package me.rerere.rikkahub.ui.pages.tavern

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.applyTavernPreviewMessagePatch
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
            selection.routeMessageWrite(JsonPrimitive("changed")) { _, _ -> }
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
            selection.routeMessageWrite(JsonPrimitive("too early")) { _, _ -> }
        }
        selection.markReady(firstConversation)

        selection.routeMessageWrite(JsonPrimitive("one")) { id, patch ->
            writes += id to patch.toString()
        }
        selection.select(TavernGreetingPreviewTarget(secondConversation, assistantId, "Second"))
        selection.markReady(secondConversation)
        selection.routeMessageWrite(JsonPrimitive("two")) { id, patch ->
            writes += id to patch.toString()
        }

        assertEquals(
            listOf(firstConversation to "\"one\"", secondConversation to "\"two\""),
            writes,
        )
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
