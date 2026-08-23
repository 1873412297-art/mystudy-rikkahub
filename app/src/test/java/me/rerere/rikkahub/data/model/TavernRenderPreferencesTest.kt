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

    @Test
    fun `non finite hud fractions fall back to the default`() {
        assertEquals(0.80f, normalizeTavernHudFraction(Float.NaN))
        assertEquals(0.80f, normalizeTavernHudFraction(Float.POSITIVE_INFINITY))
        assertEquals(0.80f, normalizeTavernHudFraction(Float.NEGATIVE_INFINITY))
    }
}
