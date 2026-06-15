package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckmarkBadge01
import me.rerere.hugeicons.stroke.PlusSign
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.ContextFilter
import me.rerere.rikkahub.data.model.ContextScope
import me.rerere.rikkahub.data.model.GroupMember
import me.rerere.rikkahub.data.model.TurnTakingStrategy
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

@Composable
fun AssistantGroupMembersPage(id: String) {
    val navController = LocalNavController.current
    val vm: AssistantGroupMembersVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val allAssistants by vm.allAssistants.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var showAddMemberSheet by remember { mutableStateOf(false) }
    var editingMember by remember { mutableStateOf<GroupMember?>(null) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Column {
                        Text("群组成员")
                        assistant?.let {
                            Text(
                                text = it.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { innerPadding ->
        val currentAssistant = assistant ?: return@Scaffold

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Turn-taking strategy section
            item(key = "strategy") {
                CardGroup {
                    item(
                        onClick = { vm.setTurnTakingStrategy(TurnTakingStrategy.MANUAL) },
                        headlineContent = { Text("手动选择") },
                        supportingContent = { Text("用户手动选择下一个发言的角色") },
                        trailingContent = {
                            if (currentAssistant.turnTakingStrategy == TurnTakingStrategy.MANUAL) {
                                Icon(HugeIcons.CheckmarkBadge01, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        },
                    )
                    item(
                        onClick = { vm.setTurnTakingStrategy(TurnTakingStrategy.AUTO_ROUND_ROBIN) },
                        headlineContent = { Text("轮询模式") },
                        supportingContent = { Text("角色按顺序轮流发言") },
                        trailingContent = {
                            if (currentAssistant.turnTakingStrategy == TurnTakingStrategy.AUTO_ROUND_ROBIN) {
                                Icon(HugeIcons.CheckmarkBadge01, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        },
                    )
                    item(
                        onClick = { vm.setTurnTakingStrategy(TurnTakingStrategy.AUTO_MODERATOR) },
                        headlineContent = { Text("自动仲裁") },
                        supportingContent = { Text("由 AI 根据对话内容决定下一个发言者") },
                        trailingContent = {
                            if (currentAssistant.turnTakingStrategy == TurnTakingStrategy.AUTO_MODERATOR) {
                                Icon(HugeIcons.CheckmarkBadge01, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        },
                    )
                }
            }

            // Members section
            item(key = "members_header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "成员 (${currentAssistant.groupMembers.size})",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(onClick = { showAddMemberSheet = true }) {
                        Icon(HugeIcons.PlusSign, null, modifier = Modifier.padding(end = 4.dp))
                        Text("添加成员")
                    }
                }
            }

            if (currentAssistant.groupMembers.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = "尚未添加成员。点击「添加成员」从现有助手中选择一个加入群组。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            } else {
                items(currentAssistant.groupMembers, key = { it.id.toString() }) { member ->
                    val sourceAssistant = allAssistants.find { it.id == member.assistantId }
                    val swipeState = rememberSwipeToDismissBoxState()
                    val dismissScope = rememberCoroutineScope()

                    SwipeToDismissBox(
                        state = swipeState,
                        backgroundContent = {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(onClick = {
                                    dismissScope.launch { swipeState.reset() }
                                }) {
                                    Icon(HugeIcons.Cancel01, contentDescription = null)
                                }
                                FilledIconButton(onClick = {
                                    dismissScope.launch {
                                        vm.removeMember(member.id)
                                        swipeState.reset()
                                    }
                                }) {
                                    Icon(
                                        HugeIcons.Delete01,
                                        contentDescription = null,
                                    )
                                }
                            }
                        },
                        enableDismissFromStartToEnd = false,
                    ) {
                        OutlinedCard {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = MaterialTheme.shapes.small,
                                ) {
                                    sourceAssistant?.let { UIAvatar(it.name, it.avatar) }
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = member.displayName.ifBlank { sourceAssistant?.name ?: "未知角色" },
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                    )
                                    if (member.systemPromptOverride != null) {
                                        Text(
                                            text = "已覆盖提示词",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    if (member.chatModelIdOverride != null) {
                                        Text(
                                            text = "已覆盖模型",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                IconButton(onClick = { editingMember = member }) {
                                    Icon(HugeIcons.Tools, contentDescription = "编辑成员")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Add member bottom sheet — shows solo assistants available to add
    if (showAddMemberSheet) {
        val soloAssistants = allAssistants
            .filter { it.assistantType == AssistantType.SOLO && it.id.toString() != id }
        val addSheetState = rememberModalBottomSheetState()

        ModalBottomSheet(
            onDismissRequest = { showAddMemberSheet = false },
            sheetState = addSheetState,
        ) {
            Column(
                modifier = Modifier.padding(bottom = 32.dp),
            ) {
                Text(
                    text = "从现有助手添加成员",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (soloAssistants.isEmpty()) {
                    Text(
                        text = "没有可用的独奏助手。请先创建一个助手。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                LazyColumn(
                    modifier = Modifier.height(400.dp),
                ) {
                    items(soloAssistants, key = { it.id.toString() }) { candidate ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.addMember(candidate)
                                    showAddMemberSheet = false
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AutoAIIcon(name = candidate.avatar.toString())
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(candidate.name, style = MaterialTheme.typography.titleSmall)
                                if (candidate.systemPrompt.isNotBlank()) {
                                    Text(
                                        text = candidate.systemPrompt.take(80),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    // Edit member bottom sheet
    editingMember?.let { member ->
        val sourceAssistant = allAssistants.find { it.id == member.assistantId }
        val sheetState = rememberModalBottomSheetState()
        var memberName by remember(member.id) { mutableStateOf(member.displayName.ifBlank { sourceAssistant?.name ?: "" }) }
        var systemPromptOverride by remember(member.id) { mutableStateOf(member.systemPromptOverride ?: "") }
        var contextFilter by remember(member.id) { mutableStateOf(member.contextFilter) }
        var showContextFilterSheet by remember { mutableStateOf(false) }

        ModalBottomSheet(
            onDismissRequest = { editingMember = null },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "编辑成员: ${sourceAssistant?.name ?: ""}",
                    style = MaterialTheme.typography.titleLarge,
                )

                OutlinedTextField(
                    value = memberName,
                    onValueChange = { memberName = it },
                    label = { Text("显示名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = systemPromptOverride,
                    onValueChange = { systemPromptOverride = it },
                    label = { Text("系统提示词覆盖（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                )

                // Context filter summary card
                Surface(
                    onClick = { showContextFilterSheet = true },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "上下文接收范围",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = contextFilterSummary(contextFilter, member, assistant?.groupMembers ?: emptyList(), allAssistants),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                        Icon(
                            HugeIcons.ArrowRight01,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // View character card of the source assistant
                sourceAssistant?.let { src ->
                    val cardJson = src.tavernCardJson
                    val hasCard = cardJson != null || src.background?.let {
                        it.startsWith("content://") || it.endsWith(".png", ignoreCase = true)
                    } == true
                    if (hasCard) {
                        Surface(
                            onClick = {
                                when {
                                    cardJson != null -> navController.navigate(
                                        Screen.TavernCardViewer(
                                            cardJson = cardJson,
                                            assistantId = src.id.toString()
                                        )
                                    )
                                    src.background != null -> navController.navigate(
                                        Screen.TavernCardViewer(
                                            cardUri = src.background,
                                            assistantId = src.id.toString()
                                        )
                                    )
                                    else -> navController.navigate(
                                        Screen.TavernCardEditor(
                                            assistantId = src.id.toString()
                                        )
                                    )
                                }
                            },
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "查看角色卡",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = src.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Icon(
                                    HugeIcons.ArrowRight01,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // Save and remove buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = {
                        vm.removeMember(member.id)
                        editingMember = null
                    }) {
                        Icon(HugeIcons.Delete01, null, modifier = Modifier.padding(end = 4.dp))
                        Text("移除成员", color = MaterialTheme.colorScheme.error)
                    }

                    TextButton(onClick = {
                        vm.updateMember(
                            member.copy(
                                displayName = memberName,
                                systemPromptOverride = systemPromptOverride.ifBlank { null },
                                contextFilter = contextFilter,
                            )
                        )
                        editingMember = null
                    }) {
                        Text("保存")
                    }
                }
            }
        }

        // Nested context filter configuration sheet
        if (showContextFilterSheet) {
            ContextFilterSheet(
                filter = contextFilter,
                member = member,
                groupMembers = assistant?.groupMembers ?: emptyList(),
                allAssistants = allAssistants,
                onUpdate = { contextFilter = it },
                onDismiss = { showContextFilterSheet = false },
            )
        }
    }
}

// region Context Filter UI

private fun contextFilterSummary(
    filter: ContextFilter,
    member: GroupMember,
    groupMembers: List<GroupMember>,
    allAssistants: List<Assistant>,
): String {
    val scopeText = when (filter.scope) {
        ContextScope.ALL -> "全部可见"
        ContextScope.SELF -> "仅自己"
        ContextScope.MEMBER_LIST -> {
            val names = filter.visibleMemberIds.mapNotNull { mid ->
                val m = groupMembers.find { it.id == mid }
                m?.displayName ?: allAssistants.find { it.id == m?.assistantId }?.name
            }
            if (names.isEmpty()) "指定成员可见" else "可见: ${names.joinToString(", ")}"
        }
        ContextScope.DIRECTED -> "仅定向消息"
    }
    val extras = buildList {
        if (filter.excludedMemberIds.isNotEmpty()) add("排除${filter.excludedMemberIds.size}人")
        if (filter.mentionEnabled) add("仅提及")
        if (filter.maxMessages > 0) add("最近${filter.maxMessages}条")
    }
    return if (extras.isEmpty()) scopeText else "$scopeText · ${extras.joinToString(" · ")}"
}

@Composable
private fun ContextFilterSheet(
    filter: ContextFilter,
    member: GroupMember,
    groupMembers: List<GroupMember>,
    allAssistants: List<Assistant>,
    onUpdate: (ContextFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var localFilter by remember { mutableStateOf(filter) }
    var showVisibleMemberPicker by remember { mutableStateOf(false) }
    var showExcludeMemberPicker by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = "上下文接收范围",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "控制该成员能看到哪些消息",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Scope selector
            item {
                Text(
                    text = "基础范围",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                CardGroup {
                    item(
                        onClick = { localFilter = localFilter.copy(scope = ContextScope.ALL) },
                        headlineContent = { Text("全部可见") },
                        supportingContent = { Text("能看到所有消息") },
                        trailingContent = {
                            if (localFilter.scope == ContextScope.ALL)
                                Icon(HugeIcons.CheckmarkBadge01, null, tint = MaterialTheme.colorScheme.primary)
                        },
                    )
                    item(
                        onClick = { localFilter = localFilter.copy(scope = ContextScope.SELF) },
                        headlineContent = { Text("仅自己") },
                        supportingContent = { Text("只能看到自己发送的消息") },
                        trailingContent = {
                            if (localFilter.scope == ContextScope.SELF)
                                Icon(HugeIcons.CheckmarkBadge01, null, tint = MaterialTheme.colorScheme.primary)
                        },
                    )
                    item(
                        onClick = { localFilter = localFilter.copy(scope = ContextScope.MEMBER_LIST) },
                        headlineContent = { Text("指定成员可见") },
                        supportingContent = {
                            val count = localFilter.visibleMemberIds.size
                            if (count > 0) Text("已选 $count 个成员") else Text("选择可见的成员")
                        },
                        trailingContent = {
                            if (localFilter.scope == ContextScope.MEMBER_LIST)
                                Icon(HugeIcons.CheckmarkBadge01, null, tint = MaterialTheme.colorScheme.primary)
                        },
                    )
                    item(
                        onClick = { localFilter = localFilter.copy(scope = ContextScope.DIRECTED) },
                        headlineContent = { Text("仅定向消息") },
                        supportingContent = { Text("只能看到明确发给自己的消息") },
                        trailingContent = {
                            if (localFilter.scope == ContextScope.DIRECTED)
                                Icon(HugeIcons.CheckmarkBadge01, null, tint = MaterialTheme.colorScheme.primary)
                        },
                    )
                }
            }

            // Visible member picker (when MEMBER_LIST)
            if (localFilter.scope == ContextScope.MEMBER_LIST) {
                item {
                    TextButton(onClick = { showVisibleMemberPicker = true }) {
                        Icon(HugeIcons.PlusSign, null, modifier = Modifier.padding(end = 4.dp))
                        Text("选择可见成员")
                    }
                }
                val visibleNames = localFilter.visibleMemberIds.mapNotNull { mid ->
                    val m = groupMembers.find { it.id == mid }
                    m?.displayName ?: allAssistants.find { it.id == m?.assistantId }?.name
                }
                if (visibleNames.isNotEmpty()) {
                    item {
                        Text(
                            text = "可见: ${visibleNames.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // Additional filters
            item {
                Text(
                    text = "附加过滤",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                CardGroup {
                    // Exclude toggle
                    item(
                        headlineContent = { Text("排除指定成员") },
                        supportingContent = {
                            val count = localFilter.excludedMemberIds.size
                            if (count > 0) Text("已排除 $count 个成员") else Text("屏蔽指定成员的消息")
                        },
                        trailingContent = {
                            Switch(
                                checked = localFilter.excludedMemberIds.isNotEmpty(),
                                onCheckedChange = { enabled ->
                                    localFilter = if (enabled) {
                                        localFilter.copy(excludedMemberIds = localFilter.excludedMemberIds)
                                    } else {
                                        localFilter.copy(excludedMemberIds = emptyList())
                                    }
                                    if (enabled) showExcludeMemberPicker = true
                                },
                            )
                        },
                    )
                    if (localFilter.excludedMemberIds.isNotEmpty()) {
                        item(
                            onClick = { showExcludeMemberPicker = true },
                            headlineContent = {
                                val excludedNames = localFilter.excludedMemberIds.mapNotNull { mid ->
                                    val m = groupMembers.find { it.id == mid }
                                    m?.displayName ?: allAssistants.find { it.id == m?.assistantId }?.name
                                }
                                Text("排除: ${excludedNames.joinToString(", ")}")
                            },
                            trailingContent = {
                                TextButton(onClick = {
                                    localFilter = localFilter.copy(excludedMemberIds = emptyList())
                                }) { Text("清除") }
                            },
                        )
                    }

                    // Mention toggle
                    item(
                        headlineContent = { Text("仅被提及时接收") },
                        supportingContent = { Text("仅当消息包含关键词时对该成员可见") },
                        trailingContent = {
                            Switch(
                                checked = localFilter.mentionEnabled,
                                onCheckedChange = { localFilter = localFilter.copy(mentionEnabled = it) },
                            )
                        },
                    )
                    if (localFilter.mentionEnabled) {
                        item {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text(
                                    text = "提及关键词（逗号分隔）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = localFilter.mentionKeywords.joinToString(", "),
                                    onValueChange = { text ->
                                        localFilter = localFilter.copy(
                                            mentionKeywords = text.split(",")
                                                .map { it.trim() }
                                                .filter { it.isNotBlank() }
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    placeholder = { Text("@角色名, 关键词") },
                                )
                            }
                        }
                    }

                    // Max messages
                    item(
                        headlineContent = { Text("限制最近 N 条") },
                        supportingContent = {
                            if (localFilter.maxMessages > 0)
                                Text("只保留最近 ${localFilter.maxMessages} 条消息")
                            else
                                Text("不限制消息数量")
                        },
                        trailingContent = {
                            Switch(
                                checked = localFilter.maxMessages > 0,
                                onCheckedChange = { enabled ->
                                    localFilter = localFilter.copy(maxMessages = if (enabled) 20 else 0)
                                },
                            )
                        },
                    )
                    if (localFilter.maxMessages > 0) {
                        item {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                OutlinedTextField(
                                    value = localFilter.maxMessages.toString(),
                                    onValueChange = { text ->
                                        text.toIntOrNull()?.let { n ->
                                            if (n in 1..999) localFilter = localFilter.copy(maxMessages = n)
                                        }
                                        if (text.isBlank()) localFilter = localFilter.copy(maxMessages = 1)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text("消息数量") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                )
                            }
                        }
                    }
                }
            }

            // Done button
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = {
                        onUpdate(localFilter)
                        onDismiss()
                    }) {
                        Text("确定")
                    }
                }
            }
        }
    }

    // Visible member picker sheet (for MEMBER_LIST scope)
    if (showVisibleMemberPicker) {
        MemberMultiPickerSheet(
            title = "选择可见成员",
            groupMembers = groupMembers.filter { it.id != member.id },
            allAssistants = allAssistants,
            selectedIds = localFilter.visibleMemberIds,
            onConfirm = { ids ->
                localFilter = localFilter.copy(visibleMemberIds = ids)
                showVisibleMemberPicker = false
            },
            onDismiss = { showVisibleMemberPicker = false },
        )
    }

    // Exclude member picker sheet
    if (showExcludeMemberPicker) {
        MemberMultiPickerSheet(
            title = "选择排除的成员",
            groupMembers = groupMembers.filter { it.id != member.id },
            allAssistants = allAssistants,
            selectedIds = localFilter.excludedMemberIds,
            onConfirm = { ids ->
                localFilter = localFilter.copy(excludedMemberIds = ids)
                showExcludeMemberPicker = false
            },
            onDismiss = { showExcludeMemberPicker = false },
        )
    }
}

@Composable
private fun MemberMultiPickerSheet(
    title: String,
    groupMembers: List<GroupMember>,
    allAssistants: List<Assistant>,
    selectedIds: List<Uuid>,
    onConfirm: (List<Uuid>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var localSelected by remember { mutableStateOf(selectedIds.toSet()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { onConfirm(localSelected.toList()) }) {
                    Text("确定")
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(groupMembers, key = { it.id.toString() }) { gm ->
                    val source = allAssistants.find { it.id == gm.assistantId }
                    val name = gm.displayName.ifBlank { source?.name ?: "?" }
                    val isSelected = gm.id in localSelected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                localSelected = if (isSelected) localSelected - gm.id
                                else localSelected + gm.id
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            source?.let { UIAvatar(it.name, it.avatar) }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        if (isSelected) {
                            Icon(HugeIcons.CheckmarkBadge01, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

// endregion
