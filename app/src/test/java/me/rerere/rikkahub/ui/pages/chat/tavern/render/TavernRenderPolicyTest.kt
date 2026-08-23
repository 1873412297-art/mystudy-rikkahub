package me.rerere.rikkahub.ui.pages.chat.tavern.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernRenderPolicyTest {
    @Test
    fun `hud defaults to eighty percent and owns webview gestures`() {
        val policy = resolveTavernRenderPolicy(
            surface = TavernRenderSurface.HUD,
            availableHeightDp = 800,
            persistedHudFraction = null,
            fullscreen = false,
        )

        assertEquals(640, policy.maxHeightDp)
        assertEquals(0.80f, policy.panelFraction)
        assertEquals(TavernVerticalScrollOwner.WEBVIEW, policy.verticalScrollOwner)
        assertTrue(policy.captureHorizontalGestures)
    }

    @Test
    fun `persisted hud fraction is clamped and message keeps parent scroll`() {
        assertEquals(
            0.90f,
            resolveTavernRenderPolicy(TavernRenderSurface.HUD, 1000, 1.4f, false).panelFraction,
        )
        val message = resolveTavernRenderPolicy(
            TavernRenderSurface.MESSAGE,
            availableHeightDp = 1000,
            persistedHudFraction = null,
            fullscreen = false,
        )
        assertEquals(TavernVerticalScrollOwner.PARENT, message.verticalScrollOwner)
        assertFalse(message.captureHorizontalGestures)
    }

    @Test
    fun `fullscreen uses all available height`() {
        val policy = resolveTavernRenderPolicy(
            TavernRenderSurface.HUD,
            availableHeightDp = 812,
            persistedHudFraction = 0.6f,
            fullscreen = true,
        )
        assertEquals(812, policy.maxHeightDp)
        assertTrue(policy.fullscreen)
    }
}
