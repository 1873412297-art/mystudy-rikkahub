package me.rerere.rikkahub.ui.pages.tavern.console

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.ai.trace.PromptTraceCopyFormatter
import me.rerere.rikkahub.data.ai.trace.PromptTraceReadResult
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.PromptTraceRepository
import kotlin.uuid.Uuid

class TavernPromptConsoleVM internal constructor(
    conversationId: String,
    observeTraces: (Uuid) -> Flow<List<PromptTraceReadResult>>,
    private val loadConversation: suspend (Uuid) -> Conversation?,
    settings: StateFlow<Settings>,
    private val clearTraces: suspend (Uuid) -> Unit,
    stateScope: CoroutineScope?,
    sharingStarted: SharingStarted,
) : ViewModel() {
    constructor(
        conversationId: String,
        promptTraceRepository: PromptTraceRepository,
        conversationRepository: ConversationRepository,
        settingsStore: SettingsStore,
    ) : this(
        conversationId = conversationId,
        observeTraces = promptTraceRepository::observeConversation,
        loadConversation = conversationRepository::getConversationById,
        settings = settingsStore.settingsFlow,
        clearTraces = promptTraceRepository::clearConversation,
        stateScope = null,
        sharingStarted = SharingStarted.WhileSubscribed(5_000),
    )

    private val conversationId = Uuid.parse(conversationId)
    private val scope = stateScope ?: viewModelScope
    private val conversation = MutableStateFlow<Conversation?>(null)
    private val explicitSelection = MutableStateFlow<Uuid?>(null)
    private val selectedTab = MutableStateFlow(TavernPromptConsoleTab.OVERVIEW)
    private val traces = observeTraces(this.conversationId)

    val uiState: StateFlow<TavernPromptConsoleUiState> = combine(
        conversation,
        traces,
        explicitSelection,
        selectedTab,
        settings,
    ) { currentConversation, traceItems, requestedTraceId, tab, currentSettings ->
        if (currentConversation == null) return@combine TavernPromptConsoleUiState()
        val selectedReplyId = currentConversation.currentMessages
            .lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.id
        val selectedId = requestedTraceId
            ?.takeIf { id -> traceItems.any { it.traceId == id } }
            ?: selectDefaultTraceId(traceItems, selectedReplyId)
        val assistant = currentSettings.assistants.find { it.id == currentConversation.assistantId }
        TavernPromptConsoleUiState(
            loading = false,
            conversationTitle = currentConversation.title,
            assistantName = assistant?.name.orEmpty(),
            traces = traceItems,
            selectedTraceId = selectedId,
            selectedTrace = traceItems.find { it.traceId == selectedId },
            selectedTab = tab,
            selectedBranchHasTrace = selectedReplyId != null &&
                traceItems.any { it.responseMessageId == selectedReplyId },
        )
    }.stateIn(
        scope = scope,
        started = sharingStarted,
        initialValue = TavernPromptConsoleUiState(),
    )

    init {
        scope.launch {
            conversation.value = loadConversation(this@TavernPromptConsoleVM.conversationId)
        }
    }

    fun selectTrace(traceId: Uuid) {
        explicitSelection.value = traceId
    }

    fun selectTab(tab: TavernPromptConsoleTab) {
        selectedTab.value = tab
    }

    fun clearConversationTraces() {
        scope.launch {
            clearTraces(conversationId)
            explicitSelection.value = null
        }
    }

    fun copySelectedTrace(): String? {
        val record = (uiState.value.selectedTrace as? PromptTraceReadResult.Available)?.record ?: return null
        return PromptTraceCopyFormatter.format(record)
    }

    fun copyMessage(index: Int): String? {
        val record = (uiState.value.selectedTrace as? PromptTraceReadResult.Available)?.record ?: return null
        return record.payload.finalMessages.getOrNull(index)?.let(PromptTraceCopyFormatter::formatMessage)
    }
}
