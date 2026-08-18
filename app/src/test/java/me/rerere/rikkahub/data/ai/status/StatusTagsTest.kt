package me.rerere.rikkahub.data.ai.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StatusTags] 单一事实来源的单元测试。
 *
 * 状态块标签族（`status_block` / `statusblock` / `statusbar` / `status` / `status!` / `状态栏`）
 * 被 StatusBlockExtractor、Markdown 分段路由、RichTextRenderPolicy 共用；
 * 本测试保证标签族正则覆盖全部变体，并保持大小写不敏感与缺失闭标签容忍。
 */
class StatusTagsTest {

    // region openTagRegex / closeTagRegex（提取器用）

    @Test
    fun `open tag regex matches all tag family variants`() {
        val variants = listOf(
            "status_block", "statusblock", "statusbar", "status", "status!", "状态栏",
            "Status_block", "STATUSBAR", "Status!", "Status",
        )
        for (variant in variants) {
            assertTrue("open tag should match <$variant>", StatusTags.openTagRegex().matches("<$variant>"))
            assertTrue("open tag should match < $variant >", StatusTags.openTagRegex().matches("< $variant >"))
        }
    }

    @Test
    fun `close tag regex matches all tag family variants`() {
        assertTrue(StatusTags.closeTagRegex().matches("</status_block>"))
        assertTrue(StatusTags.closeTagRegex().matches("</状态栏>"))
        assertTrue(StatusTags.closeTagRegex().matches("</statusbar>"))
        assertTrue(StatusTags.closeTagRegex().matches("</ StatusBlock >"))
    }

    // endregion

    // region segmentRegex（Markdown / RichTextRenderPolicy 分段用）

    @Test
    fun `segment regex matches block up to closing tag`() {
        val content = "<status_block>甲</status_block>乙"
        val match = StatusTags.segmentRegex().find(content)
        assertEquals("<status_block>甲</status_block>", match?.value)
    }

    @Test
    fun `segment regex tolerates missing closing tag`() {
        val content = "前<statusbar>未闭合内容"
        val match = StatusTags.segmentRegex().find(content)
        assertEquals("<statusbar>未闭合内容", match?.value)
    }

    @Test
    fun `segment regex is case insensitive`() {
        assertTrue(StatusTags.segmentRegex().containsMatchIn("<STATUS_BLOCK>x</STATUS_BLOCK>"))
        assertTrue(StatusTags.segmentRegex().containsMatchIn("<状态栏>x</状态栏>"))
    }

    // endregion

    // region wrapperRegex（RichTextRenderPolicy 展示文本提取用）

    @Test
    fun `wrapper regex extracts inner content`() {
        val match = StatusTags.wrapperRegex().matchEntire("<status_block>甲</status_block>")
        assertEquals("甲", match?.groupValues?.get(1))
    }

    @Test
    fun `wrapper regex accepts unclosed block at end`() {
        val match = StatusTags.wrapperRegex().matchEntire("<status_block>正文")
        assertEquals("正文", match?.groupValues?.get(1))
    }

    @Test
    fun `wrapper regex is used by status block display helper`() {
        // 与 RichTextRenderPolicy.statusBlockDisplayText 语义一致：提取块内正文、剥离标签。
        val match = StatusTags.wrapperRegex().matchEntire("<status_block>\n  正文内容\n</status_block>")
        // 开标签后的 \s* 会一并吞掉前导空白，仅保留正文与结尾换行
        assertEquals("正文内容\n", match?.groupValues?.get(1))
    }

    // endregion

    // region 消费方一致性

    @Test
    fun `statusbar variant is recognized by extractor`() {
        val result = StatusBlockExtractor.extract("正文<statusbar>『标题』\n内容</statusbar>")
        assertTrue(result.rawStatusText?.contains("statusbar") == true)
        assertTrue(result.cleanedText.contains("正文"))
    }

    @Test
    fun `status variant with bang is recognized by extractor`() {
        val result = StatusBlockExtractor.extract("正文<status!>内容</status!>")
        assertEquals("内容", result.sections.firstOrNull()?.content)
    }

    // endregion
}
