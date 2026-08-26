package me.rerere.rikkahub.ui.components.richtext.runtime

import me.rerere.rikkahub.data.model.TavernRuntimePermissions

internal class TavernRuntimePermissionStore(
    initial: TavernRuntimePermissions = TavernRuntimePermissions(),
) {
    // @JavascriptInterface 在 JavaBridge 线程读取，update 在 UI 线程写入，需要跨线程可见性
    @Volatile
    private var state = initial

    /**
     * 脚本哈希级授权提供器（可选）。每次 current() 调用时求值并与全局默认值合并：
     * 布尔位取或、域名白名单取并集。消息前端（无脚本身份）不设置，恒走全局默认值。
     * 授权失效判断（源码哈希比对）在提供器内部完成，失效时返回 null。
     */
    @Volatile
    var grantProvider: (() -> TavernRuntimePermissions?)? = null

    fun current(): TavernRuntimePermissions {
        val base = state
        val grant = grantProvider?.invoke() ?: return base
        return base.mergedWith(grant)
    }

    fun update(newState: TavernRuntimePermissions) {
        state = newState
    }
}
