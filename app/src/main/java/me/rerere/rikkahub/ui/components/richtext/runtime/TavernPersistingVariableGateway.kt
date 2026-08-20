package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/** Adds durable real-conversation chat-variable snapshots to the existing runtime gateway. */
internal class PersistingTavernRuntimeVariableGateway(
    private val delegate: TavernRuntimeVariableGateway,
    private val targetConversationId: Uuid,
    private val validateTarget: (Uuid) -> Unit = {},
    private val persistChatVariables: (Uuid, JsonObject) -> Unit,
) : TavernRuntimeVariableGateway {
    override fun get(conversationId: Uuid?, scope: String, key: String): JsonElement? =
        delegate.get(targetConversationId, scope, key)

    override fun list(conversationId: Uuid?, scope: String): JsonObject =
        delegate.list(targetConversationId, scope)

    override fun set(conversationId: Uuid?, scope: String, key: String, value: JsonElement) {
        validateTarget(targetConversationId)
        delegate.set(targetConversationId, scope, key, value)
        if (scope == TAVERN_VARIABLE_SCOPE_CHAT) publishChatSnapshot()
    }

    override fun delete(conversationId: Uuid?, scope: String, key: String): Boolean {
        validateTarget(targetConversationId)
        val deleted = delegate.delete(targetConversationId, scope, key)
        if (deleted && scope == TAVERN_VARIABLE_SCOPE_CHAT) publishChatSnapshot()
        return deleted
    }

    private fun publishChatSnapshot() {
        persistChatVariables(
            targetConversationId,
            delegate.list(targetConversationId, TAVERN_VARIABLE_SCOPE_CHAT),
        )
    }
}
