package me.rerere.rikkahub.service.group

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import kotlin.math.max
import kotlin.uuid.Uuid

data class DynamicGroupContextResult(
    val visibleMessages: List<UIMessage>,
    val adjustedRuntimeState: GroupRuntimeState,
    val layer: GroupContextLayer,
    val focus: GroupEventFocus,
    val scoreBreakdown: GroupContextScoreBreakdown,
    val debugState: GroupResolverDebugState,
)

class DynamicGroupContextResolver {
    private val extractor = GroupEventExtractor()

    fun resolve(
        groupAssistant: Assistant,
        messages: List<UIMessage>,
        effectiveMemberId: Uuid,
        runtimeState: GroupRuntimeState,
    ): DynamicGroupContextResult {
        val filteredMessages = messages.applyGroupContextFilter(groupAssistant, effectiveMemberId)
        val contextMessages = filteredMessages.withAddressedUserPrompt(
            originalMessages = messages,
            effectiveMemberId = effectiveMemberId,
            runtimeState = runtimeState,
        )
        val focus = extractor.extractFocus(groupAssistant, contextMessages, runtimeState)
        val scores = scoreMembers(groupAssistant, contextMessages, runtimeState, focus)
        val speakerScore = scores[effectiveMemberId] ?: GroupContextScoreBreakdown()
        val maxScore = scores.values.maxOfOrNull { it.total } ?: 0
        val layer = classifyLayer(
            memberId = effectiveMemberId,
            runtimeState = runtimeState,
            score = speakerScore,
            maxScore = maxScore,
            focus = focus,
        )
        val visibleMessages = buildVisibleMessages(contextMessages, effectiveMemberId, layer, focus)
        val adjustedRuntimeState = adjustRuntimeState(runtimeState, effectiveMemberId, layer, focus)
        val debugState = GroupResolverDebugState(
            speakerId = effectiveMemberId,
            layer = layer.name,
            eventRelevance = speakerScore.eventRelevance,
            recentInteraction = speakerScore.recentInteraction,
            relationshipWeight = speakerScore.relationshipWeight,
            total = speakerScore.total,
            focusCharacters = focus.characterIds,
            focusLocations = focus.locations,
            focusItems = focus.items,
            focusEvents = focus.events,
            focusSecrets = focus.secrets,
            focusEmotions = focus.emotions,
            focusConflicts = focus.conflicts,
        )
        return DynamicGroupContextResult(
            visibleMessages = visibleMessages,
            adjustedRuntimeState = adjustedRuntimeState,
            layer = layer,
            focus = focus,
            scoreBreakdown = speakerScore,
            debugState = debugState,
        )
    }

    private fun scoreMembers(
        groupAssistant: Assistant,
        messages: List<UIMessage>,
        runtimeState: GroupRuntimeState,
        focus: GroupEventFocus,
    ): Map<Uuid, GroupContextScoreBreakdown> {
        val lastSpeakerId = messages.lastOrNull { it.memberId != null }?.memberId
        val recentText = messages.takeLast(4).joinToString("\n") { it.toText() }
        return groupAssistant.groupMembers
            .filter { it.enabled }
            .associate { member ->
                var eventRelevance = 0
                var recentInteraction = 0
                var relationshipWeight = 0

                if (runtimeState.activeAddressedMemberId == member.id) {
                    eventRelevance += 100
                }
                if (member.id in focus.characterIds) {
                    eventRelevance += 8
                }
                if (member.displayName.isNotBlank() && recentText.contains(member.displayName, ignoreCase = true)) {
                    eventRelevance += 6
                }
                val privateNote = runtimeState.privateNotes[member.id].orEmpty()
                if (focus.secrets.any { privateNote.contains(it, ignoreCase = true) } ||
                    focus.items.any { privateNote.contains(it, ignoreCase = true) }
                ) {
                    eventRelevance += 8
                }

                if (member.id == lastSpeakerId) {
                    recentInteraction += 2
                }
                if (messages.takeLast(4).any { it.memberId == member.id }) {
                    recentInteraction += 1
                }

                focus.characterIds.forEach { focusCharacterId ->
                    runtimeState.relationships[GroupRelationshipKey(member.id, focusCharacterId)]?.let { relation ->
                        relationshipWeight = max(
                            relationshipWeight,
                            max(relation.affinity.coerceAtLeast(0), relation.tension.coerceAtLeast(0)),
                        )
                    }
                }

                val total = eventRelevance + recentInteraction + relationshipWeight
                member.id to GroupContextScoreBreakdown(
                    eventRelevance = eventRelevance,
                    recentInteraction = recentInteraction,
                    relationshipWeight = relationshipWeight,
                    total = total,
                )
            }
    }

    private fun classifyLayer(
        memberId: Uuid,
        runtimeState: GroupRuntimeState,
        score: GroupContextScoreBreakdown,
        maxScore: Int,
        focus: GroupEventFocus,
    ): GroupContextLayer {
        if (runtimeState.activeAddressedMemberId == memberId) {
            return GroupContextLayer.CORE
        }
        if (score.total >= 6 && score.total == maxScore) {
            return GroupContextLayer.CORE
        }
        if (focus.secrets.isNotEmpty() && score.eventRelevance >= 8) {
            return GroupContextLayer.CORE
        }
        if (score.total >= 4) {
            return GroupContextLayer.STRONGLY_RELATED
        }
        if (score.total >= 1) {
            return GroupContextLayer.WEAKLY_RELATED
        }
        return GroupContextLayer.ISOLATED
    }

