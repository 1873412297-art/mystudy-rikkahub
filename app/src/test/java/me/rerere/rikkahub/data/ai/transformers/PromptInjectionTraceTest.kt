package me.rerere.rikkahub.data.ai.transformers

import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.trace.PromptInjectionMatchType
import me.rerere.rikkahub.data.ai.trace.PromptInjectionSourceType
import me.rerere.rikkahub.data.ai.trace.PromptInjectionTrace
import me.rerere.rikkahub.data.ai.trace.PromptTraceRecorder
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.uuid.Uuid

class PromptInjectionTraceTest {
    @Test
    fun `throwing trace recorder does not change transformer output`() {
        val injection = PromptInjection.ModeInjection(
            content = "after",
            position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        )
        val assistant = Assistant(modeInjectionIds = setOf(injection.id))
        val messages = listOf(UIMessage.system("system"), UIMessage.user("hello"))
        var recordAttempts = 0
        val withoutRecorder = transformMessagesWithTrace(
            messages = messages,
            assistant = assistant,
            modeInjections = listOf(injection),
            lorebooks = emptyList(),
            promptTraceRecorder = null,
        )

        val withThrowingRecorder = transformMessagesWithTrace(
            messages = messages,
            assistant = assistant,
            modeInjections = listOf(injection),
            lorebooks = emptyList(),
            promptTraceRecorder = object : PromptTraceRecorder {
                override fun recordInjectionHits(hits: List<PromptInjectionTrace>) {
                    recordAttempts++
                    throw IllegalStateException("trace storage failed")
                }
            },
        )

        assertEquals(withoutRecorder.messages, withThrowingRecorder.messages)
        assertEquals(1, recordAttempts)
    }

