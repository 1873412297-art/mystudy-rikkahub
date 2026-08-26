package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownWebViewHeightPolicyTest {
    @Test
    fun `frontend html relies on its content height bridge`() {
        assertFalse(shouldMeasurePageHeight(applyTavernFrontendPolicy = true))
    }

    @Test
    fun `regular markdown keeps page height fallback`() {
        assertTrue(shouldMeasurePageHeight(applyTavernFrontendPolicy = false))
    }

    @Test
    fun `frontend bridge measures element bounds instead of viewport scroll height`() {
        val script = buildIframeInjectScript()

        assertTrue(script.contains("getBoundingClientRect"))
        assertFalse(script.contains("de?de.scrollHeight"))
        assertFalse(script.contains("b?b.scrollHeight"))
    }
}
