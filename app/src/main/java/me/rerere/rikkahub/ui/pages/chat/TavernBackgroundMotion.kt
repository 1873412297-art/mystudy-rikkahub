package me.rerere.rikkahub.ui.pages.chat

internal data class TavernBackgroundMotion(
    val enabled: Boolean,
    val minScale: Float,
    val maxScale: Float,
    val translationFraction: Float,
    val durationMillis: Int,
) {
    companion object {
        val Static = TavernBackgroundMotion(
            enabled = false,
            minScale = 1f,
            maxScale = 1f,
            translationFraction = 0f,
            durationMillis = 0,
        )
    }
}

internal fun resolveTavernBackgroundMotion(
    animateImage: Boolean,
    hasBackground: Boolean,
    animatorsEnabled: Boolean,
    pageVisible: Boolean,
): TavernBackgroundMotion = if (animateImage && hasBackground && animatorsEnabled && pageVisible) {
    TavernBackgroundMotion(
        enabled = true,
        minScale = 1.015f,
        maxScale = 1.045f,
        translationFraction = 0.006f,
        durationMillis = 14_000,
    )
} else {
    TavernBackgroundMotion.Static
}
