package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.ai.status.StatusVariableStore
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import kotlin.uuid.Uuid

internal const val TAVERN_VARIABLE_SCOPE_CHAT = "chat"
internal const val TAVERN_VARIABLE_SCOPE_GLOBAL = "global"

/**
 * 酒馆脚本变量读写网关：把运行时的 variables.* 调用按 scope 路由到真实存储。
 *
 * - chat 作用域 → StatusVariableStore（复用既有 Conversation.statusVariables 持久化链路，
 *   不写 Room；会话下次保存时由 ChatService 统一落盘）
 * - global 作用域 → Settings.tavernGlobalVariables（Settings DataStore）
 */
internal interface TavernRuntimeVariableGateway {
    fun get(conversationId: Uuid?, scope: String, key: String): JsonElement?

    fun list(conversationId: Uuid?, scope: String): JsonObject

    fun set(conversationId: Uuid?, scope: String, key: String, value: JsonElement)

    fun delete(conversationId: Uuid?, scope: String, key: String): Boolean
}

/**
 * 纯内存实现：controller 的缺省网关，用于无存储注入的场景（如预览与单元测试）。
 */
internal class InMemoryTavernRuntimeVariableGateway : TavernRuntimeVariableGateway {
    private val chatVariables = linkedMapOf<String, JsonElement>()
    private val globalVariables = linkedMapOf<String, JsonElement>()

    private fun storeFor(scope: String): MutableMap<String, JsonElement> {
        return if (scope == TAVERN_VARIABLE_SCOPE_GLOBAL) globalVariables else chatVariables
    }

    override fun get(conversationId: Uuid?, scope: String, key: String): JsonElement? {
        return storeFor(scope)[key]
    }

    override fun list(conversationId: Uuid?, scope: String): JsonObject {
        return JsonObject(storeFor(scope).toMap())
    }

    override fun set(conversationId: Uuid?, scope: String, key: String, value: JsonElement) {
        storeFor(scope)[key] = value
    }

    override fun delete(conversationId: Uuid?, scope: String, key: String): Boolean {
        return storeFor(scope).remove(key) != null
    }
}

/**
 * 设置读写抽象（仿 TavernWorldSettingsGateway），便于 JVM 单元测试注入假实现。
 */
internal interface TavernVariableSettingsGateway {
    fun currentSettings(): Settings

    fun updateSettings(transform: (Settings) -> Settings)
}

internal class SettingsStoreTavernVariableGateway(
    private val settingsStore: SettingsStore,
) : TavernVariableSettingsGateway {
    override fun currentSettings(): Settings = settingsStore.settingsFlow.value

    override fun updateSettings(transform: (Settings) -> Settings) {
        // @JavascriptInterface 在 WebView 的 JavaBridge 后台线程上执行，可安全阻塞
        runBlocking {
            settingsStore.update(transform)
        }
    }
}

/**
 * 生产实现：chat → StatusVariableStore，global → Settings.tavernGlobalVariables。
 * conversationId 为空（预览等无会话场景）时 chat 作用域退化为网关内临时内存存储。
 */
internal class StatusStoreTavernVariableGateway(
    private val statusVariableStore: StatusVariableStore,
    private val settingsGateway: TavernVariableSettingsGateway,
) : TavernRuntimeVariableGateway {
    private val ephemeralChatVariables = linkedMapOf<String, JsonElement>()

    override fun get(conversationId: Uuid?, scope: String, key: String): JsonElement? {
        return if (scope == TAVERN_VARIABLE_SCOPE_GLOBAL) {
            settingsGateway.currentSettings().tavernGlobalVariables[key]
        } else {
            chatStore(conversationId)[key]
        }
    }

    override fun list(conversationId: Uuid?, scope: String): JsonObject {
        return if (scope == TAVERN_VARIABLE_SCOPE_GLOBAL) {
            settingsGateway.currentSettings().tavernGlobalVariables
        } else {
            chatStore(conversationId)
        }
    }

    override fun set(conversationId: Uuid?, scope: String, key: String, value: JsonElement) {
        if (scope == TAVERN_VARIABLE_SCOPE_GLOBAL) {
            settingsGateway.updateSettings { settings ->
                settings.copy(
                    tavernGlobalVariables = JsonObject(settings.tavernGlobalVariables + (key to value))
                )
            }
        } else if (conversationId != null) {
            statusVariableStore.set(
                conversationId,
                JsonObject(statusVariableStore.getValue(conversationId) + (key to value)),
            )
        } else {
            ephemeralChatVariables[key] = value
        }
    }

    override fun delete(conversationId: Uuid?, scope: String, key: String): Boolean {
        return if (scope == TAVERN_VARIABLE_SCOPE_GLOBAL) {
            // SettingsStore.update 非原子读改写，先读当前值判断是否存在，语义与原 transform 内捕获一致
            val deleted = settingsGateway.currentSettings().tavernGlobalVariables.containsKey(key)
            settingsGateway.updateSettings { settings ->
                settings.copy(
                    tavernGlobalVariables = JsonObject(settings.tavernGlobalVariables - key)
                )
            }
            deleted
        } else if (conversationId != null) {
            val current = statusVariableStore.getValue(conversationId)
            if (!current.containsKey(key)) {
                false
            } else {
                statusVariableStore.set(conversationId, JsonObject(current - key))
                true
            }
        } else {
            ephemeralChatVariables.remove(key) != null
        }
    }

    private fun chatStore(conversationId: Uuid?): JsonObject {
        return conversationId?.let { statusVariableStore.getValue(it) }
            ?: JsonObject(ephemeralChatVariables)
    }
}
