package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AuthorNote
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 作者注释「注入位置」扩展（SillyTavern Author's Note Position 对齐）的测试。
 *
 * 默认 AT_DEPTH 保持既有行为；新增 TOP_OF_CHAT（聊天开头）与 BOTTOM_OF_CHAT（最新消息之前）。
 */
class AuthorNotePositionTest {

    private fun assistantWithNote(note: AuthorNote) = Assistant(authorNote = note)

    private fun getMessageText(message: UIMessage): String {
        return message.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("") { it.text }
    }

    private val messages = listOf(
        UIMessage.system("System prompt"),
        UIMessage.user("Message 1"),
        UIMessage.assistant("Response 1"),
        UIMessage.user("Message 2"),
        UIMessage.assistant("Response 2"),
    )

    private fun transformWith(note: AuthorNote): List<UIMessage> = transformMessages(
        messages = messages,
        assistant = assistantWithNote(note),
        modeInjections = emptyList(),
        lorebooks = emptyList(),
    )

    @Test
    fun `author note defaults to at depth injection`() {
        val note = AuthorNote(enabled = true, content = "note", depth = 2, role = MessageRole.USER)
        val result = transformWith(note)

        // depth=2 → 插入到倒数第 2 条之前（index 3），保持既有行为
        assertEquals(6, result.size)
        assertEquals("note", getMessageText(result[3]))
    }

    @Test
    fun `top of chat position injects before first user message`() {
        val note = AuthorNote(
            enabled = true,
            content = "top note",
            depth = 4,
            role = MessageRole.USER,
            position = InjectionPosition.TOP_OF_CHAT,
        )
        val result = transformWith(note)

        assertEquals(6, result.size)
        // 系统提示之后、第一条 USER 之前
        val noteIndex = result.indexOfFirst { getMessageText(it) == "top note" }
        assertEquals(1, noteIndex)
        // 注入管线仅区分 USER/ASSISTANT 角色（SYSTEM 会归一为 USER），此处断言位置语义
        assertEquals(MessageRole.USER, result[noteIndex].role)
        assertEquals(MessageRole.USER, result[noteIndex + 1].role)
    }

    @Test
    fun `bottom of chat position injects right before last message`() {
        val note = AuthorNote(
            enabled = true,
            content = "bottom note",
            depth = 1,
            role = MessageRole.USER,
            position = InjectionPosition.BOTTOM_OF_CHAT,
        )
        val result = transformWith(note)

        assertEquals(6, result.size)
        val noteIndex = result.indexOfFirst { getMessageText(it) == "bottom note" }
        // 位于最后一条（Response 2）之前
        assertEquals(result.size - 2, noteIndex)
    }

    @Test
    fun `position serializes with at depth default`() {
        // 旧 JSON 缺 position -> 默认 AT_DEPTH
        val legacy = JsonInstant.decodeFromString(
            AuthorNote.serializer(),
            """{"enabled":true,"content":"c","depth":3,"role":"user","interval":1}""",
        )
        assertEquals(InjectionPosition.AT_DEPTH, legacy.position)

        // 显式 TOP_OF_CHAT 可序列化并读回
        val withPosition = AuthorNote(enabled = true, content = "c", position = InjectionPosition.TOP_OF_CHAT)
        val encoded = JsonInstant.encodeToString(AuthorNote.serializer(), withPosition)
        val decoded = JsonInstant.decodeFromString(AuthorNote.serializer(), encoded)
        assertEquals(InjectionPosition.TOP_OF_CHAT, decoded.position)
    }
}
