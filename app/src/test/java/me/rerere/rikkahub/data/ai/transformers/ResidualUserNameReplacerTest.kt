package me.rerere.rikkahub.data.ai.transformers

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 残留 user 名显示替换（[replaceResidualUserName]）的测试。
 *
 * 生成侧 `{{user}}` 宏兜底为 "user" 时，模型会把 "user" 当作用户名写进中文叙事正文并持久化。
 * 显示层需要保守地替换：仅匹配独立单词 user/User，且至少一侧相邻 CJK 表意文字或中文标点。
 */
class ResidualUserNameReplacerTest {

    @Test
    fun `user followed by CJK is replaced`() {
        assertEquals("你没有丝毫犹豫", replaceResidualUserName("user没有丝毫犹豫", "你"))
    }

    @Test
    fun `user between CJK chars is replaced`() {
        assertEquals("但你握着它时", replaceResidualUserName("但user握着它时", "你"))
    }

    @Test
    fun `user after Chinese punctuation and before CJK is replaced`() {
        assertEquals("，你又从床铺的草垫下摸出", replaceResidualUserName("，user又从床铺的草垫下摸出", "你"))
    }

    @Test
    fun `capitalized User at string start before CJK is replaced`() {
        assertEquals("你推开门", replaceResidualUserName("User推开门", "你"))
    }

    @Test
    fun `user at string end after CJK is replaced`() {
        assertEquals("只属于你", replaceResidualUserName("只属于user", "你"))
    }

    @Test
    fun `english sentence is preserved`() {
        val input = "the user clicks the button"
        assertEquals(input, replaceResidualUserName(input, "你"))
    }

    @Test
    fun `user surrounded by ASCII punctuation only is preserved`() {
        val input = "the user, clicks."
        assertEquals(input, replaceResidualUserName(input, "你"))
    }

    @Test
    fun `username is preserved`() {
        assertEquals("username没有命中", replaceResidualUserName("username没有命中", "你"))
    }

    @Test
    fun `user1 is preserved`() {
        assertEquals("user1走进来", replaceResidualUserName("user1走进来", "你"))
    }

    @Test
    fun `super_user is preserved`() {
        assertEquals("那是super_user的东西", replaceResidualUserName("那是super_user的东西", "你"))
    }

    @Test
    fun `custom nickname is used as replacement`() {
        assertEquals("姜寻没有丝毫犹豫", replaceResidualUserName("user没有丝毫犹豫", "姜寻"))
    }

    @Test
    fun `text without user is returned unchanged`() {
        val input = "她抬起头，微笑着看向你。"
        assertEquals(input, replaceResidualUserName(input, "你"))
    }

    @Test
    fun `empty display name returns original text`() {
        val input = "user没有丝毫犹豫"
        assertEquals(input, replaceResidualUserName(input, ""))
    }

    @Test
    fun `multiple occurrences are all replaced when eligible`() {
        assertEquals("你看着你", replaceResidualUserName("user看着user", "你"))
    }
}
