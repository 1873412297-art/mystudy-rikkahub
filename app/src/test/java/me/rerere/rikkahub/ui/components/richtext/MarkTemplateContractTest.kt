package me.rerere.rikkahub.ui.components.richtext

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkTemplateContractTest {
    private val template: String by lazy {
        listOf(
            File("src/main/assets/html/mark.html"),
            File("app/src/main/assets/html/mark.html"),
        ).firstOrNull { it.exists() }?.readText()
            ?: error("mark.html template not found in test working dir")
    }

    @Test
    fun `template uses only inline local vendor placeholders`() {
        assertTrue(template.contains("{{VENDOR_LIBS}}"))
        assertTrue(template.contains("{{VENDOR_STYLES}}"))
        assertTrue(template.contains("{{TAVERN_RUNTIME}}"))
        assertFalse(template.contains("type=\"module\""))
        assertFalse(template.contains("https://"))
        assertFalse(template.contains("http://"))
        assertFalse(template.contains("esm.sh"))
        assertFalse(template.contains("cdn.jsdelivr"))
        assertFalse(template.contains("unpkg.com"))
    }

    @Test
    fun `template wires all bundled renderers without network imports`() {
        assertTrue(template.contains("(function () {"))
        assertTrue(template.contains("new MarkdownIt("))
        assertTrue(template.contains("md.use(vscodeKatex, { katex: katex })"))
        assertTrue(template.contains("md.use(MarkdownItTaskLists"))
        assertTrue(template.contains("DOMPurify.sanitize"))
        assertTrue(template.contains("hljs.highlight"))
        assertTrue(template.contains("mermaid.run"))
    }

    @Test
    fun `template reports document ready after markdown settles`() {
        assertTrue(template.contains("function reportDocumentReady()"))
        assertTrue(template.contains("window.RikkahubBridge.documentReady()"))
        assertTrue(template.contains("finally"))
        assertTrue(template.contains("reportDocumentReady();"))
    }

    @Test
    fun `bundled vendor directory provides every mark dependency`() {
        val vendorDir = listOf(
            File("src/main/assets/html/vendor"),
            File("app/src/main/assets/html/vendor"),
        ).firstOrNull { it.isDirectory } ?: error("vendor directory not found")
        val names = vendorDir.list().orEmpty().toSet()

        assertTrue("markdown-it.min.js" in names)
        assertTrue("markdown-it-task-lists.min.js" in names)
        assertTrue("@vscode_markdown-it-katex.min.js" in names)
        assertTrue("dompurify.min.js" in names)
        assertTrue("highlight.js.min.js" in names)
        assertTrue("katex.min.js" in names)
        assertTrue("mermaid.min.js" in names)
        assertTrue("katex.min.css" in names)
        assertTrue("atom-one-dark.min.css" in names)
    }

    @Test
    fun `every katex font source is bundled as an inline data URI`() {
        val vendorDir = listOf(
            File("src/main/assets/html/vendor"),
            File("app/src/main/assets/html/vendor"),
        ).firstOrNull { it.isDirectory } ?: error("vendor directory not found")
        val fontData = File(vendorDir, "katex-fonts.b64").readLines()
            .filter { it.isNotBlank() }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val inlined = inlineKatexFontSources(File(vendorDir, "katex.min.css").readText()) { fontData[it] }

        assertFalse(inlined.contains("url(fonts/"))
        assertTrue(inlined.contains("url(data:font/woff2;base64,"))
        assertTrue(fontData.keys.containsAll(setOf("KaTeX_Main-Regular", "KaTeX_AMS-Regular", "KaTeX_Size4-Regular")))
    }
}
