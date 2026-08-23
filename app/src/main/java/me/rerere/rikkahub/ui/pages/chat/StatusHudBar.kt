package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.FullScreen
import me.rerere.hugeicons.stroke.MinimizeScreen
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.rikkahub.data.ai.status.StatusOption
import me.rerere.rikkahub.data.ai.status.StatusSection
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.TavernCharacterCard
import me.rerere.rikkahub.ui.components.message.MultiCharacterStatusView
import me.rerere.rikkahub.ui.components.richtext.MarkdownWebView
import me.rerere.rikkahub.ui.components.richtext.runtime.TavernContextSnapshotInput
import me.rerere.rikkahub.ui.components.richtext.runtime.buildTavernContextSnapshot
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.pages.chat.tavern.render.TavernRenderSurface
import me.rerere.rikkahub.ui.pages.chat.tavern.render.resolveTavernRenderPolicy
import me.rerere.rikkahub.utils.JsonInstant
import org.koin.compose.koinInject

/** A section taller than this starts collapsed so the panel remains easy to scan. */
private val HudSectionAutoCollapseHeight = 200.dp

/**
 * Floating status summary. Expanded content lives in a modal sheet, so it never consumes message-list space.
 * Option clicks only return draft text to the caller; this component has no send capability.
 */
@Composable
fun StatusHudBar(
    conversation: Conversation,
    assistant: Assistant,
    isGenerating: Boolean,
    onOptionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    tavernWorldEntries: List<Pair<String, String>>? = null,
) {
    val presentation = remember(conversation) { buildStatusHudPresentation(conversation) } ?: return
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val activelyUpdating = presentation.isUpdating && isGenerating
    val currentMessage: JsonElement? = remember(presentation.sourceMessage) {
        runCatching {
            JsonInstant.encodeToJsonElement(UIMessage.serializer(), presentation.sourceMessage)
        }.getOrNull()
    }
    val tavernContextSnapshot = remember(
        conversation,
        assistant,
        settings,
        isGenerating,
        tavernWorldEntries,
    ) {
        buildStatusHudRuntimeContext(
            conversation = conversation,
            assistant = assistant,
            settings = settings,
            isGenerating = isGenerating,
            worldEntriesOverride = tavernWorldEntries,
        )
    }
    var showSheet by rememberSaveable(conversation.id) { mutableStateOf(false) }
    var fullscreen by rememberSaveable(conversation.id) { mutableStateOf(false) }
    var presentationResetSignal by rememberSaveable(conversation.id) { mutableStateOf(0) }

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
            UIAvatar(
                name = assistant.name,
                value = assistant.avatar,
                modifier = Modifier.size(26.dp),
            )
            Text(
                text = presentation.headerLine,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (activelyUpdating) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    text = "更新中",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    text = if (presentation.isUpdating) "未完成" else "已更新",
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
            dragHandle = null,
        ) {
            TavernHudSheetHost(
                persistedHudFraction = settings.tavernRenderPreferences.hudFraction,
                fullscreen = fullscreen,
                modifier = Modifier.fillMaxWidth(),
            ) {
                StatusHudPanel(
                    presentation = presentation,
                    conversation = conversation,
                    assistant = assistant,
                    currentMessage = currentMessage,
                    tavernContextSnapshot = tavernContextSnapshot,
                    fullscreen = fullscreen,
                    presentationResetSignal = presentationResetSignal,
                    onToggleFullscreen = { fullscreen = !fullscreen },
                    onRestorePresentation = { presentationResetSignal += 1 },
                    onOptionClick = { optionText ->
                        selectStatusHudOption(
                            optionText = optionText,
                            onPrefill = onOptionClick,
                            onDismiss = { showSheet = false },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Builds the same runtime context for the floating HUD that the conversation renderer receives. */
internal fun buildStatusHudRuntimeContext(
    conversation: Conversation,
    assistant: Assistant,
    settings: Settings,
    isGenerating: Boolean,
    worldEntriesOverride: List<Pair<String, String>>? = null,
): JsonObject {
    val characterCard = assistant.tavernCardJson?.let(TavernCharacterCard::fromJson)
    val worldEntries = worldEntriesOverride ?: settings.lorebooks
        .filter { lorebook -> conversation.lorebookIds.contains(lorebook.id) }
        .flatMap { lorebook -> lorebook.entries.map { it.name to it.content } }
    return buildTavernContextSnapshot(
        TavernContextSnapshotInput(
            conversation = conversation,
            assistant = assistant,
            characterCard = characterCard,
            userName = settings.displaySetting.userNickname.ifBlank { "User" },
            isGenerating = isGenerating,
            variables = conversation.statusVariables,
            worldEntries = worldEntries,
        ),
    )
}

internal fun resolveTavernHudSheetHostHeight(
    availableHeight: Int,
    persistedHudFraction: Float,
    fullscreen: Boolean,
): Int {
    if (availableHeight <= 0) return 0
    return resolveTavernRenderPolicy(
        surface = TavernRenderSurface.HUD,
        availableHeightDp = availableHeight,
        persistedHudFraction = persistedHudFraction,
        fullscreen = fullscreen,
    ).maxHeightDp.coerceAtMost(availableHeight)
}

/**
 * Measures the sheet content itself to the requested HUD height. The sheet anchor therefore
 * follows the 80%/fullscreen contract instead of wrapping a shorter child in a full-height surface.
 */
@Composable
private fun TavernHudSheetHost(
    persistedHudFraction: Float,
    fullscreen: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(
        content = content,
        modifier = modifier,
    ) { measurables, constraints ->
        val targetHeight = resolveTavernHudSheetHostHeight(
            availableHeight = constraints.maxHeight,
            persistedHudFraction = persistedHudFraction,
            fullscreen = fullscreen,
        ).coerceIn(constraints.minHeight, constraints.maxHeight)
        val placeable = measurables.single().measure(
            constraints.copy(minHeight = targetHeight, maxHeight = targetHeight),
        )
        layout(placeable.width.coerceIn(constraints.minWidth, constraints.maxWidth), targetHeight) {
            placeable.placeRelative(0, 0)
        }
    }
}

@Composable
private fun StatusHudPanel(
    presentation: StatusHudPresentation,
    conversation: Conversation,
    assistant: Assistant,
    currentMessage: JsonElement?,
    tavernContextSnapshot: JsonObject,
    fullscreen: Boolean,
    presentationResetSignal: Int,
    onToggleFullscreen: () -> Unit,
    onRestorePresentation: () -> Unit,
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
            UIAvatar(
                name = assistant.name,
                value = assistant.avatar,
                modifier = Modifier.size(44.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = assistant.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
            IconButton(onClick = onRestorePresentation) {
                Icon(
                    imageVector = HugeIcons.Refresh,
                    contentDescription = "恢复角色卡显示默认设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onToggleFullscreen) {
                if (fullscreen) {
                    Icon(
                        imageVector = HugeIcons.MinimizeScreen,
                        contentDescription = "退出状态栏全屏",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Icon(
                        imageVector = HugeIcons.FullScreen,
                        contentDescription = "全屏显示状态栏",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        val richHtml = presentation.htmlContent?.takeIf { it.isNotBlank() }
        if (richHtml != null) {
            MarkdownWebView(
                content = richHtml,
                isRawHtml = true,
                maxHeightDp = null,
                fixedHeight = true,
                minHeightDp = 240,
                tavernConversationId = conversation.id,
                tavernCurrentMessage = currentMessage,
                tavernContextSnapshot = tavernContextSnapshot,
                ownsSendHookController = false,
                presentationResetSignal = presentationResetSignal,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
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
                    tavernContextSnapshot = tavernContextSnapshot,
                    ownsSendHookController = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            presentation.sections.forEach { section ->
                HudSection(
                    section = section,
                    tavernConversationId = conversation.id,
                    tavernCurrentMessage = currentMessage,
                    tavernContextSnapshot = tavernContextSnapshot,
                )
            }

            if (presentation.options.isNotEmpty()) {
                HudOptionsRow(presentation.options, onOptionClick)
            }
            }
        }
    }
}

@Composable
private fun HudSection(
    section: StatusSection,
    tavernConversationId: kotlin.uuid.Uuid?,
    tavernCurrentMessage: JsonElement?,
    tavernContextSnapshot: JsonObject,
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
                    maxHeightDp = null,
                    tavernConversationId = tavernConversationId,
                    tavernCurrentMessage = tavernCurrentMessage,
                    tavernContextSnapshot = tavernContextSnapshot,
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
