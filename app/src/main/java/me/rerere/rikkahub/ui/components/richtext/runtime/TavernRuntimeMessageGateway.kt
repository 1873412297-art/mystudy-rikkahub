package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

/** Message operations exposed to Tavern browser scripts for one conversation. */
internal interface TavernRuntimeMessageGateway {
    fun list(conversationId: Uuid): List<TavernRuntimeMessage>

    fun get(conversationId: Uuid, messageId: String): TavernRuntimeMessage?

    fun create(conversationId: Uuid, role: MessageRole, text: String): TavernRuntimeMessage

    fun update(conversationId: Uuid, messageId: String, text: String): TavernRuntimeMessage?

    fun delete(conversationId: Uuid, messageId: String): Boolean
}

internal data class TavernRuntimeMessage(
    val messageId: String,
    val messageRole: MessageRole,
    val text: String,
    val isCurrent: Boolean,
) {
    val role: String get() = messageRole.name.lowercase()
}

/** In-memory gateway for previews and isolated controller tests. */
internal class InMemoryTavernRuntimeMessageGateway(
    initialMessages: Map<Uuid, List<TavernRuntimeMessage>> = emptyMap(),
) : TavernRuntimeMessageGateway {
    private val messagesByConversation = initialMessages.mapValuesTo(linkedMapOf()) { (_, messages) ->
        messages.toMutableList()
    }

    override fun list(conversationId: Uuid): List<TavernRuntimeMessage> =
        messagesByConversation[conversationId].orEmpty().toList()

    override fun get(conversationId: Uuid, messageId: String): TavernRuntimeMessage? =
        list(conversationId).firstOrNull { it.messageId == messageId }

    override fun create(conversationId: Uuid, role: MessageRole, text: String): TavernRuntimeMessage {
        val message = TavernRuntimeMessage(Uuid.random().toString(), role, text, true)
        messagesByConversation.getOrPut(conversationId) { mutableListOf() }.add(message)
        return message
    }

    override fun update(conversationId: Uuid, messageId: String, text: String): TavernRuntimeMessage? {
        val messages = messagesByConversation[conversationId] ?: return null
        val index = messages.indexOfFirst { it.messageId == messageId }
        if (index == -1) return null
        return messages[index].copy(text = text).also { messages[index] = it }
    }

    override fun delete(conversationId: Uuid, messageId: String): Boolean =
        messagesByConversation[conversationId]?.removeAll { it.messageId == messageId } == true
}

/** Production gateway: the existing ChatService remains the only live and persisted conversation source. */
internal class ChatServiceTavernRuntimeMessageGateway(
    private val chatService: ChatService,
) : TavernRuntimeMessageGateway {
    override fun list(conversationId: Uuid): List<TavernRuntimeMessage> =
        chatService.getTavernRuntimeMessages(conversationId).map(::toRuntimeMessage)

    override fun get(conversationId: Uuid, messageId: String): TavernRuntimeMessage? =
        list(conversationId).firstOrNull { it.messageId == messageId }

    override fun create(conversationId: Uuid, role: MessageRole, text: String): TavernRuntimeMessage = runBlocking {
        toRuntimeMessage(chatService.createTavernRuntimeMessage(conversationId, role, text))
    }

    override fun update(conversationId: Uuid, messageId: String, text: String): TavernRuntimeMessage? {
        val id = runCatching { Uuid.parse(messageId) }.getOrNull() ?: return null
        return runBlocking { chatService.updateTavernRuntimeMessageText(conversationId, id, text) }
            ?.let(::toRuntimeMessage)
    }

    override fun delete(conversationId: Uuid, messageId: String): Boolean {
        val id = runCatching { Uuid.parse(messageId) }.getOrNull() ?: return false
        return runBlocking { chatService.deleteTavernRuntimeMessage(conversationId, id) }
    }

    private fun toRuntimeMessage(message: UIMessage): TavernRuntimeMessage = TavernRuntimeMessage(
        messageId = message.id.toString(),
        messageRole = message.role,
        text = message.toText(),
        isCurrent = true,
    )
}
