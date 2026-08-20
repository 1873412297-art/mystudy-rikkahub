package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class TavernPreviewMutationRebaserTest {
    private val conversationId = Uuid.parse("50000000-0000-4000-8000-000000000001")
    private val assistantId = Uuid.parse("50000000-0000-4000-8000-000000000002")

    @Test
    fun `stale whole conversation save retains preview message and variable effects`() {
        val originalMessage = UIMessage.assistant("before")
        val before = Conversation(
            id = conversationId,
            assistantId = assistantId,
            messageNodes = listOf(originalMessage.toMessageNode()),
            statusVariables = buildJsonObject { put("hp", 1) },
        )
        val previewMessage = originalMessage.copy(parts = listOf(UIMessagePart.Text("preview")))
        val preview = before.copy(
            messageNodes = listOf(previewMessage.toMessageNode()),
            statusVariables = buildJsonObject { put("hp", 2) },
        )
        val staleSave = before.copy(messageNodes = before.messageNodes + UIMessage.user("new").toMessageNode())
        val rebaser = TavernPreviewMutationRebaser()
        rebaser.recordMessage(conversationId, originalMessage.id, "before", "preview")
        rebaser.recordVariables(conversationId, before.statusVariables, preview.statusVariables)

        val rebased = rebaser.rebase(conversationId, staleSave)

        assertEquals("preview", (rebased.messageNodes.first().messages.first().parts.first() as UIMessagePart.Text).text)
        assertEquals(JsonPrimitive(2), rebased.statusVariables["hp"])
        assertEquals(2, rebased.messageNodes.size)
    }

    @Test
    fun `later explicit edit supersedes recorded preview message`() {
        val originalMessage = UIMessage.assistant("before")
        val rebaser = TavernPreviewMutationRebaser()
        rebaser.recordMessage(conversationId, originalMessage.id, "before", "preview")
        val edited = Conversation(
            id = conversationId,
            assistantId = assistantId,
            messageNodes = listOf(
                originalMessage.copy(parts = listOf(UIMessagePart.Text("later edit"))).toMessageNode(),
            ),
        )

        val accepted = rebaser.rebase(conversationId, edited)
        val laterStale = rebaser.rebase(
            conversationId,
            edited.copy(
                messageNodes = listOf(originalMessage.copy(parts = listOf(UIMessagePart.Text("before"))).toMessageNode()),
            ),
        )

        assertEquals("later edit", (accepted.currentMessages.single().parts.first() as UIMessagePart.Text).text)
        assertEquals("before", (laterStale.currentMessages.single().parts.first() as UIMessagePart.Text).text)
    }
}
