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
}
