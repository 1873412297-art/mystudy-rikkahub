package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusPlaceholderTransformerTest {

    @Test
    fun `card status template replaces generic html and character pages`() {
        val pages = listOf(UIMessagePart.CharacterStatusPage("Aster", "<b>HP 10</b>"))

        val part = buildStatusPlaceholderPart(
            fallbackHtml = "<div>generic</div>",
            characterPages = pages,
            cardTemplate = "<html><img src=\"https://example.com/aster.png\"></html>",
        )

        assertEquals("<html><img src=\"https://example.com/aster.png\"></html>", part.htmlContent)
        assertTrue(part.characterPages.isEmpty())
    }

    @Test
    fun `text splitting copies the original part so Tavern opening metadata survives`() {
        val source = java.io.File("app/src/main/java/me/rerere/rikkahub/data/ai/transformers/StatusPlaceholderTransformer.kt")
            .takeIf { it.exists() }
            ?: java.io.File("src/main/java/me/rerere/rikkahub/data/ai/transformers/StatusPlaceholderTransformer.kt")
        val body = source.readText().substringAfter("override suspend fun visualTransform")
            .substringBefore("override suspend fun onGenerationFinish")

        assertFalse(body.contains("resultParts.add(UIMessagePart.Text("))
        assertFalse(body.contains("newParts.add(UIMessagePart.Text("))
        assertTrue(body.contains("resultParts.add(part.copy(text = text))"))
        assertTrue(body.contains("newParts.add(part.copy(text = text))"))
    }
    @Test
    fun `bare json patch range includes closing bracket`() {
        val content = """[{ "op": "replace", "path": "/世界/当前时间", "value": "子时" }]"""
        val range = findBareJsonPatch(content)

        assertNotNull(range)
        assertEquals(content, content.substring(range!!.first, range.last + 1))
    }
}
