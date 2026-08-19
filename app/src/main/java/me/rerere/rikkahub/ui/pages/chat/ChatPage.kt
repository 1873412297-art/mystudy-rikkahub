package me.rerere.rikkahub.ui.pages.chat

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.BookOpen01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.trace.isTavernPromptTraceEligible
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.TurnTakingStrategy
import me.rerere.rikkahub.data.model.TavernCharacterCard
import me.rerere.rikkahub.service.tavern.requiresNewConversationForGreetingChange
import me.rerere.rikkahub.data.model.inferLegacyOpening
import me.rerere.rikkahub.data.model.tavernOpeningRef
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.group.GroupDirectorCommandStatus
import me.rerere.rikkahub.service.tavern.resolveGreetingNavigation
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.ai.FilesPicker
import me.rerere.rikkahub.ui.components.ai.completion.GroupMentionCompletionProvider
import me.rerere.rikkahub.ui.components.ai.completion.WorkspaceCompletionProvider
import me.rerere.rikkahub.ui.components.ai.useCropLauncher
import me.rerere.rikkahub.ui.components.message.ChatMessageActionsSheet
import me.rerere.rikkahub.ui.components.message.ChatMessageCopySheet
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.pages.tavern.console.TavernPromptConsoleEntry
import me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationActions
import me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationPane
import me.rerere.rikkahub.ui.pages.chat.tavern.TavernPresentationMode
import me.rerere.rikkahub.ui.pages.chat.tavern.TavernOpeningStage
import me.rerere.rikkahub.ui.pages.chat.tavern.resolveTavernPresentation
import me.rerere.rikkahub.ui.pages.chat.tavern.requiresTavernRegenerateConfirmation
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.base64Decode
import me.rerere.rikkahub.utils.isAllowedFileType
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.io.File
import kotlin.uuid.Uuid
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun ChatPage(
    id: Uuid,
    text: String?,
    files: List<Uri>,
    nodeId: Uuid? = null,
    greetingIndex: Int? = null,
    greeting: String? = null,
) {
    val vm: ChatVM = koinViewModel(
        parameters = {
            parametersOf(id.toString())
        }
    )
    val filesManager: FilesManager = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()
    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val enableWebSearch by vm.enableWebSearch.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val greetingSession by vm.greetingSession.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val softwareKeyboardController = LocalSoftwareKeyboardController.current

    // Handle back press when drawer is open
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch {
            drawerState.close()
        }
    }

    // Hide keyboard when drawer is open
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            softwareKeyboardController?.hide()
        }
    }

    val windowAdaptiveInfo = currentWindowDpSize()
    val isBigScreen =
        windowAdaptiveInfo.width > windowAdaptiveInfo.height && windowAdaptiveInfo.width >= 1100.dp

    // 进入大屏（永久抽屉）模式时重置抽屉状态为关闭，
    // 避免从横屏旋转回竖屏后，模态抽屉残留为打开状态且无法关闭（#1304）
    LaunchedEffect(isBigScreen) {
        if (isBigScreen && drawerState.isOpen) {
            drawerState.close()
        }
    }

    val inputState = vm.inputState

    // 初始化输入状态（处理传入的 files 和 text 参数）
    var greetingRouteHandled by remember(conversation.id, greetingIndex, greeting) { mutableStateOf(false) }
    LaunchedEffect(greetingIndex, greeting, setting.assistants, conversation.assistantId) {
        if (greetingRouteHandled) return@LaunchedEffect
        if (requiresNewConversationForGreetingChange(conversation)) {
            greetingRouteHandled = true
            return@LaunchedEffect
        }
        val assistant = setting.getAssistantById(conversation.assistantId) ?: setting.getCurrentAssistant()
        val greetings = assistant.tavernCardJson
            ?.let(TavernCharacterCard::fromJson)
            ?.allGreetings()
            .orEmpty()
        resolveGreetingNavigation(greetingIndex, greeting, greetings)?.let { navigation ->
            greetingRouteHandled = true
            if (navigation.greetingIndex != null) {
                vm.selectInitialGreeting(navigation.greetingIndex)
            } else if (!navigation.legacyGreeting.isNullOrBlank()) {
                vm.applyInitialGreeting(navigation.legacyGreeting)
            }
        }
    }

    LaunchedEffect(files, text) {
        if (files.isNotEmpty()) {
            val localFiles = filesManager.createChatFilesByContents(files)
            val contentTypes = files.mapNotNull { file ->
                filesManager.getFileMimeType(file)
            }
            val parts = buildList {
                localFiles.forEachIndexed { index, file ->
                    val type = contentTypes.getOrNull(index)
                    if (type?.startsWith("image/") == true) {
                        add(UIMessagePart.Image(url = file.toString()))
                    } else if (type?.startsWith("video/") == true) {
                        add(UIMessagePart.Video(url = file.toString()))
                    } else if (type?.startsWith("audio/") == true) {
                        add(UIMessagePart.Audio(url = file.toString()))
                    }
                }
            }
            inputState.messageContent = parts
        }
        text?.base64Decode()?.let { decodedText ->
            if (decodedText.isNotEmpty()) {
                inputState.setMessageText(decodedText)
            }
        }
    }

    val chatListState = rememberLazyListState()
    LaunchedEffect(nodeId, conversation.messageNodes.size) {
        if (!vm.chatListInitialized && conversation.messageNodes.isNotEmpty()) {
            if (nodeId != null) {
                val index = conversation.messageNodes.indexOfFirst { it.id == nodeId }
                if (index >= 0) {
                    chatListState.scrollToItem(index)
                }
            } else {
                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
            }
            vm.chatListInitialized = true
        }
    }

    when {
        isBigScreen -> {
            PermanentNavigationDrawer(
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting
                    )
                }
            ) {
                ChatPageContent(
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    bigScreen = true,
                    errors = errors,
                    greetingSession = greetingSession,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
        }

        else -> {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting
                    )
                }
            ) {
                ChatPageContent(
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    bigScreen = false,
                    errors = errors,
                    greetingSession = greetingSession,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
            BackHandler(drawerState.isOpen) {
                scope.launch { drawerState.close() }
            }
        }
    }
}

