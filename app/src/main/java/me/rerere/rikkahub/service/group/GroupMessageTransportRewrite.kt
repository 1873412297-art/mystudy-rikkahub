package me.rerere.rikkahub.service.group

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import kotlin.uuid.Uuid

/**
 * Rewrites stored group chat messages into a provider-safe transient sequence.
 *
 * The returned messages are only sent to the model. They must not be stored back
 * into the conversation because speaker prefixes and continuation nudges are
 * transport hints, not user-visible chat content.
 */
internal fun List<UIMessage>.applyGroupApiRewrite(
    groupAssistant: Assistant,
    effectiveMemberId: Uuid?,
): List<UIMessage> {
    if (groupAssistant.assistantType != AssistantType.GROUP || effectiveMemberId == null) return this

    val rewritten = map { message ->
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

    return if (rewritten.isNotEmpty() && rewritten.last().role == MessageRole.ASSISTANT) {
        val currentMemberName = groupAssistant.groupMembers
            .find { it.id == effectiveMemberId }
            ?.displayName
            ?.takeIf { it.isNotBlank() }
            ?: "当前成员"
        rewritten + UIMessage.user(
            "请继续以[$currentMemberName]身份回复。不要重复上文，只输出该成员的下一句回应。"
        )
    } else {
        rewritten
    }
}
