package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01

/**
 * 通用自动折叠容器。
 *
 * 当 [content] 的实际渲染高度超过 [collapseThreshold] 时，自动收进一个圆角容器：
 * - 折叠态：内容截断到 [collapsedHeight]，底部渐变遮罩 + 「展开全部」条；
 * - 展开态：显示完整内容，底部有「收起」条；
 * - 内容未超过阈值（或未测量到）：完全原样渲染，不附加任何容器装饰。
 *
 * 与具体内容类型无关（文本、MarkdownWebView、状态面板等统一量高度）。
 *
 * @param enabled 是否允许折叠。流式生成中应传 false，等消息完成后再判定，避免跳动。
 * @param expanded 当前是否展开（由调用方按消息 id rememberSaveable）。
 * @param onExpandedChange 展开/收起切换回调。
 * @param collapseThreshold 触发折叠的内容高度阈值。
 * @param collapsedHeight 折叠态预览高度。
 * @param horizontalAlignment 内容在容器内的水平对齐（用于保持用户消息右对齐等布局）。
 */
@Composable
fun AutoCollapseContent(
    enabled: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    collapseThreshold: Dp = 480.dp,
    collapsedHeight: Dp = 300.dp,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { collapseThreshold.roundToPx() }

    // 只增不减地记录内容真实高度：折叠态下内容被约束测量会报告截断高度，
    // 单调增长可避免"测量变小 → 取消折叠 → 又超高"的抖动循环。
    var contentHeightPx by remember { mutableIntStateOf(0) }
    val collapsible = enabled && contentHeightPx > thresholdPx

    val measureModifier = Modifier.onSizeChanged { size ->
        if (size.height > contentHeightPx) contentHeightPx = size.height
    }

    if (!collapsible) {
        // 未触发折叠：原样渲染，不附加容器装饰
        Box(modifier = modifier.fillMaxWidth().then(measureModifier)) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = horizontalAlignment,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                content()
            }
        }
        return
    }

    val shape = RoundedCornerShape(12.dp)
    val containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .clip(shape)
            .background(containerColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (!expanded) Modifier.height(collapsedHeight) else Modifier)
                .clipToBounds()
        ) {
            Box(modifier = Modifier.fillMaxWidth().then(measureModifier)) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = horizontalAlignment,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    content()
                }
            }
            if (!expanded) {
                // 底部渐变遮罩：向容器背景色过渡
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(72.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, containerColor)
                            )
                        )
                )
            }
        }
        // 底部操作条（整个条都是热区）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpandedChange(!expanded) }
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (expanded) "收起" else "展开全部",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
