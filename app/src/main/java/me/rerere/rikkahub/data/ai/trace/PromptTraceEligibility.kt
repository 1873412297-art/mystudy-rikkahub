package me.rerere.rikkahub.data.ai.trace

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType

fun Assistant.isTavernPromptTraceEligible(allAssistants: List<Assistant>): Boolean {
    return when (assistantType) {
        AssistantType.SOLO -> tavernCardJson != null
        AssistantType.GROUP -> groupMembers
            .asSequence()
            .filter { it.enabled }
            .mapNotNull { member -> allAssistants.find { it.id == member.assistantId } }
            .any { source -> source.tavernCardJson != null }
    }
}
