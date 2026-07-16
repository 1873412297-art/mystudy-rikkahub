package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Test

class RichTextRendererModeTest {
    @Test
    fun `status content prefers stable dom renderer mode`() {
        assertEquals(
            RichTextRendererMode.STABLE_DOM,
            chooseRendererMode("hello\n<Status_block>x</Status_block>")
        )
    }

    @Test
    fun `html app keeps webview segment renderer mode`() {
        assertEquals(
            RichTextRendererMode.WEBVIEW_SEGMENTS,
            chooseRendererMode("<!DOCTYPE html><html><body>x</body></html>")
        )
    }
}
