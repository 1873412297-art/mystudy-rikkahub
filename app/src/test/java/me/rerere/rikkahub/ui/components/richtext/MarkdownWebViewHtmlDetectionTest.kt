package me.rerere.rikkahub.ui.components.richtext

import me.rerere.rikkahub.ui.pages.chat.tavern.render.buildTavernViewportAdapterScript
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownWebViewHtmlDetectionTest {
    @Test
    fun `custom wrapper tags are not treated as raw html documents`() {
        val content = """
            <maintext>
            story
            </maintext>
            <Status_block>
            state
            </Status_block>
        """.trimIndent()

        assertFalse(looksLikeHtml(content))
    }

    @Test
    fun `full html document is still treated as raw html`() {
        val content = """
            <!DOCTYPE html>
            <html>
            <body><script>console.log('ok')</script></body>
            </html>
        """.trimIndent()

        assertTrue(looksLikeHtml(content))
    }

    @Test
    fun `fenced html app with body style and script is treated as raw html`() {
        val content = """
            ```html
            <style>.card { color: red; }</style>
            <body>
              <div class="card">status</div>
              <script>document.body.dataset.ready = '1'</script>
            </body>
            ```
        """.trimIndent()

        assertTrue(looksLikeHtml(content))
    }

    @Test
    fun `raw tavern document receives compatibility runtime before card scripts`() {
        val html = buildSandboxHostHtml(
            userHtml = "<html><head></head><body><script>\$(init)</script></body></html>",
            bgHex = "#000000",
            textHex = "#ffffff",
        )

        val shim = html.indexOf("window.$")
        val cardScript = html.indexOf("<script>\$(init)</script>")
        assertTrue(shim >= 0)
        assertTrue(cardScript > shim)
    }

    @Test
    fun `tavern image requests use host media loader`() {
        assertEquals(
            MarkdownSubresourceRoute.REMOTE_MEDIA,
            routeMarkdownSubresource(
                rawUrl = "https://cards.example/portrait.png",
                accept = "image/avif,image/webp,*/*",
                networkAllowed = true,
                tavernScoped = true,
            ),
        )
        assertEquals(
            MarkdownSubresourceRoute.BLOCKED,
            routeMarkdownSubresource(
                rawUrl = "https://cards.example/portrait.png",
                accept = "image/*",
                networkAllowed = false,
                tavernScoped = true,
            ),
        )
    }

    @Test
    fun `raw html host repairs interactive overlays after delayed viewport layout`() {
        val script = buildIframeInjectScript()

        assertTrue(script.contains("tavernViewportAdapter"))
        assertTrue(script.contains("rikkahubOverlayRepaired"))
        assertTrue(script.contains("MutationObserver"))
        assertTrue(script.contains("visualViewport"))
        assertTrue(script.contains("maxHeight"))
        assertTrue(script.contains("overflowY"))
    }

    @Test
    fun `raw html host uses the shared viewport adapter`() {
        val script = buildIframeInjectScript()

        assertTrue(script.contains(buildTavernViewportAdapterScript()))
    }
}
