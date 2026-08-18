package me.rerere.rikkahub.ui.pages.extensions.lorebook

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Book02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Share03
import me.rerere.hugeicons.stroke.SortByDown01
import me.rerere.hugeicons.stroke.Tools
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.FloatingToolbarDefaults.floatingToolbarVerticalNestedScroll
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.export.LorebookSerializer
import me.rerere.rikkahub.data.export.rememberExporter
import me.rerere.rikkahub.data.export.rememberImporter
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ExportDialog
import me.rerere.rikkahub.ui.components.ui.EmptyState
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.pages.extensions.InjectionPositionSelector
import me.rerere.rikkahub.ui.pages.extensions.InjectionRoleSelector
import me.rerere.rikkahub.ui.pages.extensions.PromptVM
import me.rerere.rikkahub.ui.pages.extensions.getPositionLabel
import me.rerere.rikkahub.ui.pages.extensions.usesStandaloneMessage
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/**
 * 独立的世界书编辑器页面（对齐 SillyTavern 字段与体验）
 *
 * - 世界书列表：搜索、批量启停、导入/导出、预算与递归扫描设置
 * - 条目列表：搜索、按 priority 排序、批量启停
 * - 条目编辑：覆盖全部字段（主/次关键词、selective、probability、position+injectDepth、
 *   scanDepth、priority、constantActive、enabled、useRegex/caseSensitive）
 */
@Composable
fun LorebookPage(vm: PromptVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var selectedBookId by rememberSaveable { mutableStateOf<String?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val selectedBook = remember(settings.lorebooks, selectedBookId) {
        settings.lorebooks.firstOrNull { it.id.toString() == selectedBookId }
    }

    // 更新回调在重组间保持稳定，且始终读取最新的 settings
    val currentSettings by rememberUpdatedState(settings)
    val updateLorebooks: (List<Lorebook>) -> Unit = remember(vm) {
        { updated ->
            vm.updateSettings(currentSettings.copy(lorebooks = updated))
        }
    }
    val updateBook: (Lorebook) -> Unit = remember(updateLorebooks) {
        { updatedBook ->
            updateLorebooks(currentSettings.lorebooks.map { if (it.id == updatedBook.id) updatedBook else it })
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                navigationIcon = {
                    if (selectedBook != null) {
                        BackButton(onClick = { selectedBookId = null })
                    } else {
                        BackButton()
                    }
                },
                title = {
                    Text(
                        if (selectedBook != null) {
                            selectedBook.name.ifEmpty { "未命名世界书" }
                        } else {
                            "世界书"
                        }
                    )
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (selectedBook == null) {
            LorebookListView(
                lorebooks = settings.lorebooks,
                onUpdate = updateLorebooks,
                onOpenBook = { selectedBookId = it.id.toString() },
                modifier = Modifier.padding(innerPadding)
            )
        } else {
            LorebookEntriesView(
                book = selectedBook,
                onUpdateBook = updateBook,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}

// ==================== 世界书列表 ====================

@Composable
private fun LorebookListView(
    lorebooks: List<Lorebook>,
    onUpdate: (List<Lorebook>) -> Unit,
    onOpenBook: (Lorebook) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val toaster = LocalToaster.current
    val currentLorebooks by rememberUpdatedState(lorebooks)
    val editState = useEditState<Lorebook> { edited ->
        val index = lorebooks.indexOfFirst { it.id == edited.id }
        if (index >= 0) {
            onUpdate(lorebooks.toMutableList().apply { set(index, edited) })
        } else {
            onUpdate(lorebooks + edited)
        }
    }
    val importSuccessMsg = stringResource(R.string.export_import_success)
    val importFailedMsg = stringResource(R.string.export_import_failed)
    val importer = rememberImporter(LorebookSerializer) { result ->
        result.onSuccess { imported ->
            onUpdate(currentLorebooks + imported)
            toaster.show(importSuccessMsg)
        }.onFailure { error ->
            toaster.show(importFailedMsg.format(error.message))
        }
    }
    val filteredBooks = remember(lorebooks, searchQuery) {
        if (searchQuery.isBlank()) {
            lorebooks
        } else {
            lorebooks.filter { book ->
                book.name.contains(searchQuery, ignoreCase = true) ||
                    book.description.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .floatingToolbarVerticalNestedScroll(
                    expanded = expanded,
                    onExpand = { expanded = true },
                    onCollapse = { expanded = false }
                ),
            contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 128.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SearchTextField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    label = "搜索世界书",
                )
            }

            if (lorebooks.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            onUpdate(lorebooks.map { it.copy(enabled = true) })
                        }) {
                            Text("全部启用")
                        }
                        TextButton(onClick = {
                            onUpdate(lorebooks.map { it.copy(enabled = false) })
                        }) {
                            Text("全部停用")
                        }
                    }
                }
            }

            if (filteredBooks.isEmpty()) {
                item {
                    EmptyState(
                        modifier = Modifier.fillParentMaxHeight(0.7f),
                        title = if (lorebooks.isEmpty()) "还没有世界书" else "没有匹配的世界书",
                        hint = if (lorebooks.isEmpty()) "点击右下角新建，或从 SillyTavern 导入 JSON" else null,
                        contentArrangement = Arrangement.Center,
                    )
                }
            } else {
                items(filteredBooks, key = { it.id }) { book ->
                    LorebookCard(
                        book = book,
                        onEdit = { editState.open(book) },
                        onDelete = { onUpdate(lorebooks - book) },
                        onOpen = { onOpenBook(book) },
                        onToggleEnabled = { enabled ->
                            onUpdate(lorebooks.map { if (it.id == book.id) it.copy(enabled = enabled) else it })
                        }
                    )
                }
            }
        }

        HorizontalFloatingToolbar(
            expanded = expanded,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = -ScreenOffset),
            leadingContent = {
                IconButton(onClick = { importer.importFromFile() }) {
                    Icon(HugeIcons.FileImport, null)
                }
            },
        ) {
            Button(onClick = { editState.open(Lorebook()) }) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(HugeIcons.Add01, null)
                    AnimatedVisibility(expanded) {
                        Row {
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("新建世界书")
                        }
                    }
                }
            }
        }
    }

    if (editState.isEditing) {
        editState.currentState?.let { state ->
            LorebookEditSheet(
                book = state,
                onDismiss = { editState.dismiss() },
                onConfirm = { editState.confirm() },
                onEdit = { editState.currentState = it }
            )
        }
    }
}

