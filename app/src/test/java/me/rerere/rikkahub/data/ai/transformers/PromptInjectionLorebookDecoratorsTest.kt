package me.rerere.rikkahub.data.ai.transformers

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
 * 世界书触发装饰器（sticky / cooldown / delay）的管线语义测试。
 *
 * 轮次语义（与实现约定一致）：
 * - 用户轮次 = 非系统消息中 USER 消息的计数；
 * - 历史命中排除当前输入（最后一条 USER 消息）；
 * - turnsAgo = 命中消息之后的 USER 消息数（含当前输入），上一轮命中则 turnsAgo = 1；
 * - cooldown 优先于 sticky；sticky 触发不检查 selective、不消耗概率掷骰。
 */
class PromptInjectionLorebookDecoratorsTest {

    // region helpers
    /** 任何 nextInt 调用都会失败的 Random：用于证明 sticky 路径不消耗概率掷骰 */
    private object NoRandom : kotlin.random.Random() {
        override fun nextBits(bitCount: Int): Int =
            throw AssertionError("sticky path must not consume random")
    }

    private fun createAssistant(lorebookIds: Set<Uuid>) = Assistant(lorebookIds = lorebookIds)

    private fun createEntry(
        content: String = "decorator lore",
        keywords: List<String> = listOf("dragon"),
        sticky: Int = 0,
        cooldown: Int = 0,
        delay: Int = 0,
        scanDepth: Int = 2,
        probability: Int = 100,
        constantActive: Boolean = false,
    ) = PromptInjection.RegexInjection(
        name = "entry",
        position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        content = content,
        keywords = keywords,
        sticky = sticky,
        cooldown = cooldown,
        delay = delay,
        scanDepth = scanDepth,
        probability = probability,
        constantActive = constantActive,
    )

    private fun createLorebook(
        id: Uuid = Uuid.random(),
        entries: List<PromptInjection.RegexInjection>,
    ) = Lorebook(id = id, name = "book", entries = entries)

    private fun transformWithLorebook(
        lorebook: Lorebook,
        messages: List<UIMessage>,
        random: kotlin.random.Random = NoRandom,
    ): List<UIMessage> = transformMessages(
        messages = messages,
        assistant = createAssistant(lorebookIds = setOf(lorebook.id)),
        modeInjections = emptyList(),
        lorebooks = listOf(lorebook),
        random = random,
    )

    private fun injected(result: List<UIMessage>, content: String = "decorator lore"): Boolean =
        result.any { message ->
            message.parts.filterIsInstance<UIMessagePart.Text>().any { it.text.contains(content) }
        }
    // endregion

    @Test
    fun `zero decorators keep legacy keyword behavior`() {
        val book = createLorebook(entries = listOf(createEntry()))
        val hit = transformWithLorebook(
            book,
            listOf(UIMessage.system("sys"), UIMessage.user("a dragon appears")),
        )
        assertTrue(injected(hit))

        val miss = transformWithLorebook(
            book,
            listOf(UIMessage.system("sys"), UIMessage.user("nothing here")),
        )
        assertFalse(injected(miss))
    }

    @Test
    fun `sticky keeps injecting after keyword turn without consuming probability`() {
        // scanDepth=2：第 2 轮的扫描窗口（assistant + 当前 user）已看不到第 1 轮的关键词
        val book = createLorebook(entries = listOf(createEntry(sticky = 2, scanDepth = 2)))

        val turn2 = transformWithLorebook(
            book,
            listOf(
                UIMessage.system("sys"),
                UIMessage.user("a dragon appears"),   // 第 1 轮：关键词命中
                UIMessage.assistant("it roars"),
                UIMessage.user("what now"),           // 第 2 轮：无关键词，turnsAgo=1 <= sticky=2
            ),
        )
        assertTrue(injected(turn2))

        val turn4 = transformWithLorebook(
            book,
            listOf(
                UIMessage.system("sys"),
                UIMessage.user("a dragon appears"),
                UIMessage.assistant("it roars"),
                UIMessage.user("what now"),
                UIMessage.assistant("you run"),
                UIMessage.user("keep running"),
                UIMessage.assistant("you hide"),
                UIMessage.user("stay quiet"),         // 第 4 轮：turnsAgo=3 > sticky=2
            ),
        )
        assertFalse(injected(turn4))
    }

