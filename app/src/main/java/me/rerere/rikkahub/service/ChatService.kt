package me.rerere.rikkahub.service

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.LocalTools
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
import me.rerere.rikkahub.data.ai.slash.ScriptManager
import me.rerere.rikkahub.data.ai.slash.SlashCommandInterceptor
import me.rerere.rikkahub.data.ai.status.StatusVariableStore
import me.rerere.rikkahub.data.ai.transformers.StatusPlaceholderTransformer
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.TurnTakingStrategy
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.group.GroupContextBuildInput
import me.rerere.rikkahub.service.group.GroupContextBuilder
import me.rerere.rikkahub.service.group.GroupRuntimeStateUpdater
import me.rerere.rikkahub.service.group.GroupSpeakerScorer
import me.rerere.rikkahub.service.group.GroupSpeakingIntent
import me.rerere.rikkahub.service.group.DynamicGroupContextResult
import me.rerere.rikkahub.service.group.applyGroupApiRewrite
import me.rerere.rikkahub.service.group.applyGroupContextFilter as applyDynamicGroupContextFilter
import me.rerere.rikkahub.service.group.resolveAddressedMember
import me.rerere.rikkahub.service.group.DynamicGroupContextResolver
import me.rerere.rikkahub.service.group.isGroupContinuationNudge
import me.rerere.rikkahub.service.group.parseGroupModeratorDecision
import me.rerere.rikkahub.service.group.resolveEffectiveGroupMemberAssistant
import me.rerere.rikkahub.service.group.resolveManualReplyMemberIds
import me.rerere.rikkahub.service.group.toStorableGroupGeneratedMessages
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.rikkahub.utils.sendNotification
import me.rerere.rikkahub.utils.cancelNotification
import me.rerere.workspace.WorkspaceShellStatus
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

