package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.TurnTakingStrategy
import me.rerere.rikkahub.service.group.GroupDirectorEngine
import me.rerere.rikkahub.service.group.GroupPlaybackState
import kotlin.uuid.Uuid

data class GroupDirectorMemberUi(
    val id: Uuid,
    val name: String,
    val avatar: Avatar,
    val isQueuedNext: Boolean,
)

data class GroupDirectorUiState(
    val effectiveMode: TurnTakingStrategy,
    val playbackState: GroupPlaybackState,
    val isGenerating: Boolean,
    val oneRoundActive: Boolean,
    val oneRoundRemainingCount: Int,
    val members: List<GroupDirectorMemberUi>,
    val canPause: Boolean,
    val canContinueRound: Boolean,
    val canSkip: Boolean,
)

internal fun buildGroupDirectorUiState(
    conversation: Conversation,
    assistant: Assistant,
    settings: Settings,
    isGenerating: Boolean,
): GroupDirectorUiState? {
    if (assistant.assistantType != AssistantType.GROUP) return null
    val engine = GroupDirectorEngine()
    val enabledMembers = assistant.groupMembers.filter { it.enabled }
    val enabledIds = enabledMembers.map { it.id }
    val director = engine.sanitize(
        state = conversation.groupRuntimeState.director,
        enabledMemberIds = enabledIds,
        generationActive = isGenerating,
    )
    val eligibleIds = engine.eligibleMemberIds(director, enabledIds).toSet()
    return GroupDirectorUiState(
        effectiveMode = engine.effectiveStrategy(director, assistant.turnTakingStrategy),
        playbackState = director.playbackState,
        isGenerating = isGenerating,
        oneRoundActive = director.oneRoundActive,
        oneRoundRemainingCount = director.oneRoundRemainingMemberIds.size,
        members = enabledMembers.map { member ->
            val source = settings.assistants.find { it.id == member.assistantId }
            GroupDirectorMemberUi(
                id = member.id,
                name = member.displayName.ifBlank {
                    source?.name?.ifBlank { assistant.name } ?: assistant.name
                }.ifBlank { "?" },
                avatar = member.avatar,
                isQueuedNext = member.id == director.oneShotNextMemberId,
            )
        },
        canPause = director.playbackState != GroupPlaybackState.PAUSED,
        canContinueRound = !isGenerating && eligibleIds.isNotEmpty(),
        canSkip = eligibleIds.isNotEmpty(),
    )
}
