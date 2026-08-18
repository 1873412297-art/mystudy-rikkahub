package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.trace.PromptInjectionTrace
import me.rerere.rikkahub.data.ai.trace.PromptTraceRecorder
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 世界书 SillyTavern 对齐字段（selective / probability / tokenBudget / recursiveScanning）的管线语义测试
 */
class PromptInjectionLorebookExtensionsTest {

    // region helpers
    private class FixedRandom(private val value: Int) : kotlin.random.Random() {
        override fun nextBits(bitCount: Int): Int = value
        override fun nextInt(until: Int): Int {
            require(until > 0)
            return value % until
        }
    }

    private fun createAssistant(
        lorebookIds: Set<Uuid> = emptySet(),
    ) = Assistant(lorebookIds = lorebookIds)

    private fun createEntry(
        id: Uuid = Uuid.random(),
        name: String = "entry",
        enabled: Boolean = true,
        priority: Int = 0,
        position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        content: String = "content",
        keywords: List<String> = listOf("trigger"),
        secondaryKeywords: List<String> = emptyList(),
        selective: Boolean = false,
        probability: Int = 100,
        scanDepth: Int = 10,
        constantActive: Boolean = false,
        useRegex: Boolean = false,
        caseSensitive: Boolean = false,
    ) = PromptInjection.RegexInjection(
        id = id,
        name = name,
        enabled = enabled,
        priority = priority,
        position = position,
        content = content,
        keywords = keywords,
        secondaryKeywords = secondaryKeywords,
        selective = selective,
        probability = probability,
        scanDepth = scanDepth,
        constantActive = constantActive,
        useRegex = useRegex,
        caseSensitive = caseSensitive,
    )

    private fun createLorebook(
        id: Uuid = Uuid.random(),
        entries: List<PromptInjection.RegexInjection>,
        tokenBudget: Int = 0,
        recursiveScanning: Boolean = false,
    ) = Lorebook(
        id = id,
        name = "book",
        entries = entries,
        tokenBudget = tokenBudget,
        recursiveScanning = recursiveScanning,
    )

    private fun getMessageText(message: UIMessage): String {
        return message.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("") { it.text }
    }

    private fun transformWithLorebook(
        lorebook: Lorebook,
        messages: List<UIMessage>,
        random: kotlin.random.Random = FixedRandom(0),
    ): List<UIMessage> = transformMessages(
        messages = messages,
        assistant = createAssistant(lorebookIds = setOf(lorebook.id)),
        modeInjections = emptyList(),
        lorebooks = listOf(lorebook),
        random = random,
    )

    private fun systemText(result: List<UIMessage>): String = getMessageText(result.first())
    // endregion

    // region selective
    @Test
    fun `selective entry requires both primary and secondary keywords to hit`() {
        val entry = createEntry(
            keywords = listOf("hero"),
            secondaryKeywords = listOf("sword"),
            selective = true,
            content = "selective lore",
        )
        val book = createLorebook(entries = listOf(entry))

        val onlyPrimary = transformWithLorebook(
            book,
            listOf(UIMessage.system("sys"), UIMessage.user("a hero without weapon")),
        )
        assertFalse(systemText(onlyPrimary).contains("selective lore"))

        val onlySecondary = transformWithLorebook(
            book,
            listOf(UIMessage.system("sys"), UIMessage.user("a sword without wielder")),
        )
        assertFalse(systemText(onlySecondary).contains("selective lore"))

        val both = transformWithLorebook(
            book,
            listOf(UIMessage.system("sys"), UIMessage.user("a hero with a sword")),
        )
        assertTrue(systemText(both).contains("selective lore"))
    }

    @Test
    fun `selective with empty secondary keywords degrades to primary-only matching`() {
        val entry = createEntry(
            keywords = listOf("hero"),
            secondaryKeywords = emptyList(),
            selective = true,
            content = "primary only lore",
        )
        val book = createLorebook(entries = listOf(entry))

        val result = transformWithLorebook(
            book,
            listOf(UIMessage.system("sys"), UIMessage.user("a hero appears")),
        )
        assertTrue(systemText(result).contains("primary only lore"))
    }

    @Test
    fun `non selective entry ignores secondary keywords`() {
        val entry = createEntry(
            keywords = listOf("hero"),
            secondaryKeywords = listOf("sword"),
            selective = false,
            content = "normal lore",
        )
        val book = createLorebook(entries = listOf(entry))

        val result = transformWithLorebook(
            book,
            listOf(UIMessage.system("sys"), UIMessage.user("a hero appears")),
        )
        assertTrue(systemText(result).contains("normal lore"))
    }
    // endregion

    // region probability
    @Test
    fun `probability 100 always injects and probability 0 never injects`() {
        val always = createEntry(keywords = listOf("hero"), probability = 100, content = "always lore")
        val never = createEntry(keywords = listOf("hero"), probability = 0, content = "never lore")
        val book = createLorebook(entries = listOf(always, never))

        // FixedRandom(99) 对 0-100 的任何 roll 都返回 99
        val result = transformWithLorebook(
            book,
            listOf(UIMessage.system("sys"), UIMessage.user("hero")),
            random = FixedRandom(99),
        )
        val text = systemText(result)
        assertTrue(text.contains("always lore"))
        assertFalse(text.contains("never lore"))
    }

