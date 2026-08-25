package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.service.TavernRuntimeMessageService
import kotlin.uuid.Uuid

/** Message operations exposed to Tavern browser scripts for one conversation. */
internal interface TavernRuntimeMessageGateway {
    fun isReady(conversationId: Uuid): Boolean = true
    fun readSnapshot(conversationId: Uuid): List<TavernRuntimeMessage>? =
        list(conversationId).takeIf { isReady(conversationId) }

    fun list(conversationId: Uuid): List<TavernRuntimeMessage>

    fun get(conversationId: Uuid, messageId: String): TavernRuntimeMessage?

    fun create(conversationId: Uuid, role: MessageRole, text: String): TavernRuntimeMessage

    fun update(conversationId: Uuid, messageId: String, text: String): TavernRuntimeMessage?

    fun updateLatest(conversationId: Uuid, text: String): TavernRuntimeMessage? = null

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

    override fun list(conversationId: Uuid): List<TavernRuntimeMessage> {
        val messages = messagesByConversation[conversationId].orEmpty()
        return messages.mapIndexed { index, message ->
            message.copy(isCurrent = index == messages.lastIndex)
        }
    }

    override fun readSnapshot(conversationId: Uuid): List<TavernRuntimeMessage> = list(conversationId)

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

    override fun updateLatest(conversationId: Uuid, text: String): TavernRuntimeMessage? {
        val latest = messagesByConversation[conversationId]?.lastOrNull() ?: return null
        return update(conversationId, latest.messageId, text)
    }

    override fun delete(conversationId: Uuid, messageId: String): Boolean =
        messagesByConversation[conversationId]?.removeAll { it.messageId == messageId } == true
}

/** Production gateway: the existing ChatService remains the only live and persisted conversation source. */
internal class ChatServiceTavernRuntimeMessageGateway(
    private val chatService: TavernRuntimeMessageService,
) : TavernRuntimeMessageGateway {
    override fun isReady(conversationId: Uuid): Boolean = readSnapshot(conversationId) != null

    override fun readSnapshot(conversationId: Uuid): List<TavernRuntimeMessage>? = runBlocking {
        chatService.readTavernRuntimeMessageSnapshot(conversationId)
    }?.toRuntimeMessages()

    override fun list(conversationId: Uuid): List<TavernRuntimeMessage> {
        return readSnapshot(conversationId).orEmpty()
    }

    override fun get(conversationId: Uuid, messageId: String): TavernRuntimeMessage? =
        readSnapshot(conversationId)?.firstOrNull { it.messageId == messageId }

    override fun create(conversationId: Uuid, role: MessageRole, text: String): TavernRuntimeMessage = runBlocking {
        val created = chatService.createTavernRuntimeMessage(conversationId, role, text)
        val selected = chatService.readTavernRuntimeMessageSnapshot(conversationId).orEmpty()
        toRuntimeMessage(created, selected.lastOrNull()?.id == created.id)
    }

    override fun update(conversationId: Uuid, messageId: String, text: String): TavernRuntimeMessage? {
        val id = runCatching { Uuid.parse(messageId) }.getOrNull() ?: return null
        return runBlocking { chatService.updateTavernRuntimeMessageText(conversationId, id, text) }
            ?.let { message ->
                val selected = runBlocking { chatService.readTavernRuntimeMessageSnapshot(conversationId) }.orEmpty()
                toRuntimeMessage(message, selected.lastOrNull()?.id == message.id)
            }
    }

    override fun updateLatest(conversationId: Uuid, text: String): TavernRuntimeMessage? = runBlocking {
        chatService.updateLatestTavernRuntimeMessage(conversationId, text)
    }?.let { message ->
        val selected = runBlocking { chatService.readTavernRuntimeMessageSnapshot(conversationId) }.orEmpty()
        toRuntimeMessage(message, selected.lastOrNull()?.id == message.id)
    }

    override fun delete(conversationId: Uuid, messageId: String): Boolean {
        val id = runCatching { Uuid.parse(messageId) }.getOrNull() ?: return false
        return runBlocking { chatService.deleteTavernRuntimeMessage(conversationId, id) }
    }

    private fun toRuntimeMessage(message: UIMessage, isCurrent: Boolean): TavernRuntimeMessage = TavernRuntimeMessage(
        messageId = message.id.toString(),
        messageRole = message.role,
        text = message.toText(),
        isCurrent = isCurrent,
    )

    private fun List<UIMessage>.toRuntimeMessages(): List<TavernRuntimeMessage> =
        mapIndexed { index, message -> toRuntimeMessage(message, index == lastIndex) }
}
