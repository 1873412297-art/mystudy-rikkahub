package me.rerere.rikkahub.ui.pages.chat.tavern

import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.Conversation

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
): TavernPresentationDecision {
    if (assistant == null) {
        return TavernPresentationDecision(TavernPresentationMode.COMPOSE, "No active assistant")
    }
    if (assistant.assistantType != AssistantType.SOLO) {
        return TavernPresentationDecision(TavernPresentationMode.COMPOSE, "Group assistants use Compose")
    }
    if (assistant.tavernCardJson.isNullOrBlank()) {
        return TavernPresentationDecision(TavernPresentationMode.COMPOSE, "No Tavern character card")
    }
    val unsupportedPart = conversation.currentMessages
        .asSequence()
        .flatMap { it.parts.asSequence() }
        .firstOrNull { it !is UIMessagePart.Text }
    if (unsupportedPart != null) {
        return TavernPresentationDecision(
            TavernPresentationMode.COMPOSE,
            "Unsupported message part: ${unsupportedPart::class.simpleName}",
        )
    }
    return TavernPresentationDecision(TavernPresentationMode.ST_WEB)
}
