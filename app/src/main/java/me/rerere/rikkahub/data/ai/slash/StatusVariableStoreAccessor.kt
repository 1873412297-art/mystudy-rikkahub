package me.rerere.rikkahub.data.ai.slash

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.ai.status.StatusVariableStore
import kotlin.uuid.Uuid

/**
 * 以酒馆 chat 作用域变量（StatusVariableStore）为后端的变量访问器。
 * 单键操作 = getValue 后 copy JsonObject 再 set（StatusVariableStore 无单键 API）。
 */
class StatusVariableStoreAccessor(
    private val conversationId: Uuid?,
    private val store: StatusVariableStore,
) : ScriptVariableAccessor {

    override fun get(key: String): String? {
        val conversationId = conversationId ?: return null
        return store.getValue(conversationId)[key]?.let { element ->
            when (element) {
                is JsonNull -> null
                is JsonPrimitive -> element.content
                else -> element.toString()
            }
        }
    }

    override fun set(key: String, value: String) {
        val conversationId = conversationId ?: return
        val current = store.getValue(conversationId).toMutableMap()
        current[key] = JsonPrimitive(value)
        store.set(conversationId, JsonObject(current))
    }

    override fun delete(key: String) {
        val conversationId = conversationId ?: return
        val current = store.getValue(conversationId).toMutableMap()
        current.remove(key)
        store.set(conversationId, JsonObject(current))
    }

    override fun all(): Map<String, String> {
        val conversationId = conversationId ?: return emptyMap()
        return store.getValue(conversationId).entries.mapNotNull { (key, element) ->
            val value = when (element) {
                is JsonNull -> null
                is JsonPrimitive -> element.content
                else -> element.toString()
            }
            value?.let { key to it }
        }.toMap()
    }
}
