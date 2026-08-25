package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernHelperRenderSettingsTest {
    @Test
    fun `safe renderer defaults and settings round trip`() {
        val defaults = TavernHelperRenderSettings()
        assertTrue(defaults.enabled)
        assertFalse(defaults.allowScripts)
        assertFalse(defaults.allowNetwork)
        assertEquals(0, defaults.depth)

        val changed = defaults.copy(allowScripts = true, depth = 7, allowStreaming = true)
        val restored: TavernHelperRenderSettings = JsonInstant.decodeFromString(JsonInstant.encodeToString(changed))
        assertEquals(changed, restored)
    }

    @Test
    fun `frontend depth zero means all loaded messages and positive values limit newest messages`() {
        assertTrue(TavernHelperRenderSettings(depth = 0).shouldRenderFrontend(messageDepth = 499, streaming = false))
        assertTrue(TavernHelperRenderSettings(depth = 2).shouldRenderFrontend(messageDepth = 0, streaming = false))
        assertTrue(TavernHelperRenderSettings(depth = 2).shouldRenderFrontend(messageDepth = 1, streaming = false))
        assertFalse(TavernHelperRenderSettings(depth = 2).shouldRenderFrontend(messageDepth = 2, streaming = false))
    }

    @Test
    fun `streaming frontend stays source until explicitly enabled`() {
        assertFalse(TavernHelperRenderSettings().shouldRenderFrontend(messageDepth = 0, streaming = true))
        assertTrue(
            TavernHelperRenderSettings(allowStreaming = true)
                .shouldRenderFrontend(messageDepth = 0, streaming = true),
        )
    }
}
