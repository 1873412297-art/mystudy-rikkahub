package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

@Serializable
data class TavernRuntimePermissions(
    val allowScripts: Boolean = true,
    val allowWorldWrite: Boolean = false,
    val allowMessageWrite: Boolean = false,
    val allowNetwork: Boolean = false,
    val allowVariablesWrite: Boolean = false,
    val allowEventSubscribe: Boolean = false,
)
