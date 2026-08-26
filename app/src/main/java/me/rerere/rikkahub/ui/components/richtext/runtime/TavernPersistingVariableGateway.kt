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
    override fun get(conversationId: Uuid?, scope: String, key: String, ownerId: String?): JsonElement? =
        delegate.get(targetConversationId, scope, key, ownerId)

    override fun list(conversationId: Uuid?, scope: String, ownerId: String?): JsonObject =
        delegate.list(targetConversationId, scope, ownerId)

    override fun set(conversationId: Uuid?, scope: String, key: String, value: JsonElement, ownerId: String?) {
        validateTarget(targetConversationId)
        delegate.set(targetConversationId, scope, key, value, ownerId)
        if (scope == TAVERN_VARIABLE_SCOPE_CHAT) publishChatSnapshot()
    }

    override fun delete(conversationId: Uuid?, scope: String, key: String, ownerId: String?): Boolean {
        validateTarget(targetConversationId)
        val deleted = delegate.delete(targetConversationId, scope, key, ownerId)
        if (deleted && scope == TAVERN_VARIABLE_SCOPE_CHAT) publishChatSnapshot()
        return deleted
    }

    override fun replace(conversationId: Uuid?, scope: String, variables: JsonObject, ownerId: String?) {
        validateTarget(targetConversationId)
        delegate.replace(targetConversationId, scope, variables, ownerId)
        if (scope == TAVERN_VARIABLE_SCOPE_CHAT) publishChatSnapshot()
    }

    private fun publishChatSnapshot() {
        persistChatVariables(
            targetConversationId,
            delegate.list(targetConversationId, TAVERN_VARIABLE_SCOPE_CHAT),
        )
    }
}
