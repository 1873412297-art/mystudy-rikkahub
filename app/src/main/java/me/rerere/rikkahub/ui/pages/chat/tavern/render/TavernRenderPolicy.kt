package me.rerere.rikkahub.ui.pages.chat.tavern.render

internal enum class TavernRenderSurface { OPENING, HUD, MESSAGE }

internal enum class TavernVerticalScrollOwner { WEBVIEW, PARENT }

internal data class TavernRenderPolicy(
    val surface: TavernRenderSurface,
    val panelFraction: Float,
    val maxHeightDp: Int,
    val fullscreen: Boolean,
    val verticalScrollOwner: TavernVerticalScrollOwner,
    val captureHorizontalGestures: Boolean,
)

internal fun resolveTavernRenderPolicy(
    surface: TavernRenderSurface,
    availableHeightDp: Int,
    persistedHudFraction: Float?,
    fullscreen: Boolean,
): TavernRenderPolicy {
    val fraction = when {
        fullscreen -> 1f
        surface == TavernRenderSurface.HUD -> (persistedHudFraction ?: 0.80f).coerceIn(0.50f, 0.90f)
        else -> 1f
    }
    val owner = if (surface == TavernRenderSurface.MESSAGE) {
        TavernVerticalScrollOwner.PARENT
    } else {
        TavernVerticalScrollOwner.WEBVIEW
    }
    return TavernRenderPolicy(
        surface = surface,
        panelFraction = fraction,
        maxHeightDp = (availableHeightDp * fraction).toInt().coerceAtLeast(1),
        fullscreen = fullscreen,
        verticalScrollOwner = owner,
        captureHorizontalGestures = surface != TavernRenderSurface.MESSAGE,
    )
}
