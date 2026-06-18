package me.rerere.rikkahub.service.group

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.ContextScope
import kotlin.uuid.Uuid

internal fun List<UIMessage>.applyGroupContextFilter(
    groupAssistant: Assistant,
    effectiveMemberId: Uuid?,
): List<UIMessage> {
    if (groupAssistant.assistantType != AssistantType.GROUP) return this
    if (effectiveMemberId == null) return this
    val member = groupAssistant.groupMembers.find { it.id == effectiveMemberId } ?: return this
    val filter = member.contextFilter
    if (filter.scope == ContextScope.ALL &&
        filter.excludedMemberIds.isEmpty() &&
        !filter.mentionEnabled &&
        filter.maxMessages <= 0
    ) {
        return this
    }

    var result: List<UIMessage> = this
    result = when (filter.scope) {
        ContextScope.ALL -> result
        ContextScope.SELF -> result.filter { it.role == MessageRole.USER || it.memberId == effectiveMemberId }
        ContextScope.MEMBER_LIST -> result.filter { it.role == MessageRole.USER || it.memberId in filter.visibleMemberIds }
        ContextScope.DIRECTED -> result.filter { it.memberId == effectiveMemberId }
    }
    if (filter.excludedMemberIds.isNotEmpty()) {
        result = result.filter { it.memberId !in filter.excludedMemberIds }
    }
    if (filter.mentionEnabled && filter.mentionKeywords.isNotEmpty()) {
        result = result.filter { message ->
            message.role == MessageRole.USER || filter.mentionKeywords.any { keyword ->
                message.toText().contains(keyword, ignoreCase = true)
            }
        }
    }
    if (filter.maxMessages > 0 && result.size > filter.maxMessages) {
        val users = result.filter { it.role == MessageRole.USER }
        val others = result.filter { it.role != MessageRole.USER }
        val keep = (filter.maxMessages - users.size).coerceAtLeast(0)
        result = others.takeLast(keep) + users
    }
    return result
}
