package me.rerere.rikkahub.ui.pages.chat.tavern

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

enum class TavernPresentationMode {
    ST_WEB,
    COMPOSE,
}

data class TavernPresentationDecision(
    val mode: TavernPresentationMode,
    val fallbackReason: String? = null,
)

fun resolveTavernPresentation(
    assistant: Assistant?,
    conversation: Conversation,
    assistantsById: Map<Uuid, Assistant> = emptyMap(),
): TavernPresentationDecision {
    if (assistant == null) {
        return TavernPresentationDecision(TavernPresentationMode.COMPOSE, "No active assistant")
    }
    val hasTavernCard = when (assistant.assistantType) {
        AssistantType.SOLO -> !assistant.tavernCardJson.isNullOrBlank()
        AssistantType.GROUP -> !assistant.tavernCardJson.isNullOrBlank() || assistant.groupMembers.any { member ->
            member.enabled && !assistantsById[member.assistantId]?.tavernCardJson.isNullOrBlank()
        }
    }
    if (!hasTavernCard) {
        return TavernPresentationDecision(TavernPresentationMode.COMPOSE, "No Tavern character card")
    }
    return TavernPresentationDecision(TavernPresentationMode.ST_WEB)
}
