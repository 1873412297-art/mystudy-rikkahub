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
            stateRevision = 1,
        )
        val previewMessage = originalMessage.copy(parts = listOf(UIMessagePart.Text("preview")))
        val preview = before.copy(
            messageNodes = listOf(previewMessage.toMessageNode()),
            statusVariables = buildJsonObject { put("hp", 2) },
        )
        val staleSave = before.copy(messageNodes = before.messageNodes + UIMessage.user("new").toMessageNode())
        val rebaser = TavernPreviewMutationRebaser()
        rebaser.recordMessage(conversationId, originalMessage.id, "before", "preview", previewRevision = 2)
        rebaser.recordVariables(
            conversationId,
            before.statusVariables,
            preview.statusVariables,
            previewRevision = 2,
        )

        val rebased = rebaser.rebase(conversationId, staleSave)

        assertEquals("preview", (rebased.messageNodes.first().messages.first().parts.first() as UIMessagePart.Text).text)
        assertEquals(JsonPrimitive(2), rebased.statusVariables["hp"])
        assertEquals(2, rebased.messageNodes.size)
    }

    @Test
    fun `later explicit edit back to original text supersedes recorded preview message`() {
        val originalMessage = UIMessage.assistant("before")
        val rebaser = TavernPreviewMutationRebaser()
        rebaser.recordMessage(conversationId, originalMessage.id, "before", "preview", previewRevision = 2)
        val edited = Conversation(
            id = conversationId,
            assistantId = assistantId,
            messageNodes = listOf(
                originalMessage.copy(parts = listOf(UIMessagePart.Text("before"))).toMessageNode(),
            ),
            stateRevision = 2,
        )

        val accepted = rebaser.rebase(conversationId, edited)
        val laterStale = rebaser.rebase(
            conversationId,
            edited.copy(
                messageNodes = listOf(originalMessage.copy(parts = listOf(UIMessagePart.Text("before"))).toMessageNode()),
            ),
        )

        assertEquals("before", (accepted.currentMessages.single().parts.first() as UIMessagePart.Text).text)
        assertEquals("before", (laterStale.currentMessages.single().parts.first() as UIMessagePart.Text).text)
    }

    @Test
    fun `later explicit variable deletion supersedes preview addition`() {
        val before = Conversation(
            id = conversationId,
            assistantId = assistantId,
            messageNodes = emptyList(),
            stateRevision = 1,
        )
        val previewVariables = buildJsonObject { put("temporary", true) }
        val rebaser = TavernPreviewMutationRebaser()
        rebaser.recordVariables(
            conversationId,
            before.statusVariables,
            previewVariables,
            previewRevision = 2,
        )

        val explicitDeletion = before.copy(stateRevision = 2)
        val accepted = rebaser.rebase(conversationId, explicitDeletion)

        assertEquals(null, accepted.statusVariables["temporary"])
    }

    @Test
    fun `older divergent message snapshot is rebased to newer preview text`() {
        val message = UIMessage.assistant("A")
        val stale = Conversation(
            id = conversationId,
            assistantId = assistantId,
            messageNodes = listOf(message.toMessageNode()),
            stateRevision = 1,
        )
        val rebaser = TavernPreviewMutationRebaser()
        rebaser.recordMessage(
            conversationId,
            message.id,
            before = "B",
            after = "P",
            previewRevision = 3,
        )

        val rebased = rebaser.rebase(conversationId, stale)

        assertEquals("P", (rebased.currentMessages.single().parts.first() as UIMessagePart.Text).text)
    }

    @Test
    fun `older divergent variable snapshot is rebased to newer preview value`() {
        val stale = Conversation(
            id = conversationId,
            assistantId = assistantId,
            messageNodes = emptyList(),
            statusVariables = buildJsonObject { put("route", "A") },
            stateRevision = 1,
        )
        val rebaser = TavernPreviewMutationRebaser()
        rebaser.recordVariables(
            conversationId,
            before = buildJsonObject { put("route", "B") },
            after = buildJsonObject { put("route", "P") },
            previewRevision = 3,
        )

        val rebased = rebaser.rebase(conversationId, stale)

        assertEquals(JsonPrimitive("P"), rebased.statusVariables["route"])
    }

    @Test
    fun `older snapshot missing the preview message is rejected instead of persisted`() {
        val messageId = Uuid.parse("50000000-0000-4000-8000-000000000003")
        val stale = Conversation(
            id = conversationId,
            assistantId = assistantId,
            messageNodes = emptyList(),
            stateRevision = 1,
        )
        val rebaser = TavernPreviewMutationRebaser()
        rebaser.recordMessage(
            conversationId,
            messageId,
            before = "B",
            after = "P",
            previewRevision = 3,
        )

        org.junit.Assert.assertThrows(StaleTavernPreviewSnapshotException::class.java) {
            rebaser.rebase(conversationId, stale)
        }
    }

    @Test
    fun `session publication advances revision beyond current and incoming snapshots`() {
        val current = Conversation(
            id = conversationId,
            assistantId = assistantId,
            messageNodes = emptyList(),
            stateRevision = 7,
        )

        val advanced = advanceConversationRevision(current, current.copy(stateRevision = 3))

        assertEquals(8, advanced.stateRevision)
    }
}
