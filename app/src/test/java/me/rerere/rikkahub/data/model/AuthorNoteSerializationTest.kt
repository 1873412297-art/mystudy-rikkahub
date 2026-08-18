package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 作者注释（AuthorNote）及 Assistant/Conversation 新字段的 JSON 序列化向后兼容测试
 */
class AuthorNoteSerializationTest {

    private val oldAssistantJson = """
        {
          "id": "00000000-0000-4000-8000-000000000220",
          "name": "legacy assistant",
          "systemPrompt": "You are helpful."
        }
    """.trimIndent()

    private val oldConversationJson = """
        {
          "id": "00000000-0000-4000-8000-000000000221",
          "assistantId": "00000000-0000-4000-8000-000000000220",
          "title": "legacy chat",
          "messageNodes": []
        }
    """.trimIndent()

    @Test
    fun `old assistant json without author note deserializes with defaults`() {
        val assistant = JsonInstant.decodeFromString(Assistant.serializer(), oldAssistantJson)

        assertEquals("legacy assistant", assistant.name)
        assertEquals(AuthorNote(), assistant.authorNote)
        assertFalse(assistant.authorNote.enabled)
        assertEquals("", assistant.authorNote.content)
        assertEquals(4, assistant.authorNote.depth)
        assertEquals(MessageRole.USER, assistant.authorNote.role)
        assertEquals(1, assistant.authorNote.interval)
        assertFalse(assistant.allowConversationAuthorNote)
    }

    @Test
    fun `old conversation json without author note deserializes with null`() {
        val conversation = JsonInstant.decodeFromString(Conversation.serializer(), oldConversationJson)

        assertEquals("legacy chat", conversation.title)
        assertNull(conversation.authorNote)
    }

    @Test
    fun `assistant author note round trips through json`() {
        val note = AuthorNote(
            enabled = true,
            content = "保持严肃",
            depth = 2,
            role = MessageRole.ASSISTANT,
            interval = 3,
        )
        val assistant = Assistant(
            id = Uuid.parse("00000000-0000-4000-8000-000000000222"),
            authorNote = note,
            allowConversationAuthorNote = true,
        )

        val decoded = JsonInstant.decodeFromString(
            Assistant.serializer(),
            JsonInstant.encodeToString(Assistant.serializer(), assistant)
        )

        assertEquals(note, decoded.authorNote)
        assertTrue(decoded.allowConversationAuthorNote)
    }

    @Test
    fun `conversation author note round trips through json`() {
        val note = AuthorNote(
            enabled = true,
            content = "会话级注释",
            depth = 5,
            role = MessageRole.USER,
            interval = 2,
        )
        val conversation = Conversation(
            id = Uuid.parse("00000000-0000-4000-8000-000000000223"),
            assistantId = Uuid.parse("00000000-0000-4000-8000-000000000220"),
            messageNodes = emptyList(),
            authorNote = note,
        )

        val decoded = JsonInstant.decodeFromString(
            Conversation.serializer(),
            JsonInstant.encodeToString(Conversation.serializer(), conversation)
        )

        assertEquals(note, decoded.authorNote)
    }
}
