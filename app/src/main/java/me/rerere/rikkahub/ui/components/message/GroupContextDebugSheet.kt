package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "群组运行状态",
                style = MaterialTheme.typography.titleLarge,
            )

            DebugSection(title = "寻址状态") {
                DebugLine("当前点名角色", assistant.resolveMemberLabel(runtimeState.activeAddressedMemberId))
                DebugLine("点名消息 ID", runtimeState.activeAddressedTurnId?.toString() ?: "无")
            }

            DebugSection(title = "场景摘要") {
                Text(
                    text = runtimeState.scene.summary.ifBlank { "暂无摘要" },
                    style = MaterialTheme.typography.bodyMedium,
                )
                DebugLine("场景紧张度", runtimeState.scene.tension.toString())
            }

            DebugSection(title = "活跃秘密") {
                Text(
                    text = runtimeState.scene.activeSecrets.joinToString("\n").ifBlank { "无" },
                    style = MaterialTheme.typography.bodyMedium,
                )
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

private fun Assistant?.resolveMemberLabel(memberId: Uuid?): String {
    if (memberId == null) {
        return "无"
    }
    val member = this?.groupMembers?.find { it.id == memberId }
    return member?.displayName?.ifBlank { memberId.toString() } ?: memberId.toString()
}
