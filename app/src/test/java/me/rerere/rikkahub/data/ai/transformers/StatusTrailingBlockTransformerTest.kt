package me.rerere.rikkahub.data.ai.transformers

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 裸状态尾块清理（[stripTrailingStatusBlock]）的测试。
 *
 * 角色卡状态追踪提示词会让模型在回复末尾输出无标签的元信息块，例如：
 * `- Time: ... - Dramatic Updates: ... - Variable Analysis: ... []`
 * 清理必须保守：仅在末尾、至少两个不同已知 key 时才剥离。
 */
class StatusTrailingBlockTransformerTest {

    private val story = "她抬起头，微笑着看向你。"

    @Test
    fun `complete trailing block with bullets and empty json array is stripped while streaming`() {
        val input = story +
            "\n- Time: The initial state, no time has passed." +
            " - Dramatic Updates: No, this is the beginning of the story." +
            " - Variable Analysis: All variables are in their initial state as this is the first interaction. No changes are needed. []"

        assertEquals(story, stripTrailingStatusBlock(input, streaming = true))
    }

    @Test
    fun `complete trailing block is stripped on generation finish`() {
        val input = story +
            "\n- Time: The initial state, no time has passed." +
            " - Dramatic Updates: No, this is the beginning of the story." +
            " - Variable Analysis: All variables are in their initial state. No changes are needed. []"

        assertEquals(story, stripTrailingStatusBlock(input, streaming = false))
    }

    @Test
    fun `block without bullets on separate lines is stripped`() {
        val input = "夜色渐深。\nTime: 夜晚\nDramatic Updates: 无\nVariable Analysis: 状态未改变。"

        assertEquals("夜色渐深。", stripTrailingStatusBlock(input, streaming = true))
    }

    @Test
    fun `keys are matched case insensitively`() {
        val input = "$story\n- TIME: morning - dramatic updates: none - VARIABLE ANALYSIS: stable."

        assertEquals(story, stripTrailingStatusBlock(input, streaming = true))
    }

    @Test
    fun `star bullets are supported`() {
        val input = "$story\n* Time: morning\n* Dramatic Updates: none\n* Variable Analysis: stable."

        assertEquals(story, stripTrailingStatusBlock(input, streaming = true))
    }

    @Test
    fun `trailing unclosed json fragment is removed with block on generation finish`() {
        val input = "$story\n- Time: 清晨 - Dramatic Updates: 无 - Variable Analysis: 无变化。 ["

        assertEquals(story, stripTrailingStatusBlock(input, streaming = false))
    }

    @Test
    fun `unclosed trailing json fragment keeps original while streaming`() {
        val input = "$story\n- Time: 清晨 - Dramatic Updates: 无 - Variable Analysis: 无变化。 ["

        assertEquals(input, stripTrailingStatusBlock(input, streaming = true))
    }

    @Test
    fun `similar keys in the middle of text are preserved`() {
        val input = "开头。\n- Time: 清晨 - Dramatic Updates: 无\n\n第二天，故事继续，Variable Analysis 这个词出现在正文里。"

        assertEquals(input, stripTrailingStatusBlock(input, streaming = true))
        assertEquals(input, stripTrailingStatusBlock(input, streaming = false))
    }

    @Test
    fun `block followed by more prose is preserved`() {
        val input = "Time: 清晨\nDramatic Updates: 无\n\n正文继续写了很多。"

        assertEquals(input, stripTrailingStatusBlock(input, streaming = true))
        assertEquals(input, stripTrailingStatusBlock(input, streaming = false))
    }

    @Test
    fun `incomplete block without terminator is kept while streaming`() {
        val input = "$story\n- Time: 清晨 - Dramatic Updates: 无 - Variable Analysis: 仍在分析"

        assertEquals(input, stripTrailingStatusBlock(input, streaming = true))
    }

    @Test
    fun `incomplete block without terminator is stripped on generation finish`() {
        val input = "$story\n- Time: 清晨 - Dramatic Updates: 无 - Variable Analysis: 仍在分析"

        assertEquals(story, stripTrailingStatusBlock(input, streaming = false))
    }

    @Test
    fun `single known key is not enough to strip`() {
        val input = "$story\n- Time: 清晨。"

        assertEquals(input, stripTrailingStatusBlock(input, streaming = true))
        assertEquals(input, stripTrailingStatusBlock(input, streaming = false))
    }

    @Test
    fun `same key repeated twice does not strip`() {
        val input = "$story\n- Time: 清晨 - Time: 早上。"

        assertEquals(input, stripTrailingStatusBlock(input, streaming = true))
        assertEquals(input, stripTrailingStatusBlock(input, streaming = false))
    }

    @Test
    fun `message that is entirely a status block is kept to avoid empty bubble`() {
        val input = "- Time: 清晨 - Dramatic Updates: 无。"

        assertEquals(input, stripTrailingStatusBlock(input, streaming = true))
    }
}
