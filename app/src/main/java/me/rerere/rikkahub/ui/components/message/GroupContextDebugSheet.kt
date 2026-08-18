package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.service.group.GroupResolverDebugState
import me.rerere.rikkahub.service.group.GroupRuntimeState
import kotlin.uuid.Uuid

@Composable
fun GroupContextDebugSheet(
    runtimeState: GroupRuntimeState,
    assistant: Assistant? = null,
    onDismissRequest: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "群组运行状态",
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(
                    onClick = {
                        clipboardManager.setText(
                            AnnotatedString(buildRuntimeStateText(runtimeState, assistant))
                        )
                        copied = true
                    }
                ) {
                    Text(if (copied) "已复制 ✓" else "复制全部")
                }
            }

            DebugSection(title = "寻址状态") {
                DebugLine("当前点名角色", assistant.resolveMemberLabel(runtimeState.activeAddressedMemberId))
                DebugLine("点名消息 ID", runtimeState.activeAddressedTurnId?.toString() ?: "无")
            }

            DebugSection(title = "场景摘要") {
                DebugScrollableBox {
                    Text(
                        text = runtimeState.scene.summary.ifBlank { "暂无摘要" },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                DebugLine("场景紧张度", runtimeState.scene.tension.toString())
            }

            DebugSection(title = "活跃秘密") {
                DebugScrollableBox {
                    Text(
                        text = runtimeState.scene.activeSecrets.joinToString("\n").ifBlank { "无" },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            DebugSection(title = "事件状态") {
                DebugLine("近期事件数", runtimeState.eventState.recentEvents.size.toString())
                DebugTags("焦点角色", runtimeState.eventState.activeFocus?.characterIds?.map { assistant.resolveMemberLabel(it) }.orEmpty())
                DebugTags("焦点地点", runtimeState.eventState.activeFocus?.locations.orEmpty())
                DebugTags("焦点物品", runtimeState.eventState.activeFocus?.items.orEmpty())
                DebugTags("焦点事件", runtimeState.eventState.activeFocus?.events.orEmpty())
                DebugTags("焦点秘密", runtimeState.eventState.activeFocus?.secrets.orEmpty())
                DebugTags("焦点情绪", runtimeState.eventState.activeFocus?.emotions.orEmpty())
                DebugTags("焦点冲突", runtimeState.eventState.activeFocus?.conflicts.orEmpty())
            }

            DebugSection(title = "上下文分层") {
                DebugResolver(
                    assistant = assistant,
                    debugState = runtimeState.lastResolverDebug,
                )
            }

            DebugSection(title = "运行缓存") {
                DebugLine("私有记忆数量", runtimeState.privateNotes.size.toString())
                DebugLine("关系记录数量", runtimeState.relationships.size.toString())
                DebugCacheDetails(
                    runtimeState = runtimeState,
                    assistant = assistant,
                )
            }

            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onDismissRequest) {
                Text("关闭")
            }
        }
    }
}

@Composable
private fun DebugSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
        content()
        HorizontalDivider()
    }
}

@Composable
private fun DebugLine(
    label: String,
    value: String,
) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun DebugTags(
    label: String,
    values: List<String>,
) {
    DebugLine(
        label = label,
        value = values.filter { it.isNotBlank() }.joinToString(" / ").ifBlank { "无" },
    )
}

