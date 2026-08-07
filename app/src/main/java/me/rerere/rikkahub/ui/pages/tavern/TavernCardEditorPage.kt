package me.rerere.rikkahub.ui.pages.tavern

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun TavernCardEditorPage(assistantId: String) {
    val vm: TavernCardEditorVM = koinViewModel(parameters = { parametersOf(assistantId) })
    val card by vm.card.collectAsStateWithLifecycle()
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scrollState = rememberScrollState()

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
                    }
                },
                navigationIcon = { BackButton() },
                actions = {
                    TextButton(onClick = { vm.save() }) {
                        Text("保存")
                    }
                },
                scrollBehavior = scrollBehavior,
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

            FormItem(label = { Text("开场白") }) {
                OutlinedTextField(
                    value = card.firstMes,
                    onValueChange = { v -> vm.update { c -> c.copy(firstMes = v) } },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                )
            }

            // Alternate greetings
            var newGreeting by remember { mutableStateOf("") }
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("备选开场白", style = MaterialTheme.typography.labelLarge)
                card.alternateGreetings.forEachIndexed { index, greeting ->
                    FormItem(label = { Text("#${index + 1}") }) {
                        OutlinedTextField(
                            value = greeting,
                            onValueChange = { newVal ->
                                val updated = card.alternateGreetings.toMutableList().also { it[index] = newVal }
                                vm.update { c -> c.copy(alternateGreetings = updated) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4,
                        )
                    }
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
