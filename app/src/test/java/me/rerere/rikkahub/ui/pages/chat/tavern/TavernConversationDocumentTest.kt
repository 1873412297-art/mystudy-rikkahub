package me.rerere.rikkahub.ui.pages.chat.tavern

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TavernConversationDocumentTest {

    private val template: String by lazy {
        listOf(
            File("src/main/assets/html/tavern-conversation.html"),
            File("app/src/main/assets/html/tavern-conversation.html"),
        ).firstOrNull { it.exists() }?.readText()
            ?: error("tavern-conversation.html template not found in test working dir")
    }

    @Test
    fun `template provides ST selectors card css scope and native action attributes`() {
        listOf(".mes", ".mes_block", ".name_text", ".mes_text", ".ch_name").forEach {
            assertTrue("missing ST selector $it", template.contains(it))
        }
        assertTrue(template.contains("data-card-css-scope"))
        assertTrue(template.contains("data-message-action"))
        assertTrue(template.contains("data-node-id"))
        assertTrue(template.contains("data-message-id"))
        assertTrue(template.contains("data-branch-index"))
    }

    @Test
    fun `template preserves raw html in sandbox iframe with fullscreen marker`() {
        assertTrue(template.contains("data-render-mode=\"html\""))
        assertTrue(template.contains("data-html-frame"))
        assertTrue(template.contains("data-fullscreen-target"))
        assertTrue(template.contains("iframe.srcdoc = injectIframeRuntime(part.text"))
        assertTrue(template.contains("window.__RIKKAHUB_RUNTIME_SOURCE__"))
        assertTrue(template.contains("__rikkahubFrameHeight"))
        assertTrue(template.contains("sandbox"))
    }

    @Test
    fun `raw html iframe receives permission gated runtime but no trusted action capability`() {
        val iframeRuntime = template.substringAfter("function injectIframeRuntime").substringBefore("function renderHtmlPart")

        assertTrue(iframeRuntime.contains("window.__RIKKAHUB_RUNTIME_SOURCE__"))
        assertFalse(iframeRuntime.contains("TavernConversationBridge"))
        assertFalse(iframeRuntime.contains("actionToken"))
    }

    @Test
    fun `template has escaped markdown fallback and local vendor hooks only`() {
        assertTrue(template.contains("{{VENDOR_LIBS}}"))
        assertTrue(template.contains("{{VENDOR_STYLES}}"))
        assertTrue(template.contains("window.MarkdownIt"))
        assertTrue(template.contains("window.DOMPurify"))
        assertTrue(template.contains("fallback.textContent = part.text"))
        assertNoExternalCdn(template)
    }

    @Test
    fun `markdown sanitizer forbids style channel and event handlers`() {
        assertTrue(template.contains("FORBID_TAGS: ['style'"))
        assertTrue(template.contains("FORBID_ATTR: ['style', 'srcdoc', 'formaction'"))
        listOf(
            "onerror",
            "onload",
            "onclick",
            "onmouseover",
            "onmouseout",
            "onfocus",
            "onblur",
            "onchange",
            "onsubmit",
        ).forEach { attribute ->
            assertTrue("missing forbidden Markdown attribute $attribute", template.contains("'$attribute'"))
        }
    }

    @Test
    fun `template wires task lists katex and mermaid through markdown lifecycle`() {
        assertTrue(template.contains("markdown.use(window.MarkdownItTaskLists"))
        assertTrue(template.contains("markdown.use(window.vscodeKatex, { katex: window.katex })"))
        assertTrue(template.contains("markdown.renderer.rules.fence = function"))
        assertTrue(template.contains("info === 'mermaid'"))
        assertTrue(template.contains("window.mermaid.initialize({"))
        assertTrue(template.contains("window.mermaid.run({ nodes: mermaidNodes })"))
        assertTrue(template.contains("runMarkdownEnhancements(root)"))
        assertTrue(template.contains("runMarkdownEnhancements(replacement)"))
    }

    @Test
    fun `replace all removes stale theme custom properties`() {
        assertTrue(template.contains("var appliedThemeVariables = []"))
        assertTrue(template.contains("document.documentElement.style.removeProperty(name)"))
        assertTrue(template.contains("appliedThemeVariables = nextThemeVariables"))
    }

    @Test
    fun `conversation document reports ready and delegates native actions`() {
        assertTrue(template.contains("{{ACTION_TOKEN}}"))
        assertTrue(template.contains("TavernConversationBridge.ready(actionToken)"))
        assertTrue(template.contains("TavernConversationBridge.longPress(actionToken"))
        assertTrue(template.contains("TavernConversationBridge.selectBranch(actionToken"))
        assertTrue(template.contains("TavernConversationBridge.openHtml(actionToken"))
        assertTrue(template.contains("TavernConversationBridge.openLink(actionToken"))
        assertTrue(template.contains("event.isTrusted"))
    }

    @Test
    fun `document builder injects deterministic snapshot and vendors without script escape`() {
        val snapshot = TavernConversationSnapshot(
            conversationId = "conversation",
            nodes = listOf(
                TavernConversationNode(
                    id = "node",
                    selectedIndex = 0,
                    branchCount = 1,
                    selectedMessage = TavernConversationMessage(
                        id = "message",
                        role = MessageRole.ASSISTANT,
                        name = "Alice",
                        parts = listOf(
                            TavernConversationTextPart(
                                text = "</script><script>window.pwned=true</script>",
                                renderMode = UIMessagePart.RenderMode.HTML,
                            ),
                        ),
                    ),
                ),
            ),
            userName = "User",
            characterName = "Alice",
            themeCssVariables = sortedMapOf("--bg" to "#000"),
            cardCss = "body { color: red; }",
            streaming = false,
        )

        val html = buildTavernConversationDocument(
            initial = snapshot,
            template = template,
            vendorScripts = "<script>window.MarkdownIt=function(){};</script>",
            vendorStyles = "<style>.hljs{display:block}</style>",
            runtimeScript = "window.__trustedRuntimeLoaded=true;",
            actionToken = "trusted-token",
        )

        assertTrue(html.contains("window.MarkdownIt=function(){}"))
        assertTrue(html.contains(".hljs{display:block}"))
        assertTrue(html.contains("\\u003c/script>\\u003cscript>window.pwned=true\\u003c/script>"))
        assertFalse(html.contains("</script><script>window.pwned=true"))
        assertFalse(html.contains("{{INITIAL_SNAPSHOT}}"))
        assertFalse(html.contains("{{VENDOR_LIBS}}"))
        assertFalse(html.contains("{{VENDOR_STYLES}}"))
        assertFalse(html.contains("{{RUNTIME_LIB}}"))
        assertFalse(html.contains("{{ACTION_TOKEN}}"))
        assertTrue(html.contains("window.__trustedRuntimeLoaded=true"))
        assertTrue(html.contains("var actionToken = \"trusted-token\""))
        assertNoExternalCdn(html)
    }

    @Test
    fun `runtime injection uses trusted marker after real vendors containing closing head literals`() {
        val vendorDir = listOf(
            File("src/main/assets/html/vendor"),
            File("app/src/main/assets/html/vendor"),
        ).firstOrNull { it.isDirectory } ?: error("vendor directory not found")
        val realVendorScripts = listOf("dompurify.min.js", "mermaid.min.js").joinToString("\n") { name ->
            "<script>${File(vendorDir, name).readText()}</script>"
        }
        val literalIndex = realVendorScripts.indexOf("</head>")
        assertTrue("real vendors must exercise the old replaceFirst bug", literalIndex >= 0)

        val html = buildTavernConversationDocument(
            initial = emptySnapshot(),
            template = template,
            vendorScripts = realVendorScripts,
            vendorStyles = "",
            runtimeScript = "window.__runtimeMarker='after-vendors';",
            actionToken = "trusted-token",
        )

        val vendorLiteralInDocument = html.indexOf("</head>")
        val runtimeIndex = html.indexOf("window.__runtimeMarker='after-vendors';")
        val structuralHeadEnd = html.lastIndexOf("</head>")
        assertTrue(vendorLiteralInDocument >= 0)
        assertTrue(runtimeIndex > vendorLiteralInDocument)
        assertTrue(runtimeIndex < structuralHeadEnd)
    }

    @Test
    fun `bundled vendor set contains required local renderers`() {
        val vendorDir = listOf(
            File("src/main/assets/html/vendor"),
            File("app/src/main/assets/html/vendor"),
        ).firstOrNull { it.isDirectory } ?: error("vendor directory not found")
        val names = vendorDir.list().orEmpty().toSet()

        assertTrue("markdown-it.min.js" in names)
        assertTrue("dompurify.min.js" in names)
        assertTrue("highlight.js.min.js" in names)
        assertTrue("katex.min.js" in names)
        assertTrue("mermaid.min.js" in names)
        assertTrue("katex.min.css" in names)
    }

    private fun assertNoExternalCdn(value: String) {
        val lower = value.lowercase()
        assertFalse(lower.contains("esm.sh"))
        assertFalse(lower.contains("cdn.jsdelivr"))
        assertFalse(lower.contains("unpkg.com"))
        assertFalse(lower.contains("cdnjs"))
    }

    private fun emptySnapshot() = TavernConversationSnapshot(
        conversationId = "conversation",
        nodes = emptyList(),
        userName = "User",
        characterName = "Alice",
        themeCssVariables = emptyMap(),
        cardCss = "",
        streaming = false,
    )
}
