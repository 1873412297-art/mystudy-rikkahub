package me.rerere.rikkahub.ui.pages.extensions.tavernhelper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.runtime.TavernBrowserSessionRegistry
import me.rerere.rikkahub.ui.components.richtext.runtime.TavernScriptDiagnosticEntry
import me.rerere.rikkahub.ui.components.richtext.runtime.tavernScriptDiagnostics
import me.rerere.rikkahub.ui.components.richtext.runtime.redactScriptDiagnostic
import me.rerere.rikkahub.ui.components.ui.EmptyState

@Composable
internal fun TavernScriptLogPage(scriptId: String, scriptName: String) {
    val context = LocalContext.current
    val revision by tavernScriptDiagnostics.revision.collectAsStateWithLifecycle()
    val entries = tavernScriptDiagnostics.entries(scriptId)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(scriptName.ifBlank { "脚本日志" }) },
                navigationIcon = { BackButton() },
                actions = {
                    TextButton(onClick = { copyScriptLogs(context, entries) }) { Text("复制") }
                    TextButton(onClick = { tavernScriptDiagnostics.clear(scriptId) }) { Text("清空") }
                    Button(onClick = { TavernBrowserSessionRegistry.reload(scriptId) }) { Text("重新加载") }
                },
            )
        },
    ) { innerPadding ->
        if (entries.isEmpty()) {
            EmptyState(
                modifier = Modifier.padding(innerPadding),
                title = "暂无日志",
                hint = "日志只保存在当前应用进程；重新加载脚本后会显示新的运行记录。",
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { "${it.timestamp}-${it.message.hashCode()}" }) { entry ->
                    DiagnosticEntryCard(entry)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticEntryCard(entry: TavernScriptDiagnosticEntry) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text("${entry.level} · ${entry.category}") },
            supportingContent = {
                Text(
                    buildString {
                        append(entry.timestamp).append(' ').append(entry.message)
                        entry.rpcMethod?.let { append("\nRPC: ").append(it) }
                        entry.durationMs?.let { append(" · ").append(it).append("ms") }
                        entry.error?.let { append("\n错误: ").append(it) }
                    },
                    fontFamily = FontFamily.Monospace,
                )
            },
        )
    }
}

private fun copyScriptLogs(context: Context, entries: List<TavernScriptDiagnosticEntry>) {
    val text = entries.joinToString("\n") { entry ->
        buildString {
            append(entry.timestamp).append(' ')
            append(entry.level).append(' ').append(entry.category).append(' ')
            append(redactScriptDiagnostic(entry.message))
            entry.rpcMethod?.let { append(" rpc=").append(redactScriptDiagnostic(it)) }
            entry.durationMs?.let { append(" durationMs=").append(it) }
            entry.error?.let { append(" error=").append(redactScriptDiagnostic(it)) }
        }
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("酒馆脚本日志", text))
}
