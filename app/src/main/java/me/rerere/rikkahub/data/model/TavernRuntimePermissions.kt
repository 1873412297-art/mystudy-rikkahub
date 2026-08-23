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
    val allowWorldWrite: Boolean = true,
    val allowMessageWrite: Boolean = true,
    val allowNetwork: Boolean = true,
    val allowVariablesWrite: Boolean = true,
    val allowEventSubscribe: Boolean = true,
    /** 允许脚本注册宿主宏与斜杠命令（默认 true） */
    val allowMacroRegister: Boolean = true,
    /** 允许脚本读取当前模型请求头（含 API key 等敏感信息，默认 false） */
    val allowRequestHeaders: Boolean = false,
) {
    companion object {
        fun maximumCompatible() = TavernRuntimePermissions(
            allowScripts = true,
            allowWorldWrite = true,
            allowMessageWrite = true,
            allowNetwork = true,
            allowVariablesWrite = true,
            allowEventSubscribe = true,
            allowMacroRegister = true,
            allowRequestHeaders = false,
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
        )
    }
}