    @Test
    fun `trace recorder cancellation is propagated`() {
        val cancellation = CancellationException("cancel trace")

        try {
            transformMessagesWithTrace(
                messages = listOf(UIMessage.user("hello")),
                assistant = Assistant(),
                modeInjections = emptyList(),
                lorebooks = emptyList(),
                promptTraceRecorder = object : PromptTraceRecorder {
                    override fun recordInjectionHits(hits: List<PromptInjectionTrace>) {
                        throw cancellation
                    }
                },
            )
            fail("Expected CancellationException")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun `fatal trace recorder error is propagated`() {
        val fatal = AssertionError("fatal trace failure")

        try {
            transformMessagesWithTrace(
                messages = listOf(UIMessage.user("hello")),
                assistant = Assistant(),
                modeInjections = emptyList(),
                lorebooks = emptyList(),
                promptTraceRecorder = object : PromptTraceRecorder {
                    override fun recordInjectionHits(hits: List<PromptInjectionTrace>) {
                        throw fatal
                    }
                },
            )
            fail("Expected AssertionError")
        } catch (actual: AssertionError) {
            assertSame(fatal, actual)
        }
    }

    @Test
    fun `keyword match drives output and trace from one evaluation`() {
        val lorebookId = Uuid.random()
        val scannedUser = UIMessage.user("我去找密门")
        val scannedAssistant = UIMessage.assistant("门在走廊尽头")
        val entry = PromptInjection.RegexInjection(
            name = "门",
            keywords = listOf("密门", "不存在"),
            content = "门后藏着线索。",
            scanDepth = 2,
        )

        val result = transformMessagesWithTrace(
            messages = listOf(
                UIMessage.system("系统提示"),
                UIMessage.user("更早的消息"),
                scannedUser,
                scannedAssistant,
            ),
            assistant = Assistant(lorebookIds = setOf(lorebookId)),
            modeInjections = emptyList(),
            lorebooks = listOf(Lorebook(id = lorebookId, name = "宅邸", entries = listOf(entry))),
        )

        assertTrue(result.messages.first().toText().contains("门后藏着线索。"))
        val applied = result.applied.single()
        assertEquals(PromptInjectionSourceType.LOREBOOK, applied.collected.sourceType)
        assertEquals(PromptInjectionMatchType.KEYWORD, applied.collected.match?.type)
        assertEquals(listOf("密门"), applied.collected.match?.matchedTerms)
        assertEquals(listOf(scannedUser.id, scannedAssistant.id), applied.collected.match?.scannedMessageIds)
        assertEquals(lorebookId, applied.collected.lorebookId)
        assertEquals("宅邸", applied.collected.lorebookName)
        assertEquals(result.messages.first().id, applied.targetMessageId)
        assertEquals(0, applied.targetMessageIndex)
    }

    @Test
    fun `mode provenance preserves placement role priority and actual merged order`() {
        val low = PromptInjection.ModeInjection(
            name = "低优先级",
            priority = 2,
            position = InjectionPosition.TOP_OF_CHAT,
            role = MessageRole.ASSISTANT,
            content = "low",
        )
        val high = PromptInjection.ModeInjection(
            name = "高优先级",
            priority = 9,
            position = InjectionPosition.TOP_OF_CHAT,
            role = MessageRole.ASSISTANT,
            content = "high",
        )
        val messages = listOf(UIMessage.system("system"), UIMessage.user("hello"))

        val result = transformMessagesWithTrace(
            messages = messages,
            assistant = Assistant(modeInjectionIds = setOf(low.id, high.id)),
            modeInjections = listOf(low, high),
            lorebooks = emptyList(),
        )

        assertEquals(listOf("system", "high\nlow", "hello"), result.messages.map { it.toText() })
        assertEquals(listOf(high.id, low.id), result.applied.map { it.collected.injection.id })
        result.applied.forEach { applied ->
            assertEquals(PromptInjectionSourceType.MODE, applied.collected.sourceType)
            assertNull(applied.collected.match)
            assertEquals(result.messages[1].id, applied.targetMessageId)
            assertEquals(1, applied.targetMessageIndex)
        }

        val highTrace = result.applied.first().toTrace()
        assertEquals(InjectionPosition.TOP_OF_CHAT.name, highTrace.position)
        assertEquals(MessageRole.ASSISTANT, highTrace.role)
        assertEquals(9, highTrace.priority)
        assertEquals(4, highTrace.injectDepth)
        assertEquals(1, highTrace.targetMessageIndex)
    }

    @Test
    fun `regex provenance records every matched pattern and exact inserted target`() {
        val lorebookId = Uuid.random()
        val originalUser = UIMessage.user("MAGIC spell")
        val entry = PromptInjection.RegexInjection(
            name = "正则",
            keywords = listOf("mag.*", "spell", "missing"),
            useRegex = true,
            caseSensitive = false,
            position = InjectionPosition.TOP_OF_CHAT,
            content = originalUser.toText(),
        )

        val result = transformMessagesWithTrace(
            messages = listOf(originalUser),
            assistant = Assistant(lorebookIds = setOf(lorebookId)),
            modeInjections = emptyList(),
            lorebooks = listOf(Lorebook(id = lorebookId, entries = listOf(entry))),
        )

        val applied = result.applied.single()
        assertEquals(PromptInjectionMatchType.REGEX, applied.collected.match?.type)
        assertEquals(listOf("mag.*", "spell"), applied.collected.match?.matchedTerms)
        assertEquals(false, applied.collected.match?.caseSensitive)
        assertEquals(true, applied.collected.match?.regexEnabled)
        assertEquals(0, applied.targetMessageIndex)
        assertEquals(result.messages[0].id, applied.targetMessageId)
        assertEquals(originalUser.id, result.messages[1].id)
    }

    @Test
    fun `constant applies while invalid regex disabled and unmatched entries are not recorded`() {
        val lorebookId = Uuid.random()
        val disabledMode = PromptInjection.ModeInjection(
            name = "禁用模式",
            enabled = false,
            content = "Disabled mode",
        )
        val constant = PromptInjection.RegexInjection(
            name = "常驻",
            constantActive = true,
            content = "Always active",
        )
        val invalid = PromptInjection.RegexInjection(
            name = "坏正则",
            keywords = listOf("["),
            useRegex = true,
            content = "Never active",
        )
        val disabled = PromptInjection.RegexInjection(
            name = "已禁用",
            enabled = false,
            keywords = listOf("hello"),
            content = "Disabled",
        )
        val unmatched = PromptInjection.RegexInjection(
            name = "未命中",
            keywords = listOf("missing"),
            content = "Unmatched",
        )

        val result = transformMessagesWithTrace(
            messages = listOf(UIMessage.user("hello")),
            assistant = Assistant(
                modeInjectionIds = setOf(disabledMode.id),
                lorebookIds = setOf(lorebookId),
            ),
            modeInjections = listOf(disabledMode),
            lorebooks = listOf(
                Lorebook(
                    id = lorebookId,
                    entries = listOf(constant, invalid, disabled, unmatched),
                )
            ),
        )

        assertEquals(listOf(constant.id), result.applied.map { it.collected.injection.id })
        assertEquals(PromptInjectionMatchType.CONSTANT, result.applied.single().collected.match?.type)
        assertEquals(emptyList<String>(), result.applied.single().collected.match?.matchedTerms)
    }

    @Test
    fun `empty system injections are omitted from applied provenance`() {
        val emptyBefore = PromptInjection.ModeInjection(
            name = "空 before",
            position = InjectionPosition.BEFORE_SYSTEM_PROMPT,
            content = "",
        )
        val nonEmptyAfter = PromptInjection.ModeInjection(
            name = "非空 after",
            position = InjectionPosition.AFTER_SYSTEM_PROMPT,
            content = "after",
        )
        var recordedHits: List<PromptInjectionTrace> = emptyList()

        val result = transformMessagesWithTrace(
            messages = listOf(UIMessage.user("hello")),
            assistant = Assistant(modeInjectionIds = setOf(emptyBefore.id, nonEmptyAfter.id)),
            modeInjections = listOf(emptyBefore, nonEmptyAfter),
            lorebooks = emptyList(),
            promptTraceRecorder = object : PromptTraceRecorder {
                override fun recordInjectionHits(hits: List<PromptInjectionTrace>) {
                    recordedHits = hits
                }
            },
        )

        assertEquals(listOf("after", "hello"), result.messages.map { it.toText() })
        assertEquals(listOf(nonEmptyAfter.id), result.applied.map { it.collected.injection.id })
        assertEquals(listOf(nonEmptyAfter.id), recordedHits.map { it.injectionId })
        assertEquals(result.messages[0].id, result.applied.single().targetMessageId)
        assertEquals(0, result.applied.single().targetMessageIndex)
    }

    @Test
    fun `empty system injection with no output has no applied provenance`() {
        val emptyAfter = PromptInjection.ModeInjection(
            position = InjectionPosition.AFTER_SYSTEM_PROMPT,
            content = "",
        )
        val messages = listOf(UIMessage.system("system"), UIMessage.user("hello"))

        val result = transformMessagesWithTrace(
            messages = messages,
            assistant = Assistant(modeInjectionIds = setOf(emptyAfter.id)),
            modeInjections = listOf(emptyAfter),
            lorebooks = emptyList(),
        )

        assertEquals(messages, result.messages)
        assertTrue(result.applied.isEmpty())
    }

    @Test
    fun `duplicate message ids do not change the exact target index`() {
        val duplicateId = Uuid.random()
        val system = UIMessage.system("system").copy(id = duplicateId)
        val user = UIMessage.user("hello").copy(id = duplicateId)
        val injection = PromptInjection.ModeInjection(
            position = InjectionPosition.AFTER_SYSTEM_PROMPT,
            content = "after",
        )

        val result = transformMessagesWithTrace(
            messages = listOf(system, user),
            assistant = Assistant(modeInjectionIds = setOf(injection.id)),
            modeInjections = listOf(injection),
            lorebooks = emptyList(),
        )

        assertEquals(duplicateId, result.applied.single().targetMessageId)
        assertEquals(0, result.applied.single().targetMessageIndex)
    }

    @Test
    fun `no applied injections preserve the original message list instance`() {
        val messages = listOf(UIMessage.user("hello"))

        val result = transformMessagesWithTrace(
            messages = messages,
            assistant = Assistant(),
            modeInjections = emptyList(),
            lorebooks = emptyList(),
        )

        assertSame(messages, result.messages)
        assertTrue(result.applied.isEmpty())
    }
}
