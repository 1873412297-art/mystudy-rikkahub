package me.rerere.rikkahub.service.group

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import kotlin.uuid.Uuid

/**
 * Rewrites stored group chat messages into a provider-safe transient sequence.
 *
 * The returned messages are only sent to the model. They must not be stored back
 * into the conversation because speaker prefixes are transport hints, not
 * user-visible chat content.
 */
internal fun List<UIMessage>.applyGroupApiRewrite(
    groupAssistant: Assistant,
    effectiveMemberId: Uuid?,
): List<UIMessage> {
    if (groupAssistant.assistantType != AssistantType.GROUP || effectiveMemberId == null) return this

    return map { message ->
        when {
            message.role == MessageRole.ASSISTANT &&
                message.memberId != null &&
                message.memberId != effectiveMemberId -> {
                val member = groupAssistant.groupMembers.find { it.id == message.memberId }
                val prefix = member?.displayName?.takeIf { it.isNotBlank() }?.let { "[$it] " } ?: ""
                message.copy(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text(prefix + message.toText())),
                    name = null,
                )
            }

            message.memberId != null -> {
                val member = groupAssistant.groupMembers.find { it.id == message.memberId }
                val memberName = member?.displayName?.takeIf { it.isNotBlank() }
                if (memberName != null && message.name != memberName) message.copy(name = memberName) else message
            }

            message.role == MessageRole.USER && message.memberId == null -> {
                message.copy(parts = listOf(UIMessagePart.Text("[User] " + message.toText())))
            }

            else -> message
        }
    }
}

internal fun List<UIMessage>.toStorableGroupGeneratedMessages(
    originalMessageIds: Set<Uuid>,
    effectiveMemberId: Uuid,
    memberName: String?,
): List<UIMessage> {
    return filter { message ->
        message.id !in originalMessageIds &&
            message.role == MessageRole.ASSISTANT &&
            !message.parts.isEmptyInputMessage() &&
            !message.toText().isGroupContinuationNudge()
    }.map { message ->
        when {
            message.memberId != null -> message
            memberName != null -> message.copy(memberId = effectiveMemberId, name = memberName)
            else -> message.copy(memberId = effectiveMemberId)
        }
    }
}

internal fun String.isGroupContinuationNudge(): Boolean {
    val text = trim()
    return text.startsWith("请继续以[") &&
        text.contains("身份回复") &&
        text.contains("不要重复上文")
}
