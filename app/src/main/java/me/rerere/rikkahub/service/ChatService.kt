package me.rerere.rikkahub.service

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.trace.PromptTraceCleanup
import me.rerere.rikkahub.data.ai.trace.PromptTraceSectionKind
import me.rerere.rikkahub.data.ai.trace.PromptTraceSourceHint
import me.rerere.rikkahub.data.ai.trace.buildPromptTraceSeed
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.createConversationTools
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.ai.transformers.findBareJsonPatch
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.ai.slash.MacroExpandContext
import me.rerere.rikkahub.data.ai.slash.ScriptManager
import me.rerere.rikkahub.data.ai.slash.SlashCommandInterceptor
import me.rerere.rikkahub.data.ai.slash.TavernScriptRegistry
import me.rerere.rikkahub.data.ai.slash.expandMacrosIfAllowed
import me.rerere.rikkahub.data.ai.status.StatusVariableStore
import me.rerere.rikkahub.data.ai.status.TavernHostEventBus
import me.rerere.rikkahub.data.ai.status.TavernHostEventType
import me.rerere.rikkahub.data.ai.transformers.StatusPlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.StatusTrailingBlockTransformer
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.TurnTakingStrategy
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.PromptTraceRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.group.GroupContextBuildInput
import me.rerere.rikkahub.service.group.GroupContextBuilder
import me.rerere.rikkahub.service.group.GroupDirectorCommand
import me.rerere.rikkahub.service.group.GroupDirectorCommandContext
import me.rerere.rikkahub.service.group.GroupDirectorCommandResult
import me.rerere.rikkahub.service.group.GroupDirectorCommandStatus
import me.rerere.rikkahub.service.group.GroupDirectorEngine
import me.rerere.rikkahub.service.group.GroupDirectorState
import me.rerere.rikkahub.service.group.GroupPlaybackState
import me.rerere.rikkahub.service.group.GroupRuntimeStateUpdater
import me.rerere.rikkahub.service.group.GroupSpeakerScorer
import me.rerere.rikkahub.service.group.GroupSpeakingIntent
import me.rerere.rikkahub.service.group.GroupTurnSelection
import me.rerere.rikkahub.service.group.DynamicGroupContextResult
import me.rerere.rikkahub.service.group.applyGroupApiRewrite
import me.rerere.rikkahub.service.group.resolveSelectedGroupContextMessages
import me.rerere.rikkahub.service.group.resolveAddressedMember
import me.rerere.rikkahub.service.group.nextRoundRobinSelection
import me.rerere.rikkahub.service.group.normalizeGroupMemberQueue
import me.rerere.rikkahub.service.group.parseGroupModeratorDecision
import me.rerere.rikkahub.service.group.resolveEffectiveGroupMemberAssistant
import me.rerere.rikkahub.service.group.resolveManualReplyMemberIds
import me.rerere.rikkahub.service.group.selectModeratorTurn
import me.rerere.rikkahub.service.group.toStorableGroupGeneratedMessages
import me.rerere.rikkahub.ui.components.richtext.runtime.TavernSendHookStore
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.workspace.WorkspaceShellStatus
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

internal fun backgroundTextGenerationParams(
    model: Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    maxTokens: Int? = null,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    maxTokens = maxTokens?.takeIf { it > 0 },
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckTitleModelSettings,
}

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
        StatusPlaceholderTransformer,
        StatusTrailingBlockTransformer,
    )
}

internal fun renderPresetMessageMacros(
    messages: List<UIMessage>,
    settings: Settings,
    assistant: Assistant,
    model: Model,
): List<UIMessage> {
    val userName = settings.displaySetting.userNickname.ifBlank { "user" }
    val charName = assistant.name.ifBlank { "assistant" }
    return messages.map { message ->
        message.copy(
            parts = message.parts.map { part ->
                if (part is UIMessagePart.Text) {
                    part.copy(
                        text = PlaceholderTransformer.expandVisualMacros(
                            text = part.text,
                            userName = userName,
                            charName = charName,
                            modelName = model.displayName,
                            modelId = model.modelId,
                        )
                    )
                } else {
                    part
                }
            }
        )
    }
}

internal fun conversationAtGenerationStart(
    initialConversation: Conversation,
    resolvedConversation: Conversation,
): Conversation {
    require(initialConversation.id == resolvedConversation.id)
    return resolvedConversation.copy(chatSuggestions = emptyList())
}

internal suspend fun normalizeCancelledGroupGeneration(
    session: ConversationSession,
    generationJob: Job?,
    engine: GroupDirectorEngine,
    persist: suspend (Conversation) -> Unit,
): Conversation = withContext(NonCancellable) {
    session.completeOwnedGroupCancellation(
        job = generationJob,
        staleValue = { session.state.value },
    ) {
        mutateConversation(session) { current ->
            val normalizedDirector = engine.afterCancellation(current.groupRuntimeState.director)
            val updated = current.copy(
                groupRuntimeState = current.groupRuntimeState.copy(director = normalizedDirector)
            )
            persist(updated)
            updated
        }
    }
}

/** Applies a final conversation mutation while holding the shared session lock. */
internal suspend fun <T> mutateConversation(
    session: ConversationSession,
    block: suspend (Conversation) -> T,
): T = session.withConversationMutationLock { block(session.state.value) }

/** Applies UI-editable metadata without replacing live message nodes owned by generation and runtime mutations. */
internal fun mergeConversationUiFields(current: Conversation, requested: Conversation): Conversation = current.copy(
    customSystemPrompt = requested.customSystemPrompt,
    authorNote = requested.authorNote,
    workspaceCwd = requested.workspaceCwd,
    modeInjectionIds = requested.modeInjectionIds,
    lorebookIds = requested.lorebookIds,
)

/** Replaces only the validated compression baseline and keeps nodes appended while the provider was running. */
internal fun applyCompressedConversation(
    baseline: Conversation,
    latest: Conversation,
    compressedSummaries: List<String>,
    keepRecentMessages: Int,
): Conversation {
    val baselineIds = baseline.messageNodes.map { it.currentMessage.id }
    val latestPrefixIds = latest.messageNodes.take(baselineIds.size).map { it.currentMessage.id }
    check(latestPrefixIds == baselineIds) { "Conversation changed during compression" }
    val replacementNodes = buildList {
        compressedSummaries.forEach { summary -> add(UIMessage.user(summary).toMessageNode()) }
        addAll(baseline.messageNodes.takeLast(keepRecentMessages))
        addAll(latest.messageNodes.drop(baseline.messageNodes.size))
    }
    return latest.copy(messageNodes = replacementNodes, chatSuggestions = emptyList())
}

private data class ModeratorDecisionSnapshot(
    val conversation: Conversation,
    val director: GroupDirectorState,
    val orderedEligibleMemberIds: List<Uuid>,
    val allowStop: Boolean,
)

internal fun orderedModeratorEligibleMemberIds(
    persistedQueue: List<Uuid>,
    eligibleMemberIds: List<Uuid>,
): List<Uuid> = normalizeGroupMemberQueue(persistedQueue, eligibleMemberIds)

internal enum class InitializationInstallAction {
    INSTALL,
    MARK_READY,
    SKIP,
}

internal fun initializationInstallAction(
    session: ConversationSession,
    token: ConversationInitializationToken,
    sessionIsCurrent: Boolean,
    isReady: Boolean,
): InitializationInstallAction = when {
    !sessionIsCurrent || isReady -> InitializationInstallAction.SKIP
    session.canInstallInitialization(token) -> InitializationInstallAction.INSTALL
    session.isLatestInitialization(token) -> InitializationInstallAction.MARK_READY
    else -> InitializationInstallAction.SKIP
}

/** Applies persisted status variables only when this loader won the session installation race. */
internal fun applyInitializedStatusVariables(
    action: InitializationInstallAction,
    store: StatusVariableStore,
    conversationId: Uuid,
    variables: JsonObject,
) {
    if (action == InitializationInstallAction.INSTALL) {
        store.init(conversationId, variables)
    }
}

/** Stable order prevents opposing assistant deletions from taking session locks in different orders. */
internal fun orderedAssistantConversationDeletionIds(conversationIds: Collection<Uuid>): List<Uuid> =
    conversationIds.sortedBy(Uuid::toString)

internal data class AssistantConversationDeletionResult(
    val failedConversationIds: Set<Uuid>,
    val errors: Map<Uuid, Throwable>,
) {
    val succeeded: Boolean get() = failedConversationIds.isEmpty()
}

data class AssistantDeletionResult(
    val succeeded: Boolean,
    val finalizeError: Exception? = null,
)