    @Test
    fun `probability roll below threshold injects, above threshold skips`() {
        val entry = createEntry(keywords = listOf("hero"), probability = 50, content = "maybe lore")
        val book = createLorebook(entries = listOf(entry))
        val messages = listOf(UIMessage.system("sys"), UIMessage.user("hero"))

        val lucky = transformWithLorebook(book, messages, random = FixedRandom(0))
        assertTrue(systemText(lucky).contains("maybe lore"))

        val unlucky = transformWithLorebook(book, messages, random = FixedRandom(99))
        assertFalse(systemText(unlucky).contains("maybe lore"))
    }

    @Test
    fun `constant active entry bypasses probability roll`() {
        val entry = createEntry(
            keywords = emptyList(),
            constantActive = true,
            probability = 0,
            content = "constant lore",
        )
        val book = createLorebook(entries = listOf(entry))

        val result = transformWithLorebook(
            book,
            listOf(UIMessage.system("sys"), UIMessage.user("anything")),
            random = FixedRandom(99),
        )
        assertTrue(systemText(result).contains("constant lore"))
    }
    // endregion

    // region tokenBudget
    @Test
    fun `tokenBudget trims lowest priority entries first`() {
        val high = createEntry(
            keywords = listOf("hero"),
            priority = 10,
            content = "HIGH-" + "x".repeat(10),
        )
        val mid = createEntry(
            keywords = listOf("hero"),
            priority = 5,
            content = "MID-" + "y".repeat(10),
        )
        val low = createEntry(
            keywords = listOf("hero"),
            priority = 1,
            content = "LOW-" + "z".repeat(10),
        )
        // 内容长度分别为 15/14/14 字符，预算 29 只能容纳 2 条 -> 最低优先级 LOW 被裁剪
        val book = createLorebook(
            entries = listOf(low, mid, high),
            tokenBudget = 29,
        )

        val result = transformWithLorebook(
            book,
            listOf(UIMessage.system("sys"), UIMessage.user("hero")),
        )
        val text = systemText(result)
        assertTrue(text.contains("HIGH-"))
        assertTrue(text.contains("MID-"))
        assertFalse(text.contains("LOW-"))
    }

    @Test
    fun `tokenBudget keeps at least the highest priority entry when budget is tiny`() {
        val high = createEntry(keywords = listOf("hero"), priority = 10, content = "H".repeat(50))
        val low = createEntry(keywords = listOf("hero"), priority = 1, content = "L".repeat(50))
        val book = createLorebook(entries = listOf(low, high), tokenBudget = 5)

        val result = transformWithLorebook(
            book,
            listOf(UIMessage.system("sys"), UIMessage.user("hero")),
        )
        val text = systemText(result)
        assertTrue(text.contains("H".repeat(50)))
        assertFalse(text.contains("L".repeat(50)))
    }

    @Test
    fun `tokenBudget zero means no trimming`() {
        val a = createEntry(keywords = listOf("hero"), priority = 10, content = "A".repeat(100))
        val b = createEntry(keywords = listOf("hero"), priority = 1, content = "B".repeat(100))
        val book = createLorebook(entries = listOf(a, b), tokenBudget = 0)

        val result = transformWithLorebook(
            book,
            listOf(UIMessage.system("sys"), UIMessage.user("hero")),
        )
        val text = systemText(result)
        assertTrue(text.contains("A".repeat(100)))
        assertTrue(text.contains("B".repeat(100)))
    }
    // endregion

    // region recursiveScanning
    @Test
    fun `recursive scanning triggers entries whose keywords appear in matched content`() {
        val entryA = createEntry(
            keywords = listOf("alpha"),
            content = "lore mentioning beta",
        )
        val entryB = createEntry(
            keywords = listOf("beta"),
            content = "beta lore",
        )
        val messages = listOf(UIMessage.system("sys"), UIMessage.user("tell me about alpha"))

        val nonRecursive = transformWithLorebook(
            createLorebook(entries = listOf(entryA, entryB), recursiveScanning = false),
            messages,
        )
        val nonRecursiveText = systemText(nonRecursive)
        assertTrue(nonRecursiveText.contains("lore mentioning beta"))
        assertFalse(nonRecursiveText.contains("beta lore"))

        val recursive = transformWithLorebook(
            createLorebook(entries = listOf(entryA, entryB), recursiveScanning = true),
            messages,
        )
        val recursiveText = systemText(recursive)
        assertTrue(recursiveText.contains("lore mentioning beta"))
        assertTrue(recursiveText.contains("beta lore"))
    }

