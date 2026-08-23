package me.rerere.rikkahub.ui.pages.chat.tavern.render

import kotlin.math.abs

private const val VIEWPORT_GUTTER_PX = 24
private const val MIN_REPAIRED_HEIGHT_PX = 216
private const val CLIPPING_TOLERANCE_PX = 8
private const val OWNED_HEIGHT_TOLERANCE_PX = 1
private const val OVERLAY_REPAIR_MARKER = "rikkahubOverlayRepaired"
private const val ADAPTER_PRIORITY = ""

internal data class InlineStyleSnapshot(
    val value: String = "",
    val priority: String = "",
)

internal data class OwnedInlineStyle(
    val original: InlineStyleSnapshot,
    val written: InlineStyleSnapshot,
)

internal data class ViewportRepairOwnership(
    val maxHeight: OwnedInlineStyle,
    val overflowY: OwnedInlineStyle? = null,
)

internal data class ViewportRepairDecision(
    val maxHeightMutation: InlineStyleSnapshot? = null,
    val overflowYMutation: InlineStyleSnapshot? = null,
    val nextOwnership: ViewportRepairOwnership? = null,
)

internal fun decideViewportRepair(
    viewportHeightPx: Int,
    computedMaxHeightPx: Int?,
    computedOverflowY: String,
    clientHeightPx: Int,
    scrollHeightPx: Int,
    visible: Boolean,
    fixedOverlay: Boolean,
    inlineMaxHeight: InlineStyleSnapshot,
    inlineOverflowY: InlineStyleSnapshot,
    ownership: ViewportRepairOwnership? = null,
): ViewportRepairDecision? {
    if (ownership == null) {
        if (!visible || !fixedOverlay) return null
        if ((computedMaxHeightPx ?: 0) > 1) return null
        if (scrollHeightPx <= clientHeightPx + CLIPPING_TOLERANCE_PX) return null

        val maxHeight = repairedMaxHeight(viewportHeightPx)
        val overflowY = InlineStyleSnapshot("auto", ADAPTER_PRIORITY)
            .takeUnless { computedOverflowY.equals("auto", ignoreCase = true) }
        return ViewportRepairDecision(
            maxHeightMutation = maxHeight,
            overflowYMutation = overflowY,
            nextOwnership = ViewportRepairOwnership(
                maxHeight = OwnedInlineStyle(inlineMaxHeight, maxHeight),
                overflowY = overflowY?.let { OwnedInlineStyle(inlineOverflowY, it) },
            ),
        )
    }

    val ownsCurrentMaxHeight = inlineMaxHeight == ownership.maxHeight.written
    val ownsCurrentOverflowY = ownership.overflowY?.let { inlineOverflowY == it.written } == true

    fun releaseRepair(): ViewportRepairDecision = ViewportRepairDecision(
        maxHeightMutation = ownership.maxHeight.original.takeIf { ownsCurrentMaxHeight },
        overflowYMutation = ownership.overflowY?.original.takeIf { ownsCurrentOverflowY },
        nextOwnership = null,
    )

    if (!visible || !fixedOverlay || !ownsCurrentMaxHeight) return releaseRepair()

    val ownedComputedHeight = ownership.maxHeight.written.pixelValue()
    if (ownedComputedHeight == null || !computedMaxHeightPx.isNear(ownedComputedHeight)) return releaseRepair()

    val targetMaxHeight = repairedMaxHeight(viewportHeightPx)
    val nextMaxHeight = ownership.maxHeight.copy(written = targetMaxHeight)
    val ownedOverflowY = ownership.overflowY
    val nextOverflowY = ownedOverflowY?.takeIf {
        ownsCurrentOverflowY && computedOverflowY.equals("auto", ignoreCase = true)
    }
    val restoreOverflowY = ownedOverflowY?.original.takeIf {
        ownsCurrentOverflowY && nextOverflowY == null
    }
    val nextOwnership = ViewportRepairOwnership(
        maxHeight = nextMaxHeight,
        overflowY = nextOverflowY,
    )
    val maxHeightMutation = targetMaxHeight.takeUnless { it == inlineMaxHeight }

    if (maxHeightMutation == null && restoreOverflowY == null && nextOwnership == ownership) return null
    return ViewportRepairDecision(
        maxHeightMutation = maxHeightMutation,
        overflowYMutation = restoreOverflowY,
        nextOwnership = nextOwnership,
    )
}

