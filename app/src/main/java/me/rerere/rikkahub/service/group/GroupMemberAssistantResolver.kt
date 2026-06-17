package me.rerere.rikkahub.service.group

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.GroupMember
import kotlin.uuid.Uuid

internal fun resolveEffectiveGroupMemberAssistant(
    groupAssistant: Assistant,
    sourceAssistant: Assistant,
    member: GroupMember,
    resolvedModelId: Uuid,
): Assistant {
    val memberName = member.displayName.ifBlank { sourceAssistant.name }
    return sourceAssistant.copy(
        name = memberName,
        systemPrompt = member.systemPromptOverride
            ?.takeIf { it.isNotBlank() }
            ?: sourceAssistant.systemPrompt.takeIf { it.isNotBlank() }
            ?: groupAssistant.systemPrompt,
        chatModelId = resolvedModelId,
        regexes = (groupAssistant.regexes + sourceAssistant.regexes).distinct(),
    )
}