@Composable
private fun LorebookCard(
    book: Lorebook,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    var showExportDialog by remember { mutableStateOf(false) }
    val exporter = rememberExporter(book, LorebookSerializer)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor
        ),
        onClick = onOpen,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = book.name.ifEmpty { "未命名世界书" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (book.description.isNotEmpty()) {
                    Text(
                        text = book.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Tag(type = TagType.INFO) {
                        Text("${book.entries.size} 条目")
                    }
                    if (book.tokenBudget > 0) {
                        Tag(type = TagType.DEFAULT) {
                            Text("预算 ${book.tokenBudget}")
                        }
                    }
                    if (book.recursiveScanning) {
                        Tag(type = TagType.DEFAULT) {
                            Text("递归扫描")
                        }
                    }
                    if (!book.enabled) {
                        Tag(type = TagType.WARNING) {
                            Text(stringResource(R.string.prompt_page_disabled))
                        }
                    }
                }
            }
            Switch(
                checked = book.enabled,
                onCheckedChange = onToggleEnabled
            )
            IconButton(onClick = { showExportDialog = true }) {
                Icon(HugeIcons.Share03, stringResource(R.string.export_title))
            }
            IconButton(onClick = onEdit) {
                Icon(HugeIcons.Tools, stringResource(R.string.prompt_page_edit))
            }
            IconButton(onClick = onDelete) {
                Icon(HugeIcons.Delete01, stringResource(R.string.prompt_page_delete))
            }
        }
    }

    if (showExportDialog) {
        ExportDialog(
            exporter = exporter,
            onDismiss = { showExportDialog = false }
        )
    }
}

@Composable
private fun LorebookEditSheet(
    book: Lorebook,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onEdit: (Lorebook) -> Unit
) {
    EditSheetScaffold(
        title = "世界书设置",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        modifier = Modifier.fillMaxHeight(0.9f),
    ) {
        OutlinedTextField(
            value = book.name,
            onValueChange = { onEdit(book.copy(name = it)) },
            label = { Text(stringResource(R.string.prompt_page_name)) },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = book.description,
            onValueChange = { onEdit(book.copy(description = it)) },
            label = { Text(stringResource(R.string.prompt_page_description)) },
            modifier = Modifier.fillMaxWidth()
        )

        SwitchFormItem(
            label = stringResource(R.string.prompt_page_enabled),
            checked = book.enabled,
            onCheckedChange = { onEdit(book.copy(enabled = it)) }
        )

        NumberTextField(
            value = book.tokenBudget,
            onValueChange = { onEdit(book.copy(tokenBudget = it.coerceAtLeast(0))) },
            label = "Token 预算（字符数近似，0 为不限）",
        )

        SwitchFormItem(
            label = "递归扫描",
            checked = book.recursiveScanning,
            onCheckedChange = { onEdit(book.copy(recursiveScanning = it)) },
            description = "已命中条目的内容会纳入扫描文本继续匹配其他条目（最多 5 轮）"
        )
    }
}

