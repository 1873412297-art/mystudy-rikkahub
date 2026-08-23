package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownWebViewHeightCallbackTest {
    @Test
    fun `height callback from an old generation cannot update shared height`() {
        val webViewIdentity = Any()
        var sharedHeight = 64

        val accepted = runIfCurrentMarkdownWebViewHeightCallback(
            callbackGeneration = 3,
            currentGeneration = 4,
            callbackWebViewIdentity = webViewIdentity,
            currentWebViewIdentity = webViewIdentity,
        ) {
            sharedHeight = 900
        }

        assertFalse(accepted)
        assertEquals(64, sharedHeight)
    }

    @Test
    fun `height callback from a replaced webview cannot update shared height`() {
        var sharedHeight = 64

        val accepted = runIfCurrentMarkdownWebViewHeightCallback(
            callbackGeneration = 4,
            currentGeneration = 4,
            callbackWebViewIdentity = Any(),
            currentWebViewIdentity = Any(),
        ) {
            sharedHeight = 900
        }

        assertFalse(accepted)
        assertEquals(64, sharedHeight)
    }

    @Test
    fun `height callback updates only when generation and webview identity are current`() {
        val webViewIdentity = Any()
        var sharedHeight = 64

        val accepted = runIfCurrentMarkdownWebViewHeightCallback(
            callbackGeneration = 4,
            currentGeneration = 4,
            callbackWebViewIdentity = webViewIdentity,
            currentWebViewIdentity = webViewIdentity,
        ) {
            sharedHeight = 900
        }

        assertTrue(accepted)
        assertEquals(900, sharedHeight)
    }
}
