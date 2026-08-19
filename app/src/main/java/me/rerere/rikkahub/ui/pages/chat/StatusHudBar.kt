package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.rikkahub.data.ai.status.StatusOption
import me.rerere.rikkahub.data.ai.status.StatusSection
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.message.MultiCharacterStatusView
import me.rerere.rikkahub.ui.components.richtext.MarkdownWebView
import me.rerere.rikkahub.utils.JsonInstant

/** A section taller than this starts collapsed so the panel remains easy to scan. */
private val HudSectionAutoCollapseHeight = 200.dp

/**
 * Floating status summary. Expanded content lives in a modal sheet, so it never consumes message-list space.
 * Option clicks only return draft text to the caller; this component has no send capability.
 */
@Composable
fun StatusHudBar(
    conversation: Conversation,
    onOptionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation = remember(conversation) { buildStatusHudPresentation(conversation) } ?: return
    val currentMessage: JsonElement? = remember(presentation.sourceMessage) {
        runCatching {
            JsonInstant.encodeToJsonElement(UIMessage.serializer(), presentation.sourceMessage)
        }.getOrNull()
    }
    var showSheet by rememberSaveable(conversation.id) { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showSheet = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = HugeIcons.ChartColumn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = presentation.headerLine,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (presentation.isUpdating) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    text = "更新中",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    text = "已更新",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = HugeIcons.ArrowUp01,
                contentDescription = "展开状态栏",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }

    if (showSheet) {
        val sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
        ) {
            StatusHudPanel(
                presentation = presentation,
                conversation = conversation,
                currentMessage = currentMessage,
                onOptionClick = { optionText ->
                    selectStatusHudOption(
                        optionText = optionText,
                        onPrefill = onOptionClick,
                        onDismiss = { showSheet = false },
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f),
            )
        }
    }
}

@Composable
private fun StatusHudPanel(
    presentation: StatusHudPresentation,
    conversation: Conversation,
    currentMessage: JsonElement?,
    onOptionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = HugeIcons.ChartColumn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = presentation.headerLine,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (presentation.isUpdating) "状态正在更新" else "最新状态",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            presentation.htmlContent?.takeIf { it.isNotBlank() }?.let { html ->
                MarkdownWebView(
                    content = html,
                    isRawHtml = true,
                    maxHeightDp = 360,
                    tavernConversationId = conversation.id,
                    tavernCurrentMessage = currentMessage,
                    ownsSendHookController = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (presentation.pages.isNotEmpty()) {
                MultiCharacterStatusView(
                    part = UIMessagePart.StatusPlaceholder(
                        htmlContent = presentation.htmlContent.orEmpty(),
                        characterPages = presentation.pages.map {
                            UIMessagePart.CharacterStatusPage(it.name, it.html)
                        },
                    ),
                    tavernConversationId = conversation.id,
                    tavernCurrentMessage = currentMessage,
                    ownsSendHookController = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            presentation.sections.forEach { section ->
                HudSection(
                    section = section,
                    tavernConversationId = conversation.id,
                    tavernCurrentMessage = currentMessage,
                )
            }

            if (presentation.options.isNotEmpty()) {
                HudOptionsRow(presentation.options, onOptionClick)
            }
        }
    }
}

@Composable
private fun HudSection(
    section: StatusSection,
    tavernConversationId: kotlin.uuid.Uuid?,
    tavernCurrentMessage: JsonElement?,
) {
    val density = LocalDensity.current
    var expanded by remember(section.title, section.content) { mutableStateOf(true) }
    var userToggled by remember(section.title, section.content) { mutableStateOf(false) }
    val autoCollapseThresholdPx = with(density) { HudSectionAutoCollapseHeight.toPx() }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    userToggled = true
                    expanded = !expanded
                }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = section.title.ifBlank { "详情" },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                contentDescription = if (expanded) "收起本节" else "展开本节",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }

        if (expanded) {
            if (section.isHtml) {
                MarkdownWebView(
                    content = section.content,
                    isRawHtml = true,
                    maxHeightDp = 360,
                    tavernConversationId = tavernConversationId,
                    tavernCurrentMessage = tavernCurrentMessage,
                    ownsSendHookController = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            if (!userToggled && coordinates.size.height > autoCollapseThresholdPx) expanded = false
                        },
                )
            } else {
                Text(
                    text = section.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            if (!userToggled && coordinates.size.height > autoCollapseThresholdPx) expanded = false
                        },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HudOptionsRow(options: List<StatusOption>, onOptionClick: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "剧情发展",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEach { option ->
                AssistChip(
                    onClick = { onOptionClick(option.text) },
                    label = {
                        Text(
                            text = if (option.label.isBlank()) option.text else "[${option.label}] ${option.text}",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    }
}
