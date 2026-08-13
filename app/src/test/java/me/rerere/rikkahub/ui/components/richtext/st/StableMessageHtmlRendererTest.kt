package me.rerere.rikkahub.ui.components.richtext.st

import me.rerere.rikkahub.ui.components.richtext.RichTextSegment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StableMessageHtmlRendererTest {

    private val template = """
        <html>
        <body>
          <div data-rikkahub-st-message></div>
          <script>
            window.__RIKKAHUB_ST_MESSAGE__ = {{MESSAGE_JSON}};
          </script>
        </body>
        </html>
    """.trimIndent()

    @Test
    fun injectsMessageJsonIntoPlaceholder() {
        val html = buildStableMessageHtml(
            StableDomMessage(
                id = "m1",
                role = StableDomRole.ASSISTANT,
                name = "Alice",
                segments = listOf(
                    StableDomSegment("s1", RichTextSegment.Kind.MARKDOWN, "**hello**"),
                    StableDomSegment("s2", RichTextSegment.Kind.STATUS_BLOCK, "<Status_block>x</Status_block>"),
                ),
                streaming = false,
            ),
            template,
        )

        assertFalse(html.contains("{{MESSAGE_JSON}}"))
        assertTrue(html.contains("window.__RIKKAHUB_ST_MESSAGE__ = {\"id\":\"m1\""))
        assertTrue(html.contains("\"name\":\"Alice\""))
        assertTrue(html.contains("\"segment-id\"").not())
        assertTrue(html.contains("s1"))
    }

    @Test
    fun escapesScriptClosingTagsInsideJson() {
        val html = buildStableMessageHtml(
            StableDomMessage(
                id = "m1",
                role = StableDomRole.ASSISTANT,
                segments = listOf(StableDomSegment("s1", RichTextSegment.Kind.MARKDOWN, "x</ScRiPt>y")),
                streaming = false,
            ),
            template,
        )

        assertTrue(html.contains("x\\u003c/ScRiPt>y"))
        assertFalse(html.contains("x</ScRiPt>y"))
    }

    @Test
    fun injectsCssVariablesAndSanitizedExtraCss() {
        val message = StableDomMessage(id = "m", role = StableDomRole.ASSISTANT, segments = emptyList(), streaming = false)
        val template = "<style>{{CSS_VAR_BG}}|{{EXTRA_CSS}}</style>{{MESSAGE_JSON}}"
        val html = buildStableMessageHtml(
            message = message,
            template = template,
            cssVariables = mapOf("CSS_VAR_BG" to "#112233"),
            extraCss = "body{}</style><script>evil</script>",
        )
        assertTrue(html.contains("#112233"))
        assertTrue(html.contains("/* */ style>"))
        assertFalse(html.contains("</style><script>"))
    }
}

