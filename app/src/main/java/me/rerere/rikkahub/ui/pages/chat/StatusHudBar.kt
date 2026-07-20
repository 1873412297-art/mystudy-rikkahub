package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.rikkahub.data.ai.status.StatusBlockExtraction
import me.rerere.rikkahub.data.ai.status.StatusBlockExtractor
import me.rerere.rikkahub.data.ai.status.StatusSection
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.richtext.MarkdownWebView
import me.rerere.rikkahub.utils.JsonInstant

/** 展开态内容的最大高度（内部滚动）。 */
private val HudExpandedMaxHeight = 420.dp

/** 单个 section 内容超过此高度时默认收起。 */
private val HudSectionAutoCollapseHeight = 200.dp

private data class StatusHudData(
    val extraction: StatusBlockExtraction,
    val message: UIMessage,
)

/**
 * 从尾部往前找最近一条含状态块的 assistant 消息并提取。
 * 返回 null 表示当前会话没有任何状态块，HUD 不显示。
 */
private fun findLatestStatusHud(conversation: Conversation): StatusHudData? {
    conversation.currentMessages.asReversed().forEach { message ->
        if (message.role != MessageRole.ASSISTANT) return@forEach
        val text = message.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
        if (text.isBlank()) return@forEach
        val extraction = StatusBlockExtractor.extract(text)
        if (extraction.rawStatusText != null) {
            return StatusHudData(extraction = extraction, message = message)
        }
    }
    return null
}

/**
 * 动态状态栏（HUD）：展示最近一条 assistant 消息中状态块的内容，
 * 随消息流更新自动换成最新一轮的状态。
 *
 * @param onOptionClick 点击编号选项 chip 时回调，参数为选项文本（由调用方走发送链路）。
 */
@Composable
fun StatusHudBar(
    conversation: Conversation,
    onOptionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 消息流更新（流式期间 conversation 实例也会更新）→ 自动重新提取最新一轮状态
    val hud = remember(conversation) { findLatestStatusHud(conversation) } ?: return

    val tavernCurrentMessage: JsonElement? = remember(hud.message) {
        runCatching { JsonInstant.encodeToJsonElement(UIMessage.serializer(), hud.message) }.getOrNull()
    }

    var expanded by rememberSaveable(conversation.id) { mutableStateOf(false) }
    val extraction = hud.extraction

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(),
        ) {
            // 通栏头：收起态显示摘要，展开态同样保留作为折叠开关
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
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
                    text = extraction.headerLine ?: "状态栏",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (extraction.sections.isNotEmpty()) {
                    Text(
                        text = "${extraction.sections.size} 节",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                    contentDescription = if (expanded) "收起状态栏" else "展开状态栏",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }

            if (expanded) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = HudExpandedMaxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 完整 header 行（『📅日期|⏰时间|📍位置』）
                    extraction.headerLine?.let { header ->
                        Text(
                            text = header,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    extraction.sections.forEach { section ->
                        HudSection(
                            section = section,
                            tavernConversationId = conversation.id,
                            tavernCurrentMessage = tavernCurrentMessage,
                        )
                    }

                    if (extraction.options.isNotEmpty()) {
                        HudOptionsRow(
                            options = extraction.options,
                            onOptionClick = onOptionClick,
                        )
                    }
                }
            }
        }
    }
}

/** 单个状态分节：title 加粗小节头 + content，可单独折叠；内容过高时默认收起。 */
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

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
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
                // 卡片自带 HTML：走酒馆运行时渲染
                MarkdownWebView(
                    content = section.content,
                    isRawHtml = true,
                    maxHeightDp = 300,
                    tavernConversationId = tavernConversationId,
                    tavernCurrentMessage = tavernCurrentMessage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            if (!userToggled && coordinates.size.height > autoCollapseThresholdPx) {
                                expanded = false
                            }
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
                            if (!userToggled && coordinates.size.height > autoCollapseThresholdPx) {
                                expanded = false
                            }
                        },
                )
            }
        }
    }
}

/** 编号选项：一排可点击 chips，点击回调选项文本。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HudOptionsRow(
    options: List<me.rerere.rikkahub.data.ai.status.StatusOption>,
    onOptionClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
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
                            text = if (option.label.isBlank()) {
                                option.text
                            } else {
                                "[${option.label}] ${option.text}"
                            },
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