    @Test
    fun `cooldown suppresses retrigger even when keyword matches current turn`() {
        val book = createLorebook(entries = listOf(createEntry(cooldown = 2, scanDepth = 10)))
        val result = transformWithLorebook(
            book,
            listOf(
                UIMessage.system("sys"),
                UIMessage.user("a dragon appears"),   // 第 1 轮命中
                UIMessage.assistant("it roars"),
                UIMessage.user("another dragon"),     // 第 2 轮：关键词再中，但 turnsAgo=1 <= cooldown=2
            ),
        )
        assertFalse(injected(result))
    }

    @Test
    fun `cooldown expires after enough user turns`() {
        val book = createLorebook(entries = listOf(createEntry(cooldown = 1, scanDepth = 10)))
        val result = transformWithLorebook(
            book,
            listOf(
                UIMessage.system("sys"),
                UIMessage.user("a dragon appears"),   // 第 1 轮命中
                UIMessage.assistant("it roars"),
                UIMessage.user("calm down"),          // 第 2 轮（冷却中，但无关键词）
                UIMessage.assistant("you breathe"),
                UIMessage.user("dragon again"),       // 第 3 轮：turnsAgo=2 > cooldown=1，正常触发
            ),
        )
        assertTrue(injected(result))
    }

    @Test
    fun `cooldown takes precedence over sticky`() {
        val book = createLorebook(entries = listOf(createEntry(sticky = 3, cooldown = 1, scanDepth = 2)))
        val result = transformWithLorebook(
            book,
            listOf(
                UIMessage.system("sys"),
                UIMessage.user("a dragon appears"),   // 第 1 轮命中
                UIMessage.assistant("it roars"),
                UIMessage.user("what now"),           // turnsAgo=1：sticky 范围内，但 cooldown=1 优先抑制
            ),
        )
        assertFalse(injected(result))
    }

    @Test
    fun `delay blocks early user turns`() {
        val book = createLorebook(entries = listOf(createEntry(delay = 2, scanDepth = 10)))

        val turn1 = transformWithLorebook(
            book,
            listOf(UIMessage.system("sys"), UIMessage.user("a dragon appears")),
        )
        assertFalse(injected(turn1)) // 用户轮次 1 < delay=2

        val turn2 = transformWithLorebook(
            book,
            listOf(
                UIMessage.system("sys"),
                UIMessage.user("hello"),
                UIMessage.assistant("hi"),
                UIMessage.user("a dragon appears"),   // 用户轮次 2 >= delay=2，正常触发
            ),
        )
        assertTrue(injected(turn2))
    }

    @Test
    fun `cooldown bookkeeping ignores matches inside current input`() {
        // 关键词只在当前输入命中（历史无命中）：cooldown 不应阻止本次正常触发
        val book = createLorebook(entries = listOf(createEntry(cooldown = 5, scanDepth = 10)))
        val result = transformWithLorebook(
            book,
            listOf(
                UIMessage.system("sys"),
                UIMessage.user("hello"),
                UIMessage.assistant("hi"),
                UIMessage.user("a dragon appears"),
            ),
        )
        assertTrue(injected(result))
    }

    @Test
    fun `constant active entries ignore decorators`() {
        val book = createLorebook(
            entries = listOf(createEntry(constantActive = true, keywords = listOf("dragon"), delay = 99))
        )
        val result = transformWithLorebook(
            book,
            listOf(UIMessage.system("sys"), UIMessage.user("hello")),
        )
        assertTrue(injected(result))
    }

    @Test
    fun `sticky bookkeeping is based on keyword history not actual injection`() {
        // 第 1 轮关键词命中但 probability=0（掷骰失败未注入），历史仍记账：
        // 第 2 轮 sticky 生效。此处用 NoRandom 不合适，改回默认 Random 不影响断言
        // （probability=0 短路不消耗随机数）。
        val entry = createEntry(sticky = 1, probability = 0, scanDepth = 2)
        val book = createLorebook(entries = listOf(entry))
        val turn2 = transformWithLorebook(
            book,
            listOf(
                UIMessage.system("sys"),
                UIMessage.user("a dragon appears"),
                UIMessage.assistant("it roars"),
                UIMessage.user("what now"),
            ),
        )
        assertTrue(injected(turn2))
    }
}
