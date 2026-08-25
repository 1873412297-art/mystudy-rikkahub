package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

@Serializable
data class TavernHelperRenderSettings(
    val enabled: Boolean = true,
    val depth: Int = 0,
    val ignoreHiddenMessages: Boolean = false,
    val collapseFrontendCode: Boolean = true,
    val allowStreaming: Boolean = false,
    val allowScripts: Boolean = false,
    val allowNetwork: Boolean = false,
)

internal fun TavernHelperRenderSettings.shouldRenderFrontend(
    messageDepth: Int?,
    streaming: Boolean,
): Boolean = enabled &&
    (depth == 0 || messageDepth == null || messageDepth < depth.coerceIn(1, 500)) &&
    (!streaming || allowStreaming)