// ==================== 条目列表 ====================

@Composable
private fun LorebookEntriesView(
    book: Lorebook,
    onUpdateBook: (Lorebook) -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var sortByPriority by rememberSaveable { mutableStateOf(true) }
    val entryEditState = useEditState<PromptInjection.RegexInjection> { edited ->
        val index = book.entries.indexOfFirst { it.id == edited.id }
        if (index >= 0) {
            onUpdateBook(book.copy(entries = book.entries.toMutableList().apply { set(index, edited) }))
        } else {
            onUpdateBook(book.copy(entries = book.entries + edited))
        }
    }
    val filteredEntries = remember(book.entries, searchQuery, sortByPriority) {
        val searched = if (searchQuery.isBlank()) {
            book.entries
        } else {
            book.entries.filter { entry ->
                entry.name.contains(searchQuery, ignoreCase = true) ||
                    entry.content.contains(searchQuery, ignoreCase = true) ||
                    entry.keywords.any { it.contains(searchQuery, ignoreCase = true) } ||
                    entry.secondaryKeywords.any { it.contains(searchQuery, ignoreCase = true) }
            }
        }
        if (sortByPriority) searched.sortedByDescending { it.priority } else searched
    }
    val setFilteredEntriesEnabled: (Boolean) -> Unit = { enabled ->
        val filteredIds = filteredEntries.map { it.id }.toSet()
        onUpdateBook(
            book.copy(
                entries = book.entries.map { entry ->
                    if (entry.id in filteredIds) entry.copy(enabled = enabled) else entry
                }
            )
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 128.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SearchTextField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    label = "搜索条目（名称 / 关键词 / 内容）",
                )
            }

            item {
                EntriesToolbar(
                    entryCount = book.entries.size,
                    sortByPriority = sortByPriority,
                    onToggleSort = { sortByPriority = !sortByPriority },
                    onSetFilteredEnabled = setFilteredEntriesEnabled
                )
            }

            if (filteredEntries.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = HugeIcons.Book02,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (book.entries.isEmpty()) "还没有条目" else "没有匹配的条目",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(filteredEntries, key = { it.id }) { entry ->
                    RegexInjectionEntryCard(
                        entry = entry,
                        onEdit = { entryEditState.open(entry) },
                        onDelete = {
                            onUpdateBook(book.copy(entries = book.entries - entry))
                        },
                        onToggleEnabled = { enabled ->
                            onUpdateBook(
                                book.copy(
                                    entries = book.entries.map {
                                        if (it.id == entry.id) it.copy(enabled = enabled) else it
                                    }
                                )
                            )
                        }
                    )
                }
            }
        }

        HorizontalFloatingToolbar(
            expanded = true,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = -ScreenOffset),
        ) {
            Button(onClick = { entryEditState.open(PromptInjection.RegexInjection()) }) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(HugeIcons.Add01, null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("新建条目")
                }
            }
        }
    }

    if (entryEditState.isEditing) {
        entryEditState.currentState?.let { state ->
            RegexInjectionEditSheet(
                entry = state,
                onDismiss = { entryEditState.dismiss() },
                onConfirm = { entryEditState.confirm() },
                onEdit = { entryEditState.currentState = it }
            )
        }
    }
}

@Composable
private fun EntriesToolbar(
    entryCount: Int,
    sortByPriority: Boolean,
    onToggleSort: () -> Unit,
    onSetFilteredEnabled: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "共 $entryCount 条",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onToggleSort) {
            Icon(
                imageVector = HugeIcons.SortByDown01,
                contentDescription = "按优先级排序",
                tint = if (sortByPriority) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        TextButton(onClick = { onSetFilteredEnabled(true) }) {
            Text("启用")
        }
        TextButton(onClick = { onSetFilteredEnabled(false) }) {
            Text("停用")
        }
    }
}

