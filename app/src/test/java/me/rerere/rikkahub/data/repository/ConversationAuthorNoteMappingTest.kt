package me.rerere.rikkahub.data.repository

import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.AuthorNote
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * ConversationEntity 的 author_note 列与 Conversation.authorNote 的双向映射测试
 */
class ConversationAuthorNoteMappingTest {
    private val assistantId = Uuid.parse("00000000-0000-0000-0000-000000000020")

    @Test
    fun `entity mapping round trips author note`() {
        val note = AuthorNote(
            enabled = true,
            content = "会话作者注释",
            depth = 3,
            role = MessageRole.ASSISTANT,
            interval = 2,
        )
        val conversation = Conversation(
            assistantId = assistantId,
            messageNodes = emptyList(),
            authorNote = note,
        )

        val restored = conversationFromEntity(
            entity = conversationToEntity(conversation),
            messageNodes = emptyList(),
        )

        assertEquals(note, restored.authorNote)
    }

    @Test
    fun `null author note maps to null column and back`() {
        val conversation = Conversation(
            assistantId = assistantId,
            messageNodes = emptyList(),
            authorNote = null,
        )

        val entity = conversationToEntity(conversation)
        assertNull(entity.authorNote)

        val restored = conversationFromEntity(entity, emptyList())
        assertNull(restored.authorNote)
    }

    @Test
    fun `malformed author note json falls back to null`() {
        val entity = conversationToEntity(
            Conversation(assistantId = assistantId, messageNodes = emptyList())
        ).copy(authorNote = "{broken")

        val restored = conversationFromEntity(entity, emptyList())

        assertNull(restored.authorNote)
    }
}
