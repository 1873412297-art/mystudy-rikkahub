package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.QuickMessageMode

/**
 * SillyTavern Quick Reply 风格的快捷指令栏（输入区上方横向 chips）。
 *
 * - 单击：按 [QuickMessage.mode] 填入（APPEND=追加 / REPLACE=替换），
 *   [QuickMessage.autoSend] 条目填入后通过 [onAutoSend] 立即触发发送
 * - 长按：强制替换填入（不触发发送）
 * - 末尾固定「编辑」入口，跳转全局快捷指令管理页
 * - [quickMessages] 为空（助手无可见快捷指令）时不渲染
 */
@Composable
fun QuickReplyBar(
    quickMessages: List<QuickMessage>,
    onFill: (QuickMessage, replace: Boolean) -> Unit,
    onAutoSend: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (quickMessages.isEmpty()) return

    val hapticFeedback = LocalHapticFeedback.current
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(quickMessages, key = { it.id }) { quickMessage ->
            QuickReplyChip(
                quickMessage = quickMessage,
                onClick = {
                    onFill(quickMessage, quickMessage.mode == QuickMessageMode.REPLACE)
                    if (quickMessage.autoSend) onAutoSend()
                },
                onLongClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    onFill(quickMessage, true)
                },
            )
        }
        item(key = "quick_reply_edit") {
            QuickReplyEditChip(onClick = onEdit)
        }
    }
}

@Composable
private fun QuickReplyChip(
    quickMessage: QuickMessage,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = quickReplyChipBorder(),
    ) {
        QuickReplyChipContent {
            if (quickMessage.autoSend) {
                Icon(
                    imageVector = HugeIcons.Zap,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = quickMessage.title.ifBlank { "未命名指令" },
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun QuickReplyEditChip(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        border = quickReplyChipBorder(),
    ) {
        QuickReplyChipContent {
            Icon(
                imageVector = HugeIcons.Edit01,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "编辑",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** 快捷指令 chip 的统一边框 */
@Composable
private fun quickReplyChipBorder(): BorderStroke =
    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

/** 快捷指令 chip 的统一内部布局（内边距、垂直居中、间距） */
@Composable
private fun QuickReplyChipContent(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}