@Composable
private fun RegexInjectionEntryCard(
    entry: PromptInjection.RegexInjection,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = entry.name.ifEmpty { "未命名条目" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (entry.keywords.isNotEmpty()) {
                    Text(
                        text = entry.keywords.joinToString(", ") +
                            if (entry.selective && entry.secondaryKeywords.isNotEmpty()) {
                                " ⊕ " + entry.secondaryKeywords.joinToString(", ")
                            } else {
                                ""
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Tag(type = TagType.DEFAULT) {
                        Text("P${entry.priority}")
                    }
                    Tag(type = TagType.INFO) {
                        Text(getPositionLabel(entry.position))
                    }
                    if (entry.constantActive) {
                        Tag(type = TagType.INFO) {
                            Text("常驻")
                        }
                    }
                    if (entry.selective) {
                        Tag(type = TagType.INFO) {
                            Text("selective")
                        }
                    }
                    if (entry.probability < 100) {
                        Tag(type = TagType.DEFAULT) {
                            Text("${entry.probability}%")
                        }
                    }
                    if (!entry.enabled) {
                        Tag(type = TagType.WARNING) {
                            Text(stringResource(R.string.prompt_page_disabled))
                        }
                    }
                }
            }
            Switch(
                checked = entry.enabled,
                onCheckedChange = onToggleEnabled
            )
            IconButton(onClick = onEdit) {
                Icon(HugeIcons.Tools, stringResource(R.string.prompt_page_edit))
            }
            IconButton(onClick = onDelete) {
                Icon(HugeIcons.Delete01, stringResource(R.string.prompt_page_delete))
            }
        }
    }
}

// ==================== 条目编辑 ====================

@Composable
private fun RegexInjectionEditSheet(
    entry: PromptInjection.RegexInjection,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onEdit: (PromptInjection.RegexInjection) -> Unit
) {
    EditSheetScaffold(
        title = "编辑条目",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        modifier = Modifier.fillMaxHeight(0.95f),
        confirmEnabled = entry.keywords.isNotEmpty() || entry.constantActive,
        contentSpacing = 12.dp,
    ) {
        OutlinedTextField(
            value = entry.name,
            onValueChange = { onEdit(entry.copy(name = it)) },
            label = { Text(stringResource(R.string.prompt_page_name)) },
            modifier = Modifier.fillMaxWidth()
        )

        SwitchFormItem(
            label = stringResource(R.string.prompt_page_enabled),
            checked = entry.enabled,
            onCheckedChange = { onEdit(entry.copy(enabled = it)) }
        )

        SwitchFormItem(
            label = stringResource(R.string.prompt_page_constant_active),
            checked = entry.constantActive,
            onCheckedChange = { onEdit(entry.copy(constantActive = it)) },
            description = stringResource(R.string.prompt_page_constant_active_desc)
        )

        // 主关键词
        KeywordEditor(
            title = "主关键词",
            keywords = entry.keywords,
            onUpdate = { onEdit(entry.copy(keywords = it)) }
        )

        // 次关键词
        KeywordEditor(
            title = "次关键词（selective）",
            keywords = entry.secondaryKeywords,
            onUpdate = { onEdit(entry.copy(secondaryKeywords = it)) }
        )

        SwitchFormItem(
            label = "selective（主次关键词都命中才触发）",
            checked = entry.selective,
            onCheckedChange = { onEdit(entry.copy(selective = it)) }
        )

        NumberTextField(
            value = entry.probability,
            onValueChange = { onEdit(entry.copy(probability = it.coerceIn(0, 100))) },
            label = "触发概率 %（0-100）",
        )

        // ST 触发装饰器（0 = 关闭）
        NumberTextField(
            value = entry.sticky,
            onValueChange = { onEdit(entry.copy(sticky = it.coerceAtLeast(0))) },
            label = "sticky（命中后持续注入的用户轮次，0=关闭）",
        )

        NumberTextField(
            value = entry.cooldown,
            onValueChange = { onEdit(entry.copy(cooldown = it.coerceAtLeast(0))) },
            label = "cooldown（命中后冷却的用户轮次，0=关闭）",
        )

        NumberTextField(
            value = entry.delay,
            onValueChange = { onEdit(entry.copy(delay = it.coerceAtLeast(0))) },
            label = "delay（对话前 N 个用户轮次不触发，0=关闭）",
        )

        NumberTextField(
            value = entry.priority,
            onValueChange = { onEdit(entry.copy(priority = it)) },
            label = stringResource(R.string.prompt_page_priority_label),
        )

        EntryPositionFields(entry = entry, onEdit = onEdit)

        NumberTextField(
            value = entry.scanDepth,
            onValueChange = { onEdit(entry.copy(scanDepth = it)) },
            label = stringResource(R.string.prompt_page_scan_depth),
        )

        SwitchFormItem(
            label = stringResource(R.string.prompt_page_use_regex),
            checked = entry.useRegex,
            onCheckedChange = { onEdit(entry.copy(useRegex = it)) }
        )

        SwitchFormItem(
            label = stringResource(R.string.prompt_page_case_sensitive),
            checked = entry.caseSensitive,
            onCheckedChange = { onEdit(entry.copy(caseSensitive = it)) }
        )

        SwitchFormItem(
            label = stringResource(R.string.prompt_page_match_whole_words),
            checked = entry.matchWholeWords,
            onCheckedChange = { onEdit(entry.copy(matchWholeWords = it)) }
        )

        OutlinedTextField(
            value = entry.content,
            onValueChange = { onEdit(entry.copy(content = it)) },
            label = { Text(stringResource(R.string.prompt_page_injection_content)) },
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
            minLines = 4
        )
    }
}

