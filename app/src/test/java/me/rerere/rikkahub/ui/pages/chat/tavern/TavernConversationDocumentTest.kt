package me.rerere.rikkahub.ui.pages.chat.tavern

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import me.rerere.rikkahub.ui.pages.chat.tavern.render.buildTavernViewportAdapterScript

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
        listOf(".mes", ".mesAvatarWrapper", ".avatar", ".mes_block", ".name_text", ".mes_text", ".ch_name").forEach {
            assertTrue("missing ST selector $it", template.contains(it))
        }
        assertTrue(template.contains("data-card-css-scope"))
        assertTrue(template.contains("data-message-action"))
        assertTrue(template.contains("data-node-id"))
        assertTrue(template.contains("data-message-id"))
        assertTrue(template.contains("data-branch-index"))
    }

    @Test
    fun `template provides official style in-message greeting swipes and accessible gestures`() {
        listOf("swipe_left", "swipe_right", "swipes-counter").forEach {
            assertTrue("missing ST greeting control $it", template.contains(it))
        }
        assertTrue(template.contains("TavernConversationBridge.selectGreeting(actionToken"))
        assertTrue(template.contains("pointerdown"))
        assertTrue(template.contains("pointerup"))
        assertTrue(template.contains("Math.abs(dy) > Math.abs(dx)"))
        assertTrue(template.contains("aria-label"))
        assertTrue(template.contains("@media (prefers-reduced-motion: reduce)"))
    }

    @Test
    fun `template leaves composing and generation controls to native chat input`() {
        listOf("id=\"send_form\"", "id=\"send_textarea\"", "id=\"send_but\"", "id=\"stop_but\"").forEach {
            assertFalse("embedded composer must be absent: $it", template.contains(it))
        }
        assertFalse(template.contains("state.capabilities.canSend"))
        assertFalse(template.contains("state.capabilities.canStop"))
        assertFalse(template.contains("TavernConversationBridge.updateDraft(actionToken"))
        assertFalse(template.contains("TavernConversationBridge.send(actionToken"))
        assertFalse(template.contains("TavernConversationBridge.stop(actionToken"))
    }

    @Test
    fun `template leaves top level navigation to the native app bar`() {
        listOf("id=\"chat_header\"", "data-nav-action=\"drawer\"", "data-nav-action=\"new_chat\"").forEach {
            assertFalse("embedded app bar must be absent: $it", template.contains(it))
        }
        assertFalse(template.contains("TavernConversationBridge.navigation(actionToken"))
    }

    @Test
    fun `conversation document injects the shared viewport adapter before bridge ready`() {
        val document = buildTavernConversationDocument(
            initial = TavernConversationSnapshot(
                conversationId = "conversation",
                nodes = emptyList(),
                userName = "User",
                characterName = "Character",
                themeCssVariables = emptyMap(),
                cardCss = "",
                streaming = false,
            ),
            template = template,
            vendorScripts = "",
            vendorStyles = "",
        )
        val adapter = buildTavernViewportAdapterScript()

        assertTrue(template.contains("{{VIEWPORT_ADAPTER}}"))
        assertTrue(template.contains("{{VIEWPORT_ADAPTER_SOURCE}}"))
        assertTrue(document.contains(adapter))
        assertTrue(document.contains("rikkahubOverlayRepaired"))
        assertTrue(document.contains("window.__RIKKAHUB_VIEWPORT_ADAPTER_SOURCE__"))
        assertTrue(document.indexOf("const tavernViewportAdapter") < document.indexOf("TavernConversationBridge.ready"))
        assertFalse(document.contains("{{VIEWPORT_ADAPTER}}"))
        assertFalse(document.contains("{{VIEWPORT_ADAPTER_SOURCE}}"))
    }

    @Test
    fun `script capable iframes install the shared adapter before runtime and ready`() {
        val iframeRuntime = template.substringAfter("function injectIframeRuntime")
            .substringBefore("function suppressRepeatedRuntime")
        val controlledFrame = template.substringAfter("function suppressRepeatedRuntime")
            .substringBefore("function renderHtmlPart")
        val renderBranch = template.substringAfter("function renderHtmlPart")
            .substringBefore("function renderStatusPart")

        assertTrue(iframeRuntime.contains("window.__RIKKAHUB_VIEWPORT_ADAPTER_SOURCE__"))
        assertTrue(iframeRuntime.contains("bootstrap.textContent = viewportAdapter + \"\\n\" + helper"))
        assertTrue(iframeRuntime.contains("\"})();\\n\" + runtime + \"\\n\" +"))
        assertTrue(iframeRuntime.contains("parsed.head.insertBefore(bootstrap, parsed.head.firstChild)"))
        assertFalse(iframeRuntime.contains("TavernConversationBridge"))
        assertFalse(iframeRuntime.contains("actionToken"))

        assertTrue(controlledFrame.contains("viewportBootstrap.textContent = window.__RIKKAHUB_VIEWPORT_ADAPTER_SOURCE__"))
        assertTrue(controlledFrame.indexOf("viewportBootstrap") < controlledFrame.indexOf("sizeReporter"))
        assertTrue(renderBranch.indexOf("iframe.srcdoc = injectIframeRuntime") < renderBranch.indexOf("iframe.addEventListener('load'"))
        assertTrue(renderBranch.contains("iframe.setAttribute('sandbox', '')"))
        assertTrue(renderBranch.contains("sandbox=\"allow-scripts\"") || template.contains("sandbox=\"allow-scripts\""))
    }

    @Test
    fun `conversation document locks touch input to vertical scrolling`() {
        assertTrue(template.contains("html, body { touch-action: pan-y; overscroll-behavior-x: none; }"))
        assertTrue(template.contains("#chat { touch-action: pan-y; }"))
        val rawHtmlFrameStyle = template.substringAfter(".html-part iframe {").substringBefore("}")
        assertTrue(rawHtmlFrameStyle.contains("touch-action: pan-y"))
        assertTrue(template.contains("html,body{touch-action:pan-y!important;overscroll-behavior-x:none!important;}"))
    }

    @Test
    fun `template preserves raw html in sandbox iframe with fullscreen marker`() {
        assertTrue(template.contains("data-render-mode=\"html\""))
        assertTrue(template.contains("data-html-frame"))
        assertTrue(template.contains("data-fullscreen-target"))
        assertTrue(template.contains("iframe.srcdoc = suppressRepeatedRuntime(part.text)"))
        assertTrue(template.contains("iframe.srcdoc = injectIframeRuntime(part.text"))
        assertTrue(template.contains("window.__RIKKAHUB_RUNTIME_SOURCE__"))
        assertTrue(template.contains("__rikkahubFrameHeight"))
        assertTrue(template.contains("sandbox"))
    }

    @Test
    fun `committed opening removes scripts and event handlers before replay`() {
        val suppression = template.substringAfter("function suppressRepeatedRuntime")
            .substringBefore("function renderHtmlPart")

        assertTrue(suppression.contains("querySelectorAll('script,iframe"))
        assertTrue(suppression.contains("name.indexOf('on') === 0"))
        assertTrue(suppression.contains("javascript:"))
    }

    @Test
    fun `committed opening iframe keeps touch input on the vertical axis`() {
        val suppression = template.substringAfter("function suppressRepeatedRuntime")
            .substringBefore("function renderHtmlPart")

        assertTrue(
            "static sandbox iframe is missing its own vertical touch policy",
            suppression.contains("touch-action:pan-y!important;overscroll-behavior-x:none!important;"),
        )
    }

    @Test
    fun `committed opening preview leaves conversation scrolling to the parent document`() {
        val staticPreviewBranch = template.substringAfter("if (part.executeScripts === false) {")
            .substringBefore("} else {")

        assertTrue(
            "static sandbox preview must not create a second gesture owner",
            staticPreviewBranch.contains("iframe.style.pointerEvents = 'none'"),
        )
    }

    @Test
    fun `raw html iframe receives permission gated runtime but no trusted action capability`() {
        val iframeRuntime = template.substringAfter("function injectIframeRuntime").substringBefore("function renderHtmlPart")

        assertTrue(iframeRuntime.contains("window.__RIKKAHUB_RUNTIME_SOURCE__"))
        assertTrue(iframeRuntime.contains("window.__RIKKAHUB_RUNTIME_CALL__"))
        assertTrue(iframeRuntime.contains("__rikkahubRuntimeRequest"))
        assertTrue(iframeRuntime.contains("__rikkahubRuntimeResponse"))
        assertTrue(iframeRuntime.contains("requestId"))
        assertTrue(iframeRuntime.contains("event.source !== parent"))
        assertFalse(iframeRuntime.contains("TavernConversationBridge"))
        assertFalse(iframeRuntime.contains("actionToken"))
    }

    @Test
    fun `trusted parent broker binds runtime request to originating iframe`() {
        assertTrue(template.contains("data.__rikkahubRuntimeRequest"))
        assertTrue(template.contains("event.source !== frame.contentWindow"))
        assertTrue(template.contains("pending.source.postMessage"))
        assertTrue(template.contains("__rikkahubRuntimeResponse"))
        assertTrue(template.contains("requestId: pending.requestId"))
        assertTrue(template.contains("delete window[callbackName]"))
        assertTrue(template.contains("TavernRuntimeBridge.call(requestJson, callbackName, actionToken)"))
    }

    @Test
    fun `iframe bootstrap is structurally inserted before user scripts`() {
        val iframeRuntime = template.substringAfter("function injectIframeRuntime").substringBefore("function renderHtmlPart")

        assertTrue(iframeRuntime.contains("new DOMParser()"))
        assertTrue(iframeRuntime.contains("parsed.head.insertBefore(bootstrap, parsed.head.firstChild)"))
        assertTrue(iframeRuntime.contains("parsed.documentElement.outerHTML"))
        assertFalse(iframeRuntime.contains("return rawHtml + '<script>'"))
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
    fun `immersive markdown prefers Showdown and preserves sanitized fallbacks`() {
        val configure = template.substringAfter("function configureShowdown()")
            .substringBefore("function configureMarkdown()")
        val renderer = template.substringAfter("function renderMarkdownPart(part)")
            .substringBefore("function protectQuotedMarkup")
        val enhancements = template.substringAfter("function runMarkdownEnhancements(scope)")
            .substringBefore("function applyDocumentStyle")

        assertTrue(configure.contains("new window.showdown.Converter"))
        assertTrue(configure.contains("literalMidWordUnderscores: true"))
        assertTrue(configure.contains("simpleLineBreaks: true"))
        assertTrue(configure.contains("tasklists: true"))
        assertTrue(renderer.contains("showdownConverter.makeHtml"))
        assertTrue(renderer.contains("markdown.render"))
        assertTrue(renderer.contains("window.DOMPurify.sanitize"))
        assertTrue(renderer.contains("FORBID_TAGS"))
        assertTrue(renderer.contains("FORBID_ATTR"))
        assertTrue(enhancements.contains("querySelectorAll('pre code')"))
        assertTrue(enhancements.contains("window.hljs.highlightElement(code)"))
        assertTrue(enhancements.contains("language-mermaid"))
        assertTrue(enhancements.contains("window.katex.render"))
        assertTrue(enhancements.contains("trust: false"))
        assertTrue(template.contains("showdownConverter = configureShowdown()"))
    }

    @Test
    fun `template applies SillyTavern quote semantics before markdown rendering`() {
        assertTrue(template.contains("function wrapSillyTavernQuotes"))
        assertTrue(template.contains("protectQuotedMarkup"))
        assertTrue(template.contains("--SmartThemeQuoteColor"))
        assertTrue(template.contains(".mes_text q"))
        assertTrue(template.contains(".mes_text font[color] q"))
        assertTrue(template.contains(".mes q::before"))
        assertTrue(template.contains(".mes q::after"))
        listOf("ASCII_DOUBLE", "CURLY_DOUBLE", "GUILLEMET", "CJK_CORNER", "CJK_WHITE_CORNER", "FULLWIDTH_DOUBLE")
            .forEach { marker -> assertTrue("missing quote family $marker", template.contains(marker)) }
        assertTrue(template.contains("var source = wrapSillyTavernQuotes(part.text)"))
        assertTrue(template.contains("markdown.render(source)"))
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
    fun `conversation document renders every ST web part in protocol order`() {
        assertTrue(template.contains("function renderPart(part, node, message, partIndex)"))
        listOf(
            "part.partType === 'text'",
            "part.partType === 'status'",
            "part.partType === 'image'",
            "part.partType === 'video'",
            "part.partType === 'audio'",
            "part.partType === 'document'",
            "part.partType === 'reasoning'",
            "part.partType === 'tool'",
            "part.partType === 'tool_call'",
            "part.partType === 'tool_result'",
            "part.partType === 'search'",
        ).forEach { assertTrue("missing renderer branch $it", template.contains(it)) }
        assertTrue(template.contains("text.appendChild(renderPart(part, node, message, partIndex))"))
        assertTrue(template.contains("document.createElement('details')"))
        assertTrue(template.contains("document.createElement('img')"))
        assertTrue(template.contains("media.controls = true"))
        assertTrue(template.contains("mes.dataset.memberId = message.memberId || ''"))
    }

    @Test
    fun `status iframe reports its content height to the parent conversation`() {
        val statusRenderer = template.substringAfter("function renderStatusPart")
            .substringBefore("function renderMediaPart")
        val suppression = template.substringAfter("function suppressRepeatedRuntime")
            .substringBefore("function renderHtmlPart")

        assertTrue(statusRenderer.contains("data-auto-height"))
        assertTrue(statusRenderer.contains("sandbox', 'allow-scripts"))
        assertTrue(statusRenderer.contains("suppressRepeatedRuntime(pages[index].html || '', target)"))
        assertTrue(suppression.contains("meta[name=\"viewport\"]"))
        assertTrue(suppression.contains("__rikkahubFrameHeight"))
        assertTrue(template.contains("frame.dataset.autoHeight === 'true'"))
    }

    @Test
    fun `rich iframe expands to the complete card and keeps one local media fallback`() {
        val injectedRuntime = template.substringAfter("function injectIframeRuntime")
            .substringBefore("function suppressRepeatedRuntime")
        val parentMessages = template.substringAfter("window.addEventListener('message', function (event)")
            .substringAfter("if (data.__rikkahubRuntimeRequest)")

        assertTrue(injectedRuntime.contains("Math.min(raw,20000)"))
        assertFalse(injectedRuntime.contains("window.innerHeight * 4"))
        assertTrue(injectedRuntime.contains("img[loading=\\\"lazy\\\"]"))
        assertTrue(injectedRuntime.contains("setAttribute('loading','eager')"))
        assertTrue(injectedRuntime.contains("requestAnimationFrame"))
        assertTrue(injectedRuntime.contains("document.addEventListener('load'"))
        assertTrue(injectedRuntime.contains("document.addEventListener('error'"))
        assertTrue(injectedRuntime.contains("data-rikkahub-media-error"))
        assertFalse(injectedRuntime.contains("media.alt='Media unavailable'"))
        assertFalse(injectedRuntime.contains("note.textContent='Media unavailable'"))
        assertTrue(parentMessages.contains("Number.isFinite"))
        assertTrue(parentMessages.contains("__rikkahubHeightTimer"))
        assertTrue(parentMessages.contains("__rikkahubLastValidHeight"))
        assertTrue(parentMessages.contains("frame.dataset.autoHeight === 'true' ? 5000 : 20000"))
    }

    @Test
    fun `opening card uses the full row below its compact avatar header`() {
        val openingCss = template.substringAfter(".mes.opening-swipe {")
            .substringBefore("@keyframes opening-enter-forward")
        val openingRenderer = template.substringAfter("var isOpeningSwipe")
            .substringBefore("return mes;")

        assertTrue(openingRenderer.contains("mes.classList.add('opening-swipe')"))
        assertTrue(openingCss.contains(".mes.opening-swipe .mes_block"))
        assertTrue(openingCss.contains("width: 100%"))
        assertTrue(openingCss.contains(".mes.opening-swipe .mesAvatarWrapper"))
        assertTrue(openingCss.contains("position: absolute"))
        assertTrue(openingCss.contains(".mes.opening-swipe .swipe_left"))
        assertTrue(openingCss.contains(".mes.opening-swipe .swipe_right"))
    }

    @Test
    fun `failed avatar is replaced with a one character fallback`() {
        assertTrue(template.contains("function createAvatarFallback(messageName, avatarEmoji)"))
        assertTrue(template.contains("avatar.alt = ''"))
        assertTrue(template.contains("avatar.addEventListener('error'"))
        assertTrue(template.contains("avatar.replaceWith(createAvatarFallback(message.name, avatarEmoji))"))
    }

    @Test
    fun `opening navigation uses a sticky toolbar without covering the message`() {
        val openingCss = template.substringAfter(".mes.opening-swipe {")
            .substringBefore("@keyframes opening-enter-forward")

        assertTrue(template.contains("opening-swipe-nav"))
        assertTrue(openingCss.contains(".mes.opening-swipe .opening-swipe-nav"))
        assertTrue(openingCss.contains("position: sticky"))
        assertTrue(openingCss.contains("top: 0"))
        assertTrue(openingCss.contains("background: var(--rikkahub-surface)"))
        assertTrue(openingCss.contains("min-width: 44px"))
        assertFalse(openingCss.contains("position: fixed"))
    }

    @Test
    fun `rich media reveal and swipe controls respect focus and reduced motion`() {
        assertTrue(template.contains("data-rikkahub-media-ready"))
        assertTrue(template.contains(".html-part.rikkahub-frame-ready iframe"))
        assertTrue(template.contains(".mes.opening-swipe .swipe_left:focus-visible"))
        assertTrue(template.contains(".mes.opening-swipe .swipe_right:focus-visible"))
        val reducedMotion = template.substringAfter("@media (prefers-reduced-motion: reduce)")
            .substringBefore("[hidden]")
        assertTrue(reducedMotion.contains(".html-part iframe"))
        assertTrue(reducedMotion.contains("transition: none"))
        assertTrue(reducedMotion.contains("animation: none"))
    }

    @Test
    fun `rich frame height ignores tiny churn and keeps the bounded stable value`() {
        val heightHandler = template.substringAfter("if (data.__rikkahubFrameHeight === undefined")
            .substringBefore("markdown = configureMarkdown()")

        assertTrue(heightHandler.contains("Math.abs(nextHeight - previousHeight) < 2"))
        assertTrue(heightHandler.contains("Math.min(requestedHeight, maxHeight)"))
        assertTrue(heightHandler.contains("__rikkahubLastValidHeight"))
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

        assertTrue("showdown.min.js" in names)
        assertTrue("markdown-it.min.js" in names)
        assertTrue("dompurify.min.js" in names)
        assertTrue("highlight.js.min.js" in names)
        assertTrue("katex.min.js" in names)
        assertTrue("mermaid.min.js" in names)
        assertTrue("katex.min.css" in names)
    }

    @Test
    fun `committed opener replay is static and network inert`() {
        assertTrue(template.contains("iframe.setAttribute('sandbox', '')"))
        assertTrue(template.contains("connect-src 'none'"))
        assertTrue(template.contains("frame-src 'none'"))
        assertTrue(template.contains("script,iframe,frame,frameset,object,embed,form"))
        assertTrue(template.contains("['src', 'srcset', 'poster', 'data', 'action', 'formaction']"))
        assertTrue(template.contains("embeddedResource"))
    }

    @Test
    fun `opening switch uses restrained directional motion`() {
        assertTrue(template.contains(".mes.opening-forward .mes_block"))
        assertTrue(template.contains(".mes.opening-backward .mes_block"))
        assertTrue(template.contains("@keyframes opening-enter-forward"))
        assertTrue(template.contains("@keyframes opening-enter-backward"))
        assertTrue(template.contains("220ms cubic-bezier(.2,.8,.2,1)"))
        assertTrue(template.contains("translateX(18px) scale(.985)"))
        assertTrue(template.contains("translateX(-18px) scale(.985)"))
        assertTrue(template.contains("opacity: .35"))

        val openingRenderer = template.substringAfter("var isOpeningSwipe")
            .substringBefore("return mes;")
        val counterRenderer = openingRenderer.substringAfter("var counter")
        assertTrue(counterRenderer.contains("openingNav.appendChild(counter)"))
        assertFalse(counterRenderer.contains("mes.appendChild(counter)"))
    }

    @Test
    fun `opening motion is explicitly triggered and cleaned up after one animation`() {
        val trigger = template.substringAfter("function triggerOpeningTransition")
            .substringBefore("function renderMarkdownPart")

        assertTrue(trigger.contains("direction !== -1 && direction !== 1"))
        assertTrue(trigger.contains("state.openingSwipe"))
        assertTrue(trigger.contains("root.lastElementChild"))
        assertTrue(trigger.contains("root.scrollTop = 0"))
        assertTrue(trigger.contains("matches('.mes.assistant')"))
        assertTrue(trigger.contains("classList.remove('opening-forward', 'opening-backward')"))
        assertTrue(trigger.contains("prefers-reduced-motion: reduce"))
        assertTrue(trigger.contains("void block.offsetWidth"))
        assertTrue(trigger.contains("addEventListener('animationend', cleanup)"))
        assertTrue(trigger.contains("removeEventListener('animationend', cleanup)"))
        assertTrue(template.contains("triggerOpeningTransition: triggerOpeningTransition"))

        assertFalse(template.contains("function resolveOpeningTransition"))
        assertFalse(template.contains("pendingOpeningDirection"))
        assertFalse(template.contains("previousSnapshot.openingSwipe.index"))
        assertFalse(template.contains("nextSnapshot.openingSwipe.index"))
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
