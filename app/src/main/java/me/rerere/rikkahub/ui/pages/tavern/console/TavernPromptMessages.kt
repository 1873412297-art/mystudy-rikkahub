package me.rerere.rikkahub.ui.pages.tavern.console

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.trace.PromptTraceMessage
import me.rerere.rikkahub.data.ai.trace.PromptTracePart

@Composable
fun TavernPromptMessages(
    messages: List<PromptTraceMessage>,
    onCopyAll: () -> Unit,
    onCopyMessage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            FilledTonalButton(onClick = onCopyAll) {
                Text(stringResource(R.string.tavern_prompt_console_copy_all))
            }
        }
        items(messages.sortedBy { it.index }, key = { it.id.toString() }) { message ->
            var expanded by rememberSaveable(message.id.toString()) { mutableStateOf(false) }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "${message.index + 1}. ${message.role}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            message.name?.let {
                                Text(it, style = MaterialTheme.typography.labelMedium)
                            }
                            Text(
                                stringResource(
                                    R.string.tavern_prompt_console_approx,
                                    message.approximateTokens,
                                )
                            )
                        }
                        IconButton(onClick = { onCopyMessage(message.index) }) {
                            Icon(
                                imageVector = HugeIcons.Copy01,
                                contentDescription = stringResource(
                                    R.string.tavern_prompt_console_copy_message
                                ),
                            )
                        }
                    }
                    val visibleParts = if (expanded) message.parts else message.parts.take(2)
                    visibleParts.forEach { part -> Text(promptTracePartText(part)) }
                    if (message.parts.size > 2 || message.characterCount > 800) {
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(if (expanded) "Collapse" else "Expand")
                        }
                    }
                }
            }
        }
    }
}

internal fun promptTracePartText(part: PromptTracePart): String = when (part) {
    is PromptTracePart.Text -> part.text
    is PromptTracePart.Reasoning -> "[Reasoning]\n${part.text}"
    is PromptTracePart.Attachment -> buildString {
        append("[${part.value.kind}] ")
        append(part.value.displayName ?: part.value.uri ?: part.value.mimeType ?: "binary reference")
        part.value.byteLength?.let { append(" ($it bytes)") }
        part.value.sha256?.let { append(" sha256=$it") }
    }
    is PromptTracePart.Tool -> buildString {
        appendLine("[Tool ${part.toolName} / ${part.approvalState}]")
        appendLine("Input: ${part.input.preview}")
        part.outputText?.let { appendLine("Output: ${it.preview}") }
        part.outputAttachments.forEach { attachment ->
            appendLine(
                "[Tool output ${attachment.kind}] " +
                    (attachment.displayName ?: attachment.uri ?: attachment.mimeType ?: "binary reference")
            )
        }
    }.trimEnd()
}
