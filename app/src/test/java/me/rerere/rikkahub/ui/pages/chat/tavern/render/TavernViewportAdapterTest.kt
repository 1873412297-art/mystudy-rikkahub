package me.rerere.rikkahub.ui.pages.chat.tavern.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernViewportAdapterTest {
    @Test
    fun `owned repair follows viewport changes`() {
        val initial = decideViewportRepair(
            viewportHeightPx = 720,
            computedMaxHeightPx = 0,
            clientHeightPx = 48,
            scrollHeightPx = 314,
            visible = true,
            fixedOverlay = true,
        )

        assertEquals(
            ViewportRepairDecision(
                maxHeightPx = 696,
                enableVerticalScroll = true,
                ownedMaxHeightPx = 696,
            ),
            initial,
        )
        assertEquals(
            ViewportRepairDecision(
                maxHeightPx = 576,
                ownedMaxHeightPx = 576,
            ),
            decideViewportRepair(
                viewportHeightPx = 600,
                computedMaxHeightPx = initial?.ownedMaxHeightPx,
                clientHeightPx = 314,
                scrollHeightPx = 314,
                visible = true,
                fixedOverlay = true,
                inlineMaxHeightPx = initial?.ownedMaxHeightPx,
                inlineOverflowYAuto = true,
                ownedMaxHeightPx = initial?.ownedMaxHeightPx,
            ),
        )
    }

    @Test
    fun `preserves a card supplied usable max height`() {
        assertNull(
            decideViewportRepair(
                viewportHeightPx = 720,
                computedMaxHeightPx = 540,
                clientHeightPx = 314,
                scrollHeightPx = 314,
                visible = true,
                fixedOverlay = true,
                inlineMaxHeightPx = 540,
            ),
        )
    }

    @Test
    fun `unchanged owned repair does not repeat style writes`() {
        assertNull(
            decideViewportRepair(
                viewportHeightPx = 720,
                computedMaxHeightPx = 696,
                clientHeightPx = 314,
                scrollHeightPx = 314,
                visible = true,
                fixedOverlay = true,
                inlineMaxHeightPx = 696,
                inlineOverflowYAuto = true,
                ownedMaxHeightPx = 696,
            ),
        )
    }

    @Test
    fun `card supplied replacement constraint releases ownership`() {
        assertEquals(
            ViewportRepairDecision(
                releaseOwnership = true,
                clearInlineOverflowY = true,
            ),
            decideViewportRepair(
                viewportHeightPx = 720,
                computedMaxHeightPx = 540,
                clientHeightPx = 314,
                scrollHeightPx = 314,
                visible = true,
                fixedOverlay = true,
                inlineMaxHeightPx = 540,
                inlineOverflowYAuto = true,
                ownedMaxHeightPx = 696,
            ),
        )
    }

    @Test
    fun `card stylesheet constraint clears only adapter inline styles`() {
        assertEquals(
            ViewportRepairDecision(
                releaseOwnership = true,
                clearInlineMaxHeight = true,
                clearInlineOverflowY = true,
            ),
            decideViewportRepair(
                viewportHeightPx = 720,
                computedMaxHeightPx = 540,
                clientHeightPx = 314,
                scrollHeightPx = 314,
                visible = true,
                fixedOverlay = true,
                inlineMaxHeightPx = 696,
                inlineOverflowYAuto = true,
                ownedMaxHeightPx = 696,
            ),
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
    fun `shared adapter invokes state decision with marker ownership`() {
        val script = buildTavernViewportAdapterScript()

        assertTrue(script.contains("const tavernViewportAdapter"))
        assertTrue(script.contains("function decideViewportRepair(input)"))
        assertTrue(script.contains("const decision = decideViewportRepair({"))
        assertTrue(script.contains("ownedMaxHeightPx: owned ? ownedMaxHeight : null"))
        assertTrue(script.contains("if (decision.releaseOwnership)"))
        assertTrue(script.contains("delete panel.dataset.rikkahubOverlayRepaired"))
        assertTrue(
            script.contains(
                "panel.dataset.rikkahubOverlayRepaired = String(decision.ownedMaxHeightPx)",
            ),
        )
        assertTrue(script.contains("return { schedule };"))
        assertTrue(script.contains("new MutationObserver(schedule)"))
        assertTrue(script.contains("new ResizeObserver(schedule)"))
        assertTrue(script.contains("attributeFilter: ['class', 'style', 'open']"))
        assertFalse(script.contains("lastViewportHeight"))
        assertFalse(script.contains("attributeFilter: ['data-rikkahub-overlay-repaired'"))
    }
}
