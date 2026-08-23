package me.rerere.rikkahub.ui.pages.chat.tavern.render

import kotlin.math.abs

private const val VIEWPORT_GUTTER_PX = 24
private const val MIN_REPAIRED_HEIGHT_PX = 216
private const val CLIPPING_TOLERANCE_PX = 8
private const val OWNED_HEIGHT_TOLERANCE_PX = 1
private const val OVERLAY_REPAIR_MARKER = "rikkahubOverlayRepaired"

internal data class ViewportRepairDecision(
    val maxHeightPx: Int? = null,
    val enableVerticalScroll: Boolean = false,
    val ownedMaxHeightPx: Int? = null,
    val releaseOwnership: Boolean = false,
    val clearInlineMaxHeight: Boolean = false,
    val clearInlineOverflowY: Boolean = false,
)

internal fun decideViewportRepair(
    viewportHeightPx: Int,
    computedMaxHeightPx: Int?,
    clientHeightPx: Int,
    scrollHeightPx: Int,
    visible: Boolean,
    fixedOverlay: Boolean,
    inlineMaxHeightPx: Int? = computedMaxHeightPx,
    inlineOverflowYAuto: Boolean = false,
    ownedMaxHeightPx: Int? = null,
): ViewportRepairDecision? {
    if (!visible || !fixedOverlay) {
        return ownedMaxHeightPx?.let {
            releaseViewportRepair(inlineMaxHeightPx, inlineOverflowYAuto, it)
        }
    }

    if (ownedMaxHeightPx != null) {
        val stillOwnsMaxHeight =
            inlineMaxHeightPx.isNear(ownedMaxHeightPx) && computedMaxHeightPx.isNear(ownedMaxHeightPx)
        if (!stillOwnsMaxHeight) {
            return releaseViewportRepair(inlineMaxHeightPx, inlineOverflowYAuto, ownedMaxHeightPx)
        }

        val target = repairedHeight(viewportHeightPx)
        val maxHeightChanged = !inlineMaxHeightPx.isNear(target)
        val overflowChanged = !inlineOverflowYAuto
        if (!maxHeightChanged && !overflowChanged && ownedMaxHeightPx == target) return null
        return ViewportRepairDecision(
            maxHeightPx = target.takeIf { maxHeightChanged },
            enableVerticalScroll = overflowChanged,
            ownedMaxHeightPx = target,
        )
    }

    if ((computedMaxHeightPx ?: 0) > 1) return null
    if (scrollHeightPx <= clientHeightPx + CLIPPING_TOLERANCE_PX) return null
    val target = repairedHeight(viewportHeightPx)
    return ViewportRepairDecision(
        maxHeightPx = target,
        enableVerticalScroll = !inlineOverflowYAuto,
        ownedMaxHeightPx = target,
    )
}

private fun releaseViewportRepair(
    inlineMaxHeightPx: Int?,
    inlineOverflowYAuto: Boolean,
    ownedMaxHeightPx: Int,
): ViewportRepairDecision = ViewportRepairDecision(
    releaseOwnership = true,
    clearInlineMaxHeight = inlineMaxHeightPx.isNear(ownedMaxHeightPx),
    clearInlineOverflowY = inlineOverflowYAuto,
)

private fun repairedHeight(viewportHeightPx: Int): Int =
    (viewportHeightPx - VIEWPORT_GUTTER_PX).coerceAtLeast(MIN_REPAIRED_HEIGHT_PX)

private fun Int?.isNear(other: Int): Boolean =
    this != null && abs(this - other) <= OWNED_HEIGHT_TOLERANCE_PX