@Composable
private fun ChatPageContent(
    inputState: ChatInputState,
    loadingJob: Job?,
    processingStatus: String? = null,
    setting: Settings,
    bigScreen: Boolean,
    conversation: Conversation,
    drawerState: DrawerState,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    enableWebSearch: Boolean,
    currentChatModel: Model?,
    errors: List<ChatError>,
    greetingSession: me.rerere.rikkahub.service.tavern.TavernGreetingSession?,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    var previewMode by rememberSaveable { mutableStateOf(false) }
    var forceComposeTavern by rememberSaveable(conversation.id) { mutableStateOf(false) }
    var tavernActionMessageId by remember { mutableStateOf<Uuid?>(null) }
    var tavernCopyMessageId by remember { mutableStateOf<Uuid?>(null) }
    var tavernFullscreenMessageId by remember { mutableStateOf<Uuid?>(null) }
    var tavernRegenerateMessageId by remember { mutableStateOf<Uuid?>(null) }
    var showGreetingSwitchDialog by rememberSaveable { mutableStateOf(false) }
    val hazeState = rememberHazeState()
    val assistant = remember(setting.assistants, conversation.assistantId) {
        setting.getAssistantById(conversation.assistantId) ?: setting.getCurrentAssistant()
    }
    val tavernCard = remember(assistant.tavernCardJson) {
        assistant.tavernCardJson?.let(TavernCharacterCard::fromJson)
    }
    val currentOpeningMessage = remember(conversation, tavernCard) {
        conversation.currentMessages.firstOrNull { message ->
            message.parts.filterIsInstance<UIMessagePart.Text>().singleOrNull()?.tavernOpeningRef() != null ||
                (tavernCard != null && inferLegacyOpening(message, tavernCard) != null)
        }
    }
    val hasUserMessage = conversation.currentMessages.any { it.role == MessageRole.USER }
    val tavernPromptTraceEligible = remember(assistant, setting.assistants) {
        assistant.isTavernPromptTraceEligible(setting.assistants)
    }
    val groupAssistant = assistant.takeIf { it.assistantType == AssistantType.GROUP }
    val directorUiState = remember(conversation, groupAssistant, setting, loadingJob) {
        groupAssistant?.let {
            buildGroupDirectorUiState(
                conversation = conversation,
                assistant = it,
                settings = setting,
                isGenerating = loadingJob?.isActive == true,
            )
        }
    }
    var showDirectorSheet by rememberSaveable { mutableStateOf(false) }
    val enabledManualMembers = groupAssistant?.groupMembers?.filter { it.enabled }.orEmpty()
    val availableManualMemberIds = remember(enabledManualMembers) {
        enabledManualMembers.map { it.id }
    }
    val selectedIds = vm.selectedGroupMemberIds.collectAsStateWithLifecycle().value
    val isManualGroup = groupAssistant != null &&
        directorUiState?.effectiveMode == TurnTakingStrategy.MANUAL &&
        enabledManualMembers.isNotEmpty()
    var showFilesSheet by remember { mutableStateOf(false) }
    val completionProviders = remember(
        assistant.workspaceId,
        assistant.groupMembers,
        assistant.assistantType,
        conversation.workspaceCwd,
        workspaceRepository,
    ) {
        buildList {
            assistant.workspaceId?.let { workspaceId ->
                add(
                    WorkspaceCompletionProvider(
                        workspaceId = workspaceId.toString(),
                        repository = workspaceRepository,
                        currentCwd = conversation.workspaceCwd,
                    )
                )
            }
            if (assistant.assistantType == AssistantType.GROUP) {
                add(GroupMentionCompletionProvider(assistant.groupMembers))
            }
        }
    }
    val onMentionRole: (String) -> Unit = remember(inputState) {
        { roleName ->
            if (roleName.isNotBlank()) {
                inputState.insertTextAtCursor("@$roleName ")
            }
        }
    }

    val context = LocalContext.current
    val latestConversation by androidx.compose.runtime.rememberUpdatedState(conversation)
    val tavernActions = remember(vm, conversation.id) {
        object : TavernConversationActions {
            override fun onMessageLongPress(messageId: Uuid) {
                tavernActionMessageId = messageId
            }

            override fun onSelectBranch(nodeId: Uuid, index: Int) {
                val node = latestConversation.messageNodes.firstOrNull { it.id == nodeId } ?: return
                if (index in node.messages.indices) vm.selectMessageBranch(nodeId, index)
            }

            override fun onOpenHtml(messageId: Uuid) {
                tavernFullscreenMessageId = messageId
            }

            override fun onFallbackRequested() {
                forceComposeTavern = true
            }
        }
    }
    LaunchedEffect(vm) {
        vm.groupDirectorNotices.collect { status ->
            val message = when (status) {
                GroupDirectorCommandStatus.NO_ENABLED_MEMBERS -> R.string.group_director_no_members
                GroupDirectorCommandStatus.INVALID_MEMBER -> R.string.group_director_invalid_member
                GroupDirectorCommandStatus.NO_ALTERNATIVE_MEMBER -> R.string.group_director_no_alternative
                GroupDirectorCommandStatus.NOT_GROUP -> R.string.group_director_not_group
                GroupDirectorCommandStatus.APPLIED -> return@collect
            }
            toaster.show(context.getString(message))
        }
    }

    LaunchedEffect(availableManualMemberIds) {
        vm.sanitizeGroupMemberSelection(availableManualMemberIds)
    }

    TTSAutoPlay(vm = vm, setting = setting, conversation = conversation)

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        AssistantBackground(setting = setting, modifier = Modifier.hazeSource(hazeState))
        Scaffold(
            topBar = {
                TopBar(
                    settings = setting,
                    conversation = conversation,
                    bigScreen = bigScreen,
                    drawerState = drawerState,
                    previewMode = previewMode,
                    tavernPromptTraceEligible = tavernPromptTraceEligible,
                    onOpenOpening = currentOpeningMessage?.takeIf { hasUserMessage }?.let { opening ->
                        { tavernFullscreenMessageId = opening.id }
                    },
                    onNewChat = {
                        navigateToChatPage(navController)
                    },
                    onOpenTavernPromptConsole = {
                        navController.navigate(
                            Screen.TavernPromptConsole(conversation.id.toString())
                        )
                    },
                    onClickMenu = {
                        previewMode = !previewMode
                    },
                    onUpdateTitle = {
                        vm.updateTitle(it)
                    }
                )
            },
            bottomBar = {
                Column {
                    if (isManualGroup) {
                        GroupMemberSelector(
                            members = enabledManualMembers,
                            selectedMemberIds = selectedIds,
                            settings = setting,
                            onToggle = { vm.toggleGroupMember(it) },
                            onSelectionChange = { vm.setGroupMemberSelection(it) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    ChatInput(
                    state = inputState,
                    loading = loadingJob != null,
                    settings = setting,
                    hazeState = hazeState,
                    completionProviders = completionProviders,
                    onCancelClick = {
                        vm.stopGeneration()
                    },
                    enableSearch = enableWebSearch,
                    onToggleSearch = {
                        val current = setting.getCurrentAssistant()
                        vm.updateSettings(
                            setting.copy(
                                assistants = setting.assistants.map { assistant ->
                                    if (assistant.id == current.id) {
                                        assistant.copy(enableWebSearch = !enableWebSearch)
                                    } else {
                                        assistant
                                    }
                                }
                            )
                        )
                    },
                    onSendClick = {
                        if (currentChatModel == null) {
                            toaster.show("请先选择模型", type = ToastType.Error)
                            return@ChatInput
                        }
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            if (isManualGroup) {
                                if (selectedIds.isNotEmpty()) {
                                    vm.handleGroupSend(content = inputState.getContents())
                                } else {
                                    toaster.show("请先选择发言角色")
                                    return@ChatInput
                                }
                            } else {
                                vm.handleMessageSend(inputState.getContents())
                                scope.launch {
                                    chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                                }
                            }
                        }
                        inputState.clearInput()
                    },
                    onLongSendClick = {
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(content = inputState.getContents(), answer = false)
                            scope.launch {
                                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                            }
                        }
                        inputState.clearInput()
                    },
                    onUpdateChatModel = {
                        vm.setChatModel(assistant = setting.getCurrentAssistant(), model = it)
                    },
                    onUpdateAssistant = {
                        vm.updateSettings(
                            setting.copy(
                                assistants = setting.assistants.map { assistant ->
                                    if (assistant.id == it.id) {
                                        it
                                    } else {
                                        assistant
                                    }
                                }
                            )
                        )
                    },
                    onUpdateSearchService = { index ->
                        vm.updateSettings(
                            setting.copy(
                                searchServiceSelected = index
                            )
                        )
                    },
                    onMoreClick = {
                        showFilesSheet = true
                    },
                )
                }
            },
            floatingActionButton = {
                directorUiState?.let { state ->
                    GroupDirectorFab(
                        state = state,
                        onClick = { showDirectorSheet = true },
                    )
                }
            },
            containerColor = Color.Transparent,
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
            // 动态状态栏（HUD）：最近一条含状态块的 assistant 消息的状态
            StatusHudBar(
                conversation = conversation,
                onOptionClick = { optionText ->
                    if (loadingJob == null && currentChatModel != null && !isManualGroup) {
                        // 点击选项 = 直接作为用户消息发送（复用聊天页发送链路）
                        vm.handleMessageSend(listOf(UIMessagePart.Text(optionText)))
                        scope.launch {
                            chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                        }
                    } else {
                        // 生成中 / 未选模型 / 手动群聊需选人：退化为填入输入框
                        inputState.setMessageText(optionText)
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            val activeGreetingSession = greetingSession?.takeIf {
                !it.isLocked && it.candidates.isNotEmpty() && !hasUserMessage
            }
            val tavernDecision = remember(assistant, conversation) {
                resolveTavernPresentation(assistant, conversation)
            }
            val useTavernWeb = !previewMode && !forceComposeTavern &&
                tavernDecision.mode == TavernPresentationMode.ST_WEB
            if (activeGreetingSession != null && !previewMode && !forceComposeTavern) {
                TavernOpeningStage(
                    session = activeGreetingSession,
                    conversation = conversation,
                    assistant = assistant,
                    settings = setting,
                    onCommit = vm::commitGreetingCandidate,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else if (useTavernWeb) {
                TavernConversationPane(
                    conversation = conversation,
                    assistant = assistant,
                    settings = setting,
                    loading = loadingJob != null,
                    actions = tavernActions,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else {
                val showTavernFallbackReason = assistant.tavernCardJson?.isNotBlank() == true &&
                    assistant.assistantType == AssistantType.SOLO && !previewMode
                if (showTavernFallbackReason) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                            Text(
                                text = if (forceComposeTavern) {
                                    "已切换兼容视图"
                                } else {
                                    tavernDecision.fallbackReason.orEmpty()
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                            if (forceComposeTavern && tavernDecision.mode == TavernPresentationMode.ST_WEB) {
                                TextButton(onClick = { forceComposeTavern = false }) {
                                    Text("重试酒馆视图")
                                }
                            }
                        }
                    }
                }
                ChatList(
                    innerPadding = PaddingValues(0.dp),
                    conversation = conversation,
                    state = chatListState,
                    loading = loadingJob != null,
                    processingStatus = processingStatus,
                    previewMode = previewMode,
                    settings = setting,
                    hazeState = hazeState,
                    errors = errors,
                    onDismissError = onDismissError,
                    onClearAllErrors = onClearAllErrors,
                    onRegenerate = {
                        vm.regenerateAtMessage(it)
                    },
                    onEdit = {
                        inputState.editingMessage = it.id
                        inputState.setContents(it.parts)
                    },
                    onForkMessage = {
                        scope.launch {
                            val fork = vm.forkMessage(message = it)
                            navigateToChatPage(navController, chatId = fork.id)
                        }
                    },
                    onDelete = {
                        if (loadingJob != null) {
                            vm.showDeleteBlockedWhileGeneratingError()
                        } else {
                            vm.deleteMessage(it)
                        }
                    },
                    onUpdateMessage = { newNode ->
                        vm.updateConversation(
                            conversation.copy(
                                messageNodes = conversation.messageNodes.map { node ->
                                    if (node.id == newNode.id) {
                                        newNode
                                    } else {
                                        node
                                    }
                                }
                            ))
                        vm.saveConversationAsync()
                    },
                    onClickSuggestion = { suggestion ->
                        inputState.editingMessage = null
                        inputState.setMessageText(suggestion)
                    },
                    onTranslate = { message, locale ->
                        vm.translateMessage(message, locale)
                    },
                    onClearTranslation = { message ->
                        vm.clearTranslationField(message.id)
                    },
                    onJumpToMessage = { index ->
                        previewMode = false
                        scope.launch {
                            chatListState.requestScrollToItem(index)
                        }
                    },
                    onToolApproval = { toolCallId, approved, reason ->
                        vm.handleToolApproval(toolCallId, approved, reason)
                    },
                    onToolAnswer = { toolCallId, answer ->
                        vm.handleToolAnswer(toolCallId, answer)
                    },
                    onToggleFavorite = { node ->
                        vm.toggleMessageFavorite(node)
                    },
                    onConversationSystemPromptChange = { newPrompt ->
                        vm.updateConversation(conversation.copy(customSystemPrompt = newPrompt))
                        vm.saveConversationAsync()
                    },
                    onConversationAuthorNoteChange = { note ->
                        vm.updateConversation(conversation.copy(authorNote = note))
                        vm.saveConversationAsync()
                    },
                    onMentionRole = onMentionRole,
                )
            }
        }
        }

        if (showDirectorSheet && directorUiState != null) {
            GroupDirectorSheet(
                state = directorUiState,
                onDismiss = { showDirectorSheet = false },
                onCommand = vm::applyGroupDirectorCommand,
            )
        }

        if (showFilesSheet) {
            ChatFilesPickerSheet(
                inputState = inputState,
                setting = setting,
                conversation = conversation,
                assistant = assistant,
                vm = vm,
                onDismiss = { showFilesSheet = false },
            )
        }

        val actionMessage = conversation.currentMessages.firstOrNull { it.id == tavernActionMessageId }
        val actionNode = conversation.messageNodes.firstOrNull { it.currentMessage.id == tavernActionMessageId }
        if (actionMessage != null && actionNode != null) {
            ChatMessageActionsSheet(
                message = actionMessage,
                model = actionMessage.modelId?.let { modelId ->
                    setting.providers.flatMap { it.models }.firstOrNull { it.id == modelId }
                },
                onDelete = {
                    tavernActionMessageId = null
                    if (loadingJob != null) vm.showDeleteBlockedWhileGeneratingError() else vm.deleteMessage(actionMessage)
                },
                onEdit = {
                    tavernActionMessageId = null
                    inputState.editingMessage = actionMessage.id
                    inputState.setContents(actionMessage.parts)
                },
                onShare = {
                    tavernActionMessageId = null
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, actionMessage.toText())
                    }
                    context.startActivity(Intent.createChooser(share, context.getString(R.string.share)))
                },
                onFork = {
                    tavernActionMessageId = null
                    scope.launch {
                        val fork = vm.forkMessage(actionMessage)
                        navigateToChatPage(navController, chatId = fork.id)
                    }
                },
                onSelectAndCopy = {
                    tavernActionMessageId = null
                    tavernCopyMessageId = actionMessage.id
                },
                onRegenerate = {
                    tavernActionMessageId = null
                    if (requiresTavernRegenerateConfirmation(actionMessage.role)) {
                        tavernRegenerateMessageId = actionMessage.id
                    } else {
                        vm.regenerateAtMessage(actionMessage)
                    }
                },
                isFavorite = actionNode.isFavorite,
                onToggleFavorite = {
                    tavernActionMessageId = null
                    vm.toggleMessageFavorite(actionNode)
                },
                onWebViewPreview = {
                    tavernActionMessageId = null
                    tavernFullscreenMessageId = actionMessage.id
                },
                onDismissRequest = { tavernActionMessageId = null },
            )
        }

        conversation.currentMessages.firstOrNull { it.id == tavernCopyMessageId }?.let { message ->
            ChatMessageCopySheet(
                message = message,
                onDismissRequest = { tavernCopyMessageId = null },
            )
        }

        val tavernRegenerateMessage = conversation.currentMessages.firstOrNull {
            it.id == tavernRegenerateMessageId && it.role == MessageRole.USER
        }
        RikkaConfirmDialog(
            show = tavernRegenerateMessage != null,
            title = stringResource(R.string.regenerate),
            confirmText = stringResource(R.string.confirm),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                tavernRegenerateMessageId = null
                tavernRegenerateMessage?.let(vm::regenerateAtMessage)
            },
            onDismiss = { tavernRegenerateMessageId = null },
            text = { Text(stringResource(R.string.regenerate_confirm_message)) },
        )

        val fullscreenNode = conversation.messageNodes.firstOrNull { node ->
            node.messages.any { it.id == tavernFullscreenMessageId }
        }
        if (fullscreenNode != null && assistant.tavernCardJson?.isNotBlank() == true) {
            Dialog(
                onDismissRequest = { tavernFullscreenMessageId = null },
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                        ) {
                            IconButton(onClick = { tavernFullscreenMessageId = null }) {
                                Icon(HugeIcons.Cancel01, contentDescription = "关闭")
                            }
                            if (
                                hasUserMessage &&
                                currentOpeningMessage?.id == tavernFullscreenMessageId &&
                                (tavernCard?.allGreetings()?.size ?: 0) > 1
                            ) {
                                TextButton(onClick = { showGreetingSwitchDialog = true }) {
                                    Text("从其他开场新建对话")
                                }
                            }
                        }
                        TavernConversationPane(
                            conversation = conversation,
                            assistant = assistant,
                            settings = setting,
                            loading = false,
                            actions = tavernActions,
                            visibleMessageId = tavernFullscreenMessageId,
                            ownsSendHookController = false,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                }
            }
        }

        if (showGreetingSwitchDialog && tavernCard != null) {
            AlertDialog(
                onDismissRequest = { showGreetingSwitchDialog = false },
                title = { Text("选择新对话的开场") },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text("已有对话不会被修改。选择后会新建并打开一个独立对话。")
                        tavernCard.allGreetings().forEachIndexed { index, opening ->
                            TextButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    scope.launch {
                                        val newConversationId = vm.createConversationFromGreeting(
                                            assistantId = assistant.id,
                                            greetingIndex = index,
                                        )
                                        showGreetingSwitchDialog = false
                                        tavernFullscreenMessageId = null
                                        navigateToChatPage(navController, chatId = newConversationId)
                                    }
                                },
                            ) {
                                Text(
                                    text = "${index + 1}. ${opening.lineSequence().firstOrNull().orEmpty().take(80)}",
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showGreetingSwitchDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun ChatFilesPickerSheet(
    inputState: ChatInputState,
    setting: Settings,
    conversation: Conversation,
    assistant: Assistant,
    vm: ChatVM,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val filesManager: FilesManager = koinInject()
    var showInjectionSheet by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }

    fun dismissAll() {
        showInjectionSheet = false
        showCompressDialog = false
        onDismiss()
    }

    val cameraPermission = rememberPermissionState(PermissionCamera)
    PermissionManager(permissionState = cameraPermission)

    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    var cameraOutputFile by remember { mutableStateOf<File?>(null) }
    val (_, launchCameraCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            inputState.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            dismissAll()
        },
        onCleanup = {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    )
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captureSuccessful ->
        if (captureSuccessful && cameraOutputUri != null) {
            if (setting.displaySetting.skipCropImage) {
                inputState.addImages(filesManager.createChatFilesByContents(listOf(cameraOutputUri!!)))
                cameraOutputFile?.delete()
                cameraOutputFile = null
                cameraOutputUri = null
                dismissAll()
            } else {
                launchCameraCrop(cameraOutputUri!!)
            }
        } else {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    }
    val onLaunchCamera: () -> Unit = {
        if (cameraPermission.allRequiredPermissionsGranted) {
            cameraOutputFile = context.cacheDir.resolve("camera_${Uuid.random()}.jpg")
            cameraOutputUri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", cameraOutputFile!!
            )
            cameraLauncher.launch(cameraOutputUri!!)
        } else {
            cameraPermission.requestPermissions()
        }
    }

    var preCropTempFile by remember { mutableStateOf<File?>(null) }
    val (_, launchImageCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            inputState.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            dismissAll()
        },
        onCleanup = {
            preCropTempFile?.delete()
            preCropTempFile = null
        }
    )
    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                Log.d("ImagePickButton", "Selected URIs: $selectedUris")
                if (setting.displaySetting.skipCropImage) {
                    inputState.addImages(filesManager.createChatFilesByContents(selectedUris))
                    dismissAll()
                } else if (selectedUris.size == 1) {
                    val tempFile = File(context.appTempFolder, "pick_temp_${System.currentTimeMillis()}.jpg")
                    runCatching {
                        val source = selectedUris.first()
                        // HEIF/HEIC（尤其 HDR HEIF）交给 UCrop 前先解码转为 JPEG，规避裁剪解码失败
                        val converted = ImageUtils.isHeifImage(context, source) &&
                            ImageUtils.convertHeifToJpeg(context, source, tempFile)
                        if (!converted) {
                            context.contentResolver.openInputStream(source)?.use { input ->
                                tempFile.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                        preCropTempFile = tempFile
                        launchImageCrop(tempFile.toUri())
                    }.onFailure {
                        Log.e("ImagePickButton", "Failed to copy image to temp, falling back", it)
                        launchImageCrop(selectedUris.first())
                    }
                } else {
                    inputState.addImages(filesManager.createChatFilesByContents(selectedUris))
                    dismissAll()
                }
            } else {
                Log.d("ImagePickButton", "No images selected")
            }
        }

    val videoPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                inputState.addVideos(filesManager.createChatFilesByContents(selectedUris))
                dismissAll()
            }
        }

    val audioPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                inputState.addAudios(filesManager.createChatFilesByContents(selectedUris))
                dismissAll()
            }
        }

    val filePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                val documents = uris.mapNotNull { uri ->
                    val fileName = filesManager.getFileNameFromUri(uri) ?: "file"
                    val mime = filesManager.getFileMimeType(uri) ?: "text/plain"
                    if (isAllowedFileType(fileName, mime)) {
                        val localUri = filesManager.createChatFilesByContents(listOf(uri)).firstOrNull()
                            ?: run {
                                toaster.show(
                                    context.getString(R.string.chat_input_file_read_failed, fileName),
                                    type = ToastType.Error
                                )
                                return@mapNotNull null
                            }
                        UIMessagePart.Document(url = localUri.toString(), fileName = fileName, mime = mime)
                    } else {
                        toaster.show(
                            context.getString(R.string.chat_input_unsupported_file_type, fileName),
                            type = ToastType.Error
                        )
                        null
                    }
                }
                if (documents.isNotEmpty()) {
                    inputState.addFiles(documents)
                    dismissAll()
                }
            }
        }

    val filesSheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    ModalBottomSheet(
        sheetState = filesSheetState,
        onDismissRequest = { dismissAll() },
    ) {
        FilesPicker(
            conversation = conversation,
            state = inputState,
            assistant = assistant,
            mcpManager = vm.mcpManager,
            onCompressContext = { additionalPrompt, targetTokens, keepRecentMessages ->
                vm.handleCompressContext(additionalPrompt, targetTokens, keepRecentMessages)
            },
            onUpdateAssistant = {
                vm.updateSettings(
                    setting.copy(
                        assistants = setting.assistants.map { assistant ->
                            if (assistant.id == it.id) {
                                it
                            } else {
                                assistant
                            }
                        }
                    )
                )
            },
            onUpdateConversation = {
                vm.updateConversation(it)
                vm.saveConversationAsync()
            },
            showInjectionSheet = showInjectionSheet,
            onShowInjectionSheetChange = { showInjectionSheet = it },
            showCompressDialog = showCompressDialog,
            onShowCompressDialogChange = { showCompressDialog = it },
            onDismiss = { dismissAll() },
            onTakePic = onLaunchCamera,
            onPickImage = { imagePickerLauncher.launch("image/*") },
            onPickVideo = { videoPickerLauncher.launch("video/*") },
            onPickAudio = { audioPickerLauncher.launch("audio/*") },
            onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
        )
    }
}

