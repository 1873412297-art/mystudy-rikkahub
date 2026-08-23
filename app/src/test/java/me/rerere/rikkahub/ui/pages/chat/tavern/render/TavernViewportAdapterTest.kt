package me.rerere.rikkahub.ui.pages.chat.tavern.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernViewportAdapterTest {
    @Test
    fun `initial repair owns only inline properties it actually writes`() {
        val decision = decideViewportRepair(
            viewportHeightPx = 720,
            computedMaxHeightPx = 0,
            computedOverflowY = "auto",
            clientHeightPx = 48,
            scrollHeightPx = 314,
            visible = true,
            fixedOverlay = true,
            inlineMaxHeight = style("0px", "important"),
            inlineOverflowY = style("auto"),
        )

        assertEquals(style("696px"), decision?.maxHeightMutation)
        assertNull(decision?.overflowYMutation)
        assertEquals(
            OwnedInlineStyle(
                original = style("0px", "important"),
                written = style("696px"),
            ),
            decision?.nextOwnership?.maxHeight,
        )
        assertNull(decision?.nextOwnership?.overflowY)
    }

    @Test
    fun `release preserves card supplied inline overflow auto`() {
        val ownership = ownership(
            maxOriginal = style("0px"),
            maxWritten = style("696px", "important"),
            overflow = null,
        )

        assertEquals(
            ViewportRepairDecision(
                maxHeightMutation = style("0px"),
                nextOwnership = null,
            ),
            decideViewportRepair(
                viewportHeightPx = 720,
                computedMaxHeightPx = 696,
                computedOverflowY = "auto",
                clientHeightPx = 314,
                scrollHeightPx = 314,
                visible = false,
                fixedOverlay = true,
                inlineMaxHeight = style("696px", "important"),
                inlineOverflowY = style("auto"),
                ownership = ownership,
            ),
        )
    }

    @Test
    fun `release restores original values and priorities`() {
        val ownership = ownership(
            maxOriginal = style("0px", "important"),
            maxWritten = style("696px", "important"),
            overflow = OwnedInlineStyle(
                original = style("scroll", "important"),
                written = style("auto", "important"),
            ),
        )

        assertEquals(
            ViewportRepairDecision(
                maxHeightMutation = style("0px", "important"),
                overflowYMutation = style("scroll", "important"),
                nextOwnership = null,
            ),
            decideViewportRepair(
                viewportHeightPx = 720,
                computedMaxHeightPx = 696,
                computedOverflowY = "auto",
                clientHeightPx = 314,
                scrollHeightPx = 314,
                visible = true,
                fixedOverlay = false,
                inlineMaxHeight = style("696px", "important"),
                inlineOverflowY = style("auto", "important"),
                ownership = ownership,
            ),
        )
    }

    @Test
    fun `card rewrites are not overwritten while unchanged adapter property is restored`() {
        val ownership = ownership(
            maxOriginal = style("0px"),
            maxWritten = style("696px", "important"),
            overflow = OwnedInlineStyle(style("scroll"), style("auto", "important")),
        )

        assertEquals(
            ViewportRepairDecision(
                overflowYMutation = style("scroll"),
                nextOwnership = null,
            ),
            decideViewportRepair(
                viewportHeightPx = 720,
                computedMaxHeightPx = 540,
                computedOverflowY = "auto",
                clientHeightPx = 314,
                scrollHeightPx = 314,
                visible = true,
                fixedOverlay = true,
                inlineMaxHeight = style("540px", "important"),
                inlineOverflowY = style("auto", "important"),
                ownership = ownership,
            ),
        )
    }

    @Test
    fun `same numeric max height with card important priority relinquishes ownership`() {
        val ownership = ownership(
            maxOriginal = style(""),
            maxWritten = style("696px"),
            overflow = OwnedInlineStyle(style(""), style("auto", "important")),
        )

        assertEquals(
            ViewportRepairDecision(
                overflowYMutation = style(""),
                nextOwnership = null,
            ),
            decideViewportRepair(
                viewportHeightPx = 720,
                computedMaxHeightPx = 696,
                computedOverflowY = "auto",
                clientHeightPx = 314,
                scrollHeightPx = 314,
                visible = true,
                fixedOverlay = true,
                inlineMaxHeight = style("696px", "important"),
                inlineOverflowY = style("auto", "important"),
                ownership = ownership,
            ),
        )
    }

    @Test
    fun `owned repair follows viewport changes without repeated writes`() {
        val ownership = ownership(
            maxOriginal = style(""),
            maxWritten = style("696px"),
            overflow = OwnedInlineStyle(style(""), style("auto")),
        )

        assertNull(
            decideViewportRepair(
                viewportHeightPx = 720,
                computedMaxHeightPx = 696,
                computedOverflowY = "auto",
                clientHeightPx = 314,
                scrollHeightPx = 314,
                visible = true,
                fixedOverlay = true,
                inlineMaxHeight = style("696px"),
                inlineOverflowY = style("auto"),
                ownership = ownership,
            ),
        )

        val resized = decideViewportRepair(
            viewportHeightPx = 600,
            computedMaxHeightPx = 696,
            computedOverflowY = "auto",
            clientHeightPx = 314,
            scrollHeightPx = 314,
            visible = true,
            fixedOverlay = true,
            inlineMaxHeight = style("696px"),
            inlineOverflowY = style("auto"),
            ownership = ownership,
        )
        assertEquals(style("576px"), resized?.maxHeightMutation)
        assertEquals(style("576px"), resized?.nextOwnership?.maxHeight?.written)
        assertEquals(style(""), resized?.nextOwnership?.maxHeight?.original)
    }

    @Test
    fun `preserves a card supplied usable max height and ignores ineligible panels`() {
        assertNull(
            decideViewportRepair(
                viewportHeightPx = 720,
                computedMaxHeightPx = 540,
                computedOverflowY = "visible",
                clientHeightPx = 314,
                scrollHeightPx = 314,
                visible = true,
                fixedOverlay = true,
                inlineMaxHeight = style("540px"),
                inlineOverflowY = style(""),
            ),
        )
        assertNull(decideViewportRepair(720, 0, "visible", 48, 314, false, true, style(""), style("")))
        assertNull(decideViewportRepair(720, 0, "visible", 48, 314, true, false, style(""), style("")))
        assertNull(decideViewportRepair(720, null, "visible", 306, 314, true, true, style(""), style("")))
    }

    @Test
    fun `marker parser accepts only a complete finite integer`() {
        assertEquals(696, parseViewportRepairMarker("696"))
        listOf(null, "", "696junk", "696.0", "NaN", "Infinity", "-696", "0").forEach { marker ->
            assertNull("marker must be rejected: $marker", parseViewportRepairMarker(marker))
        }
    }

    @Test
    fun `generated adapter tracks explicit per property ownership without observer feedback`() {
        val script = buildTavernViewportAdapterScript()

        assertTrue(script.contains("const ownedRepairs = new WeakMap()"))
        assertTrue(script.contains("function parseViewportRepairMarker(raw)"))
        assertTrue(script.contains("/^[1-9]\\d*$/"))
        assertTrue(script.contains("getPropertyPriority(property)"))
        assertTrue(script.contains("snapshotInlineStyle(panel, 'max-height')"))
        assertTrue(script.contains("snapshotInlineStyle(panel, 'overflow-y')"))
        assertTrue(script.contains("style.setProperty(property, mutation.value, mutation.priority)"))
        assertTrue(script.contains("mutationObserver.disconnect()"))
        assertTrue(script.contains("attributeFilter: ['class', 'style', 'open']"))
        assertFalse(script.contains("Number.parseFloat(panel.dataset.rikkahubOverlayRepaired)"))
        assertFalse(script.contains("clearInlineOverflowY: input.inlineOverflowYAuto"))
    }

    private fun style(value: String, priority: String = "") = InlineStyleSnapshot(value, priority)

    private fun ownership(
        maxOriginal: InlineStyleSnapshot,
        maxWritten: InlineStyleSnapshot,
        overflow: OwnedInlineStyle?,
    ) = ViewportRepairOwnership(
        maxHeight = OwnedInlineStyle(maxOriginal, maxWritten),
        overflowY = overflow,
    )
}
