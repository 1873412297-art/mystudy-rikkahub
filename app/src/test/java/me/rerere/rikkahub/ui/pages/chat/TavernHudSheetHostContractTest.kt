package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TavernHudSheetHostContractTest {
    @Test
    fun `default sheet host measures itself to the requested fraction`() {
        val measuredHeight = resolveTavernHudSheetHostHeight(
            availableHeight = 1_000,
            persistedHudFraction = 0.80f,
            fullscreen = false,
        )

        assertEquals(800, measuredHeight)
        assertNotEquals(1_000, measuredHeight)
    }

    @Test
    fun `fullscreen sheet host measures itself to all available height`() {
        assertEquals(
            1_000,
            resolveTavernHudSheetHostHeight(
                availableHeight = 1_000,
                persistedHudFraction = 0.80f,
                fullscreen = true,
            ),
        )
    }
}
