package me.rerere.rikkahub.service.group

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import kotlin.uuid.Uuid

internal data class GroupContextPipelineResult(
    val visibleMessages: List<UIMessage>,
    val dynamicResult: DynamicGroupContextResult? = null,
)

internal fun resolveGroupContextMessages(
    groupAssistant: Assistant,
    messages: List<UIMessage>,
    effectiveMemberId: Uuid?,
    runtimeState: GroupRuntimeState,
): GroupContextPipelineResult {
    if (groupAssistant.assistantType != AssistantType.GROUP || effectiveMemberId == null) {
        return GroupContextPipelineResult(visibleMessages = messages)
    }
    if (!groupAssistant.groupContextOptions.enableLayeredContext) {
        return GroupContextPipelineResult(
            visibleMessages = messages.applyGroupContextFilter(groupAssistant, effectiveMemberId),
        )
    }
    val dynamicResult = DynamicGroupContextResolver().resolve(
        groupAssistant = groupAssistant,
        messages = messages,
        effectiveMemberId = effectiveMemberId,
        runtimeState = runtimeState,
    )
    return GroupContextPipelineResult(
        visibleMessages = dynamicResult.visibleMessages,
        dynamicResult = dynamicResult,
    )
}
