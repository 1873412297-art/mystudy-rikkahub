package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 世界书关键词「整词匹配」（SillyTavern Match Whole Words 对齐）的管线语义测试。
 *
 * 默认（matchWholeWords = false）保持子串匹配以兼容存量条目；
 * 开启后关键词必须作为独立词出现（前后不得紧邻字母/数字/下划线/CJK 字），
 * 避免 "apple" 误命中 "pineapple"、或 "云山" 误命中 "云山派"。
 */
class PromptInjectionWholeWordTest {

    // region helpers

    private fun createEntry(
        keywords: List<String> = listOf("trigger"),
        matchWholeWords: Boolean = false,
        useRegex: Boolean = false,
        caseSensitive: Boolean = false,
        secondaryKeywords: List<String> = emptyList(),
        selective: Boolean = false,
        content: String = "whole word lore",
    ) = PromptInjection.RegexInjection(
        id = Uuid.random(),
        name = "entry",
        enabled = true,
        priority = 0,
        position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        content = content,
        keywords = keywords,
        secondaryKeywords = secondaryKeywords,
        selective = selective,
        probability = 100,
        scanDepth = 10,
        constantActive = false,
        useRegex = useRegex,
        caseSensitive = caseSensitive,
        matchWholeWords = matchWholeWords,
    )

    private fun transformWithLorebook(
        entry: PromptInjection.RegexInjection,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val book = Lorebook(id = Uuid.random(), name = "book", entries = listOf(entry))
        return transformMessages(
            messages = messages,
            assistant = Assistant(lorebookIds = setOf(book.id)),
            modeInjections = emptyList(),
            lorebooks = listOf(book),
        )
    }

    private fun systemText(result: List<UIMessage>): String =
        result.first().parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    private fun containsLore(result: List<UIMessage>): Boolean = systemText(result).contains("whole word lore")

    // endregion

    @Test
    fun `default keeps substring matching for backward compatibility`() {
        val entry = createEntry(keywords = listOf("apple"), matchWholeWords = false)
        val result = transformWithLorebook(entry, listOf(UIMessage.system("sys"), UIMessage.user("a pineapple")))
        assertTrue(containsLore(result))
    }

    @Test
    fun `whole word does not match inside larger word`() {
        val entry = createEntry(keywords = listOf("apple"), matchWholeWords = true)
        val result = transformWithLorebook(entry, listOf(UIMessage.system("sys"), UIMessage.user("a pineapple")))
        assertFalse(containsLore(result))
    }

    @Test
    fun `whole word matches standalone word`() {
        val entry = createEntry(keywords = listOf("apple"), matchWholeWords = true)
        val result = transformWithLorebook(entry, listOf(UIMessage.system("sys"), UIMessage.user("I ate an apple today.")))
        assertTrue(containsLore(result))
    }

    @Test
    fun `whole word is case insensitive by default`() {
        val entry = createEntry(keywords = listOf("apple"), matchWholeWords = true)
        val result = transformWithLorebook(entry, listOf(UIMessage.system("sys"), UIMessage.user("She picked an Apple.")))
        assertTrue(containsLore(result))
    }

    @Test
    fun `whole word respects case sensitivity`() {
        val entry = createEntry(keywords = listOf("apple"), matchWholeWords = true, caseSensitive = true)
        val miss = transformWithLorebook(entry, listOf(UIMessage.system("sys"), UIMessage.user("She picked an Apple.")))
        assertFalse(containsLore(miss))

        val hit = transformWithLorebook(entry, listOf(UIMessage.system("sys"), UIMessage.user("she picked an apple.")))
        assertTrue(containsLore(hit))
    }

    @Test
    fun `whole word treats cjk as substring like sillytavern`() {
        // SillyTavern 的 \b 仅识别 ASCII 词边界，CJK 关键字保持子串匹配
        val entry = createEntry(keywords = listOf("云山"), matchWholeWords = true)

        val insideLarger = transformWithLorebook(entry, listOf(UIMessage.system("sys"), UIMessage.user("他来自云山派。")))
        assertTrue(containsLore(insideLarger))

        val standalone = transformWithLorebook(entry, listOf(UIMessage.system("sys"), UIMessage.user("我们到了云山。")))
        assertTrue(containsLore(standalone))
    }

    @Test
    fun `whole word applies to secondary keywords in selective mode`() {
        val entry = createEntry(
            keywords = listOf("castle"),
            secondaryKeywords = listOf("sword"),
            selective = true,
            matchWholeWords = true,
        )

        val insideWord = transformWithLorebook(
            entry,
            listOf(UIMessage.system("sys"), UIMessage.user("a castle and a broadsword")),
        )
        assertFalse(containsLore(insideWord))

        val standalone = transformWithLorebook(
            entry,
            listOf(UIMessage.system("sys"), UIMessage.user("a castle and a sword")),
        )
        assertTrue(containsLore(standalone))
    }

    @Test
    fun `whole word is ignored when useRegex is enabled`() {
        // 正则模式是显式匹配，整词匹配标志不叠加（与 SillyTavern 的「正则/整词」互斥语义一致）
        val entry = createEntry(
            keywords = listOf("\\bapple\\b"),
            useRegex = true,
            matchWholeWords = true,
        )
        val result = transformWithLorebook(entry, listOf(UIMessage.system("sys"), UIMessage.user("a pineapple apple")))
        assertTrue(containsLore(result))
    }
}
