package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernBackgroundMotionTest {
    @Test
    fun `motion is enabled only for an animated Tavern image`() {
        assertTrue(resolveTavernBackgroundMotion(true, true, true, true).enabled)
        assertFalse(resolveTavernBackgroundMotion(false, true, true, true).enabled)
        assertFalse(resolveTavernBackgroundMotion(true, false, true, true).enabled)
    }

    @Test
    fun `disabled system animators produce a static policy`() {
        assertEquals(TavernBackgroundMotion.Static, resolveTavernBackgroundMotion(true, true, false, true))
        assertEquals(TavernBackgroundMotion.Static, resolveTavernBackgroundMotion(true, true, true, false))
    }

    @Test
    fun `ambient motion stays inside restrained visual bounds`() {
        val motion = resolveTavernBackgroundMotion(true, true, true, true)

        assertTrue(motion.minScale >= 1.015f)
        assertTrue(motion.maxScale <= 1.045f)
        assertTrue(motion.translationFraction <= 0.012f)
        assertEquals(14_000, motion.durationMillis)
    }
}
