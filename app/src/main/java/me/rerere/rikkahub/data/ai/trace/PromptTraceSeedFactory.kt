package me.rerere.rikkahub.data.ai.trace

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

fun buildPromptTraceSeed(
    conversationId: Uuid,
    conversationAssistant: Assistant,
    generatingAssistant: Assistant,
    model: Model,
    visibleMessages: List<UIMessage>,
    allAssistants: List<Assistant>,
    speakerMemberId: Uuid? = null,
    speakerName: String? = null,
    sourceHints: List<PromptTraceSourceHint> = emptyList(),
): PromptTraceSeed? {
    if (!conversationAssistant.isTavernPromptTraceEligible(allAssistants)) return null

    val anchor = visibleMessages.lastOrNull { message ->
        message.role == MessageRole.USER && !message.parts.isEmptyInputMessage()
    }
    return PromptTraceSeed(
        conversationId = conversationId,
        requestAnchorMessageId = anchor?.id,
        assistantId = generatingAssistant.id,
        modelId = model.id,
        isGroup = conversationAssistant.assistantType == AssistantType.GROUP,
        speakerMemberId = speakerMemberId,
        speakerName = speakerName,
        sourceHints = sourceHints,
    )
}

fun removedMessageIds(before: Conversation, after: Conversation): Set<Uuid> {
    val beforeIds = before.messageNodes
        .flatMap { node -> node.messages }
        .mapTo(mutableSetOf()) { message -> message.id }
    val afterIds = after.messageNodes
        .flatMap { node -> node.messages }
        .mapTo(mutableSetOf()) { message -> message.id }
    return beforeIds - afterIds
}
