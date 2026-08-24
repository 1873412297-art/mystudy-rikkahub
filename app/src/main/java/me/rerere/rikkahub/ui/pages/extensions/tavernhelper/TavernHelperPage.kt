package me.rerere.rikkahub.ui.pages.extensions.tavernhelper

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperScope
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperScopeType
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperScript
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperScriptFolder
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperScriptNode
import me.rerere.rikkahub.data.ai.tavernhelper.searchTavernHelperNodes
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.EmptyState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun TavernHelperPage(
    assistantId: String? = null,
    vm: TavernHelperVM = koinViewModel(),
) {
    val context = LocalContext.current
    val scripts by vm.scripts.collectAsStateWithLifecycle()
    val selectedScope by vm.scope.collectAsStateWithLifecycle()
    val appSettings by vm.settings.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val render = appSettings.tavernHelperRenderSettings
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var showAddFolder by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<TavernHelperScript?>(null) }
    var pendingExportJson by remember { mutableStateOf<String?>(null) }
    var confirmScripts by rememberSaveable { mutableStateOf(false) }
    var pendingEnable by remember { mutableStateOf<TavernHelperScriptNode?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("无法读取文件")
            }.onSuccess(vm::importJson).onFailure { vm.error.value = it.message }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = pendingExportJson
        if (uri != null && json != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
                    ?: error("无法写入文件")
            }.onFailure { vm.error.value = it.message }
        }
        pendingExportJson = null
    }

    Scaffold(
        topBar = {
            Column {
                LargeFlexibleTopAppBar(
                    title = { Text("酒馆助手") },
                    navigationIcon = { BackButton() },
                    scrollBehavior = scrollBehavior,
                    colors = CustomColors.topBarColors,
                )
                SecondaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("渲染") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("脚本") })
                }
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (tab == 0) {
            RenderSettings(
                modifier = Modifier.padding(innerPadding),
                enabled = render.enabled,
                depth = render.depth,
                ignoreHidden = render.ignoreHiddenMessages,
                collapse = render.collapseFrontendCode,
                streaming = render.allowStreaming,
                scripts = render.allowScripts,
                network = render.allowNetwork,
                onEnabled = { vm.updateRenderSettings { old -> old.copy(enabled = it) } },
                onDepth = { vm.updateRenderSettings { old -> old.copy(depth = it.coerceIn(0, 100)) } },
                onIgnoreHidden = { vm.updateRenderSettings { old -> old.copy(ignoreHiddenMessages = it) } },
                onCollapse = { vm.updateRenderSettings { old -> old.copy(collapseFrontendCode = it) } },
                onStreaming = { vm.updateRenderSettings { old -> old.copy(allowStreaming = it) } },
                onScripts = { if (it) confirmScripts = true else vm.updateRenderSettings { old -> old.copy(allowScripts = false) } },
                onNetwork = { vm.updateRenderSettings { old -> old.copy(allowNetwork = it) } },
            )
        } else {
            ScriptList(
                modifier = Modifier.padding(innerPadding),
                scripts = scripts,
                selectedScope = selectedScope,
                assistantId = assistantId,
                onScope = vm::selectScope,
                onImport = { importer.launch(arrayOf("application/json", "text/json", "text/plain")) },
                onAdd = { showAdd = true },
                onAddFolder = { showAddFolder = true },
                search = search,
                onSearch = { search = it },
                onEnabled = { node, enabled ->
                    if (enabled) pendingEnable = node else vm.setEnabled(node, false)
                },
                onDelete = vm::delete,
                onEdit = { editing = it },
                onDuplicate = vm::duplicate,
                onExport = { node ->
                    pendingExportJson = vm.exportJson(node)
                    val prefix = if (node is TavernHelperScriptFolder) "酒馆助手脚本文件夹" else "酒馆助手脚本"
                    val safeName = node.name.ifBlank { "未命名" }.replace(Regex("[\\/:*?\"<>|]"), "_")
                    exporter.launch("$prefix-$safeName.json")
                },
            )
        }
    }

    if (showAdd) {
        AddScriptDialog(onDismiss = { showAdd = false }) { name, source ->
            vm.addScript(name, source)
            showAdd = false
        }
    }
    if (showAddFolder) {
        NameDialog("新增脚本文件夹", "文件夹名称", onDismiss = { showAddFolder = false }) { name ->
            vm.addFolder(name)
            showAddFolder = false
        }
    }
    editing?.let { script ->
        EditScriptDialog(
            script = script,
            onDismiss = { editing = null },
            onConfirm = {
                vm.updateScript(it)
                editing = null
            },
        )
    }
    if (confirmScripts) {
        AlertDialog(
            onDismissRequest = { confirmScripts = false },
            title = { Text("允许消息前端运行脚本？") },
            text = { Text("HTML 中的 JavaScript 可以读取当前酒馆上下文并修改变量。只对可信角色卡和回复启用。") },
            confirmButton = {
                Button(onClick = {
                    vm.updateRenderSettings { it.copy(allowScripts = true) }
                    confirmScripts = false
                }) { Text("我了解风险，启用") }
            },
            dismissButton = { TextButton(onClick = { confirmScripts = false }) { Text("取消") } },
        )
    }
    pendingEnable?.let { node ->
        AlertDialog(
            onDismissRequest = { pendingEnable = null },
            title = { Text("启用第三方脚本？") },
            text = {
                Text("脚本“${node.name.ifBlank { "未命名" }}”将在聊天页长期运行，并可调用已授权的酒馆 API。请只启用你信任的脚本。")
            },
            confirmButton = {
                Button(onClick = {
                    vm.setEnabled(node, true)
                    pendingEnable = null
                }) { Text("信任并启用") }
            },
            dismissButton = { TextButton(onClick = { pendingEnable = null }) { Text("取消") } },
        )
    }
    error?.let { message ->
        AlertDialog(
            onDismissRequest = vm::clearError,
            title = { Text("操作失败") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = vm::clearError) { Text("确定") } },
        )
    }
}