/** Holds the assistant gate until both its conversations and its owning settings have been finalized. */
internal suspend fun runAssistantDeletionGate(
    assistantId: Uuid,
    deletingAssistantIds: MutableSet<Uuid>,
    gateMutex: Mutex = Mutex(),
    deleteConversations: suspend () -> Boolean,
    finalizeAssistantDeletion: suspend () -> Unit,
): AssistantDeletionResult {
    val acquired = gateMutex.withLock { deletingAssistantIds.add(assistantId) }
    if (!acquired) return AssistantDeletionResult(succeeded = false)
    return try {
        if (!deleteConversations()) return AssistantDeletionResult(succeeded = false)
        try {
            finalizeAssistantDeletion()
            AssistantDeletionResult(succeeded = true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AssistantDeletionResult(succeeded = false, finalizeError = error)
        }
    } finally {
        withContext(NonCancellable) {
            gateMutex.withLock { deletingAssistantIds.remove(assistantId) }
        }
    }
}

enum class ConversationDeleteResult {
    DELETED,
    NOT_FOUND,
    MOVED,
}

/** A moved conversation is intentionally left to its new assistant during a batch deletion. */
internal fun isAssistantBatchDeleteSuccess(result: ConversationDeleteResult): Boolean =
    result != ConversationDeleteResult.NOT_FOUND

/** An assistant being deleted cannot receive new conversations, while moving out remains valid. */
internal fun canMoveConversationToAssistant(assistantId: Uuid, deletingAssistantIds: Set<Uuid>): Boolean =
    assistantId !in deletingAssistantIds

internal fun canRestoreConversation(assistantExists: Boolean, assistantIsDeleting: Boolean): Boolean =
    assistantExists && !assistantIsDeleting

/**
 * Resolves ownership immediately before the conditional delete. A batch may have collected the id while it belonged
 * to one assistant, then another operation may have moved it before this lock is reached.
 */
internal suspend fun deleteConversationWithExpectedAssistant(
    expectedAssistantId: Uuid,
    load: suspend () -> Conversation?,
    delete: suspend (Conversation) -> Boolean,
): ConversationDeleteResult {
    val conversation = load() ?: return ConversationDeleteResult.NOT_FOUND
    if (conversation.assistantId != expectedAssistantId) return ConversationDeleteResult.MOVED
    return if (delete(conversation)) ConversationDeleteResult.DELETED else ConversationDeleteResult.MOVED
}

internal suspend fun deleteAssistantConversationIds(
    conversationIds: List<Uuid>,
    delete: suspend (Uuid) -> Boolean,
): AssistantConversationDeletionResult {
    val failedConversationIds = linkedSetOf<Uuid>()
    val errors = linkedMapOf<Uuid, Throwable>()
    conversationIds.forEach { conversationId ->
        currentCoroutineContext().ensureActive()
        try {
            if (!delete(conversationId)) failedConversationIds += conversationId
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failedConversationIds += conversationId
            errors[conversationId] = error
        }
    }
    return AssistantConversationDeletionResult(failedConversationIds, errors)
}

/** A missing row may only be inserted by an initialized new conversation or an explicit creator. */
internal fun canPersistConversation(exists: Boolean, isReady: Boolean, allowCreate: Boolean): Boolean =
    exists || isReady || allowCreate

internal data class InitializationStatusCandidate<T>(
    val value: T,
    val statusVariables: JsonObject,
)

internal suspend fun <T> renderInitializationStatusCandidate(
    conversationId: Uuid,
    initialStatusVariables: JsonObject,
    render: suspend (StatusVariableStore) -> T,
): InitializationStatusCandidate<T> {
    val temporaryStore = StatusVariableStore()
    temporaryStore.init(conversationId, initialStatusVariables)
    return InitializationStatusCandidate(
        value = render(temporaryStore),
        statusVariables = temporaryStore.getValue(conversationId),
    )
}

/** Prevents a failed repository write from publishing candidate variables to the live store. */
internal suspend fun persistInitializationThenPublishStatusVariables(
    persistAndPublish: suspend () -> Boolean,
    publishStatusVariables: () -> Unit,
): Boolean {
    if (!persistAndPublish()) return false
    publishStatusVariables()
    return true
}

internal fun resolveLocalGroupTurnSelection(
    director: GroupDirectorState,
    effectiveStrategy: TurnTakingStrategy,
    persistedQueue: List<Uuid>,
    persistedIndex: Int,
    activeMemberId: Uuid?,
    orderedEligibleMemberIds: List<Uuid>,
): GroupTurnSelection? {
    val mayAutoSelect = effectiveStrategy == TurnTakingStrategy.AUTO_ROUND_ROBIN ||
        (
            effectiveStrategy == TurnTakingStrategy.MANUAL &&
                director.oneRoundActive &&
                director.playbackState == GroupPlaybackState.RUNNING
            )
    if (!mayAutoSelect) return null
    return nextRoundRobinSelection(
        persistedQueue = persistedQueue,
        persistedIndex = persistedIndex,
        activeMemberId = activeMemberId,
        enabledMemberIds = orderedEligibleMemberIds,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val promptTraceRepository: PromptTraceRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val folderRepository: FolderRepository,
    private val statusVariableStore: StatusVariableStore,
    private val tavernHostEventBus: TavernHostEventBus,
    private val tavernScriptRegistry: TavernScriptRegistry,
    private val tavernSendHookStore: TavernSendHookStore,
) : TavernRuntimeMessageService {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    // Slash 命令脚本引擎（懒加载——仅当用户首次发斜杠命令时才扫描磁盘脚本）
    private val scriptManager by lazy { ScriptManager(context, settingsStore) }
    private val slashInterceptor by lazy {
        SlashCommandInterceptor(scriptManager, statusVariableStore, tavernScriptRegistry)
    }
    private val groupDirectorEngine = GroupDirectorEngine()

    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val deletingAssistantIds = ConcurrentHashMap.newKeySet<Uuid>()
    private val assistantDeletionGateMutex = Mutex()
    private val tavernRuntimeReadiness = TavernRuntimeConversationReadiness()
    private val tavernRuntimeMutationLifecycle = TavernRuntimeMutationLifecycle()
    private val tavernRuntimeMessageStore by lazy {
        TavernRuntimeMessageMutationStore(object : TavernRuntimeMessagePersistenceAdapter {
            override fun isReady(conversationId: Uuid): Boolean =
                tavernRuntimeReadiness.isReady(conversationId)

            override suspend fun <T> mutate(conversationId: Uuid, block: suspend () -> T): T {
                val session = sessions[conversationId] ?: error("CONVERSATION_NOT_READY")
                return tavernRuntimeMutationLifecycle.mutate(
                    canMutate = {
                        sessions[conversationId] === session &&
                            isReady(conversationId)
                    },
                    acquireSession = session::acquire,
                    releaseSession = session::release,
                    withSessionMutationLock = session::withRuntimeMessageLock,
                    block = {
                        check(conversationRepo.existsConversationById(conversationId)) {
                            "CONVERSATION_NOT_READY"
                        }
                        block()
                    },
                )
            }

            override fun currentConversation(conversationId: Uuid): Conversation =
                getConversationFlow(conversationId).value

            override suspend fun persist(conversationId: Uuid, conversation: Conversation): Boolean {
                return saveConversationUnlocked(conversationId, conversation, PromptTraceCleanup.None)
            }

            override suspend fun persistAfterMessageRemoval(
                conversationId: Uuid,
                before: Conversation,
                after: Conversation,
            ): Boolean {
                return saveConversationUnlocked(
                    conversationId,
                    after,
                    PromptTraceCleanup.RemovedMessages(before),
                )
            }

            override fun emit(event: TavernRuntimeMessageMutationEvent) {
                tavernHostEventBus.emit(
                    type = event.type,
                    conversationId = event.conversationId,
                    payload = buildJsonObject {
                        put("messageId", event.message.id.toString())
                        put("role", event.message.role.name.lowercase())
                        put("preview", event.message.toText().take(500))
                    },
                )
            }
        })
    }
    private val _sessionsVersion = MutableStateFlow(0L)

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    /** 供 web 层订阅每会话状态变量变化（status_variables SSE 事件）。 */
    fun getStatusVariablesFlow(conversationId: Uuid): StateFlow<JsonObject> =
        statusVariableStore.getState(conversationId)

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    suspend fun cleanup() {
        tavernRuntimeMutationLifecycle.closeAdmissionsAndAwait()
        val sessionsToClose = sessions.entries.toList()
        sessionsToClose.forEach { (conversationId, session) ->
            tavernRuntimeReadiness.clear(conversationId)
            session.closeForCleanup()
            if (sessions.remove(conversationId, session)) {
                _sessionsVersion.value++
            }
        }
    }

    // ---- Session 管理 ----

    private suspend fun <T> withConversationMutation(
        conversationId: Uuid,
        block: suspend (ConversationSession) -> T,
    ): T {
        repeat(2) {
            val session = getOrCreateSession(conversationId)
            if (session.state.value.assistantId in deletingAssistantIds) {
                throw IllegalStateException("ASSISTANT_DELETION_IN_PROGRESS")
            }
            try {
                return session.withRefSuspend {
                    session.withConversationMutationLock {
                        if (sessions[conversationId] !== session) {
                            throw IllegalStateException("CONVERSATION_SESSION_REPLACED")
                        }
                        block(session)
                    }
                }
            } catch (error: IllegalStateException) {
                if (error.message !in setOf("CONVERSATION_SESSION_CLOSED", "CONVERSATION_SESSION_REPLACED")) {
                    throw error
                }
            }
        }
        throw IllegalStateException("CONVERSATION_SESSION_REPLACED")
    }

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = settings.getCurrentAssistant().id
                ),
                scope = appScope,
                onIdle = { removeSession(it) }
            ).also {
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            tavernRuntimeReadiness.clear(conversationId)
            _sessionsVersion.value++
            appScope.launch { session.closeForCleanup() }
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    override fun isTavernRuntimeConversationReady(conversationId: Uuid): Boolean =
        tavernRuntimeReadiness.isReady(conversationId)

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        val session = sessions[conversationId] ?: return MutableStateFlow(null)
        return session.processingStatus
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        if (tavernRuntimeReadiness.isReady(conversationId)) return
        val initializationToken = session.beginInitialization()
        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            if (conversation.assistantId in deletingAssistantIds) return
            settingsStore.updateAssistant(conversation.assistantId)
            val settings = settingsStore.settingsFlowRaw.first()
            val assistant = settings.getAssistantById(conversation.assistantId)
                ?: settings.getCurrentAssistant()
            val renderedConversation = renderStoredStatusInstructions(
                conversationId = conversationId,
                conversation = conversation,
                settings = settings,
                assistant = assistant,
            )
            val withoutNudges = if (assistant.assistantType == AssistantType.GROUP) {
                renderedConversation.removeGroupContinuationNudgeNodes()
            } else {
                renderedConversation
            }
            val cleanedConversation = if (assistant.assistantType == AssistantType.GROUP) {
                val enabledIds = assistant.groupMembers.filter { it.enabled }.map { it.id }
                val restoredDirector = groupDirectorEngine.sanitize(
                    state = withoutNudges.groupRuntimeState.director,
                    enabledMemberIds = enabledIds,
                    generationActive = false,
                )
                withoutNudges.copy(
                    groupRuntimeState = withoutNudges.groupRuntimeState.copy(director = restoredDirector)
                )
            } else {
                renderedConversation
            }
            installInitializedConversation(
                conversationId = conversationId,
                session = session,
                token = initializationToken,
                conversation = cleanedConversation,
                promptTraceCleanup = when {
                    withoutNudges != renderedConversation -> PromptTraceCleanup.RemovedMessages(renderedConversation)
                    else -> PromptTraceCleanup.None
                },
                shouldPersist = cleanedConversation != renderedConversation,
            )
        } else {
            // 新建对话, 并添加预设消息
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            if (assistant.id in deletingAssistantIds) return
            val presetCandidate = renderInitializationStatusCandidate(
                conversationId = conversationId,
                initialStatusVariables = JsonObject(emptyMap()),
            ) { temporaryStore ->
                renderPresetMessages(
                    conversationId = conversationId,
                    settings = currentSettings,
                    assistant = assistant,
                    statusVariableStore = temporaryStore,
                )
            }
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true
            ).updateCurrentMessages(presetCandidate.value)
                .copy(statusVariables = presetCandidate.statusVariables)
            installInitializedConversation(
                conversationId = conversationId,
                session = session,
                token = initializationToken,
                conversation = newConversation,
                promptTraceCleanup = PromptTraceCleanup.None,
                shouldPersist = false,
            )
        }
    }

    /**
     * Repository loading and rendering intentionally happen before this method. The final install verifies that no
     * newer session or mutation won the race, so an old load can never replace live runtime changes.
     */
    private suspend fun installInitializedConversation(
        conversationId: Uuid,
        session: ConversationSession,
        token: ConversationInitializationToken,
        conversation: Conversation,
        promptTraceCleanup: PromptTraceCleanup,
        shouldPersist: Boolean,
    ) {
        session.withRefSuspend {
            session.withConversationMutationLock {
                val action = initializationInstallAction(
                    session = session,
                    token = token,
                    sessionIsCurrent = sessions[conversationId] === session,
                    isReady = tavernRuntimeReadiness.isReady(conversationId),
                )
                when (action) {
                    InitializationInstallAction.INSTALL -> {
                        val installed = persistInitializationThenPublishStatusVariables(
                            persistAndPublish = {
                                if (shouldPersist) {
                                    saveConversationUnlocked(conversationId, conversation, promptTraceCleanup)
                                } else {
                                    updateConversation(conversationId, conversation)
                                    true
                                }
                            },
                            publishStatusVariables = {
                                applyInitializedStatusVariables(
                                    action = action,
                                    store = statusVariableStore,
                                    conversationId = conversationId,
                                    variables = conversation.statusVariables,
                                )
                            },
                        )
                        if (!installed) return@withConversationMutationLock
                        tavernRuntimeReadiness.markReady(conversationId)
                    }

                    InitializationInstallAction.MARK_READY -> tavernRuntimeReadiness.markReady(conversationId)
                    InitializationInstallAction.SKIP -> Unit
                }
            }
        }
    }

    // ---- 发送消息 ----

    private suspend fun renderStoredStatusInstructions(
        conversationId: Uuid,
        conversation: Conversation,
        settings: me.rerere.rikkahub.data.datastore.Settings,
        assistant: Assistant,
    ): Conversation {
        val messages = conversation.currentMessages
        if (!messages.hasUnrenderedStatusInstructions()) return conversation

        val candidate = renderInitializationStatusCandidate(
            conversationId = conversationId,
            initialStatusVariables = conversation.statusVariables,
        ) { temporaryStore ->
            renderPresetMessages(
                conversationId = conversationId,
                settings = settings,
                assistant = assistant,
                messages = messages,
                statusVariableStore = temporaryStore,
            )
        }
        return conversation.updateCurrentMessages(candidate.value)
            .copy(statusVariables = candidate.statusVariables)
    }

    private fun List<UIMessage>.hasUnrenderedStatusInstructions(): Boolean {
        return any { message ->
            message.parts.filterIsInstance<UIMessagePart.Text>().any { part ->
                part.text.contains("UpdateVariable", ignoreCase = true) ||
                    part.text.contains("StatusPlaceHolderImpl", ignoreCase = true) ||
                    findBareJsonPatch(part.text) != null
            }
        }
    }

    private suspend fun renderPresetMessages(
        conversationId: Uuid,
        settings: me.rerere.rikkahub.data.datastore.Settings,
        assistant: Assistant,
        messages: List<UIMessage> = assistant.presetMessages,
        statusVariableStore: StatusVariableStore? = null,
        allowStatusVariableMutations: Boolean = true,
    ): List<UIMessage> {
        val presetMessages = messages
        if (presetMessages.isEmpty()) return presetMessages

        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: return presetMessages

        return renderPresetMessageMacros(
            messages = presetMessages,
            settings = settings,
            assistant = assistant,
            model = model,
        ).visualTransforms(
            transformers = outputTransformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            conversationId = conversationId,
            statusVariableStore = statusVariableStore,
            allowStatusVariableMutations = allowStatusVariableMutations,
        )
    }

    suspend fun applyInitialGreeting(conversationId: Uuid, greeting: String) {
        if (greeting.isBlank()) return

        initializeConversation(conversationId)

        val settings = settingsStore.settingsFlowRaw.first()
        val conversation = getConversationFlow(conversationId).value
        val assistant = settings.getAssistantById(conversation.assistantId)
            ?: settings.getCurrentAssistant()
        val renderedGreeting = renderPresetMessages(
            conversationId = conversationId,
            settings = settings,
            assistant = assistant,
            messages = listOf(UIMessage.assistantHtml(greeting)),
        )
        if (renderedGreeting.isEmpty()) return

        withConversationMutation(conversationId) { session ->
            val latestConversation = session.state.value
            val updatedConversation = if (latestConversation.currentMessages.any { it.role == MessageRole.USER }) {
                latestConversation.copy(
                    messageNodes = latestConversation.messageNodes + renderedGreeting.map { it.toMessageNode() },
                    statusVariables = statusVariableStore.getValue(conversationId),
                )
            } else {
                latestConversation.copy(
                    messageNodes = renderedGreeting.map { it.toMessageNode() },
                    statusVariables = statusVariableStore.getValue(conversationId),
                )
            }
            saveConversationUnlocked(conversationId, updatedConversation, PromptTraceCleanup.None)
        }
    }

    private suspend fun appendUserMessage(
        conversationId: Uuid,
        session: ConversationSession,
        content: List<UIMessagePart>,
    ) {
        val settings = settingsStore.settingsFlow.first()
        val assistantId = session.state.value.assistantId
        val assistant = settings.getAssistantById(assistantId)
            ?: settings.getCurrentAssistant()
        val processedContent = preprocessUserInputParts(content, assistant, conversationId)
        val userMessage = UIMessage(
            role = MessageRole.USER,
            parts = processedContent,
        )
        withConversationMutation(conversationId) { currentSession ->
            val currentConversation = currentSession.state.value
            val addressedConversation = currentConversation.withUpdatedGroupAddressedState(assistant, userMessage)
            val newConversation = addressedConversation.copy(
                messageNodes = addressedConversation.messageNodes + userMessage.toMessageNode(),
            )
            saveConversationUnlocked(conversationId, newConversation, PromptTraceCleanup.None)
        }
    }

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return

        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        previousJob?.cancel()

        val job = appScope.launch {
            try {
                runCatching { previousJob?.join() }
                finishInterruptedPendingTools(conversationId)

                val settings = settingsStore.settingsFlow.first()
                val assistantId = session.state.value.assistantId
                val assistant = settings.getAssistantById(assistantId)
                    ?: settings.getCurrentAssistant()
                val processedContent = preprocessUserInputParts(content, assistant, conversationId)
                // 酒馆脚本 sendHook（best-effort：无活跃 WebView 时跳过，超时默认原样）
                val hookedContent = tavernSendHookStore.mutateOutgoing(processedContent, timeoutMs = 500)
                val userMessage = UIMessage(
                    role = MessageRole.USER,
                    parts = hookedContent,
                )
                withConversationMutation(conversationId) { currentSession ->
                    val currentConversation = currentSession.state.value
                    val addressedConversation = currentConversation.withUpdatedGroupAddressedState(
                        assistant = assistant,
                        userMessage = userMessage,
                    )
                    val newConversation = addressedConversation.copy(
                        messageNodes = addressedConversation.messageNodes + userMessage.toMessageNode(),
                    )
                    saveConversationUnlocked(conversationId, newConversation, PromptTraceCleanup.None)
                }

                // 酒馆脚本宿主事件：消息发送前
                tavernHostEventBus.emit(
                    type = TavernHostEventType.MESSAGE_SENDING,
                    conversationId = conversationId,
                    payload = buildJsonObject {
                        put("role", userMessage.role.name.lowercase())
                        put("preview", userMessage.toText().take(500))
                    },
                )

                // 酒馆脚本宿主事件：消息已发送（ST 命名）
                tavernHostEventBus.emit(
                    type = TavernHostEventType.MESSAGE_SENT,
                    conversationId = conversationId,
                    payload = buildJsonObject {
                        put("role", userMessage.role.name.lowercase())
                        put("preview", userMessage.toText().take(500))
                    },
                )

                // 开始补全
                if (answer) {
                    handleMessageComplete(conversationId)

                    // 酒馆脚本宿主事件：生成结束
                    tavernHostEventBus.emit(
                        type = TavernHostEventType.GENERATION_FINISHED,
                        conversationId = conversationId,
                        payload = buildJsonObject {
                            put("role", "assistant")
                        },
                    )

                    // 酒馆脚本宿主事件：assistant 消息完成（ST 命名）
                    val latestAssistantId = getConversationFlow(conversationId).value.messageNodes
                        .lastOrNull { it.role == MessageRole.ASSISTANT }
                        ?.messages?.lastOrNull()?.id?.toString()
                    tavernHostEventBus.emit(
                        type = TavernHostEventType.MESSAGE_RECEIVED,
                        conversationId = conversationId,
                        payload = buildJsonObject {
                            put("role", "assistant")
                            latestAssistantId?.let { put("messageId", it) }
                        },
                    )
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        session.setJob(job)
    }

    fun sendGroupMessage(conversationId: Uuid, content: List<UIMessagePart>, memberIds: List<Uuid>) {
        if (memberIds.isEmpty()) {
            addError(
                error = IllegalStateException("Please select at least one group member"),
                conversationId = conversationId,
                title = "No group member selected",
            )
            return
        }

        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        previousJob?.cancel()

        val job = appScope.launch {
            try {
                runCatching { previousJob?.join() }
                finishInterruptedPendingTools(conversationId)

                var replyMemberIds = memberIds.distinct()
                if (!content.isEmptyInputMessage()) {
                    appendUserMessage(conversationId, session, content)
                    val conversationAfterUserMessage = getConversationFlow(conversationId).value
                    replyMemberIds = resolveManualReplyMemberIds(
                        selectedMemberIds = replyMemberIds,
                        addressedMemberId = conversationAfterUserMessage.groupRuntimeState.activeAddressedMemberId,
                    )
                }

                replyMemberIds.forEach { memberId ->
                    handleMessageComplete(conversationId, memberId = memberId, allowAutoChain = false)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                addError(e, conversationId, title = "Group message failed")
            }
        }
        session.setJob(job)
    }

    private suspend fun preprocessUserInputParts(
        parts: List<UIMessagePart>,
        assistant: Assistant,
        conversationId: Uuid,
    ): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    val regexApplied = part.text.replaceRegexes(
                        assistant = assistant,
                        scope = AssistantAffectScope.USER,
                        visual = false
                    )
                    // 酒馆脚本注册宏：USER 正则之后同步展开（mutate 通道；失败保留原文）；
                    // 宏是脚本功能，受 allowScripts 总开关保护——关闭时跳过展开
                    val macroContext = MacroExpandContext(
                            userName = settingsStore.settingsFlow.value.displaySetting.userNickname
                                .ifBlank { "User" },
                            charName = assistant.name,
                            conversationId = conversationId.toString(),
                        )
                    val macroExpanded = if (settingsStore.settingsFlow.value.runtimePermissions.allowScripts) {
                        tavernScriptRegistry.expandMacrosAsync(regexApplied, macroContext)
                    } else regexApplied
                    part.copy(text = macroExpanded)
                }

                else -> part
            }
        }
    }

    private fun Conversation.withUpdatedGroupAddressedState(
        assistant: Assistant,
        userMessage: UIMessage,
    ): Conversation {
        if (assistant.assistantType != AssistantType.GROUP) return this
        val userText = userMessage.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
            .trim()
        val resolution = resolveAddressedMember(
            groupAssistant = assistant,
            userText = userText,
            previousAddressedMemberId = groupRuntimeState.activeAddressedMemberId,
        )
        return copy(
            groupRuntimeState = groupRuntimeState.copy(
                activeAddressedMemberId = resolution?.memberId,
                activeAddressedTurnId = resolution?.memberId?.let { userMessage.id },
            )
        )
    }

    /**
     * 触发指定群组成员回复（不发新用户消息，直接让对应 member 说话）。
     * 仅在群组助手 + 手动模式时使用。
     */
    fun triggerMemberReply(conversationId: Uuid, memberId: Uuid) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()
        val job = appScope.launch {
            try {
                finishInterruptedPendingTools(conversationId)
                handleMessageComplete(conversationId, memberId = memberId, allowAutoChain = false)
                _generationDoneFlow.emit(conversationId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                addError(e, conversationId, title = "群组成员回复失败")
            }
        }
        session.setJob(job)
    }

    suspend fun applyGroupDirectorCommand(
        conversationId: Uuid,
        command: GroupDirectorCommand,
    ): GroupDirectorCommandResult {
        val session = getOrCreateSession(conversationId)
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(session.state.value.assistantId)
            ?: settings.getCurrentAssistant()
        if (assistant.assistantType != AssistantType.GROUP) {
            return GroupDirectorCommandResult(
                state = session.state.value.groupRuntimeState.director,
                status = GroupDirectorCommandStatus.NOT_GROUP,
            )
        }
        val result = session.withGroupDirectorLock {
            mutateConversation(session) { current ->
                val enabledIds = assistant.groupMembers.filter { it.enabled }.map { it.id }
                val orderedIds = normalizeGroupMemberQueue(current.groupMemberQueue, enabledIds)
                val reduced = groupDirectorEngine.reduce(
                    state = current.groupRuntimeState.director,
                    command = command,
                    context = GroupDirectorCommandContext(
                        generationActive = session.isGroupReplyActiveLocked(),
                        orderedEnabledMemberIds = orderedIds,
                    ),
                )
                if (reduced.state != current.groupRuntimeState.director) {
                    saveConversationUnlocked(
                        conversationId,
                        current.copy(
                            groupRuntimeState = current.groupRuntimeState.copy(director = reduced.state),
                        ),
                        PromptTraceCleanup.None,
                    )
                }
                reduced
            }
        }
        if (result.status == GroupDirectorCommandStatus.APPLIED && result.shouldStartGeneration) {
            startGroupDirectorGeneration(conversationId)
        }
        return result
    }

    private suspend fun startGroupDirectorGeneration(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        session.withGroupDirectorLock {
            if (session.isGenerating) return@withGroupDirectorLock
            val director = session.state.value.groupRuntimeState.director
            if (
                director.playbackState == GroupPlaybackState.PAUSED &&
                director.oneShotNextMemberId == null
            ) {
                return@withGroupDirectorLock
            }
            val job = appScope.launch(start = CoroutineStart.LAZY) {
                try {
                    handleMessageComplete(conversationId = conversationId, allowAutoChain = true)
                    _generationDoneFlow.emit(conversationId)
                } catch (error: CancellationException) {
                    normalizeCancelledGroupGeneration(
                        session = session,
                        generationJob = coroutineContext[Job],
                        engine = groupDirectorEngine,
                    ) { updated ->
                        saveConversationUnlocked(conversationId, updated, PromptTraceCleanup.None)
                    }
                    throw error
                } catch (error: Exception) {
                    addError(error, conversationId, title = "Group director failed")
                }
            }
            session.setJob(job)
            job.start()
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true,
        memberId: Uuid? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                if (message.role == MessageRole.USER) {
                    withConversationMutation(conversationId) { currentSession ->
                        val conversation = currentSession.state.value
                        val newConversation = buildConversationAfterUserRegeneration(conversation, message.id)
                        saveConversationUnlocked(
                            conversationId,
                            newConversation,
                            PromptTraceCleanup.RemovedMessages(conversation),
                        )
                    }
                    handleMessageComplete(conversationId, memberId = memberId, allowAutoChain = false)
                } else {
                    if (regenerateAssistantMsg) {
                        val nodeIndex = withConversationMutation(conversationId) { currentSession ->
                            val conversation = currentSession.state.value
                            conversation.messageNodes.indexOf(conversation.getMessageNodeByMessage(message))
                        }
                        handleMessageComplete(
                            conversationId,
                            memberId = memberId,
                            messageRange = 0..<nodeIndex,
                            allowAutoChain = memberId == null,
                        )
                    } else {
                        withConversationMutation(conversationId) { currentSession ->
                            saveConversationUnlocked(
                                conversationId,
                                currentSession.state.value,
                                PromptTraceCleanup.None,
                            )
                        }
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        session.setJob(job)
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                val newApprovalState = when {
                    answer != null -> ToolApprovalState.Answered(answer)
                    approved -> ToolApprovalState.Approved
                    else -> ToolApprovalState.Denied(reason)
                }

                val hasPendingTools = withConversationMutation(conversationId) { currentSession ->
                    val conversation = currentSession.state.value
                    val updatedNodes = conversation.messageNodes.map { node ->
                        node.copy(
                            messages = node.messages.map { msg ->
                                msg.copy(
                                    parts = msg.parts.map { part ->
                                        when {
                                            part is UIMessagePart.Tool && part.toolCallId == toolCallId -> {
                                                part.copy(approvalState = newApprovalState)
                                            }

                                            else -> part
                                        }
                                    }
                                )
                            }
                        )
                    }
                    val updatedConversation = conversation.copy(messageNodes = updatedNodes)
                    saveConversationUnlocked(conversationId, updatedConversation, PromptTraceCleanup.None)
                    updatedNodes.any { node ->
                        node.currentMessage.parts.any { part ->
                            part is UIMessagePart.Tool && part.isPending
                        }
                    }
                }

                // Only continue generation when all pending tools are handled
                if (!hasPendingTools) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job)
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null,
        memberId: Uuid? = null,
        allowAutoChain: Boolean = true,
    ) {
        // 酒馆脚本宿主事件：生成开始（ST 命名）
        tavernHostEventBus.emit(
            type = TavernHostEventType.GENERATION_STARTED,
            conversationId = conversationId,
        )
        val generationJob = coroutineContext[Job]
        val settings = settingsStore.settingsFlow.first()
        val initialConversation = getConversationFlow(conversationId).value
        val groupAssistant = settings.getAssistantById(initialConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val groupRepliesSinceLastUser = if (groupAssistant.assistantType == AssistantType.GROUP) {
            countGroupRepliesSinceLastUserMessage(initialConversation, groupAssistant)
        } else {
            0
        }
        val isAddressedTurn = groupAssistant.assistantType == AssistantType.GROUP &&
            memberId == null &&
            groupRepliesSinceLastUser == 0 &&
            initialConversation.groupRuntimeState.activeAddressedMemberId != null

        // 群组对话：解析当前发言成员，并按成员的 systemPrompt/model 覆盖派生出 effective Assistant
        val (assistant, model, effectiveMemberId) = if (groupAssistant.assistantType == AssistantType.GROUP) {
            val resolvedMemberId = memberId
                ?: initialConversation.groupRuntimeState.activeAddressedMemberId
                    ?.takeIf { isAddressedTurn }
                ?: resolveNextSpeaker(
                    conversation = initialConversation,
                    groupAssistant = groupAssistant,
                    settings = settings,
                    allowModeratorStop = groupRepliesSinceLastUser > 0,
                    generationJob = generationJob,
                )
            if (resolvedMemberId == null) return
            val baseModel = settings.findModelById(groupAssistant.chatModelId ?: settings.chatModelId)
                ?: return
            val member = groupAssistant.groupMembers.find { it.id == resolvedMemberId }
                ?.takeIf { it.enabled }
                ?: return
            val sourceAssistant = settings.getAssistantById(member.assistantId) ?: groupAssistant
            val modelId = member.chatModelIdOverride ?: groupAssistant.chatModelId ?: settings.chatModelId
            val resolvedModel = settings.findModelById(modelId) ?: baseModel
            val merged = resolveEffectiveGroupMemberAssistant(
                groupAssistant = groupAssistant,
                sourceAssistant = sourceAssistant,
                member = member,
                resolvedModelId = resolvedModel.id,
            )
            Triple(merged, resolvedModel, resolvedMemberId)
        } else {
            val soloModel = settings.findModelById(groupAssistant.chatModelId ?: settings.chatModelId) ?: return
            Triple(groupAssistant, soloModel, null)
        }

        if (groupAssistant.assistantType == AssistantType.GROUP && effectiveMemberId != null) {
            val session = getOrCreateSession(conversationId)
            session.withGroupDirectorLock {
                session.markGroupReplyStartedLocked(generationJob)
            }
        }

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }
        val useExternalWebSearch = shouldUseExternalWebSearch(assistant, model)
        var dynamicContextResult: DynamicGroupContextResult? = null

        runCatching {

            // Reset suggestions without overwriting group speaker state persisted during resolution.
            val generationSession = getOrCreateSession(conversationId)
            generationSession.withGroupDirectorLock {
                generationSession.withConversationMutationLock {
                    val resolvedConversation = generationSession.state.value
                    updateConversation(
                        conversationId,
                        conversationAtGenerationStart(initialConversation, resolvedConversation),
                    )
                }
            }

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (useExternalWebSearch || mcpManager.getAllAvailableTools().isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            val conversation = if (groupAssistant.assistantType == AssistantType.GROUP) {
                val session = getOrCreateSession(conversationId)
                session.withGroupDirectorLock {
                    mutateConversation(session) { rawConversation ->
                        val checkedConversation = rawConversation.removeInvalidUnresolvedToolMessages()
                        val cleanedConversation = checkedConversation.removeGroupContinuationNudgeNodes()
                        if (cleanedConversation != rawConversation) {
                            saveConversationUnlocked(
                                conversationId,
                                cleanedConversation,
                                PromptTraceCleanup.RemovedMessages(rawConversation),
                            )
                        }
                        cleanedConversation
                    }
                }
            } else {
                checkInvalidMessages(conversationId)
                getConversationFlow(conversationId).value
            }
            val groupContext = resolveSelectedGroupContextMessages(
                groupAssistant = groupAssistant,
                messages = conversation.currentMessages,
                messageRange = messageRange,
                effectiveMemberId = effectiveMemberId,
                runtimeState = conversation.groupRuntimeState,
            )
            dynamicContextResult = groupContext.dynamicResult
            val visibleMessages = groupContext.visibleMessages.applyEnhancementPrompt(assistant)
            val localSpeakerScore = if (
                effectiveMemberId != null && groupAssistant.assistantType == AssistantType.GROUP
            ) {
                GroupSpeakerScorer().score(
                    groupAssistant = groupAssistant,
                    messages = conversation.currentMessages,
                    runtimeState = conversation.groupRuntimeState,
                    activeMemberId = conversation.activeGroupMemberId,
                ).firstOrNull { it.memberId == effectiveMemberId }
            } else {
                null
            }
            val speakingIntent = effectiveMemberId?.let {
                GroupSpeakingIntent(
                    speakerId = it,
                    intent = localSpeakerScore?.intent ?: "respond",
                    reason = localSpeakerScore?.reason
                        ?: "Manual or existing turn-taking selected this speaker.",
                )
            }
            val groupContextBuildResult = if (
                effectiveMemberId != null &&
                groupAssistant.assistantType == AssistantType.GROUP &&
                groupAssistant.groupContextOptions.enableLayeredContext
            ) {
                GroupContextBuilder().build(
                    GroupContextBuildInput(
                        visibleMessages = visibleMessages,
                        groupAssistant = groupAssistant,
                        effectiveMemberId = effectiveMemberId,
                        runtimeState = dynamicContextResult?.adjustedRuntimeState ?: conversation.groupRuntimeState,
                        contextOptions = groupAssistant.groupContextOptions,
                        speakingIntent = speakingIntent,
                    )
                )
            } else {
                null
            }
            val layeredMessages = groupContextBuildResult?.messages ?: visibleMessages
            val sourceHints = groupContextBuildResult?.syntheticMessageId?.let { messageId ->
                listOf(
                    PromptTraceSourceHint(
                        messageId = messageId,
                        kind = PromptTraceSectionKind.GROUP_LAYERED_CONTEXT,
                        label = "Group layered context",
                    )
                )
            }.orEmpty()
            val originalMessageIds = layeredMessages.map { it.id }.toSet()
            val messagesForGeneration = layeredMessages.applyGroupApiRewrite(groupAssistant, effectiveMemberId)
            val memberName = effectiveMemberId
                ?.let { id -> groupAssistant.groupMembers.find { it.id == id } }
                ?.displayName
                ?.takeIf { it.isNotBlank() }
            val promptTraceSeed = buildPromptTraceSeed(
                conversationId = conversationId,
                conversationAssistant = groupAssistant,
                generatingAssistant = assistant,
                model = model,
                visibleMessages = visibleMessages,
                allAssistants = settings.assistants,
                speakerMemberId = effectiveMemberId,
                speakerName = memberName,
                sourceHints = sourceHints,
            )

            // start generating
            val session = getOrCreateSession(conversationId)
            generationHandler.generateText(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                messages = messagesForGeneration,
                assistant = assistant,
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                conversationAuthorNote = conversation.authorNote,
                workspaceCwd = conversation.workspaceCwd,
                conversationId = conversationId,
                memberId = effectiveMemberId,
                promptTraceSeed = promptTraceSeed,
                memories = if (assistant.useGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                },
                inputTransformers = buildList {
                    add(slashInterceptor)
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(workspaceReminderTransformer)
                },
                outputTransformers = outputTransformers,
                tools = buildList {
                    if (useExternalWebSearch) {
                        addAll(createSearchTools(settings))
                    }
                    addAll(localTools.getTools(assistant.localTools))
                    if (assistant.enableRecentChatsReference) {
                        addAll(createConversationTools(conversationRepo, assistant.id))
                    }
                    addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), conversation.workspaceCwd))
                    if (assistant.enabledSkills.isNotEmpty()) {
                        addAll(
                            createSkillTools(
                                enabledSkills = assistant.enabledSkills,
                                allSkills = skillManager.listSkills(),
                            )
                        )
                    }
                    mcpManager.getAllAvailableTools().also { allTools ->
                        val invalidNames = allTools
                            .map { it.second }
                            .distinct()
                            .filter { name ->
                                name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
                            }
                        if (invalidNames.isNotEmpty()) {
                            addError(
                                error = IllegalStateException(
                                    context.getString(
                                        R.string.error_mcp_invalid_server_name,
                                        invalidNames.joinToString(", ")
                                    )
                                ),
                                conversationId = conversationId,
                            )
                            return
                        }
                    }.forEach { (serverId, serverName, tool) ->
                        add(
                            Tool(
                                name = "mcp__${serverName}__${tool.name}",
                                description = tool.description ?: "",
                                parameters = { tool.inputSchema },
                                needsApproval = { tool.needsApproval },
                                execute = {
                                    mcpManager.callTool(serverId, tool.name, it.jsonObject)
                                },
                            )
                        )
                    }
                },
            ).onCompletion {
                // 可能被取消了，或者意外结束，兜底更新
                val session = getOrCreateSession(conversationId)
                val updatedConversation = session.withGroupDirectorLock {
                    session.withConversationMutationLock {
                        val updated = session.state.value.copy(
                            messageNodes = session.state.value.messageNodes.map { node ->
                                node.copy(messages = node.messages.map { it.finishReasoning() })
                            },
                            updateAt = Instant.now(),
                        )
                        updateConversation(conversationId, updated)
                        updated
                    }
                }

                // 生成结束：取消 Live Update 通知，后台时发送完成通知
                appEventBus.emit(
                    AppEvent.ChatGenerationEnded(
                        conversationId = conversationId,
                        senderName = senderName,
                        contentPreview = updatedConversation.currentMessages.lastOrNull()
                            ?.toText()?.take(50)?.trim() ?: "",
                    )
                )
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        // 群组对话：给每个 chunk message 打当前发言成员的 memberId + name
                        val stampedMessages = if (effectiveMemberId != null) {
                            chunk.messages.toStorableGroupGeneratedMessages(
                                originalMessageIds = originalMessageIds,
                                effectiveMemberId = effectiveMemberId,
                                memberName = memberName,
                            )
                        } else chunk.messages
                        val session = getOrCreateSession(conversationId)
                        val updatedConversation = session.withGroupDirectorLock {
                            session.withConversationMutationLock {
                                val merged = session.state.value.mergeMessages(stampedMessages)
                                updateConversation(conversationId, merged)
                                merged
                            }
                        }

                        chunk.messages.lastOrNull()?.let { lastMessage ->
                            appEventBus.tryEmit(
                                AppEvent.ChatGenerationUpdate(conversationId, lastMessage, senderName)
                            )
                        }
                    }
                }
            }
        }.onFailure {
            if (it is CancellationException) {
                if (groupAssistant.assistantType == AssistantType.GROUP) {
                    normalizeCancelledGroupGeneration(
                        session = getOrCreateSession(conversationId),
                        generationJob = generationJob,
                        engine = groupDirectorEngine,
                    ) { updated ->
                        saveConversationUnlocked(conversationId, updated, PromptTraceCleanup.None)
                    }
                }
                throw it
            }
            if (groupAssistant.assistantType == AssistantType.GROUP && effectiveMemberId != null) {
                val session = getOrCreateSession(conversationId)
                session.completeGroupReplyHandoff(generationJob) {
                    mutateConversation(session) { current ->
                        val failedState = groupDirectorEngine.afterFailure(current.groupRuntimeState.director)
                        if (failedState != current.groupRuntimeState.director) {
                            saveConversationUnlocked(
                                conversationId,
                                current.copy(
                                    groupRuntimeState = current.groupRuntimeState.copy(director = failedState)
                                ),
                                PromptTraceCleanup.None,
                            )
                        }
                    }
                    GroupGenerationHandoffResult(Unit, shouldContinue = false)
                }
            }
            // 兜底取消 Live Update 通知（生成开始前失败时 onCompletion 不会执行）
            appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))

            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            val groupHandoff = if (
                groupAssistant.assistantType == AssistantType.GROUP && effectiveMemberId != null
            ) {
                val session = getOrCreateSession(conversationId)
                session.completeGroupReplyHandoff(generationJob) {
                    val result = mutateConversation(session) { latest ->
                        val runtimeWithDebug = latest.groupRuntimeState.copy(
                            lastResolverDebug = dynamicContextResult?.debugState
                                ?: latest.groupRuntimeState.lastResolverDebug,
                        )
                        val updatedRuntime = GroupRuntimeStateUpdater().updateAfterReply(
                            previous = runtimeWithDebug,
                            groupAssistant = groupAssistant,
                            messages = latest.currentMessages,
                            speakerId = effectiveMemberId,
                        )
                        val updated = latest.copy(
                            groupRuntimeState = updatedRuntime.copy(
                                director = groupDirectorEngine.afterReply(
                                    updatedRuntime.director,
                                    effectiveMemberId,
                                ),
                            ),
                        )
                        val alreadySent = countGroupRepliesSinceLastUserMessage(updated, groupAssistant)
                        val director = updated.groupRuntimeState.director
                        val effectiveStrategy = groupDirectorEngine.effectiveStrategy(
                            director,
                            groupAssistant.turnTakingStrategy,
                        )
                        val shouldContinue = allowAutoChain && groupDirectorEngine.shouldContinueAfterReply(
                            state = director,
                            effectiveStrategy = effectiveStrategy,
                            isAddressedTurn = isAddressedTurn,
                            alreadySent = alreadySent,
                            configuredLimit = groupAssistant.groupReplyOptions.maxAutoRepliesPerUserTurn,
                        )
                        saveConversationUnlocked(conversationId, updated, PromptTraceCleanup.None)
                        GroupGenerationHandoffResult(updated, shouldContinue)
                    }
                    result
                }
            } else {
                null
            }
            val conversationAfterRuntimeUpdate = groupHandoff?.value ?: run {
                withConversationMutation(conversationId) { session ->
                    session.state.value.also { conversation ->
                        saveConversationUnlocked(conversationId, conversation, PromptTraceCleanup.None)
                    }
                }
            }

            // Only generate title/suggestions for main generation, not group member replies
            if (effectiveMemberId == null) {
                launchWithConversationReference(conversationId) {
                    generateTitle(conversationId, conversationAfterRuntimeUpdate)
                }
                launchWithConversationReference(conversationId) {
                    generateSuggestion(conversationId, conversationAfterRuntimeUpdate)
                }
            } else if (groupHandoff?.shouldContinue == true) {
                handleMessageComplete(conversationId = conversationId, allowAutoChain = true)
            }
        }
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String? = null): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, " +
                    "status=${workspace.shellStatus}",
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
    }

    // ---- 检查无效消息 ----

    private suspend fun checkInvalidMessages(conversationId: Uuid) {
        withConversationMutation(conversationId) { session ->
            val before = session.state.value
            val after = before.removeInvalidUnresolvedToolMessages()
            if (after != before) {
                saveConversationUnlocked(
                    conversationId,
                    after,
                    PromptTraceCleanup.RemovedMessages(before),
                )
            }
        }
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution """ +
                        """completed."}"""
                )
            ),
            approvalState = ToolApprovalState.Denied("Generation cancelled by user")
        )
    }

    private suspend fun finishInterruptedPendingTools(conversationId: Uuid) {
        withConversationMutation(conversationId) { session ->
            val currentConversation = session.state.value
            val lastNode = currentConversation.messageNodes.lastOrNull() ?: return@withConversationMutation
            val lastMessage = lastNode.currentMessage
            val updatedMessage = lastMessage.finishPendingTools(::cancelToolByUser)
            if (updatedMessage == lastMessage) return@withConversationMutation
            val updatedConversation = currentConversation.copy(
                messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                    messages = lastNode.messages.map { message ->
                        if (message.id == lastMessage.id) updatedMessage else message
                    }
                )
            )
            saveConversationUnlocked(conversationId, updatedConversation, PromptTraceCleanup.None)
        }
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return@withContext

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.titleModelId, fallback = settings.fastModelId)
                ?: return@runCatching
            val provider = model.findProvider(settings.providers) ?: return@runCatching

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) })
                    ),
                ),
                params = backgroundTextGenerationParams(model),
            )

            val title = result.message.toText().trim()
            withConversationMutation(conversationId) { session ->
                saveConversationUnlocked(
                    conversationId,
                    session.state.value.copy(title = title),
                    PromptTraceCleanup.None,
                )
            }
        }.onFailure {
            it.printStackTrace()
            addError(
                error = it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckTitleModelSettings,
            )
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(
        conversationId: Uuid,
        conversation: Conversation,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return@runCatching
            val model = settings.findModelById(settings.suggestionModelId, fallback = settings.fastModelId)
                ?: return@runCatching
            val provider = model.findProvider(settings.providers) ?: return@runCatching

            sessions[conversationId]?.let { session ->
                session.withRefSuspend {
                    mutateConversation(session) { current ->
                        updateConversation(conversationId, current.copy(chatSuggestions = emptyList()))
                    }
                }
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }),
                    )
                ),
                params = backgroundTextGenerationParams(model),
            )
            val suggestions =
                result.message.toText().split("\n").map { it.trim() }
                    .filter { it.isNotBlank() }

            withConversationMutation(conversationId) { session ->
                saveConversationUnlocked(
                    conversationId,
                    session.state.value.copy(chatSuggestions = suggestions.take(10)),
                    PromptTraceCleanup.None,
                )
            }
        }.onFailure {
            it.printStackTrace()
        }
    }

    // ---- 压缩对话历史 ----

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32
    ): Result<Unit> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")
        val effectiveTargetTokens = targetTokens.takeIf { it > 0 }

        val providerHandler = providerManager.getProviderByType(provider)

        val maxMessagesPerChunk = 256
        val allMessages = conversation.currentMessages

        // Split messages into those to compress and those to keep
        val messagesToCompress: List<UIMessage>
        val messagesToKeep: List<UIMessage>

        if (keepRecentMessages > 0 && allMessages.size > keepRecentMessages) {
            messagesToCompress = allMessages.dropLast(keepRecentMessages)
            messagesToKeep = allMessages.takeLast(keepRecentMessages)
        } else if (keepRecentMessages > 0) {
            // Not enough messages to compress while keeping recent ones
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        } else {
            messagesToCompress = allMessages
            messagesToKeep = emptyList()
        }

        fun splitMessages(messages: List<UIMessage>): List<List<UIMessage>> {
            if (messages.size <= maxMessagesPerChunk) return listOf(messages)
            val mid = messages.size / 2
            val left = splitMessages(messages.subList(0, mid))
            val right = splitMessages(messages.subList(mid, messages.size))
            return left + right
        }

        suspend fun compressMessages(messages: List<UIMessage>): String {
            val contentToCompress = messages.joinToString("\n\n") { it.summaryAsText(maxLength = 2000) }
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to (effectiveTargetTokens?.toString() ?: "the model maximum"),
                "additional_context" to if (additionalPrompt.isNotBlank()) {
                    "Additional instructions from user: $additionalPrompt"
                } else "",
                "locale" to Locale.getDefault().displayName
            )

            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(model),
            )

            return result.message.toText().trim().takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Failed to generate compressed summary")
        }

        val compressedSummaries = coroutineScope {
            splitMessages(messagesToCompress)
                .map { chunk -> async { compressMessages(chunk) } }
                .awaitAll()
        }

        withConversationMutation(conversationId) { session ->
            val latest = session.state.value
            val updated = applyCompressedConversation(
                baseline = conversation,
                latest = latest,
                compressedSummaries = compressedSummaries,
                keepRecentMessages = messagesToKeep.size,
            )
            saveConversationUnlocked(
                conversationId,
                updated,
                PromptTraceCleanup.RemovedMessages(conversation),
            )
        }
    }

    // ---- 对话状态更新 ----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        checkFilesDelete(conversation, session.state.value)
        session.state.value = conversation
        session.recordConversationMutation()
    }

    suspend fun updateConversationState(
        conversationId: Uuid,
        update: (Conversation) -> Conversation,
    ) {
        withConversationMutation(conversationId) { session ->
            updateConversation(conversationId, update(session.state.value))
        }
    }

    suspend fun persistCurrentConversation(conversationId: Uuid) {
        withConversationMutation(conversationId) { session ->
            saveConversationUnlocked(conversationId, session.state.value, PromptTraceCleanup.None)
        }
    }

    suspend fun updateTitle(conversationId: Uuid, title: String): Boolean = updateExistingConversationField(
        conversationId = conversationId,
        transform = { current -> current.copy(title = title) },
        updateInactive = { conversationRepo.updateConversationTitle(conversationId, title) },
    )

    suspend fun updatePinnedStatus(conversationId: Uuid): Boolean = updateExistingConversationField(
        conversationId = conversationId,
        transform = { current -> current.copy(isPinned = !current.isPinned) },
        updateInactive = { conversationRepo.toggleConversationPinStatus(conversationId) },
    )

    suspend fun updateAssistant(
        conversationId: Uuid,
        assistantId: Uuid,
        clearFolder: Boolean,
    ): Boolean {
        return assistantDeletionGateMutex.withLock {
            if (!canMoveConversationToAssistant(assistantId, deletingAssistantIds)) return@withLock false
            updateExistingConversationField(
                conversationId = conversationId,
                transform = { current ->
                    current.copy(assistantId = assistantId, folderId = if (clearFolder) null else current.folderId)
                },
                updateInactive = {
                    conversationRepo.updateConversationAssistant(conversationId, assistantId, clearFolder)
                },
            )
        }
    }

    suspend fun updateConversationMessageFields(
        conversationId: Uuid,
        requested: Conversation,
    ): Conversation = updateConversationFields(conversationId) { current ->
        mergeConversationUiFields(current, requested)
    }

    /** Restores only into an existing assistant that is not in the full assistant-deletion gate. */
    suspend fun restoreConversationAtomic(conversation: Conversation): Boolean {
        return assistantDeletionGateMutex.withLock {
            val settings = settingsStore.settingsFlowRaw.first()
            val assistantExists = settings.getAssistantById(conversation.assistantId) != null
            if (!canRestoreConversation(assistantExists, conversation.assistantId in deletingAssistantIds)) {
                return@withLock false
            }
            if (conversationRepo.existsConversationById(conversation.id)) return@withLock false
            conversationRepo.insertConversation(conversation)
            true
        }
    }

    suspend fun updateConversationInjections(
        conversationId: Uuid,
        modeInjectionIds: Set<Uuid>,
        lorebookIds: Set<Uuid>,
    ): Conversation = updateConversationFields(conversationId) { current ->
        current.copy(modeInjectionIds = modeInjectionIds, lorebookIds = lorebookIds)
    }

    suspend fun replaceConversationMessageNode(
        conversationId: Uuid,
        node: me.rerere.rikkahub.data.model.MessageNode,
    ): Conversation = updateConversationFields(conversationId) { current ->
        current.copy(messageNodes = current.messageNodes.map { existing ->
            if (existing.id == node.id) node else existing
        })
    }

    private suspend fun updateConversationFields(
        conversationId: Uuid,
        transform: (Conversation) -> Conversation,
    ): Conversation = withConversationMutation(conversationId) { session ->
        val updated = transform(session.state.value)
        saveConversationUnlocked(conversationId, updated, PromptTraceCleanup.None)
        updated
    }

    /**
     * Updates a persisted conversation without creating a session for an unknown id. Active sessions take the shared
     * mutation lock; inactive conversations use a DAO field update so their message tree is never read and re-saved.
     */
    private suspend fun updateExistingConversationField(
        conversationId: Uuid,
        transform: (Conversation) -> Conversation,
        updateInactive: suspend () -> Boolean,
    ): Boolean {
        repeat(2) {
            val session = sessions[conversationId] ?: return updateInactive()
            val updated = try {
                session.withRefSuspend {
                    session.withConversationMutationLock {
                        if (sessions[conversationId] !== session) return@withConversationMutationLock null
                        if (!conversationRepo.existsConversationById(conversationId)) {
                            return@withConversationMutationLock false
                        }
                        val next = transform(session.state.value)
                        saveConversationUnlocked(conversationId, next, PromptTraceCleanup.None)
                        true
                    }
                }
            } catch (error: IllegalStateException) {
                if (error.message == "CONVERSATION_SESSION_CLOSED") null else throw error
            }
            if (updated != null) return updated
        }
        return updateInactive()
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 若该会话当前有活跃 session（正在查看或后台生成），先同步内存态再落库：
     * 否则仅改数据库 folder_id，而内存里那份 Conversation 仍是旧 folderId，
     * 后续任意 saveConversation(id, state.value) 会用整对象把 folder_id 覆盖回旧值，导致移动丢失。
     * 先改内存可确保这段窗口内的整对象保存也带上新 folderId。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?): Boolean =
        updateExistingConversationField(
            conversationId = conversationId,
            transform = { current -> current.copy(folderId = folderId) },
            updateInactive = { conversationRepo.updateConversationFolderId(conversationId, folderId) },
        )

    private data class ConversationDeletionCommitResult(
        val result: ConversationDeleteResult,
        val conversation: Conversation? = null,
    )

    private suspend fun commitConversationDeletion(
        conversationId: Uuid,
        expectedAssistantId: Uuid?,
    ): ConversationDeletionCommitResult {
        val conversation = conversationRepo.getConversationById(conversationId)
            ?: return ConversationDeletionCommitResult(ConversationDeleteResult.NOT_FOUND)
        if (expectedAssistantId != null && conversation.assistantId != expectedAssistantId) {
            return ConversationDeletionCommitResult(ConversationDeleteResult.MOVED)
        }
        val commit = if (expectedAssistantId == null) {
            conversationRepo.commitConversationDeletion(conversation)
        } else {
            conversationRepo.commitConversationDeletionIfAssistantId(conversation, expectedAssistantId)
        }
        if (!commit.committed) {
            val result = if (expectedAssistantId == null) {
                ConversationDeleteResult.NOT_FOUND
            } else {
                ConversationDeleteResult.MOVED
            }
            return ConversationDeletionCommitResult(result)
        }
        return ConversationDeletionCommitResult(ConversationDeleteResult.DELETED, commit.conversation)
    }

    private suspend fun cleanupCommittedConversationDeletion(conversation: Conversation) {
        conversationRepo.cleanupCommittedConversationDeletion(conversation).forEach { error ->
            Log.w(TAG, "Conversation deletion cleanup failed: ${conversation.id}", error)
        }
    }

    private fun closeCommittedConversationSession(conversationId: Uuid, session: ConversationSession?) {
        tavernRuntimeReadiness.clear(conversationId)
        statusVariableStore.remove(conversationId)
        if (session != null) {
            session.closeLockedForCleanup()
            if (sessions.remove(conversationId, session)) _sessionsVersion.value++
        }
    }

    private suspend fun deleteInactiveConversationAtomically(
        conversationId: Uuid,
        expectedAssistantId: Uuid?,
    ): ConversationDeleteResult {
        val commit = commitConversationDeletion(conversationId, expectedAssistantId)
        val conversation = commit.conversation ?: return commit.result
        closeCommittedConversationSession(conversationId, session = null)
        cleanupCommittedConversationDeletion(conversation)
        return ConversationDeleteResult.DELETED
    }

    /** Commits Room deletion before revoking the live owner; cleanup cannot revive an already deleted id. */
    suspend fun deleteConversationAtomic(
        conversationId: Uuid,
        expectedAssistantId: Uuid? = null,
    ): ConversationDeleteResult = withContext(NonCancellable) {
        repeat(2) {
            val session = sessions[conversationId] ?: return@withContext deleteInactiveConversationAtomically(
                conversationId,
                expectedAssistantId,
            )
            val committed = try {
                session.withRefSuspend {
                    session.withConversationMutationLock {
                        if (sessions[conversationId] !== session) return@withConversationMutationLock null
                        if (expectedAssistantId != null && session.state.value.assistantId != expectedAssistantId) {
                            return@withConversationMutationLock ConversationDeletionCommitResult(
                                ConversationDeleteResult.MOVED,
                            )
                        }
                        val commit = commitConversationDeletion(conversationId, expectedAssistantId)
                        val conversation = commit.conversation ?: return@withConversationMutationLock commit
                        closeCommittedConversationSession(conversationId, session)
                        ConversationDeletionCommitResult(ConversationDeleteResult.DELETED, conversation)
                    }
                }
            } catch (error: IllegalStateException) {
                if (error.message == "CONVERSATION_SESSION_CLOSED") null else throw error
            }
            if (committed == null) return@repeat
            val conversation = committed.conversation
            if (conversation != null) cleanupCommittedConversationDeletion(conversation)
            return@withContext committed.result
        }
        deleteInactiveConversationAtomically(conversationId, expectedAssistantId)
    }

    private suspend fun deleteConversationsForDeletingAssistant(assistantId: Uuid): Boolean {
        val repositoryIds = conversationRepo.getConversationsOfAssistant(assistantId).first().map { it.id }
        val liveSessionIds = sessions.values
            .filter { it.state.value.assistantId == assistantId }
            .map { it.id }
        val conversationIds = orderedAssistantConversationDeletionIds(repositoryIds + liveSessionIds)
        return deleteAssistantConversationIds(conversationIds) { conversationId ->
            isAssistantBatchDeleteSuccess(
                deleteConversationAtomic(conversationId, expectedAssistantId = assistantId),
            )
        }.succeeded
    }

    /** Holds the deletion gate through conversation removal and caller-owned assistant settings/memory finalization. */
    suspend fun deleteAssistantAtomically(
        assistantId: Uuid,
        finalizeAssistantDeletion: suspend () -> Unit,
    ): AssistantDeletionResult {
        val acquired = assistantDeletionGateMutex.withLock { deletingAssistantIds.add(assistantId) }
        if (!acquired) return AssistantDeletionResult(succeeded = false)
        return try {
            if (!deleteConversationsForDeletingAssistant(assistantId)) {
                AssistantDeletionResult(succeeded = false)
            } else {
                try {
                    finalizeAssistantDeletion()
                    AssistantDeletionResult(succeeded = true)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.e(TAG, "Failed to finalize assistant deletion: $assistantId", error)
                    AssistantDeletionResult(succeeded = false, finalizeError = error)
                }
            }
        } finally {
            withContext(NonCancellable) {
                assistantDeletionGateMutex.withLock { deletingAssistantIds.remove(assistantId) }
            }
        }
    }

    /** Closes every live assistant conversation through the same gate as full assistant deletion. */
    suspend fun deleteConversationsOfAssistantAtomic(assistantId: Uuid): Boolean =
        deleteAssistantAtomically(assistantId) {}.succeeded

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessions.values.any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 先把内存中归属该文件夹的活跃 session folderId 置空，再删库：
     * 否则 clearFolder 只改了数据库，而活跃 session 内存态仍指向该文件夹，
     * 后续整对象保存会写回一个已被删除的 folder_id，导致会话在列表中悬空。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        sessions.values
            .filter { it.state.value.folderId == folderId }
            .forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
        folderRepository.deleteFolder(folderId)
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val newFiles = newConversation.files
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    suspend fun saveConversation(
        conversationId: Uuid,
        conversation: Conversation,
        allowCreate: Boolean = false,
    ) {
        withConversationMutation(conversationId) {
            saveConversationUnlocked(
                conversationId,
                conversation,
                PromptTraceCleanup.None,
                allowCreate,
            )
        }
    }

    private suspend fun saveConversationAfterRemovingMessages(
        conversationId: Uuid,
        before: Conversation,
        after: Conversation,
    ) {
        withConversationMutation(conversationId) {
            saveConversationUnlocked(conversationId, after, PromptTraceCleanup.RemovedMessages(before))
        }
    }

    private suspend fun saveConversationUnlocked(
        conversationId: Uuid,
        conversation: Conversation,
        promptTraceCleanup: PromptTraceCleanup,
        allowCreate: Boolean = false,
    ): Boolean {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (conversation.assistantId in deletingAssistantIds) return false
        if (!canPersistConversation(exists, tavernRuntimeReadiness.isReady(conversationId), allowCreate)) {
            return false
        }
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return false // 新会话且为空时不保存
        }

        val updatedConversation = conversation.copy()
        persistConversationAndCleanupPromptTraces(
            conversationId = conversationId,
            conversation = updatedConversation,
            promptTraceCleanup = promptTraceCleanup,
            promptTraceRepository = promptTraceRepository,
        ) { persistedConversation ->
            persistConversationThenPublishLive(
                conversation = persistedConversation,
                persist = { candidate ->
                    if (!exists) {
                        conversationRepo.insertConversation(candidate)
                    } else {
                        conversationRepo.updateConversation(candidate)
                    }
                },
                publishLive = { committed -> updateConversation(conversationId, committed) },
            )
        }
        return true
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                generationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the latest live state while holding the same mutation lock as runtime writes.
                withConversationMutation(conversationId) { session ->
                    saveConversationUnlocked(
                        conversationId,
                        session.state.value,
                        PromptTraceCleanup.None,
                    )
                }
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        appScope.launch {
            withConversationMutation(conversationId) { session ->
                val currentConversation = session.state.value
                val updatedNodes = currentConversation.messageNodes.map { node ->
                    if (node.messages.any { it.id == messageId }) {
                        val updatedMessages = node.messages.map { msg ->
                            if (msg.id == messageId) msg.copy(translation = translationText) else msg
                        }
                        node.copy(messages = updatedMessages)
                    } else {
                        node
                    }
                }
                updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
            }
        }
    }

    // ---- 消息操作 ----

    /** Selected branch used by the Tavern browser runtime; remains backed by the live conversation session. */
    override fun getTavernRuntimeMessages(conversationId: Uuid): List<UIMessage> =
        getConversationFlow(conversationId).value.currentMessages

    override suspend fun readTavernRuntimeMessageSnapshot(conversationId: Uuid): List<UIMessage>? {
        val session = sessions[conversationId] ?: return null
        return session.withRefSuspend {
            session.withConversationMutationLock {
                if (sessions[conversationId] !== session || !tavernRuntimeReadiness.isReady(conversationId)) {
                    null
                } else {
                    session.state.value.currentMessages
                }
            }
        }
    }

    /** Appends a real message node, persists it, refreshes live state, and emits the matching Tavern event. */
    override suspend fun createTavernRuntimeMessage(
        conversationId: Uuid,
        role: MessageRole,
        text: String,
    ): UIMessage = tavernRuntimeMessageStore.create(conversationId, role, text)

    /** Updates the existing message object in-place; unlike [editMessage], this never creates a swipe. */
    override suspend fun updateTavernRuntimeMessageText(
        conversationId: Uuid,
        messageId: Uuid,
        text: String,
    ): UIMessage? = tavernRuntimeMessageStore.update(conversationId, messageId, text)

    override suspend fun updateLatestTavernRuntimeMessage(
        conversationId: Uuid,
        text: String,
    ): UIMessage? = tavernRuntimeMessageStore.updateLatest(conversationId, text)

    /** Deletes an exact selected-branch message using the normal ChatService persistence and cleanup path. */
    override suspend fun deleteTavernRuntimeMessage(conversationId: Uuid, messageId: Uuid): Boolean =
        tavernRuntimeMessageStore.delete(conversationId, messageId)

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return

        val currentConversation = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(parts, assistant, conversationId)
        val edited = withConversationMutation(conversationId) { session ->
            val latestConversation = session.state.value
            var found = false
            val updatedNodes = latestConversation.messageNodes.map { node ->
                if (!node.messages.any { it.id == messageId }) {
                    return@map node
                }
                found = true
                node.copy(
                    messages = node.messages + UIMessage(
                        role = node.role,
                        parts = processedParts,
                    ),
                    selectIndex = node.messages.size,
                )
            }
            if (found) {
                saveConversationUnlocked(
                    conversationId,
                    latestConversation.copy(messageNodes = updatedNodes),
                    PromptTraceCleanup.None,
                )
            }
            found
        }

        if (!edited) return

        tavernHostEventBus.emit(
            type = TavernHostEventType.MESSAGE_EDITED,
            conversationId = conversationId,
            payload = buildJsonObject { put("messageId", messageId.toString()) },
        )
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val forkConversation = buildForkConversationAtMessage(
            currentConversation = currentConversation,
            messageId = messageId,
            copyPart = { part -> part.copyWithForkedFileUrl() },
        )

        saveConversation(forkConversation.id, forkConversation, allowCreate = true)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val changed = withConversationMutation(conversationId) { session ->
            val currentConversation = session.state.value
            val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
                ?: throw NotFoundException("Message node not found")
            if (selectIndex !in targetNode.messages.indices) {
                throw BadRequestException("Invalid selectIndex")
            }
            if (targetNode.selectIndex == selectIndex) return@withConversationMutation false
            val updatedNodes = currentConversation.messageNodes.map { node ->
                if (node.id == nodeId) node.copy(selectIndex = selectIndex) else node
            }
            saveConversationUnlocked(
                conversationId,
                currentConversation.copy(messageNodes = updatedNodes),
                PromptTraceCleanup.None,
            )
            true
        }

        if (!changed) return

        tavernHostEventBus.emit(
            type = TavernHostEventType.MESSAGE_SWIPED,
            conversationId = conversationId,
            payload = buildJsonObject {
                put("nodeId", nodeId.toString())
                put("selectIndex", selectIndex)
            },
        )
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val deleted = withConversationMutation(conversationId) { session ->
            val currentConversation = session.state.value
            val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)
                ?: return@withConversationMutation false
            saveConversationUnlocked(
                conversationId,
                updatedConversation,
                PromptTraceCleanup.RemovedMessages(currentConversation),
            )
            true
        }

        if (!deleted) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        tavernHostEventBus.emit(
            type = TavernHostEventType.MESSAGE_DELETED,
            conversationId = conversationId,
            payload = buildJsonObject { put("messageId", messageId.toString()) },
        )
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        appScope.launch {
            withConversationMutation(conversationId) { session ->
                val currentConversation = session.state.value
                val updatedNodes = currentConversation.messageNodes.map { node ->
                    if (node.messages.any { it.id == messageId }) {
                        val updatedMessages = node.messages.map { msg ->
                            if (msg.id == messageId) msg.copy(translation = null) else msg
                        }
                        node.copy(messages = updatedMessages)
                    } else {
                        node
                    }
                }
                updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
            }
        }
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        val job = sessions[conversationId]?.getJob() ?: return
        job.cancel()
        runCatching { job.join() }
        finishInterruptedPendingTools(conversationId)
    }

    // ---- 群组发言决策 ----

    private fun countGroupRepliesSinceLastUserMessage(
        conversation: Conversation,
        groupAssistant: Assistant,
    ): Int {
        val lastUserIndex = conversation.messageNodes.indexOfLast { node ->
            node.currentMessage.role == MessageRole.USER
        }
        if (lastUserIndex < 0) return 0
        return conversation.messageNodes
            .drop(lastUserIndex + 1)
            .count { node ->
                val role = node.currentMessage.role
                val memberId = node.currentMessage.memberId
                role == MessageRole.ASSISTANT &&
                    memberId != null &&
                    groupAssistant.groupMembers.any { it.id == memberId && it.enabled }
            }
    }

    /** 解析下一个发言者：依据助手的 turnTakingStrategy。返回 null 表示无可用成员。 */
    private suspend fun resolveNextSpeaker(
        conversation: Conversation,
        groupAssistant: Assistant,
        settings: me.rerere.rikkahub.data.datastore.Settings,
        allowModeratorStop: Boolean = false,
        generationJob: Job? = null,
    ): Uuid? {
        val session = getOrCreateSession(conversation.id)
        return try {
            val moderatorSnapshot = session.withGroupDirectorLock {
                mutateConversation(session) { current ->
                    val enabledIds = groupAssistant.groupMembers.filter { it.enabled }.map { it.id }
                    val director = groupDirectorEngine.sanitize(
                        state = current.groupRuntimeState.director,
                        enabledMemberIds = enabledIds,
                        generationActive = true,
                    )
                    val strategy = groupDirectorEngine.effectiveStrategy(
                        director,
                        groupAssistant.turnTakingStrategy,
                    )
                    if (strategy == TurnTakingStrategy.AUTO_MODERATOR && director.oneShotNextMemberId == null) {
                        ModeratorDecisionSnapshot(
                            conversation = current,
                            director = director,
                            orderedEligibleMemberIds = orderedModeratorEligibleMemberIds(
                                current.groupMemberQueue,
                                groupDirectorEngine.eligibleMemberIds(director, enabledIds),
                            ),
                            allowStop = allowModeratorStop || director.oneRoundActive,
                        )
                    } else {
                        null
                    }
                }
            }
            // Provider work deliberately runs outside conversationMutationMutex.
            val moderatorDecision = moderatorSnapshot?.let { snapshot ->
                resolveNextSpeakerViaModerator(
                    conversation = snapshot.conversation,
                    groupAssistant = groupAssistant,
                    settings = settings,
                    allowStop = snapshot.allowStop,
                    eligibleMemberIds = snapshot.orderedEligibleMemberIds,
                )
            }
            session.withGroupDirectorLock {
                mutateConversation(session) { current ->
                    val enabledIds = groupAssistant.groupMembers.filter { it.enabled }.map { it.id }
                    val director = groupDirectorEngine.sanitize(
                        state = current.groupRuntimeState.director,
                        enabledMemberIds = enabledIds,
                        generationActive = true,
                    )
                    val eligibleIds = groupDirectorEngine.eligibleMemberIds(director, enabledIds)
                    val orderedEligible = normalizeGroupMemberQueue(current.groupMemberQueue, eligibleIds)
                    val effectiveStrategy = groupDirectorEngine.effectiveStrategy(
                        director,
                        groupAssistant.turnTakingStrategy,
                    )

                    val normalSelection = if (director.oneShotNextMemberId != null) {
                        null
                    } else {
                        when (effectiveStrategy) {
                            TurnTakingStrategy.MANUAL,
                            TurnTakingStrategy.AUTO_ROUND_ROBIN -> resolveLocalGroupTurnSelection(
                                director = director,
                                effectiveStrategy = effectiveStrategy,
                                persistedQueue = current.groupMemberQueue,
                                persistedIndex = current.groupMemberQueueIndex,
                                activeMemberId = current.activeGroupMemberId,
                                orderedEligibleMemberIds = orderedEligible,
                            )
                            TurnTakingStrategy.AUTO_MODERATOR -> {
                                if (
                                    moderatorSnapshot == null ||
                                    moderatorSnapshot.director != director ||
                                    moderatorSnapshot.orderedEligibleMemberIds != orderedEligible ||
                                    moderatorSnapshot.conversation.groupRuntimeState != current.groupRuntimeState ||
                                    moderatorSnapshot.conversation.activeGroupMemberId != current.activeGroupMemberId ||
                                    moderatorSnapshot.conversation.groupMemberQueue != current.groupMemberQueue ||
                                    moderatorSnapshot.conversation.groupMemberQueueIndex !=
                                    current.groupMemberQueueIndex ||
                                    moderatorSnapshot.conversation.currentMessages.map { it.id } !=
                                    current.currentMessages.map { it.id }
                                ) {
                                    return@mutateConversation null
                                }
                                selectModeratorTurn(
                                    persistedQueue = current.groupMemberQueue,
                                    enabledMemberIds = orderedEligible,
                                    activeMemberId = current.activeGroupMemberId,
                                    resolvedMemberId = moderatorDecision,
                                    allowConsecutiveSameSpeaker =
                                        groupAssistant.groupReplyOptions.allowConsecutiveSameSpeaker,
                                )
                            }
                        }
                    }

                    val selection = groupDirectorEngine.applyCandidate(
                        state = director,
                        normalCandidateId = normalSelection?.memberId,
                        orderedCandidateMemberIds = normalSelection?.queue ?: orderedEligible,
                    )
                    val selectedId = selection.memberId
                    if (selectedId == null) {
                        val stopped = if (
                            selection.state.playbackState == GroupPlaybackState.PAUSED &&
                            selection.status == GroupDirectorCommandStatus.APPLIED
                        ) {
                            selection.state
                        } else {
                            groupDirectorEngine.afterNoCandidate(
                                state = selection.state,
                                effectiveStrategy = effectiveStrategy,
                            )
                        }
                        if (stopped != current.groupRuntimeState.director) {
                            saveConversationUnlocked(
                                current.id,
                                current.copy(
                                    groupRuntimeState = current.groupRuntimeState.copy(director = stopped),
                                ),
                                PromptTraceCleanup.None,
                            )
                        }
                        session.releaseGroupGenerationLocked(generationJob)
                        return@mutateConversation null
                    }

                    val committedQueue = normalSelection?.queue ?: orderedEligible
                    val committed = current.copy(
                        activeGroupMemberId = selectedId,
                        groupMemberQueue = committedQueue,
                        groupMemberQueueIndex = committedQueue.indexOf(selectedId).coerceAtLeast(0),
                        groupRuntimeState = current.groupRuntimeState.copy(director = selection.state),
                    )
                    saveConversationUnlocked(current.id, committed, PromptTraceCleanup.None)
                    session.markGroupReplyStartedLocked(generationJob)
                    selectedId
                }
            }
        } catch (error: CancellationException) {
            normalizeCancelledGroupGeneration(
                session = session,
                generationJob = generationJob,
                engine = groupDirectorEngine,
            ) { updated ->
                saveConversationUnlocked(conversation.id, updated, PromptTraceCleanup.None)
            }
            throw error
        }
    }

    /** AUTO_MODERATOR：用一个轻量模型决定下一发言者，失败时回退到 round-robin。 */
    private suspend fun resolveNextSpeakerViaModerator(
        conversation: Conversation,
        groupAssistant: Assistant,
        settings: me.rerere.rikkahub.data.datastore.Settings,
        allowStop: Boolean,
        eligibleMemberIds: List<Uuid>,
    ): Uuid? {
        val eligibleSet = eligibleMemberIds.toSet()
        val enabled = groupAssistant.groupMembers.filter { it.enabled && it.id in eligibleSet }
        if (enabled.isEmpty()) return null
        if (enabled.size == 1) return enabled.first().id
        val localScores = GroupSpeakerScorer().score(
            groupAssistant = groupAssistant,
            messages = conversation.currentMessages,
            runtimeState = conversation.groupRuntimeState,
            activeMemberId = conversation.activeGroupMemberId,
        ).filter { it.memberId in eligibleSet }
        val queueFallback = nextRoundRobinSelection(
            persistedQueue = conversation.groupMemberQueue,
            persistedIndex = conversation.groupMemberQueueIndex,
            activeMemberId = conversation.activeGroupMemberId,
            enabledMemberIds = enabled.map { it.id },
        )?.memberId
        val localFallback = localScores.firstOrNull()?.memberId ?: queueFallback

        val descriptions = enabled.joinToString("\n") { m ->
            val source = settings.getAssistantById(m.assistantId)
            val name = m.displayName.ifBlank { source?.name ?: "Unknown" }
            "- ID:${m.id} | $name"
        }
        val prompt = buildString {
            appendLine("You are a conversation moderator. Decide which character should speak next.")
            if (allowStop) {
                appendLine(
                    "Reply ONLY with a character ID (UUID), or STOP if the current user turn has already " +
                        "been answered enough.",
                )
            } else {
                appendLine("Reply ONLY with the character ID (UUID).")
            }
            appendLine()
            appendLine("Characters:")
            appendLine(descriptions)
            appendLine()
            appendLine("Recent conversation:")
            conversation.currentMessages.takeLast(6).forEach { msg ->
                val tag = when (msg.role) {
                    MessageRole.USER -> "User"
                    MessageRole.ASSISTANT -> {
                        msg.memberId?.let { mid ->
                            groupAssistant.groupMembers.find { it.id == mid }?.displayName
                        } ?: "Assistant"
                    }
                    else -> msg.role.name
                }
                appendLine("[$tag]: ${msg.toText().take(200)}")
            }
        }

        return try {
            val moderatorModel = settings.findModelById(
                groupAssistant.chatModelId ?: settings.chatModelId
            ) ?: return localFallback
            val moderatorProvider = moderatorModel.findProvider(settings.providers)
                ?: return localFallback
            val providerImpl = providerManager.getProviderByType(moderatorProvider)
            val result = providerImpl.generateText(
                providerSetting = moderatorProvider,
                messages = listOf(UIMessage.user(prompt)),
                params = TextGenerationParams(model = moderatorModel, maxTokens = 32),
            )
            val responseText = result.message.parts.filterIsInstance<UIMessagePart.Text>()
                ?.joinToString("") { it.text }?.trim().orEmpty()
            parseGroupModeratorDecision(
                responseText = responseText,
                enabledMembers = enabled,
                localFallback = localFallback,
                allowStop = allowStop,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            localFallback
        }
    }
}

/**
 * 在最后一条 USER 文本消息末尾追加增强提示词（仅作用于发送给模型的消息列表，不修改持久化的对话）。
 * 当 [Assistant.enableEnhancementPrompt] 关闭或文本为空时直接返回原列表。
 */
private fun List<UIMessage>.applyEnhancementPrompt(assistant: Assistant): List<UIMessage> {
    if (!assistant.enableEnhancementPrompt) return this
    val extra = assistant.enhancementPrompt
    if (extra.isBlank()) return this
    val lastUserIdx = indexOfLast { it.role == MessageRole.USER }
    if (lastUserIdx < 0) return this
    val userMsg = this[lastUserIdx]
    val parts = userMsg.parts.toMutableList()
    val lastTextIdx = parts.indexOfLast { it is UIMessagePart.Text }
    if (lastTextIdx < 0) return this
    val textPart = parts[lastTextIdx] as UIMessagePart.Text
    parts[lastTextIdx] = textPart.copy(text = textPart.text + "\n\n" + extra)
    return toMutableList().also { it[lastUserIdx] = userMsg.copy(parts = parts) }
}
