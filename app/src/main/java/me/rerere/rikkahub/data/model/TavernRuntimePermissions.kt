package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

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
)
