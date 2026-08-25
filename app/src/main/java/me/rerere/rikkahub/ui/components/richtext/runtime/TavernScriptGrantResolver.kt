package me.rerere.rikkahub.ui.components.richtext.runtime

import me.rerere.rikkahub.data.model.TavernRuntimePermissions

/**
 * 脚本哈希级授权解析：给定 scriptId 返回当前生效的授权权限集；授权不存在或
 * 源码哈希已变化（脚本被编辑/换版本）时返回 null，调用方退化为全局默认权限。
 */
internal fun interface TavernScriptGrantResolver {
    fun resolve(scriptId: String): TavernRuntimePermissions?
}

/**
 * Settings 持久化的授权记录 + 可注入的源码哈希源（生产环境由脚本仓库供给当前
 * sourceSha256；消息前端无 scriptId，不参与授权合并）。
 */
internal class SettingsBackedTavernScriptGrantResolver(
    private val settingsGateway: TavernVariableSettingsGateway,
    private val currentSourceSha256: (scriptId: String) -> String?,
) : TavernScriptGrantResolver {
    override fun resolve(scriptId: String): TavernRuntimePermissions? {
        val grant = settingsGateway.currentSettings().tavernScriptPermissionGrants[scriptId] ?: return null
        val currentHash = currentSourceSha256(scriptId) ?: return null
        return grant.permissions.takeIf { grant.sourceSha256 == currentHash }
    }
}

/** 授权管理：授予 / 撤销（供未来「信任当前版本」确认入口调用）。 */
internal fun TavernVariableSettingsGateway.grantScriptPermissions(
    scriptId: String,
    sourceSha256: String,
    permissions: TavernRuntimePermissions,
    grantedAt: Long,
) {
    updateSettings { settings ->
        settings.copy(
            tavernScriptPermissionGrants = settings.tavernScriptPermissionGrants +
                (scriptId to me.rerere.rikkahub.data.model.TavernScriptPermissionGrant(
                    sourceSha256 = sourceSha256,
                    permissions = permissions,
                    grantedAt = grantedAt,
                ))
        )
    }
}

internal fun TavernVariableSettingsGateway.revokeScriptPermissions(scriptId: String) {
    updateSettings { settings ->
        settings.copy(tavernScriptPermissionGrants = settings.tavernScriptPermissionGrants - scriptId)
    }
}