internal fun parseViewportRepairMarker(marker: String?): Int? {
    if (marker == null || !marker.matches(Regex("[1-9]\\d*"))) return null
    return marker.toLongOrNull()?.takeIf { it in 2..Int.MAX_VALUE }?.toInt()
}

private fun repairedMaxHeight(viewportHeightPx: Int): InlineStyleSnapshot = InlineStyleSnapshot(
    value = "${(viewportHeightPx - VIEWPORT_GUTTER_PX).coerceAtLeast(MIN_REPAIRED_HEIGHT_PX)}px",
    priority = ADAPTER_PRIORITY,
)

private fun InlineStyleSnapshot.pixelValue(): Int? =
    value.removeSuffix("px").takeIf { value.endsWith("px") }?.toIntOrNull()

private fun Int?.isNear(other: Int): Boolean =
    this != null && abs(this - other) <= OWNED_HEIGHT_TOLERANCE_PX

internal fun buildTavernViewportAdapterScript(): String = """
const tavernViewportAdapter = (() => {
  let frame = 0;
  let mutationObserver = null;
  const ownedRepairs = new WeakMap();
  function snapshotInlineStyle(panel, property) {
    return {
      value: panel.style.getPropertyValue(property),
      priority: panel.style.getPropertyPriority(property),
    };
  }
  function sameInlineStyle(first, second) {
    return !!first && !!second && first.value === second.value && first.priority === second.priority;
  }
  function parseViewportRepairMarker(raw) {
    if (typeof raw !== 'string' || !/^[1-9]\d*$/.test(raw)) return null;
    const parsed = Number(raw);
    return Number.isSafeInteger(parsed) && parsed > 1 ? parsed : null;
  }
  function parsePixelValue(raw) {
    if (typeof raw !== 'string' || !/^[0-9]+px$/.test(raw)) return null;
    const parsed = Number(raw.slice(0, -2));
    return Number.isSafeInteger(parsed) ? parsed : null;
  }
  function nearlyEqual(first, second) {
    return Number.isFinite(first) && Number.isFinite(second) &&
      Math.abs(first - second) <= $OWNED_HEIGHT_TOLERANCE_PX;
  }
  function repairedMaxHeight(viewportHeight) {
    return {
      value: Math.max($MIN_REPAIRED_HEIGHT_PX, viewportHeight - $VIEWPORT_GUTTER_PX) + 'px',
      priority: '$ADAPTER_PRIORITY',
    };
  }
  function decideViewportRepair(input) {
    const ownership = input.ownership;
    if (!ownership) {
      if (!input.visible || !input.fixedOverlay) return null;
      if ((input.computedMaxHeightPx || 0) > 1) return null;
      if (input.scrollHeightPx <= input.clientHeightPx + $CLIPPING_TOLERANCE_PX) return null;
      const maxHeight = repairedMaxHeight(input.viewportHeightPx);
      const overflowY = input.computedOverflowY.toLowerCase() === 'auto'
        ? null
        : { value: 'auto', priority: '$ADAPTER_PRIORITY' };
      return {
        maxHeightMutation: maxHeight,
        overflowYMutation: overflowY,
        nextOwnership: {
          maxHeight: { original: input.inlineMaxHeight, written: maxHeight },
          overflowY: overflowY ? { original: input.inlineOverflowY, written: overflowY } : null,
        },
      };
    }

    const ownsCurrentMaxHeight = sameInlineStyle(input.inlineMaxHeight, ownership.maxHeight.written);
    const ownsCurrentOverflowY = !!ownership.overflowY &&
      sameInlineStyle(input.inlineOverflowY, ownership.overflowY.written);
    function releaseRepair() {
      return {
        maxHeightMutation: ownsCurrentMaxHeight ? ownership.maxHeight.original : null,
        overflowYMutation: ownsCurrentOverflowY ? ownership.overflowY.original : null,
        nextOwnership: null,
      };
    }
    if (!input.visible || !input.fixedOverlay || !ownsCurrentMaxHeight) return releaseRepair();
    const ownedComputedHeight = parsePixelValue(ownership.maxHeight.written.value);
    if (ownedComputedHeight === null || !nearlyEqual(input.computedMaxHeightPx, ownedComputedHeight)) {
      return releaseRepair();
    }

    const targetMaxHeight = repairedMaxHeight(input.viewportHeightPx);
    const nextMaxHeight = { original: ownership.maxHeight.original, written: targetMaxHeight };
    const nextOverflowY = ownsCurrentOverflowY && input.computedOverflowY.toLowerCase() === 'auto'
      ? ownership.overflowY
      : null;
    const restoreOverflowY = ownsCurrentOverflowY && !nextOverflowY
      ? ownership.overflowY.original
      : null;
    const nextOwnership = { maxHeight: nextMaxHeight, overflowY: nextOverflowY };
    const maxHeightMutation = sameInlineStyle(input.inlineMaxHeight, targetMaxHeight) ? null : targetMaxHeight;
    const ownershipChanged = nextOverflowY !== ownership.overflowY ||
      !sameInlineStyle(nextMaxHeight.written, ownership.maxHeight.written);
    if (!maxHeightMutation && !restoreOverflowY && !ownershipChanged) return null;
    return {
      maxHeightMutation: maxHeightMutation,
      overflowYMutation: restoreOverflowY,
      nextOwnership: nextOwnership,
    };
  }
  function schedule() {
    if (frame) return;
    frame = requestAnimationFrame(refresh);
  }
  function connectMutationObserver() {
    if (typeof MutationObserver === 'undefined' || !document.documentElement) return;
    try {
      if (!mutationObserver) mutationObserver = new MutationObserver(schedule);
      mutationObserver.observe(document.documentElement, {
        attributes: true,
        attributeFilter: ['class', 'style', 'open'],
        childList: true,
        subtree: true,
      });
    } catch (_) {}
  }
  function applyStyleMutation(style, property, mutation) {
    if (!mutation) return;
    if (mutation.value) style.setProperty(property, mutation.value, mutation.priority);
    else style.removeProperty(property);
  }
  function applyDecision(panel, decision) {
    if (mutationObserver) mutationObserver.disconnect();
    try {
      applyStyleMutation(panel.style, 'max-height', decision.maxHeightMutation);
      applyStyleMutation(panel.style, 'overflow-y', decision.overflowYMutation);
      if (decision.nextOwnership) {
        ownedRepairs.set(panel, decision.nextOwnership);
        const marker = parsePixelValue(decision.nextOwnership.maxHeight.written.value);
        if (marker !== null && panel.dataset.$OVERLAY_REPAIR_MARKER !== String(marker)) {
          panel.dataset.$OVERLAY_REPAIR_MARKER = String(marker);
        }
      } else {
        ownedRepairs.delete(panel);
        delete panel.dataset.$OVERLAY_REPAIR_MARKER;
      }
    } finally {
      connectMutationObserver();
    }
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
      const hasOwnedPanel = panels.some((panel) => ownedRepairs.has(panel));
      if ((!fixedOverlay || !overlayVisible) && !hasOwnedPanel) return;
      panels.forEach((panel) => {
        let ownership = ownedRepairs.get(panel) || null;
        if (ownership) {
          const marker = parseViewportRepairMarker(panel.dataset.$OVERLAY_REPAIR_MARKER);
          const writtenHeight = parsePixelValue(ownership.maxHeight.written.value);
          if (marker === null || marker !== writtenHeight) {
            ownedRepairs.delete(panel);
            ownership = null;
          }
        }
        const panelStyle = getComputedStyle(panel);
        const computedMaxHeight = Number.parseFloat(panelStyle.maxHeight);
        const decision = decideViewportRepair({
          viewportHeightPx: viewportHeight,
          computedMaxHeightPx: Number.isFinite(computedMaxHeight) ? computedMaxHeight : null,
          computedOverflowY: panelStyle.overflowY || '',
          inlineMaxHeight: snapshotInlineStyle(panel, 'max-height'),
          inlineOverflowY: snapshotInlineStyle(panel, 'overflow-y'),
          clientHeightPx: panel.clientHeight,
          scrollHeightPx: panel.scrollHeight,
          visible: overlayVisible && panelStyle.display !== 'none' && panelStyle.visibility !== 'hidden',
          fixedOverlay: fixedOverlay,
          ownership: ownership,
        });
        if (decision) applyDecision(panel, decision);
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
    connectMutationObserver();
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', observe, { once: true });
  } else {
    observe();
  }
  return { schedule };
})();
""".trimIndent()
