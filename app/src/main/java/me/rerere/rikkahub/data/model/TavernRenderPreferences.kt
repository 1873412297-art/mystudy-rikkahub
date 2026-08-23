package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

@Serializable
data class TavernRenderPreferences(
    val hudFraction: Float = 0.80f,
) {
    fun normalized(): TavernRenderPreferences = copy(
        hudFraction = normalizeTavernHudFraction(hudFraction),
    )
}

fun normalizeTavernHudFraction(value: Float): Float = value.coerceIn(0.50f, 0.90f)
