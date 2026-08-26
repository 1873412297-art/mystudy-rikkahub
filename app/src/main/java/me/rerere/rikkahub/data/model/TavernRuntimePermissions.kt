package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

fun tavernCardPermissionFingerprint(cardJson: String): String = MessageDigest
    .getInstance("SHA-256")
    .digest(cardJson.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

@Serializable
data class TavernRuntimePermissions(
    val allowScripts: Boolean = true,
    /** 运行消息前端脚本（总开关 allowScripts 之下的细分位，默认 true 保持既有行为） */
    val allowMessageScripts: Boolean = true,
    /** 运行常驻浏览器脚本（默认 true 保持既有行为） */
    val allowBrowserScripts: Boolean = true,
    val allowWorldWrite: Boolean = true,
    val allowMessageWrite: Boolean = true,
    val allowNetwork: Boolean = true,
    /**
     * 网络访问域名白名单（配合 allowNetwork 使用）。
     * 空列表 = 不限制（保持既有行为）；非空时仅名单内域名放行。
     */
    val allowedNetworkDomains: List<String> = emptyList(),
    val allowVariablesWrite: Boolean = true,
    val allowEventSubscribe: Boolean = true,
    /** 允许脚本注册宿主宏与斜杠命令（默认 true，保持旧版本兼容行为） */
    val allowMacroRegister: Boolean = true,
    /** 允许脚本读取当前模型请求头（含 API key 等敏感信息，默认 false） */
    val allowRequestHeaders: Boolean = false,
    /** 允许脚本发起模型生成（产生真实 API 调用与费用，默认 false） */
    val allowGeneration: Boolean = false,
    /** 允许脚本修改角色或助手/预设（含 character/preset 作用域变量写入，默认 false） */
    val allowAssistantWrite: Boolean = false,
) {
    /**
     * 全局默认值与脚本哈希级授权的合并：布尔位取或（任一允许即放行），域名白名单取并集。
     */
    fun mergedWith(grant: TavernRuntimePermissions): TavernRuntimePermissions {
        return TavernRuntimePermissions(
            allowScripts = allowScripts || grant.allowScripts,
            allowMessageScripts = allowMessageScripts || grant.allowMessageScripts,
            allowBrowserScripts = allowBrowserScripts || grant.allowBrowserScripts,
            allowWorldWrite = allowWorldWrite || grant.allowWorldWrite,
            allowMessageWrite = allowMessageWrite || grant.allowMessageWrite,
            allowNetwork = allowNetwork || grant.allowNetwork,
            allowedNetworkDomains = (allowedNetworkDomains + grant.allowedNetworkDomains).distinct(),
            allowVariablesWrite = allowVariablesWrite || grant.allowVariablesWrite,
            allowEventSubscribe = allowEventSubscribe || grant.allowEventSubscribe,
            allowMacroRegister = allowMacroRegister || grant.allowMacroRegister,
            allowRequestHeaders = allowRequestHeaders || grant.allowRequestHeaders,
            allowGeneration = allowGeneration || grant.allowGeneration,
            allowAssistantWrite = allowAssistantWrite || grant.allowAssistantWrite,
        )
    }

    companion object {
        /**
         * 兼容迁移用的最大权限集：保持旧版本「默认全放行」行为。
         * allowRequestHeaders / allowGeneration / allowAssistantWrite 为敏感或新能力，
         * 旧脚本不存在这些调用路径，保持关闭。
         */
        fun maximumCompatible() = TavernRuntimePermissions(
            allowScripts = true,
            allowMessageScripts = true,
            allowBrowserScripts = true,
            allowWorldWrite = true,
            allowMessageWrite = true,
            allowNetwork = true,
            allowVariablesWrite = true,
            allowEventSubscribe = true,
            allowMacroRegister = true,
            allowRequestHeaders = false,
            allowGeneration = false,
            allowAssistantWrite = false,
        )

        fun conservative() = TavernRuntimePermissions(
            allowScripts = false,
            allowWorldWrite = false,
            allowMessageWrite = false,
            allowNetwork = false,
            allowVariablesWrite = false,
            allowEventSubscribe = false,
            allowMacroRegister = false,
            allowRequestHeaders = false,
            allowGeneration = false,
            allowAssistantWrite = false,
        )
    }
}

/**
 * 脚本哈希级授权记录：用户「信任当前版本」时落库。
 * 仅当授权时的源码 SHA-256 与脚本当前源码哈希一致时才生效；
 * 源码变化后旧授权自动失效（解析层比对，见 SettingsBackedTavernScriptGrantResolver）。
 */
@Serializable
data class TavernScriptPermissionGrant(
    val sourceSha256: String,
    val permissions: TavernRuntimePermissions,
    val grantedAt: Long,
)
