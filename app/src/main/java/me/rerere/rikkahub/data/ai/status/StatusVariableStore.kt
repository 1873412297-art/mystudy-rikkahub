package me.rerere.rikkahub.data.ai.status

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * Per-conversation mutable variable store for status/expression variables.
 * Updated by the AI model via `<UpdateVariable>` XML tags containing JSONPatch operations.
 * Uses ConcurrentHashMap matching ChatService.sessions pattern.
 */
class StatusVariableStore {

    private val stores = ConcurrentHashMap<Uuid, MutableStateFlow<JsonObject>>()

    /**
     * Get or create a reactive state flow for a conversation's variables.
     */
    fun getState(conversationId: Uuid): StateFlow<JsonObject> {
        return stores.getOrPut(conversationId) {
            MutableStateFlow(JsonObject(emptyMap()))
        }
    }

    /**
     * Initialize the variable store for a conversation from persisted state.
     * Called when a conversation is first loaded.
     */
    fun init(conversationId: Uuid, initial: JsonObject) {
        stores[conversationId] = MutableStateFlow(initial)
    }

    /**
     * Get the current value for a conversation.
     */
    fun getValue(conversationId: Uuid): JsonObject {
        return stores[conversationId]?.value ?: JsonObject(emptyMap())
    }

    /**
     * Apply JSONPatch operations and emit the new state.
     * Returns the updated JsonObject.
     */
    fun applyPatch(conversationId: Uuid, ops: List<JsonPatchOp>): JsonObject {
        val flow = stores.getOrPut(conversationId) {
            MutableStateFlow(JsonObject(emptyMap()))
        }
        val current = flow.value
        val updated = current.applyPatch(ops) as JsonObject
        flow.value = updated
        return updated
    }

    /**
     * Directly set the entire variable state for a conversation.
     */
    fun set(conversationId: Uuid, value: JsonObject) {
        stores.getOrPut(conversationId) {
            MutableStateFlow(JsonObject(emptyMap()))
        }.value = value
    }

    /**
     * Remove a conversation's variable store (cleanup).
     */
    fun remove(conversationId: Uuid) {
        stores.remove(conversationId)
    }

    /**
     * Convert the variable store for a conversation to a plain Map for JS interop.
     */
    fun toJsObject(conversationId: Uuid): Map<String, Any?> {
        val value = stores[conversationId]?.value ?: JsonObject(emptyMap())
        @Suppress("UNCHECKED_CAST")
        return value.toPlainValue() as? Map<String, Any?> ?: emptyMap()
    }
}
