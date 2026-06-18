package me.rerere.rikkahub.ui.components.richtext.st

import me.rerere.rikkahub.ui.components.richtext.RichTextSegment
import org.junit.Assert.assertTrue
import org.junit.Test

class StableMessageHtmlRendererTest {
    @Test
    fun `renderer embeds message json and segment root`() {
        val html = buildStableMessageHtml(
            StableDomMessage(
                id = "m1",
                role = StableDomRole.ASSISTANT,
                segments = listOf(StableDomSegment("s1", RichTextSegment.Kind.MARKDOWN, "hello")),
                streaming = false,
            )
        )

        assertTrue(html.contains("data-rikkahub-st-message"))
        assertTrue(html.contains("window.__RIKKAHUB_ST_MESSAGE__"))
        assertTrue(html.contains("hello"))
    }
}

