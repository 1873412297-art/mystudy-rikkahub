package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.status.TavernHostEventType
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/** ChatService-owned readiness state; clearing it prevents a recreated session from mutating stale live state. */
internal class TavernRuntimeConversationReadiness {
    private val readyConversationIds = ConcurrentHashMap.newKeySet<Uuid>()

    fun isReady(conversationId: Uuid): Boolean = conversationId in readyConversationIds

    fun markReady(conversationId: Uuid) {
        readyConversationIds += conversationId
    }

    fun clear(conversationId: Uuid) {
        readyConversationIds.remove(conversationId)
    }

    fun clearAll() {
        readyConversationIds.clear()
    }
}

/** The real ChatService surface consumed by the browser-runtime gateway. */
internal interface TavernRuntimeMessageService {
    fun isTavernRuntimeConversationReady(conversationId: Uuid): Boolean

    fun getTavernRuntimeMessages(conversationId: Uuid): List<UIMessage>

    /** Returns null when the bound live session was evicted or is not initialized. */
    suspend fun readTavernRuntimeMessageSnapshot(conversationId: Uuid): List<UIMessage>? =
        getTavernRuntimeMessages(conversationId).takeIf { isTavernRuntimeConversationReady(conversationId) }

    suspend fun createTavernRuntimeMessage(conversationId: Uuid, role: MessageRole, text: String): UIMessage

    suspend fun updateTavernRuntimeMessageText(conversationId: Uuid, messageId: Uuid, text: String): UIMessage?

    suspend fun updateLatestTavernRuntimeMessage(conversationId: Uuid, text: String): UIMessage? = null

    suspend fun deleteTavernRuntimeMessage(conversationId: Uuid, messageId: Uuid): Boolean
}

/**
 * Persistence boundary for browser-runtime message mutations.
 *
 * Implementations own the live state and durable storage. [mutate] must keep the bound conversation alive and
 * serialize read-modify-save so the store can safely perform an atomic mutation.
 */
internal interface TavernRuntimeMessagePersistenceAdapter {
    fun isReady(conversationId: Uuid): Boolean

    suspend fun <T> mutate(conversationId: Uuid, block: suspend () -> T): T

    fun currentConversation(conversationId: Uuid): Conversation

    suspend fun persist(conversationId: Uuid, conversation: Conversation)

    suspend fun persistAfterMessageRemoval(
        conversationId: Uuid,
        before: Conversation,
        after: Conversation,
    )

    fun emit(event: TavernRuntimeMessageMutationEvent)
}

internal data class TavernRuntimeMessageMutationEvent(
    val type: TavernHostEventType,
    val conversationId: Uuid,
    val message: UIMessage,
)

/**
 * Production mutation path shared by the Tavern gateway and [ChatService].
 *
 * Event emission deliberately happens only after [TavernRuntimeMessagePersistenceAdapter.persist] succeeds.
 */
internal class TavernRuntimeMessageMutationStore(
    private val persistence: TavernRuntimeMessagePersistenceAdapter,
) {
    suspend fun create(conversationId: Uuid, role: MessageRole, text: String): UIMessage =
        persistence.mutate(conversationId) {
            check(persistence.isReady(conversationId)) { "CONVERSATION_NOT_READY" }
            require(role == MessageRole.USER || role == MessageRole.ASSISTANT || role == MessageRole.SYSTEM)
            val message = UIMessage(role = role, parts = listOf(UIMessagePart.Text(text)))
            val conversation = persistence.currentConversation(conversationId)
            persistence.persist(
                conversationId,
                conversation.copy(messageNodes = conversation.messageNodes + message.toMessageNode()),
            )
            persistence.emit(
                TavernRuntimeMessageMutationEvent(
                    type = if (role == MessageRole.USER) {
                        TavernHostEventType.MESSAGE_SENT
                    } else {
                        TavernHostEventType.MESSAGE_RECEIVED
                    },
                    conversationId = conversationId,
                    message = message,
                ),
            )
            message
        }

    suspend fun update(conversationId: Uuid, messageId: Uuid, text: String): UIMessage? =
        persistence.mutate(conversationId) {
            check(persistence.isReady(conversationId)) { "CONVERSATION_NOT_READY" }
            val conversation = persistence.currentConversation(conversationId)
            var updated: UIMessage? = null
            val nodes = conversation.messageNodes.map { node ->
                if (node.currentMessage.id != messageId) {
                    node
                } else {
                    node.copy(messages = node.messages.map { message ->
                        if (message.id == messageId) {
                            message.replaceRuntimeMessageText(text).also { updated = it }
                        } else {
                            message
                        }
                    })
                }
            }
            val result = updated ?: return@mutate null
            persistence.persist(conversationId, conversation.copy(messageNodes = nodes))
            persistence.emit(
                TavernRuntimeMessageMutationEvent(TavernHostEventType.MESSAGE_EDITED, conversationId, result),
            )
            result
        }

    suspend fun updateLatest(conversationId: Uuid, text: String): UIMessage? = persistence.mutate(conversationId) {
        check(persistence.isReady(conversationId)) { "CONVERSATION_NOT_READY" }
        val conversation = persistence.currentConversation(conversationId)
        val latest = conversation.currentMessages.lastOrNull() ?: return@mutate null
        var updated: UIMessage? = null
        val nodes = conversation.messageNodes.map { node ->
            if (node.currentMessage.id != latest.id) {
                node
            } else {
                node.copy(messages = node.messages.map { message ->
                    if (message.id == latest.id) {
                        message.replaceRuntimeMessageText(text).also { updated = it }
                    } else {
                        message
                    }
                })
            }
        }
        val result = updated ?: return@mutate null
        persistence.persist(conversationId, conversation.copy(messageNodes = nodes))
        persistence.emit(
            TavernRuntimeMessageMutationEvent(TavernHostEventType.MESSAGE_EDITED, conversationId, result),
        )
        result
    }

    suspend fun delete(conversationId: Uuid, messageId: Uuid): Boolean = persistence.mutate(conversationId) {
        check(persistence.isReady(conversationId)) { "CONVERSATION_NOT_READY" }
        val conversation = persistence.currentConversation(conversationId)
        val nodeIndex = conversation.messageNodes.indexOfFirst { it.currentMessage.id == messageId }
        if (nodeIndex == -1) return@mutate false
        val target = conversation.messageNodes[nodeIndex]
        val retained = target.messages.filterNot { it.id == messageId }
        val nodes = conversation.messageNodes.toMutableList().apply {
            if (retained.isEmpty()) {
                removeAt(nodeIndex)
            } else {
                this[nodeIndex] = target.copy(
                    messages = retained,
                    selectIndex = target.selectIndex.coerceAtMost(retained.lastIndex),
                )
            }
        }
        persistence.persistAfterMessageRemoval(
            conversationId = conversationId,
            before = conversation,
            after = conversation.copy(messageNodes = nodes),
        )
        persistence.emit(
            TavernRuntimeMessageMutationEvent(
                TavernHostEventType.MESSAGE_DELETED,
                conversationId,
                target.currentMessage,
            ),
        )
        true
    }
}

/** Replaces text while retaining the same message ID, role, attachments, annotations, and metadata. */
internal fun UIMessage.replaceRuntimeMessageText(text: String): UIMessage {
    var replaced = false
    val updatedParts = parts.map { part ->
        if (!replaced && part is UIMessagePart.Text) {
            replaced = true
            part.copy(text = text)
        } else {
            part
        }
    }
    return copy(parts = if (replaced) updatedParts else listOf(UIMessagePart.Text(text)) + updatedParts)
}