@Composable
private fun EntryPositionFields(
    entry: PromptInjection.RegexInjection,
    onEdit: (PromptInjection.RegexInjection) -> Unit,
) {
    Text(
        stringResource(R.string.prompt_page_injection_position),
        style = MaterialTheme.typography.titleSmall
    )
    InjectionPositionSelector(
        position = entry.position,
        onSelect = { onEdit(entry.copy(position = it)) }
    )

    AnimatedVisibility(visible = entry.position == InjectionPosition.AT_DEPTH) {
        NumberTextField(
            value = entry.injectDepth,
            onValueChange = { onEdit(entry.copy(injectDepth = it)) },
            label = stringResource(R.string.prompt_page_inject_depth),
        )
    }

    AnimatedVisibility(visible = entry.position.usesStandaloneMessage()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.prompt_page_injection_role),
                style = MaterialTheme.typography.titleSmall
            )
            InjectionRoleSelector(
                role = entry.role,
                onSelect = { onEdit(entry.copy(role = it)) }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeywordEditor(
    title: String,
    keywords: List<String>,
    onUpdate: (List<String>) -> Unit,
) {
    var newKeyword by remember { mutableStateOf("") }

    Text(title, style = MaterialTheme.typography.titleSmall)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        keywords.forEach { keyword ->
            InputChip(
                selected = false,
                onClick = {},
                label = { Text(keyword) },
                trailingIcon = {
                    IconButton(
                        onClick = { onUpdate(keywords - keyword) },
                        modifier = Modifier.size(16.dp)
                    ) {
                        Icon(HugeIcons.Cancel01, null, modifier = Modifier.size(12.dp))
                    }
                }
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = newKeyword,
            onValueChange = { newKeyword = it },
            label = { Text(stringResource(R.string.prompt_page_new_keyword)) },
            modifier = Modifier.weight(1f),
            singleLine = true
        )
        FilledIconButton(
            onClick = {
                if (newKeyword.isNotBlank()) {
                    onUpdate(keywords + newKeyword.trim())
                    newKeyword = ""
                }
            }
        ) {
            Icon(HugeIcons.Add01, stringResource(R.string.prompt_page_add))
        }
    }
}

// ==================== 共享 UI 组件 ====================

@Composable
private fun SearchTextField(
    query: String,
    onQueryChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@Composable
private fun SwitchFormItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
) {
    FormItem(
        label = { Text(label) },
        description = if (description != null) {
            { Text(description) }
        } else {
            null
        },
        tail = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    )
}

@Composable
private fun NumberTextField(
    value: Int,
    onValueChange: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { text -> text.toIntOrNull()?.let(onValueChange) },
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

@Composable
private fun EditSheetScaffold(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    contentSpacing: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = {
            IconButton(onClick = {
                scope.launch {
                    sheetState.hide()
                    onDismiss()
                }
            }) {
                Icon(HugeIcons.ArrowDown01, null)
            }
        }
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(contentSpacing)
            ) {
                content()
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.prompt_page_cancel))
                }
                TextButton(
                    onClick = onConfirm,
                    enabled = confirmEnabled
                ) {
                    Text(stringResource(R.string.prompt_page_confirm))
                }
            }
        }
    }
}
