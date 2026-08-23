package me.rerere.rikkahub.ui.pages.chat.tavern.render

internal data class ViewportRepairDecision(
    val maxHeightPx: Int,
    val enableVerticalScroll: Boolean,
)

internal fun decideViewportRepair(
    viewportHeightPx: Int,
    computedMaxHeightPx: Int?,
    clientHeightPx: Int,
    scrollHeightPx: Int,
    visible: Boolean,
    fixedOverlay: Boolean,
): ViewportRepairDecision? {
    if (!visible || !fixedOverlay) return null
    if ((computedMaxHeightPx ?: 0) > 1) return null
    if (scrollHeightPx <= clientHeightPx + 8) return null
    return ViewportRepairDecision(
        maxHeightPx = (viewportHeightPx - 24).coerceAtLeast(216),
        enableVerticalScroll = true,
    )
}

internal fun buildTavernViewportAdapterScript(): String = """
const tavernViewportAdapter = (() => {
  let frame = 0;
  let lastViewportHeight = 0;
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
      if (style.position !== 'fixed' || style.display === 'none' || style.visibility === 'hidden') return;
      Array.from(overlay.children).forEach((panel) => {
        const panelStyle = getComputedStyle(panel);
        const maxHeight = Number.parseFloat(panelStyle.maxHeight);
        const clipped = (!Number.isFinite(maxHeight) || maxHeight <= 1) &&
          panel.scrollHeight > panel.clientHeight + 8;
        if (!clipped) return;
        const target = Math.max(216, viewportHeight - 24) + 'px';
        if (panel.style.maxHeight !== target) panel.style.maxHeight = target;
        if (panel.style.overflowY !== 'auto') panel.style.overflowY = 'auto';
        if (panel.dataset.rikkahubOverlayRepaired !== 'true') {
          panel.dataset.rikkahubOverlayRepaired = 'true';
        }
      });
    });
    lastViewportHeight = viewportHeight;
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
