package me.rerere.rikkahub.service.group

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

data class GroupSpeakerScore(
    val memberId: Uuid,
    val score: Int,
    val intent: String,
    val reason: String,
)

class GroupSpeakerScorer {
    fun score(
        groupAssistant: Assistant,
        messages: List<UIMessage>,
        runtimeState: GroupRuntimeState,
        activeMemberId: Uuid?,
    ): List<GroupSpeakerScore> {
        val recentText = messages.takeLast(8).joinToString("\n") { it.toText() }
        val lastMemberId = messages.lastOrNull { it.memberId != null }?.memberId

        return groupAssistant.groupMembers
            .filter { it.enabled }
            .map { member ->
                val name = member.displayName
                val mentioned = name.isNotBlank() && recentText.contains(name, ignoreCase = true)
                val relationship = activeMemberId?.let {
                    runtimeState.relationships[GroupRelationshipKey(member.id, it)]
                }
                val tension = relationship?.tension ?: 0
                val affinity = relationship?.affinity ?: 0
                val consecutivePenalty = if (member.id == lastMemberId) -3 else 0
                val mentionBoost = if (mentioned) 10 else 0
                val tensionBoost = if (tension >= 6) 5 else 0
                val affinityBoost = if (affinity >= 4) 2 else 0
                val score = mentionBoost + tensionBoost + affinityBoost + consecutivePenalty
                val intent = when {
                    mentioned -> "answer_user"
                    tension >= 6 -> "challenge"
                    affinity >= 4 -> "comfort"
                    else -> "respond"
                }
                val reason = when (intent) {
                    "answer_user" -> "The user or another speaker mentioned $name."
                    "challenge" -> "Relationship tension is high."
                    "comfort" -> "Relationship affinity is high."
                    else -> "Default participation."
                }
                GroupSpeakerScore(
                    memberId = member.id,
                    score = score,
                    intent = intent,
                    reason = reason,
                )
            }
            .sortedWith(compareByDescending<GroupSpeakerScore> { it.score }.thenBy { it.memberId.toString() })
    }
}