@Composable
private fun RenderSettings(
    modifier: Modifier,
    enabled: Boolean,
    depth: Int,
    ignoreHidden: Boolean,
    collapse: Boolean,
    streaming: Boolean,
    scripts: Boolean,
    network: Boolean,
    onEnabled: (Boolean) -> Unit,
    onDepth: (Int) -> Unit,
    onIgnoreHidden: (Boolean) -> Unit,
    onCollapse: (Boolean) -> Unit,
    onStreaming: (Boolean) -> Unit,
    onScripts: (Boolean) -> Unit,
    onNetwork: (Boolean) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SettingSwitch("渲染消息前端", "直接在现有对话消息中显示 HTML 界面", enabled, onEnabled) }
        item {
            Card {
                ListItem(
                    headlineContent = { Text("渲染深度") },
                    supportingContent = { Text("0 表示仅最新消息；当前：$depth") },
                    trailingContent = {
                        Row {
                            TextButton(onClick = { onDepth(depth - 1) }) { Text("−") }
                            TextButton(onClick = { onDepth(depth + 1) }) { Text("+") }
                        }
                    },
                )
            }
        }
        item { SettingSwitch("忽略隐藏消息", "隐藏消息中的前端块不创建运行实例", ignoreHidden, onIgnoreHidden) }
        item { SettingSwitch("折叠前端源码", "界面渲染后默认不重复展示源码", collapse, onCollapse) }
        item { SettingSwitch("流式期间渲染", "生成未结束时也更新前端界面", streaming, onStreaming) }
        item { SettingSwitch("运行前端脚本", "默认关闭；开启前会进行风险确认", scripts, onScripts) }
        item { SettingSwitch("允许 HTTPS 网络资源", "HTTP 和本地文件访问始终拒绝", network, onNetwork) }
    }
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Card {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(description) },
            trailingContent = { Switch(checked = checked, onCheckedChange = onChecked) },
        )
    }
}

