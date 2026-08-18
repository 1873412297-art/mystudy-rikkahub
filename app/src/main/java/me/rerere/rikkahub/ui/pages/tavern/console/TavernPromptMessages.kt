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
                                text = stringResource(
                                    R.string.tavern_prompt_console_message_header,
                                    message.index + 1,
                                    message.role,
                                ),
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
                    visibleParts.forEach { part ->
                        Text(promptTracePartText(part = part, expanded = expanded))
                    }
                    val hasLongPreviewablePart = message.parts.any { part ->
                        when (part) {
                            is PromptTracePart.Text -> part.text.length > PART_PREVIEW_CHARACTER_LIMIT
                            is PromptTracePart.Reasoning -> part.text.length > PART_PREVIEW_CHARACTER_LIMIT
                            is PromptTracePart.Attachment,
                            is PromptTracePart.Tool -> false
                        }
                    }
                    if (message.parts.size > 2 || hasLongPreviewablePart) {
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(
                                stringResource(
                                    if (expanded) {
                                        R.string.tavern_prompt_console_collapse
                                    } else {
                                        R.string.tavern_prompt_console_expand
                                    }
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val PART_PREVIEW_CHARACTER_LIMIT = 800

@Composable
private fun promptTracePartText(
    part: PromptTracePart,
    expanded: Boolean,
): String = when (part) {
    is PromptTracePart.Text -> part.text.previewWhenCollapsed(expanded)
    is PromptTracePart.Reasoning -> stringResource(
        R.string.tavern_prompt_console_reasoning_part,
        part.text.previewWhenCollapsed(expanded),
    )
    is PromptTracePart.Attachment -> {
        val value = part.value
        val reference = value.displayName
            ?: value.uri
            ?: value.mimeType
            ?: stringResource(R.string.tavern_prompt_console_binary_reference)
        var text = stringResource(
            R.string.tavern_prompt_console_attachment_part,
            value.kind,
            reference,
        )
        if (value.byteLength != null) {
            text += " " + stringResource(
                R.string.tavern_prompt_console_byte_count,
                value.byteLength,
            )
        }
        if (value.sha256 != null) {
            text += " sha256=${value.sha256}"
        }
        text
    }
    is PromptTracePart.Tool -> {
        val lines = mutableListOf(
            stringResource(
                R.string.tavern_prompt_console_tool_part,
                part.toolName,
                part.approvalState,
            ),
            stringResource(R.string.tavern_prompt_console_tool_input, part.input.preview),
        )
        if (part.outputText != null) {
            lines += stringResource(
                R.string.tavern_prompt_console_tool_output,
                part.outputText.preview,
            )
        }
        for (attachment in part.outputAttachments) {
            val reference = attachment.displayName
                ?: attachment.uri
                ?: attachment.mimeType
                ?: stringResource(R.string.tavern_prompt_console_binary_reference)
            lines += stringResource(
                R.string.tavern_prompt_console_tool_output_attachment,
                attachment.kind,
                reference,
            )
        }
        lines.joinToString("\n")
    }
}

private fun String.previewWhenCollapsed(expanded: Boolean): String {
    if (expanded || length <= PART_PREVIEW_CHARACTER_LIMIT) return this
    return take(PART_PREVIEW_CHARACTER_LIMIT) + "…"
}
