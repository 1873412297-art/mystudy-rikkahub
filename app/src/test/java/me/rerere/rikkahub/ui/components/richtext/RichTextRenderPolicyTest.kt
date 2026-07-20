package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichTextRenderPolicyTest {
    @Test
    fun `maintext wrapper is stripped before rendering`() {
        val content = """
            <maintext>
            hello
            <Status_block>world</Status_block>
            </maintext>
        """.trimIndent()

        assertEquals("hello\n<Status_block>world</Status_block>", normalizeRichTextContent(content))
    }

    @Test
    fun `maintext wrapper does not force raw html rendering`() {
        val content = """
            <maintext>
            hello
            <Status_block>world</Status_block>
            </maintext>
        """.trimIndent()

        val intent = analyzeRichTextContent(content)
        assertFalse(intent.isRawHtmlDocument)
        assertTrue(intent.hasStatusBlock)
    }

    @Test
    fun `content is split into markdown status and json patch segments in source order`() {
        val content = """
            intro
            <Status_block>state</Status_block>
            middle
            [{ "op": "replace", "path": "/世界/当前时间", "value": "子时" }]
            outro
        """.trimIndent()

        val segments = parseRichTextSegments(content)

        assertEquals(
            listOf(
                RichTextSegment.Kind.MARKDOWN,
                RichTextSegment.Kind.STATUS_BLOCK,
                RichTextSegment.Kind.MARKDOWN,
                RichTextSegment.Kind.JSON_PATCH,
                RichTextSegment.Kind.MARKDOWN,
            ),
            segments.map { it.kind }
        )
        assertEquals("state", segments[1].raw.trim())
        assertEquals("""[{ "op": "replace", "path": "/世界/当前时间", "value": "子时" }]""", segments[3].raw.trim())
    }

    @Test
    fun `maintext markdown before status block is unwrapped and stays visible`() {
        val content = """
            <maintext>
            正文
            </maintext>
            <Status_block>状态</Status_block>
        """.trimIndent()

        val segments = parseRichTextSegments(content)

        assertEquals(listOf(RichTextSegment.Kind.MARKDOWN, RichTextSegment.Kind.STATUS_BLOCK), segments.map { it.kind })
        assertEquals("正文", segments[0].raw.trim())
    }

    @Test
    fun `status block display text strips protocol and details tags`() {
        val content = """
            <Status_block>
            『📅 日期：秦武阳十五年三月 春 | ⏰ 时间：深夜』
            <details><summary>[角色状态]</summary>
            ```
            - 👨 {{user}}的状态
              - 👤 身份：云山宗杂役弟子
            ```
            </details>
            『剧情发展』
            1. [普通] 继续观察。
            </Status_block>
        """.trimIndent()

        val status = parseRichTextSegments(content).single()

        assertEquals(RichTextSegment.Kind.STATUS_BLOCK, status.kind)
        assertFalse(status.raw.contains("<Status_block>", ignoreCase = true))
        assertFalse(status.raw.contains("</Status_block>", ignoreCase = true))
        assertFalse(status.raw.contains("<details", ignoreCase = true))
        assertFalse(status.raw.contains("</details>", ignoreCase = true))
        assertFalse(status.raw.contains("<summary", ignoreCase = true))
        assertFalse(status.raw.contains("```"))
        assertTrue(status.raw.contains("[角色状态]"))
        assertTrue(status.raw.contains("{{user}}的状态"))
        assertTrue(status.raw.contains("『剧情发展』"))
    }

    @Test
    fun `invalid json patch gets a diagnostic segment instead of plain markdown`() {
        val content = """[{ "op": "replace", "path": "/世界/当前时间", "value": "子时" }"""

        val segments = parseRichTextSegments(content)

        assertEquals(listOf(RichTextSegment.Kind.JSON_PATCH_DIAGNOSTIC), segments.map { it.kind })
        assertTrue(segments.single().raw.contains("JSON Patch"))
    }

    @Test
    fun `new status tag variants route to status segment`() {
        for (tag in listOf("statusbar", "StatusBlock", "状态栏", "status!")) {
            val segments = parseRichTextSegments("intro\n<$tag>state</$tag>")

            assertEquals(
                "tag <$tag> should route to STATUS_BLOCK",
                listOf(RichTextSegment.Kind.MARKDOWN, RichTextSegment.Kind.STATUS_BLOCK),
                segments.map { it.kind },
            )
            assertEquals("state", segments[1].raw.trim())
        }
    }

    @Test
    fun `statusbar variant triggers webview rendering`() {
        val intent = analyzeRichTextContent("正文\n<statusbar>state</statusbar>")

        assertTrue(intent.hasStatusBlock)
        assertTrue(intent.useMarkdownWebView)
        assertFalse(intent.isRawHtmlDocument)
    }

    @Test
    fun `unterminated statusblock variant is still a status segment`() {
        val segments = parseRichTextSegments("正文\n<StatusBlock>state\nmore")

        assertEquals(RichTextSegment.Kind.STATUS_BLOCK, segments.last().kind)
    }
}