@Composable
private fun DebugScrollableBox(
    maxHeight: Dp = 160.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

@Composable
private fun DebugCacheDetails(
    runtimeState: GroupRuntimeState,
    assistant: Assistant?,
) {
    var expanded by remember { mutableStateOf(false) }

    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "收起详情 ▴" else "查看详情 ▾")
    }

    if (!expanded) return

    val notes = runtimeState.privateNotes.filterValues { it.isNotBlank() }
    Text(
        text = "私有记忆",
        style = MaterialTheme.typography.titleSmall,
    )
    if (notes.isEmpty()) {
        DebugLine("内容", "无")
    } else {
        DebugScrollableBox(maxHeight = 200.dp) {
            notes.forEach { (memberId, note) ->
                Text(
                    text = assistant.resolveMemberLabel(memberId),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }

    Text(
        text = "关系记录",
        style = MaterialTheme.typography.titleSmall,
    )
    if (runtimeState.relationships.isEmpty()) {
        DebugLine("内容", "无")
    } else {
        DebugScrollableBox(maxHeight = 200.dp) {
            runtimeState.relationships.forEach { (key, state) ->
                Text(
                    text = "${assistant.resolveMemberLabel(key.fromMemberId)} → ${assistant.resolveMemberLabel(key.toMemberId)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "好感 ${state.affinity} / 紧张 ${state.tension}" +
                        state.note.takeIf { it.isNotBlank() }?.let { " / 备注: $it" }.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun DebugResolver(
    assistant: Assistant?,
    debugState: GroupResolverDebugState?,
) {
    if (debugState == null) {
        DebugLine("解析结果", "暂无")
        return
    }

    DebugLine("当前回复角色", assistant.resolveMemberLabel(debugState.speakerId))
    DebugLine("上下文层级", debugState.layer)
    DebugLine(
        "评分",
        "event=${debugState.eventRelevance}, recent=${debugState.recentInteraction}, relation=${debugState.relationshipWeight}, total=${debugState.total}",
    )
    DebugTags("解析角色焦点", debugState.focusCharacters.map { assistant.resolveMemberLabel(it) })
    DebugTags("解析地点焦点", debugState.focusLocations)
    DebugTags("解析物品焦点", debugState.focusItems)
    DebugTags("解析事件焦点", debugState.focusEvents)
    DebugTags("解析秘密焦点", debugState.focusSecrets)
    DebugTags("解析情绪焦点", debugState.focusEmotions)
    DebugTags("解析冲突焦点", debugState.focusConflicts)
}

private fun buildRuntimeStateText(
    runtimeState: GroupRuntimeState,
    assistant: Assistant?,
): String {
    return buildString {
        appendLine("【群组运行状态】")
        appendLine()

        appendLine("— 寻址状态 —")
        appendLine("当前点名角色: ${assistant.resolveMemberLabel(runtimeState.activeAddressedMemberId)}")
        appendLine("点名消息 ID: ${runtimeState.activeAddressedTurnId?.toString() ?: "无"}")
        appendLine()

        appendLine("— 场景摘要 —")
        appendLine(runtimeState.scene.summary.ifBlank { "暂无摘要" })
        appendLine("场景紧张度: ${runtimeState.scene.tension}")
        appendLine()

        appendLine("— 活跃秘密 —")
        appendLine(runtimeState.scene.activeSecrets.joinToString("\n").ifBlank { "无" })
        appendLine()

        appendLine("— 事件状态 —")
        appendLine("近期事件数: ${runtimeState.eventState.recentEvents.size}")
        val focus = runtimeState.eventState.activeFocus
        appendLine("焦点角色: ${focus?.characterIds?.map { assistant.resolveMemberLabel(it) }?.joinToString(" / ")?.ifBlank { "无" } ?: "无"}")
        appendLine("焦点地点: ${focus?.locations?.joinToString(" / ")?.ifBlank { "无" } ?: "无"}")
        appendLine("焦点物品: ${focus?.items?.joinToString(" / ")?.ifBlank { "无" } ?: "无"}")
        appendLine("焦点事件: ${focus?.events?.joinToString(" / ")?.ifBlank { "无" } ?: "无"}")
        appendLine("焦点秘密: ${focus?.secrets?.joinToString(" / ")?.ifBlank { "无" } ?: "无"}")
        appendLine("焦点情绪: ${focus?.emotions?.joinToString(" / ")?.ifBlank { "无" } ?: "无"}")
        appendLine("焦点冲突: ${focus?.conflicts?.joinToString(" / ")?.ifBlank { "无" } ?: "无"}")
        appendLine()

        appendLine("— 上下文分层 —")
        val debug = runtimeState.lastResolverDebug
        if (debug == null) {
            appendLine("解析结果: 暂无")
        } else {
            appendLine("当前回复角色: ${assistant.resolveMemberLabel(debug.speakerId)}")
            appendLine("上下文层级: ${debug.layer}")
            appendLine("评分: event=${debug.eventRelevance}, recent=${debug.recentInteraction}, relation=${debug.relationshipWeight}, total=${debug.total}")
            appendLine("解析角色焦点: ${debug.focusCharacters.map { assistant.resolveMemberLabel(it) }.joinToString(" / ").ifBlank { "无" }}")
            appendLine("解析地点焦点: ${debug.focusLocations.joinToString(" / ").ifBlank { "无" }}")
            appendLine("解析物品焦点: ${debug.focusItems.joinToString(" / ").ifBlank { "无" }}")
            appendLine("解析事件焦点: ${debug.focusEvents.joinToString(" / ").ifBlank { "无" }}")
            appendLine("解析秘密焦点: ${debug.focusSecrets.joinToString(" / ").ifBlank { "无" }}")
            appendLine("解析情绪焦点: ${debug.focusEmotions.joinToString(" / ").ifBlank { "无" }}")
            appendLine("解析冲突焦点: ${debug.focusConflicts.joinToString(" / ").ifBlank { "无" }}")
        }
        appendLine()

        appendLine("— 运行缓存 —")
        appendLine("私有记忆数量: ${runtimeState.privateNotes.size}")
        runtimeState.privateNotes.filterValues { it.isNotBlank() }.forEach { (memberId, note) ->
            appendLine("  [${assistant.resolveMemberLabel(memberId)}] $note")
        }
        appendLine("关系记录数量: ${runtimeState.relationships.size}")
        runtimeState.relationships.forEach { (key, state) ->
            appendLine(
                "  ${assistant.resolveMemberLabel(key.fromMemberId)} → ${assistant.resolveMemberLabel(key.toMemberId)}: " +
                    "好感 ${state.affinity} / 紧张 ${state.tension}" +
                    state.note.takeIf { it.isNotBlank() }?.let { " / 备注: $it" }.orEmpty()
            )
        }
    }
}

private fun Assistant?.resolveMemberLabel(memberId: Uuid?): String {
    if (memberId == null) {
        return "无"
    }
    val member = this?.groupMembers?.find { it.id == memberId }
    return member?.displayName?.ifBlank { memberId.toString() } ?: memberId.toString()
}