internal fun backgroundTextGenerationParams(
    model: Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
    maxTokens: Int? = null,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    maxTokens = maxTokens?.takeIf { it > 0 },
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)

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

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val statusVariableStore: StatusVariableStore,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    // Slash 命令脚本引擎（懒加载——仅当用户首次发斜杠命令时才扫描磁盘脚本）
    private val scriptManager by lazy { ScriptManager(context, settingsStore) }
    private val slashInterceptor by lazy { SlashCommandInterceptor(scriptManager) }

    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

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

    // 前台状态管理
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> _isForeground.value = true
            Lifecycle.Event.ON_STOP -> _isForeground.value = false
            else -> {}
        }
    }

    init {
        // 添加生命周期观察者
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    fun cleanup() = runCatching {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
    }

    // ---- Session 管理 ----

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
            session.cleanup()
            _sessionsVersion.value++
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
        getOrCreateSession(conversationId) // 确保 session 存在
        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            settingsStore.updateAssistant(conversation.assistantId)
            statusVariableStore.init(conversationId, conversation.statusVariables)
            val settings = settingsStore.settingsFlowRaw.first()
            val assistant = settings.getAssistantById(conversation.assistantId)
                ?: settings.getCurrentAssistant()
            val renderedConversation = renderStoredStatusInstructions(
                conversationId = conversationId,
                conversation = conversation,
                settings = settings,
                assistant = assistant,
            )
            val cleanedConversation = if (assistant.assistantType == AssistantType.GROUP) {
                renderedConversation.removeGroupContinuationNudgeNodes()
            } else {
                renderedConversation
            }
            updateConversation(conversationId, cleanedConversation)
            if (cleanedConversation != renderedConversation) {
                saveConversation(conversationId, cleanedConversation)
            }
        } else {
            // 新建对话, 并添加预设消息
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            statusVariableStore.init(conversationId, JsonObject(emptyMap()))
            val presetMessages = renderPresetMessages(
                conversationId = conversationId,
                settings = currentSettings,
                assistant = assistant,
            )
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true
            ).updateCurrentMessages(presetMessages)
                .copy(statusVariables = statusVariableStore.getValue(conversationId))
            updateConversation(conversationId, newConversation)
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

        val renderedMessages = renderPresetMessages(
            conversationId = conversationId,
            settings = settings,
            assistant = assistant,
            messages = messages,
        )
        return conversation.updateCurrentMessages(renderedMessages)
            .copy(statusVariables = statusVariableStore.getValue(conversationId))
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

        val hasUserMessages = conversation.currentMessages.any { it.role == MessageRole.USER }
        val updatedConversation = if (hasUserMessages) {
            conversation.copy(
                messageNodes = conversation.messageNodes + renderedGreeting.map { it.toMessageNode() },
                statusVariables = statusVariableStore.getValue(conversationId),
            )
        } else {
            conversation.copy(
                messageNodes = renderedGreeting.map { it.toMessageNode() },
                statusVariables = statusVariableStore.getValue(conversationId),
            )
        }
        saveConversation(conversationId, updatedConversation)
    }

    private suspend fun appendUserMessage(
        conversationId: Uuid,
        session: ConversationSession,
        content: List<UIMessagePart>,
    ) {
        val currentConversation = session.state.value
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val processedContent = preprocessUserInputParts(content, assistant)
        val userMessage = UIMessage(
            role = MessageRole.USER,
            parts = processedContent,
        )
        val addressedConversation = currentConversation.withUpdatedGroupAddressedState(assistant, userMessage)
        val newConversation = addressedConversation.copy(
            messageNodes = addressedConversation.messageNodes + userMessage.toMessageNode(),
        )
        saveConversation(conversationId, newConversation)
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

                val currentConversation = session.state.value
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: settings.getCurrentAssistant()
                val processedContent = preprocessUserInputParts(content, assistant)
                val userMessage = UIMessage(
                    role = MessageRole.USER,
                    parts = processedContent,
                )
                val addressedConversation = currentConversation.withUpdatedGroupAddressedState(
                    assistant = assistant,
                    userMessage = userMessage,
                )

                // 添加消息到列表
                val newConversation = addressedConversation.copy(
                    messageNodes = addressedConversation.messageNodes + userMessage.toMessageNode(),
                )
                saveConversation(conversationId, newConversation)

                // 开始补全
                if (answer) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
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
            } catch (e: Exception) {
                e.printStackTrace()
                addError(e, conversationId, title = "Group message failed")
            }
        }
        session.setJob(job)
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
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
            } catch (e: Exception) {
                e.printStackTrace()
                addError(e, conversationId, title = "群组成员回复失败")
            }
        }
        session.setJob(job)
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
                val conversation = session.state.value

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    val newConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                    )
                    saveConversation(conversationId, newConversation)
                    handleMessageComplete(conversationId, memberId = memberId, allowAutoChain = false)
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        handleMessageComplete(
                            conversationId,
                            memberId = memberId,
                            messageRange = 0..<nodeIndex,
                            allowAutoChain = memberId == null,
                        )
                    } else {
                        saveConversation(conversationId, conversation)
                    }
                }

                _generationDoneFlow.emit(conversationId)
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
                val conversation = session.state.value
                val newApprovalState = when {
                    answer != null -> ToolApprovalState.Answered(answer)
                    approved -> ToolApprovalState.Approved
                    else -> ToolApprovalState.Denied(reason)
                }

                // Update the tool approval state
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
                saveConversation(conversationId, updatedConversation)

                // Check if there are still pending tools
                val hasPendingTools = updatedNodes.any { node ->
                    node.currentMessage.parts.any { part ->
                        part is UIMessagePart.Tool && part.isPending
                    }
                }

                // Only continue generation when all pending tools are handled
                if (!hasPendingTools) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
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

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }
        var dynamicContextResult: DynamicGroupContextResult? = null

        runCatching {

            // Reset suggestions without overwriting group speaker state persisted during resolution.
            val resolvedConversation = getConversationFlow(conversationId).value
            updateConversation(
                conversationId,
                conversationAtGenerationStart(initialConversation, resolvedConversation),
            )

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (settings.enableWebSearch || mcpManager.getAllAvailableTools().isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            val rawConversation = getConversationFlow(conversationId).value
            val conversation = if (groupAssistant.assistantType == AssistantType.GROUP) {
                rawConversation.removeGroupContinuationNudgeNodes().also { cleanedConversation ->
                    if (cleanedConversation != rawConversation) {
                        saveConversation(conversationId, cleanedConversation)
                    }
                }
            } else {
                rawConversation
            }
            val baseVisibleMessages = conversation.currentMessages.let {
                if (messageRange != null) {
                    it.subList(messageRange.start, messageRange.endInclusive + 1)
                } else {
                    it
                }
            }.applyDynamicGroupContextFilter(groupAssistant, effectiveMemberId)
            val visibleMessages = if (
                effectiveMemberId != null &&
                groupAssistant.assistantType == AssistantType.GROUP &&
                groupAssistant.groupContextOptions.enableLayeredContext
            ) {
                DynamicGroupContextResolver().resolve(
                    groupAssistant = groupAssistant,
                    messages = baseVisibleMessages,
                    effectiveMemberId = effectiveMemberId,
                    runtimeState = conversation.groupRuntimeState,
                ).also {
                    dynamicContextResult = it
                }.visibleMessages
            } else {
                baseVisibleMessages
            }.applyEnhancementPrompt(assistant)
            val localSpeakerScore = if (effectiveMemberId != null && groupAssistant.assistantType == AssistantType.GROUP) {
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
            val layeredMessages = if (
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
                ).messages
            } else {
                visibleMessages
            }
            val originalMessageIds = layeredMessages.map { it.id }.toSet()
            val messagesForGeneration = layeredMessages.applyGroupApiRewrite(groupAssistant, effectiveMemberId)

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
                workspaceCwd = conversation.workspaceCwd,
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
                    if (settings.enableWebSearch) {
                        addAll(createSearchTools(settings))
                    }
                    addAll(localTools.getTools(assistant.localTools))
                    addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), conversation.workspaceCwd))
                    if (assistant.enabledSkills.isNotEmpty()) {
                        addAll(
                            createSkillTools(
                                enabledSkills = assistant.enabledSkills,
                                allSkills = skillManager.listSkills(),
                                skillManager = skillManager,
                            )
                        )
                    }
                    mcpManager.getAllAvailableTools().forEach { (serverId, serverName, tool) ->
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
                // 取消 Live Update 通知
                cancelLiveUpdateNotification(conversationId)

                // 可能被取消了，或者意外结束，兜底更新
                val updatedConversation = getConversationFlow(conversationId).value.copy(
                    messageNodes = getConversationFlow(conversationId).value.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { it.finishReasoning() })
                    },
                    updateAt = Instant.now()
                )
                updateConversation(conversationId, updatedConversation)

                // Show notification if app is not in foreground
                if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration) {
                    sendGenerationDoneNotification(conversationId, senderName)
                }
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        // 群组对话：给每个 chunk message 打当前发言成员的 memberId + name
                        val stampedMessages = if (effectiveMemberId != null) {
                            val member = groupAssistant.groupMembers.find { it.id == effectiveMemberId }
                            val memberName = member?.displayName?.takeIf { it.isNotBlank() }
                            chunk.messages.toStorableGroupGeneratedMessages(
                                originalMessageIds = originalMessageIds,
                                effectiveMemberId = effectiveMemberId,
                                memberName = memberName,
                            )
                        } else chunk.messages
                        val currentConv = getConversationFlow(conversationId).value
                        val updatedConversation = currentConv.mergeMessages(stampedMessages)
                        updateConversation(conversationId, updatedConversation)

                        // 如果应用不在前台，发送 Live Update 通知
                        if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration && settings.displaySetting.enableLiveUpdateNotification) {
                            sendLiveUpdateNotification(conversationId, stampedMessages, senderName)
                        }
                    }
                }
            }
        }.onFailure {
            // 取消 Live Update 通知
            cancelLiveUpdateNotification(conversationId)

            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            val finalConversation = getConversationFlow(conversationId).value
            val conversationAfterRuntimeUpdate = if (
                groupAssistant.assistantType == AssistantType.GROUP &&
                effectiveMemberId != null
            ) {
                val runtimeWithDebug = finalConversation.groupRuntimeState.copy(
                    lastResolverDebug = dynamicContextResult?.debugState ?: finalConversation.groupRuntimeState.lastResolverDebug,
                )
                finalConversation.copy(
                    groupRuntimeState = GroupRuntimeStateUpdater().updateAfterReply(
                        previous = runtimeWithDebug,
                        groupAssistant = groupAssistant,
                        messages = finalConversation.currentMessages,
                        speakerId = effectiveMemberId,
                    )
                )
            } else {
                finalConversation
            }
            if (conversationAfterRuntimeUpdate !== finalConversation) {
                updateConversation(conversationId, conversationAfterRuntimeUpdate)
            }
            saveConversation(conversationId, conversationAfterRuntimeUpdate)

            // Only generate title/suggestions for main generation, not group member replies
            if (effectiveMemberId == null) {
                launchWithConversationReference(conversationId) {
                    generateTitle(conversationId, conversationAfterRuntimeUpdate)
                }
                launchWithConversationReference(conversationId) {
                    generateSuggestion(conversationId, conversationAfterRuntimeUpdate)
                }
            } else if (
                allowAutoChain &&
                groupAssistant.assistantType == AssistantType.GROUP &&
                groupAssistant.turnTakingStrategy != TurnTakingStrategy.MANUAL &&
                !isAddressedTurn
            ) {
                val configuredMaxReplies = groupAssistant.groupReplyOptions.maxAutoRepliesPerUserTurn.coerceAtLeast(1)
                val moderatorAutoCap = groupAssistant.groupMembers.count { it.enabled }
                    .coerceIn(1, 3)
                val maxReplies = if (groupAssistant.turnTakingStrategy == TurnTakingStrategy.AUTO_MODERATOR) {
                    configuredMaxReplies.coerceAtLeast(moderatorAutoCap)
                } else {
                    configuredMaxReplies
                }
                val alreadySent = countGroupRepliesSinceLastUserMessage(
                    conversationAfterRuntimeUpdate,
                    groupAssistant,
                )
                if (alreadySent < maxReplies) {
                    handleMessageComplete(
                        conversationId = conversationId,
                        allowAutoChain = true,
                    )
                }
            }
        }
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String? = null): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
    }

    // ---- 检查无效消息 ----

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool = node.currentMessage.getTools().any {
                    !it.isExecuted && it.approvalState.canResumeToolExecution()
                }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // Remove messages that still have unresolved tool approvals.
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            ),
            approvalState = ToolApprovalState.Denied("Generation cancelled by user")
        )
    }

    private suspend fun finishInterruptedPendingTools(conversationId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val updatedMessage = lastMessage.finishPendingTools(::cancelToolByUser)
        if (updatedMessage == lastMessage) {
            return
        }

        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { message ->
                    if (message.id == lastMessage.id) updatedMessage else message
                }
            )
        )
        saveConversation(conversationId, updatedConversation)
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.titleModelId, fallback = settings.fastModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

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

            // 生成完，conversation可能不是最新了，因此需要重新获取
            conversationRepo.getConversationById(conversation.id)?.let {
                saveConversation(
                    conversationId,
                    it.copy(title = result.choices[0].message?.toText()?.trim() ?: "")
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

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return
            val model = settings.findModelById(settings.suggestionModelId, fallback = settings.fastModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            sessions[conversationId]?.let { session ->
                updateConversation(
                    conversationId,
                    session.state.value.copy(chatSuggestions = emptyList())
                )
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
                result.choices[0].message?.toText()?.split("\n")?.map { it.trim() }
                    ?.filter { it.isNotBlank() } ?: emptyList()

            val latestConversation = conversationRepo.getConversationById(conversationId)
                ?: sessions[conversationId]?.state?.value
                ?: conversation
            saveConversation(
                conversationId,
                latestConversation.copy(
                    chatSuggestions = suggestions.take(
                        10
                    )
                )
            )
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

            return result.choices[0].message?.toText()?.trim()
                ?: throw IllegalStateException("Failed to generate compressed summary")
        }

        val compressedSummaries = coroutineScope {
            splitMessages(messagesToCompress)
                .map { chunk -> async { compressMessages(chunk) } }
                .awaitAll()
        }

        // Create new conversation with compressed history as multiple user messages + kept messages
        val newMessageNodes = buildList {
            compressedSummaries.forEach { summary ->
                add(UIMessage.user(summary).toMessageNode())
            }
            addAll(messagesToKeep.map { it.toMessageNode() })
        }
        val newConversation = conversation.copy(
            messageNodes = newMessageNodes,
            chatSuggestions = emptyList(),
        )

        saveConversation(conversationId, newConversation)
    }

    // ---- 通知 ----

    private fun sendGenerationDoneNotification(conversationId: Uuid, senderName: String) {
        // 先取消 Live Update 通知
        cancelLiveUpdateNotification(conversationId)

        val conversation = getConversationFlow(conversationId).value
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = 1
        ) {
            title = senderName
            content = conversation.currentMessages.lastOrNull()?.toText()?.take(50)?.trim() ?: ""
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, conversationId)
        }
    }

    private fun getLiveUpdateNotificationId(conversationId: Uuid): Int {
        return conversationId.hashCode() + 10000
    }

    private fun sendLiveUpdateNotification(
        conversationId: Uuid,
        messages: List<UIMessage>,
        senderName: String
    ) {
        val lastMessage = messages.lastOrNull() ?: return
        val parts = lastMessage.parts

        // 确定当前状态
        val (chipText, statusText, contentText) = determineNotificationContent(parts)

        context.sendNotification(
            channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            notificationId = getLiveUpdateNotificationId(conversationId)
        ) {
            title = senderName
            content = contentText
            subText = statusText
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_PROGRESS
            useBigTextStyle = true
            contentIntent = getPendingIntent(context, conversationId)
            requestPromotedOngoing = true
            shortCriticalText = chipText
        }
    }

    private fun determineNotificationContent(parts: List<UIMessagePart>): Triple<String, String, String> {
        // 检查最近的 part 来确定状态
        val lastReasoning = parts.filterIsInstance<UIMessagePart.Reasoning>().lastOrNull()
        val lastTool = parts.filterIsInstance<UIMessagePart.Tool>().lastOrNull()
        val lastText = parts.filterIsInstance<UIMessagePart.Text>().lastOrNull()

        return when {
            // 正在执行工具
            lastTool != null && !lastTool.isExecuted -> {
                val toolName = lastTool.toolName.substringAfterLast("__")
                Triple(
                    context.getString(R.string.notification_live_update_chip_tool),
                    context.getString(R.string.notification_live_update_tool, toolName),
                    lastTool.input.take(100)
                )
            }
            // 正在思考（Reasoning 未结束）
            lastReasoning != null && lastReasoning.finishedAt == null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_thinking),
                    context.getString(R.string.notification_live_update_thinking),
                    lastReasoning.reasoning.takeLast(200)
                )
            }
            // 正在写回复
            lastText != null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_writing),
                    lastText.text.takeLast(200)
                )
            }
            // 默认状态
            else -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_title),
                    ""
                )
            }
        }
    }

    private fun cancelLiveUpdateNotification(conversationId: Uuid) {
        context.cancelNotification(getLiveUpdateNotificationId(conversationId))
    }

    private fun getPendingIntent(context: Context, conversationId: Uuid): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // ---- 对话状态更新 ----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        checkFilesDelete(conversation, session.state.value)
        session.state.value = conversation
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
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

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }

        val updatedConversation = conversation.copy()
        updateConversation(conversationId, updatedConversation)

        if (!exists) {
            conversationRepo.insertConversation(updatedConversation)
        } else {
            conversationRepo.updateConversation(updatedConversation)
        }
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

                // Save the conversation after translation is complete
                saveConversation(conversationId, getConversationFlow(conversationId).value)
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
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = translationText)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

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
        val processedParts = preprocessUserInputParts(parts, assistant)
        var edited = false

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) {
                return@map node
            }
            edited = true

            node.copy(
                messages = node.messages + UIMessage(
                    role = node.role,
                    parts = processedParts,
                ),
                selectIndex = node.messages.size
            )
        }

        if (!edited) return

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        val forkConversation = Conversation(
            id = Uuid.random(),
            assistantId = currentConversation.assistantId,
            messageNodes = copiedNodes,
            customSystemPrompt = currentConversation.customSystemPrompt,
            modeInjectionIds = currentConversation.modeInjectionIds,
            lorebookIds = currentConversation.lorebookIds,
        )

        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.id == nodeId) {
                node.copy(selectIndex = selectIndex)
            } else {
                node
            }
        }

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        saveConversation(conversationId, updatedConversation)
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
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = null)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        val job = sessions[conversationId]?.getJob() ?: return
        job.cancel()
        runCatching { job.join() }
        finishInterruptedPendingTools(conversationId)
    }

    private fun Conversation.removeGroupContinuationNudgeNodes(): Conversation {
        var changed = false
        val cleanedNodes = messageNodes.mapNotNull { node ->
            val selectedMessageId = runCatching { node.currentMessage.id }.getOrNull()
            val filteredMessages = node.messages.filterNot { message ->
                message.toText().isGroupContinuationNudge()
            }
            if (filteredMessages.size != node.messages.size) changed = true
            if (filteredMessages.isEmpty()) {
                null
            } else {
                val selectedIndex = filteredMessages.indexOfFirst { it.id == selectedMessageId }
                    .takeIf { it >= 0 }
                    ?: node.selectIndex.coerceAtMost(filteredMessages.lastIndex)
                node.copy(messages = filteredMessages, selectIndex = selectedIndex)
            }
        }
        if (cleanedNodes.size != messageNodes.size) changed = true
        return if (changed) copy(messageNodes = cleanedNodes) else this
    }

    // ---- 群组发言决策 ----

    /** 取下一个轮转发言者（round-robin），不修改 conversation。 */
    private fun getNextSpeakerRoundRobin(conversation: Conversation): Uuid? {
        val queue = conversation.groupMemberQueue
        if (queue.isEmpty()) return null
        val nextIndex = (conversation.groupMemberQueueIndex + 1) % queue.size
        return queue[nextIndex]
    }

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

    private fun getNextDifferentSpeaker(
        queue: List<Uuid>,
        currentSpeakerId: Uuid?,
    ): Uuid? {
        if (queue.isEmpty()) return null
        if (currentSpeakerId == null) return queue.firstOrNull()
        return queue.firstOrNull { it != currentSpeakerId } ?: queue.firstOrNull()
    }

    /** 解析下一个发言者：依据助手的 turnTakingStrategy。返回 null 表示无可用成员。 */
    private suspend fun resolveNextSpeaker(
        conversation: Conversation,
        groupAssistant: Assistant,
        settings: me.rerere.rikkahub.data.datastore.Settings,
        allowModeratorStop: Boolean = false,
    ): Uuid? {
        when (groupAssistant.turnTakingStrategy) {
            TurnTakingStrategy.MANUAL -> return conversation.activeGroupMemberId
                ?: groupAssistant.groupMembers.firstOrNull { it.enabled }?.id
            TurnTakingStrategy.AUTO_ROUND_ROBIN -> {
                val preferredId = getNextSpeakerRoundRobin(conversation)
                    ?: groupAssistant.groupMembers.firstOrNull { it.enabled }?.id
                val nextId = if (!groupAssistant.groupReplyOptions.allowConsecutiveSameSpeaker) {
                    val activeId = conversation.activeGroupMemberId
                    if (preferredId != null && preferredId == activeId) {
                        getNextDifferentSpeaker(
                            conversation.groupMemberQueue.ifEmpty {
                                groupAssistant.groupMembers.filter { it.enabled }.map { it.id }
                            },
                            activeId,
                        ) ?: preferredId
                    } else {
                        preferredId
                    }
                } else {
                    preferredId
                }
                if (nextId != null) {
                    val queue = conversation.groupMemberQueue.ifEmpty {
                        groupAssistant.groupMembers.filter { it.enabled }.map { it.id }
                    }
                    val nextIndex = (conversation.groupMemberQueueIndex + 1) % queue.size.coerceAtLeast(1)
                    saveConversation(conversation.id, conversation.copy(
                        activeGroupMemberId = nextId,
                        groupMemberQueue = queue,
                        groupMemberQueueIndex = nextIndex,
                    ))
                }
                return nextId
            }
            TurnTakingStrategy.AUTO_MODERATOR -> {
                val resolved = resolveNextSpeakerViaModerator(
                    conversation = conversation,
                    groupAssistant = groupAssistant,
                    settings = settings,
                    allowStop = allowModeratorStop,
                )
                val queue = conversation.groupMemberQueue.ifEmpty {
                    groupAssistant.groupMembers.filter { it.enabled }.map { it.id }
                }
                val activeId = conversation.activeGroupMemberId
                val nextId = if (groupAssistant.groupReplyOptions.allowConsecutiveSameSpeaker) {
                    resolved
                } else if (resolved != null && resolved == activeId) {
                    getNextDifferentSpeaker(queue, activeId)
                } else {
                    resolved
                }
                if (nextId != null) {
                    val nextIndex = queue.indexOf(nextId).takeIf { it >= 0 } ?: 0
                    saveConversation(conversation.id, conversation.copy(
                        activeGroupMemberId = nextId,
                        groupMemberQueue = queue,
                        groupMemberQueueIndex = nextIndex,
                    ))
                }
                return nextId
            }
        }
    }

    /** AUTO_MODERATOR：用一个轻量模型决定下一发言者，失败时回退到 round-robin。 */
    private suspend fun resolveNextSpeakerViaModerator(
        conversation: Conversation,
        groupAssistant: Assistant,
        settings: me.rerere.rikkahub.data.datastore.Settings,
        allowStop: Boolean,
    ): Uuid? {
        val enabled = groupAssistant.groupMembers.filter { it.enabled }
        if (enabled.isEmpty()) return null
        if (enabled.size == 1) return enabled.first().id
        val localScores = GroupSpeakerScorer().score(
            groupAssistant = groupAssistant,
            messages = conversation.currentMessages,
            runtimeState = conversation.groupRuntimeState,
            activeMemberId = conversation.activeGroupMemberId,
        )
        val localFallback = localScores.firstOrNull()?.memberId ?: getNextSpeakerRoundRobin(conversation)

        val descriptions = enabled.joinToString("\n") { m ->
            val source = settings.getAssistantById(m.assistantId)
            val name = m.displayName.ifBlank { source?.name ?: "Unknown" }
            "- ID:${m.id} | $name"
        }
        val prompt = buildString {
            appendLine("You are a conversation moderator. Decide which character should speak next.")
            if (allowStop) {
                appendLine("Reply ONLY with a character ID (UUID), or STOP if the current user turn has already been answered enough.")
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
            val responseText = result.choices.firstOrNull()?.message
                ?.parts?.filterIsInstance<UIMessagePart.Text>()
                ?.joinToString("") { it.text }?.trim().orEmpty()
            parseGroupModeratorDecision(
                responseText = responseText,
                enabledMembers = enabled,
                localFallback = localFallback,
                allowStop = allowStop,
            )
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

