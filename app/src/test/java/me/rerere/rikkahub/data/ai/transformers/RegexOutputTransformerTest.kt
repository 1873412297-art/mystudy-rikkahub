package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * RegexOutputTransformer 的深度过滤测试。
 *
 * TransformerContext 依赖 Android Context，JVM 单测无法构造，
 * 因此直接测试 visualTransform 委托的纯函数核心 [applyVisualRegexes]。
 */
class RegexOutputTransformerTest {

    private fun regex(
        find: String = "hello",
        replace: String = "hi",
        minDepth: Int? = null,
        maxDepth: Int? = null,
        options: Set<RegexOption> = emptySet(),
    ) = AssistantRegex(
        id = Uuid.random(),
        name = "test",
        findRegex = find,
        replaceString = replace,
        affectingScope = setOf(AssistantAffectScope.ASSISTANT),
        visualOnly = false,
        options = options,
        minDepth = minDepth,
        maxDepth = maxDepth,
    )

    private fun assistantWith(vararg regexes: AssistantRegex) = Assistant(regexes = regexes.toList())

    private fun textOf(message: UIMessage): String =
        message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    @Test
    fun `depth limited rule applies only to messages within reverse depth range`() {
        val rule = regex(minDepth = 1, maxDepth = 2)
        val messages = listOf(
            UIMessage.assistant("hello oldest"),   // depth 3 → 超出上限，不替换
            UIMessage.assistant("hello older"),    // depth 2 → 命中
            UIMessage.assistant("hello newer"),    // depth 1 → 命中
            UIMessage.assistant("hello newest"),   // depth 0 → 低于下限，不替换
        )

        val result = applyVisualRegexes(messages, assistantWith(rule))

        assertEquals("hello oldest", textOf(result[0]))
        assertEquals("hi older", textOf(result[1]))
        assertEquals("hi newer", textOf(result[2]))
        assertEquals("hello newest", textOf(result[3]))
    }

    @Test
    fun `rule without depth limits applies to all assistant messages like legacy behavior`() {
        val rule = regex()
        val messages = listOf(
            UIMessage.assistant("hello 1"),
            UIMessage.assistant("hello 2"),
            UIMessage.assistant("hello 3"),
        )

        val result = applyVisualRegexes(messages, assistantWith(rule))

        assertEquals(listOf("hi 1", "hi 2", "hi 3"), result.map { textOf(it) })
    }

    @Test
    fun `non assistant messages are skipped even when rule matches`() {
        val rule = regex()
        val messages = listOf(
            UIMessage.user("hello user"),
            UIMessage.assistant("hello assistant"),
        )

        val result = applyVisualRegexes(messages, assistantWith(rule))

        assertEquals("hello user", textOf(result[0]))
        assertEquals("hi assistant", textOf(result[1]))
    }

    @Test
    fun `reasoning parts are transformed with same depth rule`() {
        val rule = regex(find = "think", replace = "reason", minDepth = 0, maxDepth = 0)
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Reasoning(reasoning = "think old")),
            ), // depth 1 → 不命中
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Reasoning(reasoning = "think new")),
            ), // depth 0 → 命中
        )

        val result = applyVisualRegexes(messages, assistantWith(rule))

        val oldReasoning = result[0].parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        val newReasoning = result[1].parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertEquals("think old", oldReasoning.reasoning)
        assertEquals("reason new", newReasoning.reasoning)
    }

    @Test
    fun `options are honored when compiling rules in transformer path`() {
        val rule = regex(find = "hello", replace = "hi", options = setOf(RegexOption.IGNORE_CASE))
        val messages = listOf(UIMessage.assistant("HeLLo world"))

        val result = applyVisualRegexes(messages, assistantWith(rule))

        assertEquals("hi world", textOf(result[0]))
    }

    @Test
    fun `multiple rules apply in list order with depth filter each`() {
        val replaceWorld = regex(find = "world", replace = "earth") // 无深度限制
        val replaceEarth = regex(find = "earth", replace = "mars", minDepth = 0, maxDepth = 0) // 仅最新消息
        val messages = listOf(
            UIMessage.assistant("hello world"), // depth 1
            UIMessage.assistant("hello world"), // depth 0
        )

        val result = applyVisualRegexes(messages, assistantWith(replaceWorld, replaceEarth))

        assertEquals("hello earth", textOf(result[0]))
        assertEquals("hello mars", textOf(result[1]))
    }

    @Test
    fun `empty regex list keeps messages unchanged`() {
        val messages = listOf(UIMessage.assistant("hello"))

        val result = applyVisualRegexes(messages, Assistant())

        assertEquals("hello", textOf(result[0]))
    }
}
