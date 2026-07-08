package me.rerere.rikkahub.ui.components.richtext.runtime

import me.rerere.rikkahub.data.model.TavernRuntimePermissions

internal class TavernRuntimePermissionStore(
    initial: TavernRuntimePermissions = TavernRuntimePermissions(),
) {
    private var state = initial

    fun current(): TavernRuntimePermissions = state

    fun update(newState: TavernRuntimePermissions) {
        state = newState
    }
}
