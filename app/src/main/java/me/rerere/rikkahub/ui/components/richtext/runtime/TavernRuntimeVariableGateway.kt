package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.ai.status.StatusVariableStore
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import kotlin.uuid.Uuid

internal const val TAVERN_VARIABLE_SCOPE_CHAT = "chat"
internal const val TAVERN_VARIABLE_SCOPE_GLOBAL = "global"
internal const val TAVERN_VARIABLE_SCOPE_CHARACTER = "character"
internal const val TAVERN_VARIABLE_SCOPE_PRESET = "preset"
internal const val TAVERN_VARIABLE_SCOPE_MESSAGE = "message"
internal const val TAVERN_VARIABLE_SCOPE_SCRIPT = "script"

internal val TAVERN_VARIABLE_SCOPES = setOf(
    TAVERN_VARIABLE_SCOPE_CHAT,
    TAVERN_VARIABLE_SCOPE_GLOBAL,
    TAVERN_VARIABLE_SCOPE_CHARACTER,
    TAVERN_VARIABLE_SCOPE_PRESET,
    TAVERN_VARIABLE_SCOPE_MESSAGE,
    TAVERN_VARIABLE_SCOPE_SCRIPT,
)

/**
 * 酒馆脚本变量读写网关：把运行时的 variables.* 调用按 scope 路由到真实存储。
 *
 * ownerId 仅对 message / script 作用域有意义（分别为 messageId / scriptId），其余作用域忽略。
 *
 * 存储映射：
 * - chat → StatusVariableStore（复用既有 Conversation.statusVariables 持久化链路）
 * - global → Settings.tavernGlobalVariables（Settings DataStore）
 * - character / preset → 当前 Assistant.tavernVariables 的同名命名空间（Settings DataStore；
 *   RikkaHub 无独立预设实体，preset 锚定当前助手，与 character 变量分命名空间隔离）
 * - script → Settings.tavernScriptVariables[scriptId]（Settings DataStore）
 * - message → 网关内会话级内存存储（按 conversationId+messageId 隔离；
 *   消息级持久化通道尚不存在，进程重建后丢失，属已记录偏差）
 */
internal interface TavernRuntimeVariableGateway {
    fun get(conversationId: Uuid?, scope: String, key: String, ownerId: String? = null): JsonElement?

    fun list(conversationId: Uuid?, scope: String, ownerId: String? = null): JsonObject

    fun set(conversationId: Uuid?, scope: String, key: String, value: JsonElement, ownerId: String? = null)

    fun delete(conversationId: Uuid?, scope: String, key: String, ownerId: String? = null): Boolean

    /** 整体替换某作用域（及 owner）的变量表 */
    fun replace(conversationId: Uuid?, scope: String, variables: JsonObject, ownerId: String? = null)
}

/**
 * 纯内存实现：controller 的缺省网关，用于无存储注入的场景（如预览与单元测试）。
 */
internal class InMemoryTavernRuntimeVariableGateway : TavernRuntimeVariableGateway {
    private data class StoreKey(val conversationId: Uuid?, val scope: String, val ownerId: String?)

    private val stores = linkedMapOf<StoreKey, MutableMap<String, JsonElement>>()

    private fun storeFor(conversationId: Uuid?, scope: String, ownerId: String?): MutableMap<String, JsonElement> {
        val effectiveOwner = ownerId.takeIf {
            scope == TAVERN_VARIABLE_SCOPE_MESSAGE || scope == TAVERN_VARIABLE_SCOPE_SCRIPT
        }
        return stores.getOrPut(StoreKey(conversationId, scope, effectiveOwner)) { linkedMapOf() }
    }

    override fun get(conversationId: Uuid?, scope: String, key: String, ownerId: String?): JsonElement? {
        return storeFor(conversationId, scope, ownerId)[key]
    }

    override fun list(conversationId: Uuid?, scope: String, ownerId: String?): JsonObject {
        return JsonObject(storeFor(conversationId, scope, ownerId).toMap())
    }

    override fun set(conversationId: Uuid?, scope: String, key: String, value: JsonElement, ownerId: String?) {
        storeFor(conversationId, scope, ownerId)[key] = value
    }

    override fun delete(conversationId: Uuid?, scope: String, key: String, ownerId: String?): Boolean {
        return storeFor(conversationId, scope, ownerId).remove(key) != null
    }

