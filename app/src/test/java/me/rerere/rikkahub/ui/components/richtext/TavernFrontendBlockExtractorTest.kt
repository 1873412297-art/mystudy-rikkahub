package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Test

class TavernFrontendBlockExtractorTest {
    @Test
    fun `extract preserves narrative and ordinary code around multiple frontend blocks`() {
        val message = """
            开场叙事

            ```html
            <section id="first">第一块</section>
            ```

            中间叙事

            ```kotlin
            val answer = 42
            ```

            ```frontend
            <button id="second">第二块</button>
            ```
        """.trimIndent()

        assertEquals(
            listOf(
                TavernFrontendSegment.Text("开场叙事\n\n"),
                TavernFrontendSegment.Frontend(
                    language = "html",
                    html = "<section id=\"first\">第一块</section>\n",
                ),
                TavernFrontendSegment.Text("\n中间叙事\n\n"),
                TavernFrontendSegment.Code(
                    language = "kotlin",
                    code = "val answer = 42\n",
                ),
                TavernFrontendSegment.Text("\n"),
                TavernFrontendSegment.Frontend(
                    language = "frontend",
                    html = "<button id=\"second\">第二块</button>\n",
                ),
            ),
            TavernFrontendBlockExtractor.extract(message),
        )
    }

    @Test
    fun `complete html document in an unlabelled fence is frontend`() {
        val message = """
            ```
            <!DOCTYPE html>
            <html><body><div>面板</div></body></html>
            ```
        """.trimIndent()

        assertEquals(
            listOf(
                TavernFrontendSegment.Frontend(
                    language = "",
                    html = "<!DOCTYPE html>\n<html><body><div>面板</div></body></html>\n",
                ),
            ),
            TavernFrontendBlockExtractor.extract(message),
        )
    }

    @Test
    fun `html labelled fence without an element remains ordinary code`() {
        val message = """
            ```html
            plain text only
            ```
        """.trimIndent()

        assertEquals(
            listOf(
                TavernFrontendSegment.Code(
                    language = "html",
                    code = "plain text only\n",
                ),
            ),
            TavernFrontendBlockExtractor.extract(message),
        )
    }

    @Test
    fun `mixed fence markers remain narrative text`() {
        val message = "`~`html\n<div>不能执行</div>\n```"

        assertEquals(
            listOf(TavernFrontendSegment.Text(message)),
            TavernFrontendBlockExtractor.extract(message),
        )
    }
}