    @Test
    fun `recursive scanning terminates on self referencing content`() {
        val entry = createEntry(
            keywords = listOf("alpha"),
            content = "alpha loop content",
        )
        val book = createLorebook(entries = listOf(entry), recursiveScanning = true)

        val collected = collectInjectionMatches(
            messages = listOf(UIMessage.system("sys"), UIMessage.user("alpha")),
            assistant = createAssistant(lorebookIds = setOf(book.id)),
            modeInjections = emptyList(),
            lorebooks = listOf(book),
            random = FixedRandom(0),
        )
        // 自引用不死循环，且条目只注入一次
        assertEquals(1, collected.size)
        assertEquals(entry.id, collected.single().injection.id)
    }

    @Test
    fun `recursive scanning is capped at 5 rounds`() {
        // E0 kw k0 -> content k1, E1 kw k1 -> content k2, ... E5 kw k5 -> content k6
        val entries = (0..5).map { i ->
            createEntry(
                keywords = listOf("k$i"),
                content = "k${i + 1}",
                priority = i,
            )
        }
        val book = createLorebook(entries = entries, recursiveScanning = true)

        val collected = collectInjectionMatches(
            messages = listOf(UIMessage.system("sys"), UIMessage.user("k0")),
            assistant = createAssistant(lorebookIds = setOf(book.id)),
            modeInjections = emptyList(),
            lorebooks = listOf(book),
            random = FixedRandom(0),
        )
        // 第 0 轮命中 E0，递归第 1-4 轮命中 E1-E4，达到 5 轮上限后停止，E5 不命中
        assertEquals(5, collected.size)
        assertTrue(collected.none { it.injection.content == "k6" })
        assertEquals(
            listOf(0, 1, 2, 3, 4),
            collected.map { it.match!!.recursiveRound }.sorted(),
        )
    }

    @Test
    fun `disabled recursive scanning does not cascade`() {
        val entryA = createEntry(keywords = listOf("alpha"), content = "mentions beta")
        val entryB = createEntry(keywords = listOf("beta"), content = "beta lore")
        val book = createLorebook(entries = listOf(entryA, entryB), recursiveScanning = false)

        val collected = collectInjectionMatches(
            messages = listOf(UIMessage.system("sys"), UIMessage.user("alpha")),
            assistant = createAssistant(lorebookIds = setOf(book.id)),
            modeInjections = emptyList(),
            lorebooks = listOf(book),
            random = FixedRandom(0),
        )
        assertEquals(1, collected.size)
        assertEquals(entryA.id, collected.single().injection.id)
    }
    // endregion

    // region trace
    @Test
    fun `trace records selective probability and recursive round`() {
        val entryA = createEntry(
            keywords = listOf("alpha"),
            secondaryKeywords = listOf("omega"),
            selective = true,
            probability = 80,
            content = "contains beta",
        )
        val entryB = createEntry(
            keywords = listOf("beta"),
            content = "beta lore",
        )
        val book = createLorebook(entries = listOf(entryA, entryB), recursiveScanning = true)
        val messages = listOf(UIMessage.system("sys"), UIMessage.user("alpha and omega"))

        var recorded = emptyList<PromptInjectionTrace>()
        transformMessagesWithTrace(
            messages = messages,
            assistant = createAssistant(lorebookIds = setOf(book.id)),
            modeInjections = emptyList(),
            lorebooks = listOf(book),
            random = FixedRandom(0),
            promptTraceRecorder = object : PromptTraceRecorder {
                override fun recordInjectionHits(hits: List<PromptInjectionTrace>) {
                    recorded = hits
                }
            },
        )

        assertEquals(2, recorded.size)
        val traceA = recorded.first { it.injectionId == entryA.id }
        assertEquals(true, traceA.match?.selective)
        assertEquals(listOf("omega"), traceA.match?.secondaryMatchedTerms)
        assertEquals(80, traceA.match?.probability)
        assertEquals(0, traceA.match?.recursiveRound)
        val traceB = recorded.first { it.injectionId == entryB.id }
        assertEquals(false, traceB.match?.selective)
        assertEquals(1, traceB.match?.recursiveRound)
    }
    // endregion

    // region regression
    @Test
    fun `new fields keep default and priority ordering is preserved`() {
        val entry = createEntry(keywords = listOf("hero"))
        assertEquals(emptyList<String>(), entry.secondaryKeywords)
        assertEquals(false, entry.selective)
        assertEquals(100, entry.probability)
        val book = createLorebook(entries = listOf(entry))
        assertEquals(0, book.tokenBudget)
        assertEquals(false, book.recursiveScanning)

        val low = createEntry(keywords = listOf("hero"), priority = 1, content = "low priority")
        val high = createEntry(keywords = listOf("hero"), priority = 9, content = "high priority")
        val ordered = createLorebook(entries = listOf(low, high))
        val result = transformWithLorebook(
            ordered,
            listOf(UIMessage.system("sys"), UIMessage.user("hero")),
        )
        val text = systemText(result)
        assertTrue(text.indexOf("high priority") < text.indexOf("low priority"))
    }
    // endregion
}
