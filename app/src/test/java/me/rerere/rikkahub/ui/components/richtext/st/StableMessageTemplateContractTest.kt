package me.rerere.rikkahub.ui.components.richtext.st

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import me.rerere.rikkahub.ui.components.richtext.RichTextSegment

/**
 * 防止 st-message.html 模板回退为纯文本/CDN 渲染的契约测试。
 * JVM 测试直接读仓库源文件（assets 仅在 Android 运行时可用）。
 */
class StableMessageTemplateContractTest {

    private val template: String by lazy {
        val candidates = listOf(
            File("src/main/assets/html/st-message.html"),
            File("app/src/main/assets/html/st-message.html"),
        )
        candidates.firstOrNull { it.exists() }?.readText()
            ?: error("st-message.html template not found in test working dir")
    }

    @Test
    fun templateUsesInlineVendorPlaceholdersNotCdn() {
        assertTrue(template.contains("{{VENDOR_LIBS}}"))
        assertTrue(template.contains("{{VENDOR_STYLES}}"))
        assertFalse(template.contains("esm.sh"))
        assertTrue(template.contains("window.MarkdownIt"))
        assertTrue(template.contains("window.DOMPurify"))
    }

    @Test
    fun templateRendersMarkdownSegmentsThroughMarkdownItAndDomPurify() {
        assertTrue(template.contains("DOMPurify.sanitize"))
        assertTrue(template.contains("md.render"))
    }

    @Test
    fun templateKeepsStableDomShapeForSTCompat() {
        assertTrue(template.contains("mes_block"))
        assertTrue(template.contains("mes_text"))
        assertTrue(template.contains("dataset.segmentId"))
        assertTrue(template.contains("'name ch_name'"))
        assertTrue(template.contains("dataset.messageId"))
        assertTrue(template.contains("{{CSS_VAR_BG}}"))
        assertTrue(template.contains("{{EXTRA_CSS}}"))
        assertTrue(template.contains("RikkahubDomBridge"))
        assertTrue(template.contains("applySegmentPatch"))
        assertTrue(template.contains("renderMarkdownAll"))
        assertFalse(template.contains("mes_segment"))
        assertFalse(template.contains("mes_header"))
    }

    @Test
    fun templateFallsBackToEscapedPlainTextWhenLibsUnavailable() {
        assertTrue(template.contains("esc(segment.raw)"))
        assertTrue(template.contains("renderPlain"))
    }

    @Test
    fun rendererInlinesVendorScriptsIntoPlaceholder() {
        val message = StableDomMessage(
            id = "m1",
            role = StableDomRole.ASSISTANT,
            segments = emptyList(),
            streaming = false,
        )
        val html = buildStableMessageHtml(message, template, vendorScripts = "<script>fake-lib.js</script>")
        assertTrue(html.contains("fake-lib.js"))
        assertFalse(html.contains("{{VENDOR_LIBS}}"))
    }

    @Test
    fun rendererOutputHasNoResidualPlaceholders() {
        val message = StableDomMessage(
            id = "m1",
            role = StableDomRole.ASSISTANT,
            segments = listOf(
                StableDomSegment(id = "s0", kind = RichTextSegment.Kind.MARKDOWN, raw = "hello"),
                StableDomSegment(id = "s1", kind = RichTextSegment.Kind.STATUS_BLOCK, raw = "<status_block>"),
                StableDomSegment(id = "s2", kind = RichTextSegment.Kind.JSON_PATCH, raw = "<json_patch>"),
            ),
            streaming = false,
        )
        val html = buildStableMessageHtml(message, template)
        assertFalse(html.contains("{{"))
    }
}
