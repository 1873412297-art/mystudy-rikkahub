package me.rerere.rikkahub.service

import android.util.Log
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.trace.PromptTraceCleanup
import me.rerere.rikkahub.data.ai.trace.removedMessageIdsAfter
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.PromptTraceRepository
import me.rerere.rikkahub.web.NotFoundException
import kotlin.uuid.Uuid

/**
 * Production seam shared by ChatService message-removal saves and instrumentation tests.
 * Conversation persistence happens before best-effort trace cleanup, matching chat save semantics.
 */
internal suspend fun persistConversationAndCleanupPromptTraces(
    conversationId: Uuid,
    conversation: Conversation,
    promptTraceCleanup: PromptTraceCleanup,
    promptTraceRepository: PromptTraceRepository,
    persistConversation: suspend (Conversation) -> Unit,
) {
    val removedIds = promptTraceCleanup.removedMessageIdsAfter(conversation)
    persistConversation(conversation)
    if (removedIds.isNotEmpty()) {
        runCatching {
            promptTraceRepository.deleteForRemovedMessages(conversationId, removedIds)
        }.onFailure { error ->
            Log.w("PromptTracePersistence", "Prompt trace cleanup failed", error)
        }
    }
}

/** Publishes the live conversation only after the repository transaction has completed successfully. */
internal suspend fun persistConversationThenPublishLive(
    conversation: Conversation,
    persist: suspend (Conversation) -> Unit,
    publishLive: (Conversation) -> Unit,
) {
    persist(conversation)
    publishLive(conversation)
}

internal fun buildConversationAfterUserRegeneration(
    conversation: Conversation,
    messageId: Uuid,
): Conversation {
    val nodeIndex = conversation.messageNodes.indexOfFirst { node ->
        node.messages.any { it.id == messageId }
    }
    if (nodeIndex == -1) throw NotFoundException("Message not found")
    return conversation.copy(messageNodes = conversation.messageNodes.subList(0, nodeIndex + 1))
}

internal fun buildForkConversationAtMessage(
    currentConversation: Conversation,
    messageId: Uuid,
    copyPart: (UIMessagePart) -> UIMessagePart,
): Conversation {
    val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
        node.messages.any { it.id == messageId }
    }
    if (targetNodeIndex == -1) throw NotFoundException("Message not found")

    val copiedNodes = currentConversation.messageNodes
        .subList(0, targetNodeIndex + 1)
        .map { node ->
            node.copy(
                id = Uuid.random(),
                messages = node.messages.map { message ->
                    message.copy(parts = message.parts.map(copyPart))
                },
            )
        }

    return Conversation(
        id = Uuid.random(),
        assistantId = currentConversation.assistantId,
        messageNodes = copiedNodes,
        customSystemPrompt = currentConversation.customSystemPrompt,
        modeInjectionIds = currentConversation.modeInjectionIds,
        lorebookIds = currentConversation.lorebookIds,
    )
}
