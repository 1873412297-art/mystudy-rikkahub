package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.components.richtext.MarkdownWebView
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Displays multi-character status with swipeable pages.
 *
 * Fixed-height layout prevents the "large blank space" issue where
 * WebView height measurement varies between pages.
 *
 * Layout:
 *   Surface (rounded, fixed max height)
 *   └─ Column
 *       ├─ Title bar: character name + page indicator (compact, fixed height)
 *       └─ HorizontalPager (fixed 320dp)
 *           ├─ Page 0: 绛雪 (scrollable internally if overflow)
 *           ├─ Page 1: 竹夭
 *           └─ ...
 */
@Composable
fun MultiCharacterStatusView(
    part: UIMessagePart.StatusPlaceholder,
    modifier: Modifier = Modifier,
    /** 酒馆脚本运行时上下文：消息所属会话 ID（透传 MarkdownWebView） */
    tavernConversationId: Uuid? = null,
    /** 当前消息 JSON（透传 MarkdownWebView） */
    tavernCurrentMessage: JsonElement? = null,
    /** 上下文快照（透传 MarkdownWebView） */
    tavernContextSnapshot: JsonObject? = null,
    /** 消息角色（透传 MarkdownWebView） */
    tavernMessageRole: MessageRole? = null,
    /** 请求头数据源（透传 MarkdownWebView，requestHeaders.get RPC 按需拉取） */
    tavernHeaderSource: (() -> List<Pair<String, String>>)? = null,
) {
    val pages = part.characterPages
    if (pages.isEmpty()) return

    val pagerState = rememberPagerState(pageCount = { pages.size })
    val colorScheme = MaterialTheme.colorScheme

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        shape = RoundedCornerShape(12.dp),
        color = colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── Title bar: world info + character name + page dots, all in one row ──
            val worldInfo = remember(part.htmlContent) { extractWorldInfo(part.htmlContent) }
            val hasWorld = worldInfo != null
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = pages[pagerState.currentPage].name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.primary,
                    maxLines = 1,
                )
                if (hasWorld) {
                    Text(
                        text = " · $worldInfo",
                        fontSize = 11.sp,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (pages.size > 1) {
                    repeat(pages.size) { idx ->
                        Box(
                            modifier = Modifier
                                .padding(start = 2.dp)
                                .size(if (idx == pagerState.currentPage) 6.dp else 4.dp)
                                .clip(CircleShape)
                                .background(
                                    if (idx == pagerState.currentPage) colorScheme.primary
                                    else colorScheme.outlineVariant
                                ),
                        )
                    }
                }
            }

            // ── Pager: all pages fill identical height, overflow scrolls internally ──
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp),
            ) { page ->
                MarkdownWebView(
                    content = pages[page].html,
                    isRawHtml = true,
                    maxHeightDp = 200,
                    tavernConversationId = tavernConversationId,
                    tavernCurrentMessage = tavernCurrentMessage,
                    tavernContextSnapshot = tavernContextSnapshot,
                    tavernMessageRole = tavernMessageRole,
                    tavernHeaderSource = tavernHeaderSource,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** Extract world info time + place from the compact header HTML. */
private fun extractWorldInfo(htmlContent: String): String? {
    // Match patterns like: 🕐 未时  📍 顾家山庄·慈脂佛堂
    val parts = mutableListOf<String>()
    Regex("""🕐\s*(.+?)(?:</span>|<)""").find(htmlContent)?.let { parts.add("🕐 ${it.groupValues[1]}") }
    Regex("""📍\s*(.+?)(?:</span>|<)""").find(htmlContent)?.let { parts.add("📍 ${it.groupValues[1]}") }
    return if (parts.isNotEmpty()) parts.joinToString("  ") else null
}
