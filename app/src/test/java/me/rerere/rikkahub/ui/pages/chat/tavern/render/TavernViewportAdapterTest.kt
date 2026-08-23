package me.rerere.rikkahub.ui.pages.chat.tavern.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernViewportAdapterTest {
    @Test
    fun `repairs only a clipped zero-height panel`() {
        assertEquals(
            ViewportRepairDecision(maxHeightPx = 696, enableVerticalScroll = true),
            decideViewportRepair(
                viewportHeightPx = 720,
                computedMaxHeightPx = 0,
                clientHeightPx = 48,
                scrollHeightPx = 314,
                visible = true,
                fixedOverlay = true,
            ),
        )
    }

    @Test
    fun `preserves a card supplied usable max height`() {
        assertNull(
            decideViewportRepair(720, 540, 314, 314, visible = true, fixedOverlay = true),
        )
    }

    @Test
    fun `ignores hidden and non fixed content`() {
        assertNull(decideViewportRepair(720, 0, 48, 314, visible = false, fixedOverlay = true))
        assertNull(decideViewportRepair(720, 0, 48, 314, visible = true, fixedOverlay = false))
    }

    @Test
    fun `ignores panels without meaningful clipping`() {
        assertNull(decideViewportRepair(720, null, 306, 314, visible = true, fixedOverlay = true))
    }

    @Test
    fun `shared adapter schedules observers and avoids its marker attribute`() {
        val script = buildTavernViewportAdapterScript()

        assertTrue(script.contains("const tavernViewportAdapter"))
        assertTrue(script.contains("return { schedule };"))
        assertTrue(script.contains("new MutationObserver(schedule)"))
        assertTrue(script.contains("new ResizeObserver(schedule)"))
        assertTrue(script.contains("attributeFilter: ['class', 'style', 'open']"))
        assertTrue(script.contains("panel.dataset.rikkahubOverlayRepaired"))
        assertTrue(script.contains("panel.style.maxHeight !== target"))
        assertTrue(script.contains("panel.style.overflowY !== 'auto'"))
        assertFalse(script.contains("attributeFilter: ['data-rikkahub-overlay-repaired'"))
    }
}
