package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertFalse
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
}
