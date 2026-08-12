package me.rerere.rikkahub.data.ai.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TavernCardCssExtractorTest {

    @Test
    fun `extracts css from v2 data extensions css`() {
        val card = """{"data":{"extensions":{"css":"body { color: red; }"}}}"""
        assertEquals("body { color: red; }", TavernCardCssExtractor.extract(card))
    }

    @Test
    fun `extracts css from status_css key`() {
        val card = """{"data":{"extensions":{"status_css":"h1 { font-size: 20px; }"}}}"""
        assertEquals("h1 { font-size: 20px; }", TavernCardCssExtractor.extract(card))
    }

    @Test
    fun `extracts css from nested status object`() {
        val card = """{"data":{"extensions":{"status":{"css":".row { padding: 2px; }"}}}}"""
        assertEquals(".row { padding: 2px; }", TavernCardCssExtractor.extract(card))
    }

    @Test
    fun `extracts css from v1 top-level extensions`() {
        val card = """{"extensions":{"status":{"status_css":"div { margin: 0; }"}}}"""
        assertEquals("div { margin: 0; }", TavernCardCssExtractor.extract(card))
    }

    @Test
    fun `returns null for invalid json`() {
        assertNull(TavernCardCssExtractor.extract("{not json"))
    }

    @Test
    fun `returns null when no css keys present`() {
        assertNull(TavernCardCssExtractor.extract("""{"data":{"extensions":{"other":"x"}}}"""))
    }
}
