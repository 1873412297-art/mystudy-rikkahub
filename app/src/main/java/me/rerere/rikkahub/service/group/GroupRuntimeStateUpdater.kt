package me.rerere.rikkahub.service.group

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

class GroupRuntimeStateUpdater {
    fun updateAfterReply(
        previous: GroupRuntimeState,
        groupAssistant: Assistant,
        messages: List<UIMessage>,
        speakerId: Uuid,
    ): GroupRuntimeState {
        val latestText = messages.lastOrNull { it.memberId == speakerId }?.toText()?.trim().orEmpty()
        if (latestText.isBlank()) return previous

        val conflictMarkers = listOf("不相信", "怀疑", "反驳", "敌意", "危险", "背叛")
        val secretMarkers = listOf("秘密", "隐瞒", "不能说", "不要告诉", "藏")
        val tensionDelta = if (conflictMarkers.any { latestText.contains(it) }) 1 else 0
        val activeSecrets = if (secretMarkers.any { latestText.contains(it) }) {
            (previous.scene.activeSecrets + latestText.take(80)).distinct().takeLast(8)
        } else {
            previous.scene.activeSecrets
        }
        val speakerName = groupAssistant.groupMembers.find { it.id == speakerId }?.displayName?.ifBlank { null }
            ?: "角色"
        val summaryLine = "$speakerName: ${latestText.take(120)}"
        val newSummary = listOf(previous.scene.summary, summaryLine)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .takeLast(800)

        return previous.copy(
            scene = previous.scene.copy(
                summary = newSummary,
                tension = (previous.scene.tension + tensionDelta).coerceIn(0, 10),
                activeSecrets = activeSecrets,
            )
        )
    }
}
