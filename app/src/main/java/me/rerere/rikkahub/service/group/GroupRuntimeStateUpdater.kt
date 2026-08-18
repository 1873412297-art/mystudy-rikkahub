package me.rerere.rikkahub.service.group

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

private const val MAX_SCENE_EVENT_LINES = 8
private const val MAX_SCENE_EVENT_CHARS = 120
private const val MAX_RECENT_EVENTS = 12

class GroupRuntimeStateUpdater {
    private val extractor = GroupEventExtractor()

    fun updateAfterReply(
        previous: GroupRuntimeState,
        groupAssistant: Assistant,
        messages: List<UIMessage>,
        speakerId: Uuid,
    ): GroupRuntimeState {
        val latestMessage = messages.lastOrNull { it.memberId == speakerId } ?: return previous
        val latestText = latestMessage.toText().trim()
        if (latestText.isBlank()) return previous

        val cleanedText = latestText.toSceneEventText()
        if (cleanedText.isBlank()) return previous

        val conflictMarkers = listOf("不相信", "怀疑", "反驳", "敌意", "危险", "背叛")
        val secretMarkers = listOf("秘密", "隐瞒", "不能说", "不要告诉", "真相")
        val tensionDelta = if (conflictMarkers.any { latestText.contains(it) }) 1 else 0
        val activeSecrets = if (secretMarkers.any { latestText.contains(it) }) {
            (previous.scene.activeSecrets + cleanedText.take(80)).distinct().takeLast(8)
        } else {
            previous.scene.activeSecrets
        }
        val speakerName = groupAssistant.groupMembers.find { it.id == speakerId }?.displayName?.ifBlank { null }
            ?: "角色"
        val summaryLine = "$speakerName: $cleanedText"
        val newSummary = previous.scene.summary
            .appendUniqueSceneLine(summaryLine)
            .takeRecentSceneLines(MAX_SCENE_EVENT_LINES)

        val recentWindow = messages.takeLast(3)
        val eventRecord = extractor.extractRecord(
            groupAssistant = groupAssistant,
            sourceMessageId = latestMessage.id,
            speakerId = speakerId,
            messages = recentWindow,
            runtimeState = previous,
        )
        val newRecentEvents = (previous.eventState.recentEvents + eventRecord)
            .distinctBy { it.sourceMessageId }
            .takeLast(MAX_RECENT_EVENTS)
        val newFocus = extractor.extractFocus(
            groupAssistant = groupAssistant,
            messages = recentWindow,
            runtimeState = previous.copy(
                eventState = previous.eventState.copy(recentEvents = newRecentEvents),
            ),
        )

        return previous.copy(
            scene = previous.scene.copy(
                summary = newSummary,
                tension = (previous.scene.tension + tensionDelta).coerceIn(0, 10),
                activeSecrets = activeSecrets,
            ),
            eventState = previous.eventState.copy(
                recentEvents = newRecentEvents,
                activeFocus = newFocus,
            ),
        )
    }
}

private fun String.toSceneEventText(): String {
    return lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .replace(Regex("\\s+"), " ")
        .take(MAX_SCENE_EVENT_CHARS)
        .trim()
}

private fun String.appendUniqueSceneLine(line: String): String {
    val existing = lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
    val normalizedLine = line.normalizedSceneLine()
    if (existing.any { it.normalizedSceneLine() == normalizedLine }) {
        return existing.joinToString("\n")
    }
    return (existing + line).joinToString("\n")
}

private fun String.takeRecentSceneLines(maxLines: Int): String {
    return lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .takeLast(maxLines)
        .joinToString("\n")
}

private fun String.normalizedSceneLine(): String {
    return replace(Regex("\\s+"), "")
        .replace(Regex("[。！？!?，,；;：:]"), "")
}
