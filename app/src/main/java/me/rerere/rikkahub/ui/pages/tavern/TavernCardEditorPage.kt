package me.rerere.rikkahub.ui.pages.tavern

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.model.tavernPreviewTargetLabel
import me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationActions
import me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationPane
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

@Composable
fun TavernCardEditorPage(assistantId: String) {
    val vm: TavernCardEditorVM = koinViewModel(parameters = { parametersOf(assistantId) })
    val card by vm.card.collectAsStateWithLifecycle()
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val previewTargets by vm.previewTargets.collectAsStateWithLifecycle()
    val selectedPreviewTarget by vm.selectedPreviewTarget.collectAsStateWithLifecycle()
    val selectedPreviewTargetReady by vm.selectedPreviewTargetReady.collectAsStateWithLifecycle()
    val selectedPreviewConversation by vm.selectedPreviewConversation.collectAsStateWithLifecycle()
    val previewOwner = remember { TavernGreetingPreviewOwner() }
    val activePreviewFieldKey by previewOwner.active.collectAsStateWithLifecycle()
    val selectedPreviewLabel = selectedPreviewConversation
        ?.takeIf { it.id == selectedPreviewTarget?.conversationId }
        ?.tavernPreviewTargetLabel()
        ?: selectedPreviewTarget?.let {
            val rawId = it.conversationId.toString()
            "${it.title} · ${rawId.take(8)}…${rawId.takeLast(4)}"
        }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scrollState = rememberScrollState()
    var showPreviewTargetPicker by remember { mutableStateOf(false) }

    if (showPreviewTargetPicker) {
        AlertDialog(
            onDismissRequest = { showPreviewTargetPicker = false },
            title = { Text("选择全功能预览会话") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "脚本、网络、变量、世界书、消息和注册副作用会直接写入你选择的真实会话。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (previewTargets.isEmpty()) {
                        Text("此角色还没有可用的真实会话，请先创建一段对话。")
                    }
                    previewTargets.forEach { conversation ->
                        TextButton(
                            onClick = {
                                vm.selectPreviewTarget(conversation)
                                showPreviewTargetPicker = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(conversation.tavernPreviewTargetLabel())
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPreviewTargetPicker = false }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Column {
                        Text(card.name.ifBlank { "创建角色卡" })
                        assistant?.let {
                            Text(
                                text = it.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        selectedPreviewLabel?.let { targetLabel ->
                            Text(
                                text = "预览 → $targetLabel",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                navigationIcon = { BackButton() },
                actions = {
                    TextButton(onClick = { vm.save() }) {
                        Text("保存")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Basic Info ──
            SectionHeader("基本信息")

            FormItem(label = { Text("名称") }) {
                OutlinedTextField(
                    value = card.name,
                    onValueChange = { v -> vm.update { c -> c.copy(name = v) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            FormItem(label = { Text("作者") }) {
                OutlinedTextField(
                    value = card.creator,
                    onValueChange = { v -> vm.update { c -> c.copy(creator = v) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            FormItem(label = { Text("版本") }) {
                OutlinedTextField(
                    value = card.characterVersion,
                    onValueChange = { v -> vm.update { c -> c.copy(characterVersion = v) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            FormItem(label = { Text("标签（逗号分隔）") }) {
                OutlinedTextField(
                    value = card.tags.joinToString(", "),
                    onValueChange = { v ->
                        val tags = v.split(",").map { t -> t.trim() }.filter { t -> t.isNotBlank() }
                        vm.update { c -> c.copy(tags = tags) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            FormItem(label = { Text("创作者备注") }) {
                OutlinedTextField(
                    value = card.creatorNotes,
                    onValueChange = { v -> vm.update { c -> c.copy(creatorNotes = v) } },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 5,
                )
            }

            // ── Character ──
            SectionHeader("角色设定")

            FormItem(label = { Text("描述") }) {
                OutlinedTextField(
                    value = card.description,
                    onValueChange = { v -> vm.update { c -> c.copy(description = v) } },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 10,
                )
            }
            FormItem(label = { Text("性格") }) {
                OutlinedTextField(
                    value = card.personality,
                    onValueChange = { v -> vm.update { c -> c.copy(personality = v) } },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                )
            }
            FormItem(label = { Text("世界观/场景") }) {
                OutlinedTextField(
                    value = card.scenario,
                    onValueChange = { v -> vm.update { c -> c.copy(scenario = v) } },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                )
            }

            // ── Messages ──
            SectionHeader("消息/对话")

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("全功能预览目标", style = MaterialTheme.typography.labelLarge)
                        Text(
                            selectedPreviewLabel ?: "尚未选择（不会自动选择）",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selectedPreviewTarget == null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    TextButton(onClick = { showPreviewTargetPicker = true }) {
                        Text(if (selectedPreviewTarget == null) "选择会话" else "重新选择")
                    }
                }
            }

            GreetingSourcePreviewEditor(
                label = "开场白",
                value = card.firstMes,
                onValueChange = { value -> vm.update { it.copy(firstMes = value) } },
                assistant = assistant,
                settings = settings,
                target = selectedPreviewTarget,
                targetReady = selectedPreviewTargetReady,
                targetConversation = selectedPreviewConversation,
                previewActive = activePreviewFieldKey == "first_mes",
                onShowSource = { previewOwner.showSource("first_mes") },
                onShowPreview = { previewOwner.show("first_mes") },
                onSelectTarget = { showPreviewTargetPicker = true },
                onMessageWrite = vm::writePreviewCurrentMessage,
                onChatVariablesWrite = vm::writePreviewChatVariables,
            )

            // Alternate greetings
            var newGreeting by remember { mutableStateOf("") }
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("备选开场白", style = MaterialTheme.typography.labelLarge)
                card.alternateGreetings.forEachIndexed { index, greeting ->
                    GreetingSourcePreviewEditor(
                        label = "#${index + 1}",
                        value = greeting,
                        onValueChange = { newVal ->
                            val updated = card.alternateGreetings.toMutableList().also { it[index] = newVal }
                            vm.update { c -> c.copy(alternateGreetings = updated) }
                        },
                        assistant = assistant,
                        settings = settings,
                        target = selectedPreviewTarget,
                        targetReady = selectedPreviewTargetReady,
                        targetConversation = selectedPreviewConversation,
                        previewActive = activePreviewFieldKey == "alternate_$index",
                        onShowSource = { previewOwner.showSource("alternate_$index") },
                        onShowPreview = { previewOwner.show("alternate_$index") },
                        onSelectTarget = { showPreviewTargetPicker = true },
                        onMessageWrite = vm::writePreviewCurrentMessage,
                        onChatVariablesWrite = vm::writePreviewChatVariables,
                    )
                }
                FormItem(label = { Text("新增") }) {
                    OutlinedTextField(
                        value = newGreeting,
                        onValueChange = { newGreeting = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("输入内容后点添加") },
                    )
                }
                TextButton(onClick = {
                    if (newGreeting.isNotBlank()) {
                        vm.update { c -> c.copy(alternateGreetings = c.alternateGreetings + newGreeting) }
                        newGreeting = ""
                    }
                }) { Text("添加备选开场白") }
            }

            FormItem(label = { Text("示例对话") }) {
                OutlinedTextField(
                    value = card.mesExample,
                    onValueChange = { v -> vm.update { c -> c.copy(mesExample = v) } },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 12,
                )
            }

            // ── System ──
            SectionHeader("系统设置")

            FormItem(label = { Text("系统提示词") }) {
                OutlinedTextField(
                    value = card.systemPrompt,
                    onValueChange = { v -> vm.update { c -> c.copy(systemPrompt = v) } },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 12,
                )
            }
            FormItem(label = { Text("历史后指令") }) {
                OutlinedTextField(
                    value = card.postHistoryInstructions,
                    onValueChange = { v -> vm.update { c -> c.copy(postHistoryInstructions = v) } },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 6,
                )
            }

            // ── Character Book (世界书) ──
            SectionHeader("世界书 (Character Book)")

            val book = card.characterBook
            Text(
                "世界书条目会在对话中根据关键词自动触发，将内容注入到系统提示词中。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (book != null && book.entries.isNotEmpty()) {
                book.entries.forEachIndexed { entryIdx, entry ->
                    val entryName = entry.name ?: entry.keys.firstOrNull() ?: "条目#${entryIdx + 1}"
                    FormItem(label = { Text(entryName) }) {
                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedTextField(
                                value = entry.keys.joinToString(", "),
                                onValueChange = { v ->
                                    val keys = v.split(",").map { it.trim() }.filter { it.isNotBlank() }
                                    val updated = book.entries.toMutableList().also { it[entryIdx] = entry.copy(keys = keys) }
                                    vm.update { c -> c.copy(characterBook = book.copy(entries = updated)) }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("触发词（逗号分隔）") },
                            )
                            OutlinedTextField(
                                value = entry.content,
                                onValueChange = { v ->
                                    val updated = book.entries.toMutableList().also { it[entryIdx] = entry.copy(content = v) }
                                    vm.update { c -> c.copy(characterBook = book.copy(entries = updated)) }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                maxLines = 4,
                                label = { Text("内容") },
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = entry.priority?.toString() ?: "",
                                    onValueChange = { v ->
                                        val p = v.toIntOrNull()
                                        val updated = book.entries.toMutableList().also { it[entryIdx] = entry.copy(priority = p) }
                                        vm.update { c -> c.copy(characterBook = book.copy(entries = updated)) }
                                    },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    label = { Text("优先级") },
                                )
                                OutlinedTextField(
                                    value = entry.position ?: "after_char",
                                    onValueChange = { v ->
                                        val updated = book.entries.toMutableList().also { it[entryIdx] = entry.copy(position = v.ifBlank { "after_char" }) }
                                        vm.update { c -> c.copy(characterBook = book.copy(entries = updated)) }
                                    },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    label = { Text("位置(before_char/after_char)") },
                                )
                            }
                            TextButton(onClick = {
                                val updated = book.entries.toMutableList().also { it.removeAt(entryIdx) }
                                vm.update { c -> c.copy(characterBook = if (updated.isEmpty()) null else book.copy(entries = updated)) }
                            }) { Text("删除条目", color = MaterialTheme.colorScheme.error) }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }

            TextButton(onClick = {
                val newEntry = me.rerere.rikkahub.data.model.CharacterBookEntry(
                    id = (book?.entries?.size ?: 0) + 1,
                    keys = emptyList(),
                    content = "",
                    enabled = true,
                    insertionOrder = book?.entries?.size ?: 0,
                    position = "after_char",
                    priority = 10,
                    constant = false,
                )
                val existing = book ?: me.rerere.rikkahub.data.model.CharacterBook(
                    name = "",
                    description = "",
                    scanDepth = 4,
                    entries = emptyList(),
                )
                vm.update { c -> c.copy(characterBook = existing.copy(entries = existing.entries + newEntry)) }
            }) { Text("+ 添加世界书条目") }

            // ── Extensions (扩展) ──
            SectionHeader("扩展 (Extensions)")
            var extensionsText by remember(card.extensions) {
                mutableStateOf(card.extensions?.toString() ?: "")
            }

            FormItem(label = { Text("扩展 JSON") }) {
                OutlinedTextField(
                    value = extensionsText,
                    onValueChange = { extensionsText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 10,
                    placeholder = { Text("粘贴 SillyTavern 扩展 JSON，如 regex、css 等。留空则不保存。") },
                )
            }
            TextButton(onClick = {
                if (extensionsText.isBlank()) {
                    vm.update { c -> c.copy(extensions = null) }
                } else {
                    try {
                        val extJson = kotlinx.serialization.json.Json.parseToJsonElement(extensionsText)
                        vm.update { c -> c.copy(extensions = extJson.jsonObject) }
                    } catch (_: Exception) {
                        // Keep current value if invalid
                    }
                }
            }) { Text("应用扩展 JSON") }

            // ── Note ──
            Text(
                text = "保存后，角色卡JSON将关联到此助手。可以在助手中通过「查看角色卡」打开预览或继续编辑。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 32.dp),
            )
        }
    }
}

@Composable
private fun GreetingSourcePreviewEditor(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    assistant: Assistant?,
    settings: Settings,
    target: TavernGreetingPreviewTarget?,
    targetReady: Boolean,
    targetConversation: Conversation?,
    previewActive: Boolean,
    onShowSource: () -> Unit,
    onShowPreview: () -> Unit,
    onSelectTarget: () -> Unit,
    onMessageWrite: (Uuid, JsonElement) -> Unit,
    onChatVariablesWrite: (Uuid, JsonObject) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Row {
                TextButton(onClick = onShowSource) { Text("源码") }
                TextButton(onClick = onShowPreview) { Text("实时预览") }
            }
        }
        if (!previewActive) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
            )
        } else if (target == null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("实时预览会运行完整脚本，必须先手动选择一段真实会话。")
                    Button(onClick = onSelectTarget) { Text("选择真实会话") }
                }
            }
        } else if (
            assistant == null || targetConversation == null ||
            !targetReady || targetConversation.id != target.conversationId ||
            targetConversation.assistantId != target.assistantId
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator()
                Text("正在连接预览目标：${target.title}")
            }
        } else {
            val previewConversation = remember(targetConversation.id, value) {
                targetConversation.copy(messageNodes = listOf(UIMessage.assistantHtml(value).toMessageNode()))
            }
            val actions = remember {
                object : TavernConversationActions {
                    override fun onMessageLongPress(messageId: Uuid) = Unit
                    override fun onSelectBranch(nodeId: Uuid, index: Int) = Unit
                    override fun onOpenHtml(messageId: Uuid) = Unit
                    override fun onFallbackRequested() = Unit
                }
            }
            Text(
                "脚本副作用将直接写入：${target.title}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TavernConversationPane(
                conversation = previewConversation,
                assistant = assistant,
                settings = settings,
                loading = false,
                actions = actions,
                ownsSendHookController = true,
                currentMessageWriter = { patch -> onMessageWrite(target.conversationId, patch) },
                chatVariablesWriter = onChatVariablesWrite,
                modifier = Modifier.fillMaxWidth().height(360.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    }
}