    private fun buildVisibleMessages(
        messages: List<UIMessage>,
        effectiveMemberId: Uuid,
        layer: GroupContextLayer,
        focus: GroupEventFocus,
    ): List<UIMessage> {
        return when (layer) {
            GroupContextLayer.CORE -> {
                val roundWindow = if (focus.secrets.isNotEmpty() || focus.conflicts.isNotEmpty() || focus.events.size >= 2) 10 else 6
                messages.takeRecentRounds(roundWindow)
            }

            GroupContextLayer.STRONGLY_RELATED -> {
                val recent = messages.takeRecentRounds(2)
                val ownLatest = messages.lastOrNull { it.memberId == effectiveMemberId }
                messages.keepInOriginalOrder(
                    recent +
                        messages.takeRelationshipDramaAnchors(effectiveMemberId, focus) +
                        listOfNotNull(ownLatest)
                )
            }

            GroupContextLayer.WEAKLY_RELATED -> {
                val lastUser = messages.lastOrNull { it.role == MessageRole.USER }
                val ownLatest = messages.lastOrNull { it.memberId == effectiveMemberId }
                val latestPublicReply = messages.lastOrNull {
                    it.role == MessageRole.ASSISTANT && it.memberId != null && it.memberId != effectiveMemberId
                }
                messages.keepInOriginalOrder(
                    listOfNotNull(ownLatest, latestPublicReply) +
                        messages.takeRelationshipDramaAnchors(effectiveMemberId, focus) +
                        listOfNotNull(lastUser)
                )
            }

            GroupContextLayer.ISOLATED -> {
                listOfNotNull(messages.lastOrNull { it.role == MessageRole.USER })
            }
        }
    }

    private fun adjustRuntimeState(
        runtimeState: GroupRuntimeState,
        effectiveMemberId: Uuid,
        layer: GroupContextLayer,
        focus: GroupEventFocus,
    ): GroupRuntimeState {
        return when (layer) {
            GroupContextLayer.CORE -> runtimeState
            GroupContextLayer.STRONGLY_RELATED -> runtimeState.copy(
                privateNotes = emptyMap(),
                relationships = runtimeState.relationships.filterKeys { it.fromMemberId == effectiveMemberId && it.toMemberId in focus.characterIds.toSet() },
                scene = runtimeState.scene.copy(summary = runtimeState.scene.summary.toRecentSceneSummary(maxLines = 1)),
            )

            GroupContextLayer.WEAKLY_RELATED -> runtimeState.copy(
                privateNotes = emptyMap(),
                relationships = emptyMap(),
                scene = runtimeState.scene.copy(summary = runtimeState.scene.summary.toRecentSceneSummary(maxLines = 1)),
            )

            GroupContextLayer.ISOLATED -> GroupRuntimeState(
                activeAddressedMemberId = runtimeState.activeAddressedMemberId,
                activeAddressedTurnId = runtimeState.activeAddressedTurnId,
            )
        }
    }
}

private fun List<UIMessage>.withAddressedUserPrompt(
    originalMessages: List<UIMessage>,
    effectiveMemberId: Uuid,
    runtimeState: GroupRuntimeState,
): List<UIMessage> {
    if (runtimeState.activeAddressedMemberId != effectiveMemberId) return this
    val latestUser = originalMessages.lastOrNull { it.role == MessageRole.USER } ?: return this
    if (any { it.id == latestUser.id }) return this
    return (this + latestUser).keepInOriginalOrder(originalMessages)
}

private fun List<UIMessage>.keepInOriginalOrder(selectedMessages: List<UIMessage>): List<UIMessage> {
    val selectedIds = selectedMessages.map { it.id }.toSet()
    return filter { it.id in selectedIds }.distinctBy { it.id }
}

private fun List<UIMessage>.takeRelationshipDramaAnchors(
    effectiveMemberId: Uuid,
    focus: GroupEventFocus,
): List<UIMessage> {
    if (!focus.hasRelationshipDramaSignal()) return emptyList()
    val focusCharacterIds = focus.characterIds.toSet() - effectiveMemberId
    if (focusCharacterIds.isEmpty()) return emptyList()
    return focusCharacterIds.mapNotNull { memberId ->
        lastOrNull { message ->
            message.role == MessageRole.ASSISTANT && message.memberId == memberId
        }
    }
}

private fun GroupEventFocus.hasRelationshipDramaSignal(): Boolean {
    val text = (secrets + emotions + conflicts + events)
        .joinToString(" ")
        .lowercase()
    return RELATIONSHIP_DRAMA_KEYWORDS.any { keyword -> keyword in text }
}

private val RELATIONSHIP_DRAMA_KEYWORDS = listOf(
    "affair",
    "betrayal",
    "cheat",
    "cheating",
    "jealous",
    "jealousy",
    "ntr",
    "rival",
    "secret romance",
)

private fun List<UIMessage>.takeRecentRounds(userTurns: Int): List<UIMessage> {
    if (isEmpty()) return emptyList()
    val userIndexes = mapIndexedNotNull { index, message ->
        index.takeIf { message.role == MessageRole.USER }
    }
    if (userIndexes.isEmpty()) return takeLast(userTurns * 2)
    val startIndex = userIndexes.takeLast(userTurns).firstOrNull() ?: 0
    return drop(startIndex)
}

private fun String.toRecentSceneSummary(maxLines: Int = 1): String {
    return lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .takeLast(maxLines)
        .joinToString("\n")
}