    override fun replace(conversationId: Uuid?, scope: String, variables: JsonObject, ownerId: String?) {
        val store = storeFor(conversationId, scope, ownerId)
        store.clear()
        store.putAll(variables)
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
 * 生产实现：chat → StatusVariableStore，global → Settings.tavernGlobalVariables，
 * character/preset → 当前 Assistant.tavernVariables 命名空间，script → Settings.tavernScriptVariables，
 * message → 会话级内存存储。
 * conversationId 为空（预览等无会话场景）时 chat 作用域退化为网关内临时内存存储。
 */
internal class StatusStoreTavernVariableGateway(
    private val statusVariableStore: StatusVariableStore,
    private val settingsGateway: TavernVariableSettingsGateway,
) : TavernRuntimeVariableGateway {
    private val ephemeralChatVariables = linkedMapOf<String, JsonElement>()

    /** message 作用域：会话级内存存储（conversationId to (messageId to 变量表)） */
    private val messageVariables = linkedMapOf<Uuid?, MutableMap<String, JsonObject>>()

    override fun get(conversationId: Uuid?, scope: String, key: String, ownerId: String?): JsonElement? {
        return storeOf(conversationId, scope, ownerId)[key]
    }

    override fun list(conversationId: Uuid?, scope: String, ownerId: String?): JsonObject {
        return storeOf(conversationId, scope, ownerId)
    }

    override fun set(conversationId: Uuid?, scope: String, key: String, value: JsonElement, ownerId: String?) {
        mutateStore(conversationId, scope, ownerId) { current ->
            JsonObject(current + (key to value))
        }
    }

    override fun delete(conversationId: Uuid?, scope: String, key: String, ownerId: String?): Boolean {
        val exists = storeOf(conversationId, scope, ownerId).containsKey(key)
        if (exists) {
            mutateStore(conversationId, scope, ownerId) { current ->
                JsonObject(current - key)
            }
        }
        return exists
    }

    override fun replace(conversationId: Uuid?, scope: String, variables: JsonObject, ownerId: String?) {
        mutateStore(conversationId, scope, ownerId) { variables }
    }

    // ── 读取 ──

    private fun storeOf(conversationId: Uuid?, scope: String, ownerId: String?): JsonObject {
        return when (scope) {
            TAVERN_VARIABLE_SCOPE_GLOBAL -> settingsGateway.currentSettings().tavernGlobalVariables
            TAVERN_VARIABLE_SCOPE_CHARACTER, TAVERN_VARIABLE_SCOPE_PRESET ->
                assistantScopeStore(scope)
            TAVERN_VARIABLE_SCOPE_SCRIPT -> scriptStore(ownerId)
            TAVERN_VARIABLE_SCOPE_MESSAGE -> messageStore(conversationId, ownerId)
            else -> chatStore(conversationId)
        }
    }

    // ── 写入（整体替换语义，由各操作组合） ──

    private fun mutateStore(
        conversationId: Uuid?,
        scope: String,
        ownerId: String?,
        transform: (JsonObject) -> JsonObject,
    ) {
        when (scope) {
            TAVERN_VARIABLE_SCOPE_GLOBAL -> settingsGateway.updateSettings { settings ->
                settings.copy(tavernGlobalVariables = transform(settings.tavernGlobalVariables))
            }
            TAVERN_VARIABLE_SCOPE_CHARACTER, TAVERN_VARIABLE_SCOPE_PRESET -> {
                settingsGateway.updateSettings { settings ->
                    val current = settings.getCurrentAssistant()
                    val namespace = (current.tavernVariables[scope] as? JsonObject) ?: JsonObject(emptyMap())
                    val updatedAssistant = current.copy(
                        tavernVariables = JsonObject(current.tavernVariables + (scope to transform(namespace)))
                    )
                    settings.copy(
                        assistants = settings.assistants.map { assistant ->
                            if (assistant.id == updatedAssistant.id) updatedAssistant else assistant
                        }
                    )
                }
            }
            TAVERN_VARIABLE_SCOPE_SCRIPT -> settingsGateway.updateSettings { settings ->
                val scriptId = ownerId ?: EPHEMERAL_OWNER
                val store = (settings.tavernScriptVariables[scriptId] as? JsonObject) ?: JsonObject(emptyMap())
                settings.copy(
                    tavernScriptVariables = JsonObject(
                        settings.tavernScriptVariables + (scriptId to transform(store))
                    )
                )
            }
            TAVERN_VARIABLE_SCOPE_MESSAGE -> {
                val messageId = ownerId ?: EPHEMERAL_OWNER
                val perConversation = messageVariables.getOrPut(conversationId) { linkedMapOf() }
                val store = perConversation[messageId] ?: JsonObject(emptyMap())
                perConversation[messageId] = transform(store)
            }
            else -> {
                if (conversationId != null) {
                    statusVariableStore.set(
                        conversationId,
                        transform(statusVariableStore.getValue(conversationId)),
                    )
                } else {
                    ephemeralChatVariables.clear()
                    ephemeralChatVariables.putAll(transform(JsonObject(ephemeralChatVariables)))
                }
            }
        }
    }

    private fun assistantScopeStore(scope: String): JsonObject {
        val assistant = settingsGateway.currentSettings().getCurrentAssistant()
        return (assistant.tavernVariables[scope] as? JsonObject) ?: JsonObject(emptyMap())
    }

    private fun scriptStore(ownerId: String?): JsonObject {
        val scriptId = ownerId ?: EPHEMERAL_OWNER
        return (settingsGateway.currentSettings().tavernScriptVariables[scriptId] as? JsonObject)
            ?: JsonObject(emptyMap())
    }

    private fun messageStore(conversationId: Uuid?, ownerId: String?): JsonObject {
        val messageId = ownerId ?: EPHEMERAL_OWNER
        return messageVariables[conversationId]?.get(messageId) ?: JsonObject(emptyMap())
    }

    private fun chatStore(conversationId: Uuid?): JsonObject {
        return conversationId?.let { statusVariableStore.getValue(it) }
            ?: JsonObject(ephemeralChatVariables)
    }

    private companion object {
        /** owner 缺失时的兜底键（如无会话预览场景） */
        const val EPHEMERAL_OWNER = "__ephemeral__"
    }
}