@Composable
private fun TopBar(
    settings: Settings,
    conversation: Conversation,
    drawerState: DrawerState,
    bigScreen: Boolean,
    previewMode: Boolean,
    tavernPromptTraceEligible: Boolean,
    onOpenOpening: (() -> Unit)?,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onOpenTavernPromptConsole: () -> Unit,
    onUpdateTitle: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val titleState = useEditState<String> {
        onUpdateTitle(it)
    }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = {
            if (!bigScreen) {
                IconButton(
                    onClick = {
                        scope.launch { drawerState.open() }
                    }
                ) {
                    Icon(HugeIcons.Menu03, "Messages")
                }
            }
        },
        title = {
            val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
            Surface(
                onClick = {
                    if (conversation.messageNodes.isNotEmpty()) {
                        titleState.open(conversation.title)
                    } else {
                        toaster.show(editTitleWarning, type = ToastType.Warning)
                    }
                },
                color = Color.Transparent,
            ) {
                Column {
                    val assistant = settings.getCurrentAssistant()
                    val model = settings.getCurrentChatModel()
                    val provider = model?.findProvider(providers = settings.providers, checkOverwrite = false)
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (model != null && provider != null) {
                        Text(
                            text = "${assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }} / ${model.displayName} (${provider.name})",
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            color = LocalContentColor.current.copy(0.65f),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                            )
                        )
                    }
                }
            }
        },
        actions = {
            if (onOpenOpening != null) {
                IconButton(onClick = onOpenOpening) {
                    Icon(HugeIcons.BookOpen01, contentDescription = "查看开场")
                }
            }
            TavernPromptConsoleEntry(
                visible = tavernPromptTraceEligible,
                onOpen = onOpenTavernPromptConsole,
            )

            IconButton(
                onClick = {
                    onClickMenu()
                }
            ) {
                Icon(if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet, "Chat Options")
            }

            IconButton(
                onClick = {
                    onNewChat()
                }
            ) {
                Icon(HugeIcons.MessageAdd01, "New Message")
            }
        },
    )
    titleState.EditStateContent { title, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                titleState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_title))
            },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        titleState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        titleState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}
