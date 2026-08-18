package me.rerere.rikkahub.data.ai.trace

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptTokenEstimatorTest {
    @Test
    fun `empty and whitespace text estimate zero`() {
        assertEquals(0, PromptTokenEstimator.estimate(""))
        assertEquals(0, PromptTokenEstimator.estimate(" \n\t"))
    }

    @Test
    fun `cjk kana and hangul count approximately one each`() {
        assertEquals(6, PromptTokenEstimator.estimate("中文かな한글"))
    }

    @Test
    fun `latin letters and digits use four code point buckets`() {
        assertEquals(1, PromptTokenEstimator.estimate("test"))
        assertEquals(2, PromptTokenEstimator.estimate("test1234"))
    }

    @Test
    fun `punctuation contributes conservatively`() {
        assertEquals(5, PromptTokenEstimator.estimate("Hi, 世界!"))
    }
}
