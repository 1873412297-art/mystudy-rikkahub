package me.rerere.rikkahub.ui.pages.tavern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TavernCardViewerParserTest {
    @Test
    fun `png viewer parser retains the source image uri`() {
        val json = """{"name":"Alice","first_mes":"Hello"}"""

        val card = parseTavernCardForViewer(json, "content://cards/alice.png")

        assertNotNull(card)
        assertEquals("content://cards/alice.png", card?.sourceImageUri)
    }
}
