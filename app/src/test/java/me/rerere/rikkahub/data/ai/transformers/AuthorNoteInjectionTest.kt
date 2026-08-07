package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.trace.PromptInjectionSourceType
import me.rerere.rikkahub.data.ai.trace.PromptInjectionTrace
import me.rerere.rikkahub.data.ai.trace.PromptTraceRecorder
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AuthorNote
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AuthorNoteInjectionTest {

    // region Helpers
    private fun assistantWithNote(
        note: AuthorNote,
        allowConversationAuthorNote: Boolean = false,
        modeInjectionIds: Set<Uuid> = emptySet(),
        lorebookIds: Set<Uuid> = emptySet(),
    ) = Assistant(
        authorNote = note,
        allowConversationAuthorNote = allowConversationAuthorNote,
        modeInjectionIds = modeInjectionIds,
        lorebookIds = lorebookIds,
    )

    private fun getMessageText(message: UIMessage): String {
        return message.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("") { it.text }
    }

    private fun createAssistantWithUnexecutedTool(toolCallId: String, toolName: String): UIMessage {
        return UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = toolCallId,
                    toolName = toolName,
                    input = "{}",
                    output = emptyList()
                )
            )
        )
    }

    private class RecordingTraceRecorder : PromptTraceRecorder {
        val hits = mutableListOf<PromptInjectionTrace>()
        override fun recordInjectionHits(hits: List<PromptInjectionTrace>) {
            this.hits += hits
        }
    }
    // endregion

    // region 基本注入行为
    @Test
    fun `author note injects at specified depth with configured role`() {
        val note = AuthorNote(
            enabled = true,
            content = "保持严肃克制的文风",
            depth = 2,
            role = MessageRole.ASSISTANT,
        )
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Message 1"),
            UIMessage.assistant("Response 1"),
            UIMessage.user("Message 2"),
            UIMessage.assistant("Response 2")
        )

        val result = transformMessages(
            messages = messages,
            assistant = assistantWithNote(note),
            modeInjections = emptyList(),
            lorebooks = emptyList()
        )

        // depth=2 → 插入到倒数第 2 条之前（index 3）
        assertEquals(6, result.size)
        assertEquals(MessageRole.ASSISTANT, result[3].role)
        assertEquals("保持严肃克制的文风", getMessageText(result[3]))
        assertEquals(MessageRole.USER, result[4].role)
        assertEquals("Message 2", getMessageText(result[4]))
    }

    @Test
    fun `disabled author note does not inject`() {
        val note = AuthorNote(enabled = false, content = "Should not appear", depth = 1)
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello")
        )

        val result = transformMessages(
            messages = messages,
            assistant = assistantWithNote(note),
            modeInjections = emptyList(),
            lorebooks = emptyList()
        )

        assertEquals(messages, result)
    }

    @Test
    fun `blank author note content does not inject`() {
        val note = AuthorNote(enabled = true, content = "   ", depth = 1)
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello")
        )

        val result = transformMessages(
            messages = messages,
            assistant = assistantWithNote(note),
            modeInjections = emptyList(),
            lorebooks = emptyList()
        )

        assertEquals(messages, result)
    }

    @Test
    fun `author note injects without system message`() {
        val note = AuthorNote(enabled = true, content = "AN", depth = 2)
        val messages = listOf(
            UIMessage.user("Message 1"),
            UIMessage.assistant("Response 1"),
            UIMessage.user("Message 2"),
            UIMessage.assistant("Response 2"),
            UIMessage.user("Message 3"),
        )

        val result = transformMessages(
            messages = messages,
            assistant = assistantWithNote(note),
            modeInjections = emptyList(),
            lorebooks = emptyList()
        )

        // 无系统消息时 AT_DEPTH 仍然生效：depth=2 → 插入到倒数第 2 条之前
        assertEquals(6, result.size)
        assertEquals("AN", getMessageText(result[3]))
        assertEquals("Response 2", getMessageText(result[4]))
        assertEquals(MessageRole.USER, result[3].role)
    }
    // endregion

    // region interval 间隔规则
    @Test
    fun `interval rule is deterministic`() {
        // interval=2：第 1、3、5… 轮注入，第 2、4… 轮跳过
        assertTrue(shouldInjectAuthorNoteAtUserTurn(1, 2))
        assertFalse(shouldInjectAuthorNoteAtUserTurn(2, 2))
        assertTrue(shouldInjectAuthorNoteAtUserTurn(3, 2))
        assertFalse(shouldInjectAuthorNoteAtUserTurn(4, 2))
        // interval=3：第 1、4、7… 轮注入
        assertTrue(shouldInjectAuthorNoteAtUserTurn(1, 3))
        assertFalse(shouldInjectAuthorNoteAtUserTurn(2, 3))
        assertFalse(shouldInjectAuthorNoteAtUserTurn(3, 3))
        assertTrue(shouldInjectAuthorNoteAtUserTurn(4, 3))
        // interval<=1：每轮都注入
        assertTrue(shouldInjectAuthorNoteAtUserTurn(1, 1))
        assertTrue(shouldInjectAuthorNoteAtUserTurn(7, 1))
        assertTrue(shouldInjectAuthorNoteAtUserTurn(2, 0))
        // interval>1 且没有用户消息：不注入
        assertFalse(shouldInjectAuthorNoteAtUserTurn(0, 2))
    }

    @Test
    fun `interval 2 skips every other user turn`() {
        val note = AuthorNote(enabled = true, content = "AN", depth = 1, interval = 2)
        val assistant = assistantWithNote(note)

        // 第 1 轮（1 条用户消息）→ 注入
        val turn1 = listOf(UIMessage.user("u1"))
        val result1 = transformMessages(turn1, assistant, emptyList(), emptyList())
        assertEquals(2, result1.size)
        assertTrue(result1.any { getMessageText(it) == "AN" })

        // 第 2 轮（2 条用户消息）→ 跳过
        val turn2 = listOf(
            UIMessage.user("u1"),
            UIMessage.assistant("a1"),
            UIMessage.user("u2"),
        )
        val result2 = transformMessages(turn2, assistant, emptyList(), emptyList())
        assertEquals(turn2, result2)

        // 第 3 轮（3 条用户消息）→ 注入
        val turn3 = listOf(
            UIMessage.user("u1"),
            UIMessage.assistant("a1"),
            UIMessage.user("u2"),
            UIMessage.assistant("a2"),
            UIMessage.user("u3"),
        )
        val result3 = transformMessages(turn3, assistant, emptyList(), emptyList())
        assertEquals(6, result3.size)
        assertTrue(result3.any { getMessageText(it) == "AN" })
    }
    // endregion

    // region 会话级覆盖与门控
    @Test
    fun `conversation author note overrides assistant note when allowed`() {
        val assistantNote = AuthorNote(enabled = true, content = "ASST", depth = 1)
        val conversationNote = AuthorNote(enabled = true, content = "CONV", depth = 1)
        val recorder = RecordingTraceRecorder()
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello"),
        )

        val result = transformMessagesWithTrace(
            messages = messages,
            assistant = assistantWithNote(assistantNote, allowConversationAuthorNote = true),
            modeInjections = emptyList(),
            lorebooks = emptyList(),
            conversationAuthorNote = conversationNote,
            promptTraceRecorder = recorder,
        )

        assertEquals(3, result.messages.size)
        assertTrue(result.messages.any { getMessageText(it) == "CONV" })
        assertFalse(result.messages.any { getMessageText(it) == "ASST" })
        // trace 中会话级注释以「会话作者注释」命名，便于排查来源
        assertEquals(1, recorder.hits.size)
        assertEquals("会话作者注释", recorder.hits.single().injectionName)
    }

    @Test
    fun `conversation author note ignored when assistant disallows override`() {
        val assistantNote = AuthorNote(enabled = true, content = "ASST", depth = 1)
        val conversationNote = AuthorNote(enabled = true, content = "CONV", depth = 1)
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello"),
        )

        val result = transformMessages(
            messages = messages,
            assistant = assistantWithNote(assistantNote, allowConversationAuthorNote = false),
            modeInjections = emptyList(),
            lorebooks = emptyList(),
            conversationAuthorNote = conversationNote,
        )

        assertEquals(3, result.size)
        assertTrue(result.any { getMessageText(it) == "ASST" })
        assertFalse(result.any { getMessageText(it) == "CONV" })
    }

    @Test
    fun `disabled conversation note falls back to assistant note`() {
        val assistantNote = AuthorNote(enabled = true, content = "ASST", depth = 1)
        val conversationNote = AuthorNote(enabled = false, content = "CONV", depth = 1)
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello"),
        )

        val result = transformMessages(
            messages = messages,
            assistant = assistantWithNote(assistantNote, allowConversationAuthorNote = true),
            modeInjections = emptyList(),
            lorebooks = emptyList(),
            conversationAuthorNote = conversationNote,
        )

        assertEquals(3, result.size)
        assertTrue(result.any { getMessageText(it) == "ASST" })
        assertFalse(result.any { getMessageText(it) == "CONV" })
    }

    @Test
    fun `no effective note when both disabled`() {
        val assistantNote = AuthorNote(enabled = false, content = "ASST", depth = 1)
        val conversationNote = AuthorNote(enabled = false, content = "CONV", depth = 1)
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello"),
        )

        val result = transformMessages(
            messages = messages,
            assistant = assistantWithNote(assistantNote, allowConversationAuthorNote = true),
            modeInjections = emptyList(),
            lorebooks = emptyList(),
            conversationAuthorNote = conversationNote,
        )

        assertEquals(messages, result)
    }
    // endregion

    // region 与其他注入共存
    @Test
    fun `author note coexists with mode and lorebook injections in deterministic order`() {
        val modeInjection = PromptInjection.ModeInjection(
            id = Uuid.random(),
            position = InjectionPosition.AFTER_SYSTEM_PROMPT,
            content = "MODE",
        )
        val lorebook = Lorebook(
            id = Uuid.random(),
            entries = listOf(
                PromptInjection.RegexInjection(
                    id = Uuid.random(),
                    position = InjectionPosition.AT_DEPTH,
                    injectDepth = 2,
                    role = MessageRole.USER,
                    priority = 10,
                    constantActive = true,
                    content = "LORE",
                )
            )
        )
        val note = AuthorNote(enabled = true, content = "AN", depth = 2, role = MessageRole.USER)
        val assistant = assistantWithNote(
            note = note,
            modeInjectionIds = setOf(modeInjection.id),
            lorebookIds = setOf(lorebook.id),
        )
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello"),
            UIMessage.assistant("Hi!"),
        )

        val result = transformMessages(
            messages = messages,
            assistant = assistant,
            modeInjections = listOf(modeInjection),
            lorebooks = listOf(lorebook),
        )

        // mode 注入进入系统消息；author note 与 lorebook 同深度同 role 合并为一条消息
        assertEquals(4, result.size)
        assertTrue(getMessageText(result[0]).contains("MODE"))
        val mergedText = getMessageText(result[1])
        assertTrue(mergedText.contains("LORE"))
        assertTrue(mergedText.contains("AN"))
        // 按优先级降序：priority=10 的 LORE 在 priority=0 的 AN 之前
        assertTrue(mergedText.indexOf("LORE") < mergedText.indexOf("AN"))
        assertEquals("Hello", getMessageText(result[2]))
    }

    @Test
    fun `author note avoids inserting between user and assistant tool message`() {
        val note = AuthorNote(enabled = true, content = "AN", depth = 1)
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello"),
            createAssistantWithUnexecutedTool("call_1", "search"),
        )

        val result = transformMessages(
            messages = messages,
            assistant = assistantWithNote(note),
            modeInjections = emptyList(),
            lorebooks = emptyList()
        )

        // depth=1 原本落在 USER → ASSISTANT(tool) 之间，findSafeInsertIndex 应向前避让
        assertEquals(4, result.size)
        assertEquals("AN", getMessageText(result[1]))
        assertEquals("Hello", getMessageText(result[2]))
        assertEquals(MessageRole.ASSISTANT, result[3].role)
    }
    // endregion

    // region Trace
    @Test
    fun `trace records author note with AUTHOR_NOTE source type`() {
        val note = AuthorNote(
            enabled = true,
            content = "AN",
            depth = 2,
            role = MessageRole.ASSISTANT,
        )
        val recorder = RecordingTraceRecorder()
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Message 1"),
            UIMessage.assistant("Response 1"),
            UIMessage.user("Message 2"),
        )

        val result = transformMessagesWithTrace(
            messages = messages,
            assistant = assistantWithNote(note),
            modeInjections = emptyList(),
            lorebooks = emptyList(),
            promptTraceRecorder = recorder,
        )

        assertEquals(1, recorder.hits.size)
        val hit = recorder.hits.single()
        assertEquals(PromptInjectionSourceType.AUTHOR_NOTE, hit.sourceType)
        assertEquals("作者注释", hit.injectionName)
        assertEquals("AT_DEPTH", hit.position)
        assertEquals(2, hit.injectDepth)
        assertEquals(MessageRole.ASSISTANT, hit.role)
        assertEquals("AN", hit.content)
        // 注入目标索引指向最终消息列表中的合并消息
        val target = result.messages[hit.targetMessageIndex!!]
        assertEquals("AN", getMessageText(target))
        assertEquals(hit.targetMessageId, target.id)
    }
    // endregion
}
