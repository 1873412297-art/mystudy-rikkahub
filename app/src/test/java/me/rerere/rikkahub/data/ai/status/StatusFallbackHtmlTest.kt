package me.rerere.rikkahub.data.ai.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StatusFallbackHtml] 共享 fallback HTML 构建器的单元测试。
 *
 * 该构建器同时被 [StatusRenderer]（QuickJS 不可用时）与 StatusPlaceholderTransformer（流式阶段）
 * 使用，因此输出必须稳定、且对所有动态值做 HTML 转义（& < >），防止状态变量注入 HTML。
 */
class StatusFallbackHtmlTest {

    @Test
    fun `empty variables and no expression produce minimal shell`() {
        val html = StatusFallbackHtml.build(emptyMap(), emptyMap())
        assertEquals(
            "<div style=\"font-family:sans-serif;font-size:13px;line-height:1.5;\"></div>",
            html,
        )
    }

    @Test
    fun `expression is rendered as bold header`() {
        val html = StatusFallbackHtml.build(emptyMap(), mapOf("expression" to "happy"))
        assertTrue(html.contains("<div style=\"font-size:16px;font-weight:600;margin-bottom:4px;\">happy</div>"))
    }

    @Test
    fun `blank expression is skipped`() {
        val html = StatusFallbackHtml.build(emptyMap(), mapOf("expression" to "   "))
        assertTrue(!html.contains("expression"))
        assertTrue(html.contains("font-size:16px") == false)
    }

    @Test
    fun `scalar values are rendered with bold keys`() {
        val html = StatusFallbackHtml.build(mapOf("hp" to "80"), emptyMap())
        assertTrue(html.contains("<div><b>hp:</b> 80</div>"))
    }

    @Test
    fun `nested maps produce indented sections`() {
        val html = StatusFallbackHtml.build(
            mapOf("world" to mapOf("time" to "深夜", "place" to "云山")),
            emptyMap(),
        )
        assertTrue(html.contains("<div style=\"font-weight:600;margin-top:4px;\">world</div>"))
        assertTrue(html.contains("margin-left:8px;"))
        assertTrue(html.contains("<div><b>time:</b> 深夜</div>"))
        assertTrue(html.contains("<div><b>place:</b> 云山</div>"))
    }

    @Test
    fun `lists are rendered inline`() {
        val html = StatusFallbackHtml.build(mapOf("tags" to listOf("a", "b")), emptyMap())
        assertTrue(html.contains("<div><b>tags:</b> a, b</div>"))
    }

    @Test
    fun `html special chars in values are escaped`() {
        val html = StatusFallbackHtml.build(mapOf("note" to "<script>alert(1)</script> & \"quoted\""), emptyMap())
        assertTrue(!html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp;"))
    }

    @Test
    fun `html special chars in keys and expression are escaped`() {
        val html = StatusFallbackHtml.build(
            mapOf("<攻击>" to "10"),
            mapOf("expression" to "<angry>"),
        )
        assertTrue(!html.contains("<攻击>"))
        assertTrue(html.contains("&lt;攻击&gt;"))
        assertTrue(!html.contains("<angry>"))
        assertTrue(html.contains("&lt;angry&gt;"))
    }

    @Test
    fun `list values are escaped too`() {
        val html = StatusFallbackHtml.build(mapOf("items" to listOf("<a>", "b&c")), emptyMap())
        assertTrue(!html.contains("<a>"))
        assertTrue(html.contains("&lt;a&gt;, b&amp;c"))
    }

    @Test
    fun `null values are rendered as placeholder dash`() {
        val html = StatusFallbackHtml.build(mapOf("x" to null), emptyMap())
        assertTrue(html.contains("<div><b>x:</b> —</div>"))
    }
}
