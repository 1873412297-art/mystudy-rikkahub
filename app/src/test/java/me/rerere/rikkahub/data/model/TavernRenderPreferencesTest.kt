package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TavernRenderPreferencesTest {
    @Test
    fun `hud fraction defaults and clamps`() {
        assertEquals(0.80f, TavernRenderPreferences().hudFraction)
        assertEquals(0.50f, normalizeTavernHudFraction(0.2f))
        assertEquals(0.90f, normalizeTavernHudFraction(1.2f))
    }
}