@Composable
private fun ScriptList(
    modifier: Modifier,
    scripts: List<TavernHelperScriptNode>,
    selectedScope: TavernHelperScope,
    assistantId: String?,
    onScope: (TavernHelperScope) -> Unit,
    onImport: () -> Unit,
    onAdd: () -> Unit,
    onAddFolder: () -> Unit,
    search: String,
    onSearch: (String) -> Unit,
    onEnabled: (TavernHelperScriptNode, Boolean) -> Unit,
    onDelete: (TavernHelperScriptNode) -> Unit,
    onEdit: (TavernHelperScript) -> Unit,
    onDuplicate: (TavernHelperScriptNode) -> Unit,
    onExport: (TavernHelperScriptNode) -> Unit,
) {
    val searchResult = remember(scripts, search) { searchTavernHelperNodes(scripts, search) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedScope.type == TavernHelperScopeType.GLOBAL,
                    onClick = { onScope(TavernHelperScope(TavernHelperScopeType.GLOBAL)) },
                    label = { Text("全局") },
                )
                assistantId?.let {
                    FilterChip(
                        selected = selectedScope.type == TavernHelperScopeType.CHARACTER,
                        onClick = { onScope(TavernHelperScope(TavernHelperScopeType.CHARACTER, it)) },
                        label = { Text("角色卡") },
                    )
                    FilterChip(
                        selected = selectedScope.type == TavernHelperScopeType.ASSISTANT,
                        onClick = { onScope(TavernHelperScope(TavernHelperScopeType.ASSISTANT, it)) },
                        label = { Text("助手/预设") },
                    )
                }
            }
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = onSearch,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索名称、说明或源码；支持 /正则/i") },
                supportingText = searchResult.error?.let { message -> ({ Text(message, color = MaterialTheme.colorScheme.error) }) },
                singleLine = true,
            )
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAdd) { Icon(HugeIcons.Add01, null); Text("新增脚本") }
                TextButton(onClick = onAddFolder) { Icon(HugeIcons.Folder01, null); Text("新增文件夹") }
                TextButton(onClick = onImport) { Icon(HugeIcons.FileImport, null); Text("导入 JSON") }
            }
        }
        if (searchResult.nodes.isEmpty()) {
            item { EmptyState(icon = HugeIcons.Puzzle, title = "暂无脚本", hint = "新增或导入脚本后会显示在这里") }
        }
        items(searchResult.nodes, key = { it.id }) { node ->
            ScriptNodeCard(node, onEnabled, onDelete, onEdit, onDuplicate, onExport)
        }
    }
}

@Composable
private fun ScriptNodeCard(
    node: TavernHelperScriptNode,
    onEnabled: (TavernHelperScriptNode, Boolean) -> Unit,
    onDelete: (TavernHelperScriptNode) -> Unit,
    onEdit: (TavernHelperScript) -> Unit,
    onDuplicate: (TavernHelperScriptNode) -> Unit,
    onExport: (TavernHelperScriptNode) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            leadingContent = {
                Icon(if (node is TavernHelperScriptFolder) HugeIcons.Folder01 else HugeIcons.Zap, null)
            },
            headlineContent = { Text(node.name.ifBlank { "未命名脚本" }) },
            supportingContent = {
                Text(if (node is TavernHelperScriptFolder) "${node.scripts.size} 个脚本" else (node as TavernHelperScript).info)
            },
            trailingContent = { Switch(checked = node.enabled, onCheckedChange = { onEnabled(node, it) }) },
        )
        if (node is TavernHelperScriptFolder) {
            node.scripts.forEach { child ->
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(child.name.ifBlank { "未命名脚本" }) },
                    trailingContent = { Switch(child.enabled, onCheckedChange = { onEnabled(child, it) }) },
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onEdit(child) }) { Text("编辑") }
                    TextButton(onClick = { onDuplicate(child) }) { Text("复制") }
                    TextButton(onClick = { onExport(child) }) { Text("导出") }
                    IconButton(onClick = { onDelete(child) }) { Icon(HugeIcons.Delete01, "删除") }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (node is TavernHelperScript) {
                TextButton(onClick = { onEdit(node) }) { Text("编辑") }
            }
            TextButton(onClick = { onDuplicate(node) }) { Text("复制") }
            TextButton(onClick = { onExport(node) }) { Text("导出") }
            IconButton(onClick = { onDelete(node) }) { Icon(HugeIcons.Delete01, "删除") }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    label: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun EditScriptDialog(
    script: TavernHelperScript,
    onDismiss: () -> Unit,
    onConfirm: (TavernHelperScript) -> Unit,
) {
    var name by remember(script.id) { mutableStateOf(script.name) }
    var info by remember(script.id) { mutableStateOf(script.info) }
    var source by remember(script.id) { mutableStateOf(script.content) }
    var includeData by remember(script.id) { mutableStateOf(script.exportWith.data) }
    var includeButtons by remember(script.id) { mutableStateOf(script.exportWith.button) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑酒馆脚本") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
                OutlinedTextField(info, { info = it }, label = { Text("说明") }, singleLine = true)
                OutlinedTextField(
                    source,
                    { source = it },
                    label = { Text("JavaScript 源码") },
                    minLines = 10,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                SettingSwitch("导出时包含数据", "关闭时导出的 data 为空", includeData) { includeData = it }
                SettingSwitch("导出时包含按钮", "关闭时导出的按钮列表为空", includeButtons) { includeButtons = it }
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank(), onClick = {
                onConfirm(
                    script.copy(
                        name = name.trim(),
                        info = info,
                        content = source,
                        exportWith = script.exportWith.copy(data = includeData, button = includeButtons),
                    ),
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun AddScriptDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增酒馆脚本") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it },
                    label = { Text("JavaScript 源码") },
                    minLines = 8,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { onConfirm(name, source) }) { Text("保存（默认禁用）") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
