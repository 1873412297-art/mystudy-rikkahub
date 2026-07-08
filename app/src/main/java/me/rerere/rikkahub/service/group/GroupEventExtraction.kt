package me.rerere.rikkahub.service.group

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

private val LOCATION_KEYWORDS = listOf(
    "佛堂", "竹林", "山庄", "后山", "药园", "庭院", "书房", "大殿", "房中", "房间", "密室",
)
private val ITEM_KEYWORDS = listOf(
    "玉钥匙", "钥匙", "佛珠", "卷轴", "神秘小瓶", "药瓶", "长剑", "令牌", "药草", "乌凰琴",
)
private val EVENT_KEYWORDS = listOf(
    "警告", "质问", "隐瞒", "追问", "争吵", "安慰", "保护", "揭穿", "试探", "偷听", "对峙", "继续",
)
private val SECRET_KEYWORDS = listOf(
    "秘密", "真相", "不要告诉", "不能说", "隐瞒", "阴谋", "背叛", "不是人", "身份",
)
private val EMOTION_KEYWORDS = listOf(
    "愤怒", "紧张", "怀疑", "担忧", "害怕", "温柔", "嫉妒", " suspicious", "protective",
)
private val CONFLICT_KEYWORDS = listOf(
    "危险", "不信", "怀疑", "反驳", "敌意", "冲突", "威胁", "争执",
)

class GroupEventExtractor {
    fun extractFocus(
        groupAssistant: Assistant,
        messages: List<UIMessage>,
        runtimeState: GroupRuntimeState,
    ): GroupEventFocus {
        val localText = messages.takeLast(3).joinToString("\n") { it.toText() }
        val localCharacters = groupAssistant.groupMembers
            .filter { it.enabled && it.displayName.isNotBlank() && localText.contains(it.displayName, ignoreCase = true) }
            .map { it.id }

        val activeFocus = runtimeState.eventState.activeFocus
        return GroupEventFocus(
            characterIds = (activeFocus?.characterIds.orEmpty() + localCharacters).distinct(),
            locations = mergeKeywordMatches(activeFocus?.locations.orEmpty(), localText, LOCATION_KEYWORDS),
            items = mergeKeywordMatches(activeFocus?.items.orEmpty(), localText, ITEM_KEYWORDS),
            events = mergeKeywordMatches(activeFocus?.events.orEmpty(), localText, EVENT_KEYWORDS),
            secrets = mergeKeywordMatches(activeFocus?.secrets.orEmpty(), localText, SECRET_KEYWORDS),
            emotions = mergeKeywordMatches(activeFocus?.emotions.orEmpty(), localText, EMOTION_KEYWORDS),
            conflicts = mergeKeywordMatches(activeFocus?.conflicts.orEmpty(), localText, CONFLICT_KEYWORDS),
        )
    }

    fun extractRecord(
        groupAssistant: Assistant,
        sourceMessageId: Uuid,
        speakerId: Uuid?,
        messages: List<UIMessage>,
        runtimeState: GroupRuntimeState,
    ): GroupEventRecord {
        val focus = extractFocus(groupAssistant, messages, runtimeState)
        val importance = buildList {
            addAll(focus.secrets)
            addAll(focus.conflicts)
            addAll(focus.events)
        }.distinct().size.coerceIn(0, 10)
        return GroupEventRecord(
            sourceMessageId = sourceMessageId,
            speakerId = speakerId,
            characters = focus.characterIds,
            locations = focus.locations,
            items = focus.items,
            events = focus.events,
            secrets = focus.secrets,
            emotions = focus.emotions,
            conflicts = focus.conflicts,
            importance = importance,
        )
    }

    private fun mergeKeywordMatches(
        existing: List<String>,
        text: String,
        keywords: List<String>,
    ): List<String> {
        return (existing + keywords.filter { text.contains(it, ignoreCase = true) }).distinct()
    }
}