internal fun buildTavernViewportAdapterScript(): String = """
const tavernViewportAdapter = (() => {
  let frame = 0;
  function nearlyEqual(first, second) {
    return Number.isFinite(first) && Number.isFinite(second) &&
      Math.abs(first - second) <= $OWNED_HEIGHT_TOLERANCE_PX;
  }
  function repairedHeight(viewportHeight) {
    return Math.max($MIN_REPAIRED_HEIGHT_PX, viewportHeight - $VIEWPORT_GUTTER_PX);
  }
  function decideViewportRepair(input) {
    if (!input.visible || !input.fixedOverlay) {
      if (input.ownedMaxHeightPx === null) return null;
      return {
        releaseOwnership: true,
        clearInlineMaxHeight: nearlyEqual(input.inlineMaxHeightPx, input.ownedMaxHeightPx),
        clearInlineOverflowY: input.inlineOverflowYAuto,
      };
    }
    if (input.ownedMaxHeightPx !== null) {
      const stillOwnsMaxHeight =
        nearlyEqual(input.inlineMaxHeightPx, input.ownedMaxHeightPx) &&
        nearlyEqual(input.computedMaxHeightPx, input.ownedMaxHeightPx);
      if (!stillOwnsMaxHeight) {
        return {
          releaseOwnership: true,
          clearInlineMaxHeight: nearlyEqual(input.inlineMaxHeightPx, input.ownedMaxHeightPx),
          clearInlineOverflowY: input.inlineOverflowYAuto,
        };
      }
      const target = repairedHeight(input.viewportHeightPx);
      const maxHeightChanged = !nearlyEqual(input.inlineMaxHeightPx, target);
      const overflowChanged = !input.inlineOverflowYAuto;
      if (!maxHeightChanged && !overflowChanged && input.ownedMaxHeightPx === target) return null;
      return {
        maxHeightPx: maxHeightChanged ? target : null,
        enableVerticalScroll: overflowChanged,
        ownedMaxHeightPx: target,
      };
    }
    if ((input.computedMaxHeightPx || 0) > 1) return null;
    if (input.scrollHeightPx <= input.clientHeightPx + $CLIPPING_TOLERANCE_PX) return null;
    const target = repairedHeight(input.viewportHeightPx);
    return {
      maxHeightPx: target,
      enableVerticalScroll: !input.inlineOverflowYAuto,
      ownedMaxHeightPx: target,
    };
  }
  function schedule() {
    if (frame) return;
    frame = requestAnimationFrame(refresh);
  }
  function refresh() {
    frame = 0;
    const viewportHeight = Math.max(240, Math.floor(
      (window.visualViewport && window.visualViewport.height) || window.innerHeight || 0
    ));
    if (!document.body) return;
    document.body.querySelectorAll('*').forEach((overlay) => {
      const style = getComputedStyle(overlay);
      const fixedOverlay = style.position === 'fixed';
      const overlayVisible = style.display !== 'none' && style.visibility !== 'hidden';
      const panels = Array.from(overlay.children);
      const hasOwnedPanel = panels.some((panel) => panel.dataset.$OVERLAY_REPAIR_MARKER);
      if ((!fixedOverlay || !overlayVisible) && !hasOwnedPanel) return;
      panels.forEach((panel) => {
        const panelStyle = getComputedStyle(panel);
        const computedMaxHeight = Number.parseFloat(panelStyle.maxHeight);
        const inlineMaxHeight = Number.parseFloat(panel.style.maxHeight);
        const ownedMaxHeight = Number.parseFloat(panel.dataset.$OVERLAY_REPAIR_MARKER);
        const owned = Number.isFinite(ownedMaxHeight) && ownedMaxHeight > 1;
        const decision = decideViewportRepair({
          viewportHeightPx: viewportHeight,
          computedMaxHeightPx: Number.isFinite(computedMaxHeight) ? computedMaxHeight : null,
          inlineMaxHeightPx: Number.isFinite(inlineMaxHeight) ? inlineMaxHeight : null,
          clientHeightPx: panel.clientHeight,
          scrollHeightPx: panel.scrollHeight,
          visible: overlayVisible && panelStyle.display !== 'none' && panelStyle.visibility !== 'hidden',
          fixedOverlay: fixedOverlay,
          inlineOverflowYAuto: panel.style.overflowY === 'auto',
          ownedMaxHeightPx: owned ? ownedMaxHeight : null,
        });
        if (!decision) return;
        if (decision.releaseOwnership) {
          if (decision.clearInlineMaxHeight) panel.style.removeProperty('max-height');
          if (decision.clearInlineOverflowY) panel.style.removeProperty('overflow-y');
          delete panel.dataset.$OVERLAY_REPAIR_MARKER;
          return;
        }
        if (decision.maxHeightPx !== null) {
          const target = decision.maxHeightPx + 'px';
          if (panel.style.maxHeight !== target) panel.style.maxHeight = target;
        }
        if (decision.enableVerticalScroll && panel.style.overflowY !== 'auto') {
          panel.style.overflowY = 'auto';
        }
        if (decision.ownedMaxHeightPx !== null &&
            panel.dataset.$OVERLAY_REPAIR_MARKER !== String(decision.ownedMaxHeightPx)) {
          panel.dataset.$OVERLAY_REPAIR_MARKER = String(decision.ownedMaxHeightPx);
        }
      });
    });
  }
  function observe() {
    schedule();
    window.addEventListener('resize', schedule);
    window.addEventListener('orientationchange', schedule);
    if (window.visualViewport) window.visualViewport.addEventListener('resize', schedule);
    if (typeof ResizeObserver !== 'undefined') {
      try {
        const resizeObserver = new ResizeObserver(schedule);
        resizeObserver.observe(document.documentElement);
        if (document.body) resizeObserver.observe(document.body);
      } catch (_) {}
    }
    if (typeof MutationObserver !== 'undefined') {
      try {
        const mutationObserver = new MutationObserver(schedule);
        mutationObserver.observe(document.documentElement, {
          attributes: true,
          attributeFilter: ['class', 'style', 'open'],
          childList: true,
          subtree: true,
        });
      } catch (_) {}
    }
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', observe, { once: true });
  } else {
    observe();
  }
  return { schedule };
})();
""".trimIndent()
