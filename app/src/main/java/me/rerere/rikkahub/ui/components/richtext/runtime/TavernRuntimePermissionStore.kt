package me.rerere.rikkahub.ui.components.richtext.runtime

import me.rerere.rikkahub.data.model.TavernRuntimePermissions

internal class TavernRuntimePermissionStore(
    initial: TavernRuntimePermissions = TavernRuntimePermissions(),
) {
    // @JavascriptInterface 在 JavaBridge 线程读取，update 在 UI 线程写入，需要跨线程可见性
    @Volatile
    private var state = initial

    fun current(): TavernRuntimePermissions = state

    fun update(newState: TavernRuntimePermissions) {
        state = newState
    }
}
