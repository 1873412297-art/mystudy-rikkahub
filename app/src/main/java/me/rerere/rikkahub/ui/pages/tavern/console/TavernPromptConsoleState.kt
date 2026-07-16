package me.rerere.rikkahub.ui.pages.tavern.console

import me.rerere.rikkahub.data.ai.trace.PromptTraceReadResult
import kotlin.uuid.Uuid

enum class TavernPromptConsoleTab { OVERVIEW, HITS, SENT_MESSAGES, PREVIEW }

data class TavernPromptConsoleUiState(
    val loading: Boolean = true,
    val conversationTitle: String = "",
    val assistantName: String = "",
    val traces: List<PromptTraceReadResult> = emptyList(),
    val selectedTraceId: Uuid? = null,
    val selectedTrace: PromptTraceReadResult? = null,
    val selectedTab: TavernPromptConsoleTab = TavernPromptConsoleTab.OVERVIEW,
    val selectedBranchHasTrace: Boolean = false,
)

fun selectDefaultTraceId(
    traces: List<PromptTraceReadResult>,
    selectedResponseMessageId: Uuid?,
): Uuid? {
    return selectedResponseMessageId
        ?.let { responseId -> traces.firstOrNull { it.responseMessageId == responseId } }
        ?.traceId
        ?: traces.firstOrNull()?.traceId
}
