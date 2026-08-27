package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MarkdownStableDomKeyTest {
    @Test
    fun `streaming content key ignores content changes`() {
        assertEquals(stableDomHtmlContentKey("hello", true), stableDomHtmlContentKey("hello world", true))
    }

    @Test
    fun `non streaming content key tracks content`() {
        assertNotEquals(stableDomHtmlContentKey("hello", false), stableDomHtmlContentKey("hello world", false))
    }

    @Test
    fun `streaming flip changes effective remember inputs`() {
        assertNotEquals(stableDomHtmlContentKey("same", true), stableDomHtmlContentKey("same", false))
    }
}
