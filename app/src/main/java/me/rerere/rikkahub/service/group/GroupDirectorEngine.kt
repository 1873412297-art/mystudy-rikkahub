package me.rerere.rikkahub.service.group

import me.rerere.rikkahub.data.model.TurnTakingStrategy
import kotlin.uuid.Uuid

sealed interface GroupDirectorCommand {
    data object PauseAfterCurrent : GroupDirectorCommand
    data object ContinueOneRound : GroupDirectorCommand
    data object SkipNext : GroupDirectorCommand
    data class QueueMemberOnce(val memberId: Uuid) : GroupDirectorCommand
    data class SetMode(val strategy: TurnTakingStrategy) : GroupDirectorCommand
}

enum class GroupDirectorCommandStatus {
    APPLIED,
    NOT_GROUP,
    NO_ENABLED_MEMBERS,
    INVALID_MEMBER,
    NO_ALTERNATIVE_MEMBER,
}

data class GroupDirectorCommandContext(
    val generationActive: Boolean,
    val orderedEnabledMemberIds: List<Uuid>,
)

data class GroupDirectorCommandResult(
    val state: GroupDirectorState,
    val status: GroupDirectorCommandStatus = GroupDirectorCommandStatus.APPLIED,
    val shouldStartGeneration: Boolean = false,
)

data class GroupDirectorSelectionResult(
    val memberId: Uuid?,
    val state: GroupDirectorState,
    val status: GroupDirectorCommandStatus = GroupDirectorCommandStatus.APPLIED,
)

class GroupDirectorEngine {
    fun effectiveStrategy(
        state: GroupDirectorState,
        assistantDefault: TurnTakingStrategy,
    ): TurnTakingStrategy = state.modeOverride ?: assistantDefault

    fun sanitize(
        state: GroupDirectorState,
        enabledMemberIds: List<Uuid>,
        generationActive: Boolean,
    ): GroupDirectorState {
        val enabled = enabledMemberIds.distinct()
        val enabledSet = enabled.toSet()
        val remaining = state.oneRoundRemainingMemberIds
            .filter { it in enabledSet }
            .distinct()
        val roundActive = state.oneRoundActive && remaining.isNotEmpty()
        val allowedOneShotIds = if (roundActive) remaining.toSet() else enabledSet
        val oneShot = state.oneShotNextMemberId?.takeIf { it in allowedOneShotIds }
        val keepReturnToPaused = state.oneShotReturnToPaused &&
            (generationActive || oneShot != null)
        val playback = when {
            state.oneRoundActive && !roundActive -> GroupPlaybackState.PAUSED
            !generationActive && state.playbackState == GroupPlaybackState.PAUSE_AFTER_CURRENT ->
                GroupPlaybackState.PAUSED
            !generationActive && roundActive -> GroupPlaybackState.PAUSED
            !generationActive && state.oneShotReturnToPaused -> GroupPlaybackState.PAUSED
            else -> state.playbackState
        }
        return state.copy(
            playbackState = playback,
            oneShotNextMemberId = oneShot,
            oneShotReturnToPaused = keepReturnToPaused,
            oneRoundActive = roundActive,
            oneRoundRemainingMemberIds = remaining,
        )
    }

    fun eligibleMemberIds(
        state: GroupDirectorState,
        enabledMemberIds: List<Uuid>,
    ): List<Uuid> {
        val enabled = enabledMemberIds.distinct()
        if (!state.oneRoundActive) return enabled
        val enabledSet = enabled.toSet()
        return state.oneRoundRemainingMemberIds
            .filter { it in enabledSet }
            .distinct()
    }

    fun reduce(
        state: GroupDirectorState,
        command: GroupDirectorCommand,
        context: GroupDirectorCommandContext,
    ): GroupDirectorCommandResult {
        val enabled = context.orderedEnabledMemberIds.distinct()
        val clean = sanitize(state, enabled, context.generationActive)
        val eligible = eligibleMemberIds(clean, enabled)
        return when (command) {
            GroupDirectorCommand.PauseAfterCurrent -> GroupDirectorCommandResult(
                state = clean.copy(
                    playbackState = if (context.generationActive) {
                        GroupPlaybackState.PAUSE_AFTER_CURRENT
                    } else {
                        GroupPlaybackState.PAUSED
                    }
                )
            )

            GroupDirectorCommand.ContinueOneRound -> {
                if (enabled.isEmpty()) {
                    GroupDirectorCommandResult(clean, GroupDirectorCommandStatus.NO_ENABLED_MEMBERS)
                } else {
                    val remaining = if (clean.oneRoundActive && clean.oneRoundRemainingMemberIds.isNotEmpty()) {
                        clean.oneRoundRemainingMemberIds
                    } else {
                        enabled
                    }
                    GroupDirectorCommandResult(
                        state = clean.copy(
                            playbackState = GroupPlaybackState.RUNNING,
                            oneRoundActive = true,
                            oneRoundRemainingMemberIds = remaining,
                        ),
                        shouldStartGeneration = !context.generationActive,
                    )
                }
            }

            GroupDirectorCommand.SkipNext -> {
                if (eligible.size < 2) {
                    GroupDirectorCommandResult(
                        state = clean.copy(skipNextRequested = false),
                        status = GroupDirectorCommandStatus.NO_ALTERNATIVE_MEMBER,
                    )
                } else {
                    GroupDirectorCommandResult(clean.copy(skipNextRequested = true))
                }
            }

            is GroupDirectorCommand.QueueMemberOnce -> when {
                enabled.isEmpty() -> GroupDirectorCommandResult(
                    clean,
                    GroupDirectorCommandStatus.NO_ENABLED_MEMBERS,
                )
                command.memberId !in eligible -> GroupDirectorCommandResult(
                    clean,
                    GroupDirectorCommandStatus.INVALID_MEMBER,
                )
                else -> GroupDirectorCommandResult(
                    state = clean.copy(
                        oneShotNextMemberId = command.memberId,
                        oneShotReturnToPaused = clean.playbackState != GroupPlaybackState.RUNNING,
                    ),
                    shouldStartGeneration = !context.generationActive,
                )
            }

            is GroupDirectorCommand.SetMode -> GroupDirectorCommandResult(
                clean.copy(
                    modeOverride = command.strategy,
                    playbackState = if (command.strategy == TurnTakingStrategy.MANUAL) {
                        if (context.generationActive) {
                            GroupPlaybackState.PAUSE_AFTER_CURRENT
                        } else {
                            GroupPlaybackState.PAUSED
                        }
                    } else {
                        clean.playbackState
                    },
                )
            )
        }
    }

    fun applyCandidate(
        state: GroupDirectorState,
        normalCandidateId: Uuid?,
        orderedCandidateMemberIds: List<Uuid>,
    ): GroupDirectorSelectionResult {
        val ordered = orderedCandidateMemberIds.distinct()
        val clean = sanitize(state, ordered, generationActive = true)
        if (clean.playbackState == GroupPlaybackState.PAUSED && clean.oneShotNextMemberId == null) {
            return GroupDirectorSelectionResult(null, clean)
        }
        val usedOneShot = clean.oneShotNextMemberId != null
        val candidate = clean.oneShotNextMemberId ?: normalCandidateId
        if (candidate == null || candidate !in ordered) {
            return GroupDirectorSelectionResult(null, clean)
        }
        val selected = if (clean.skipNextRequested) {
            val start = ordered.indexOf(candidate)
            (1 until ordered.size)
                .asSequence()
                .map { offset -> ordered[(start + offset) % ordered.size] }
                .firstOrNull { it != candidate }
                ?: return GroupDirectorSelectionResult(
                    memberId = null,
                    state = clean.copy(skipNextRequested = false),
                    status = GroupDirectorCommandStatus.NO_ALTERNATIVE_MEMBER,
                )
        } else {
            candidate
        }
        return GroupDirectorSelectionResult(
            memberId = selected,
            state = clean.copy(
                oneShotNextMemberId = if (usedOneShot) null else clean.oneShotNextMemberId,
                skipNextRequested = false,
            ),
        )
    }

    fun afterReply(state: GroupDirectorState, speakerId: Uuid): GroupDirectorState {
        val remaining = if (state.oneRoundActive) {
            state.oneRoundRemainingMemberIds.filterNot { it == speakerId }
        } else {
            state.oneRoundRemainingMemberIds
        }
        val roundFinished = state.oneRoundActive && remaining.isEmpty()
        val oneShotFinished = state.oneShotReturnToPaused && state.oneShotNextMemberId == null
        val pauseFinished = state.playbackState == GroupPlaybackState.PAUSE_AFTER_CURRENT &&
            state.oneShotNextMemberId == null
        return state.copy(
            playbackState = if (roundFinished || oneShotFinished || pauseFinished) {
                GroupPlaybackState.PAUSED
            } else {
                state.playbackState
            },
            oneShotReturnToPaused = if (oneShotFinished) false else state.oneShotReturnToPaused,
            oneRoundActive = state.oneRoundActive && !roundFinished,
            oneRoundRemainingMemberIds = remaining,
        )
    }

    fun afterNoCandidate(
        state: GroupDirectorState,
        effectiveStrategy: TurnTakingStrategy? = null,
    ): GroupDirectorState = if (state.oneRoundActive) {
        state.copy(
            playbackState = GroupPlaybackState.PAUSED,
            oneRoundActive = false,
            oneRoundRemainingMemberIds = emptyList(),
            skipNextRequested = false,
        )
    } else {
        state.copy(
            playbackState = if (
                effectiveStrategy == TurnTakingStrategy.MANUAL ||
                state.playbackState == GroupPlaybackState.PAUSE_AFTER_CURRENT
            ) {
                GroupPlaybackState.PAUSED
            } else {
                state.playbackState
            },
            skipNextRequested = false,
        )
    }

    fun afterFailure(state: GroupDirectorState): GroupDirectorState {
        val mustPause = state.oneRoundActive ||
            state.oneShotReturnToPaused ||
            state.playbackState == GroupPlaybackState.PAUSE_AFTER_CURRENT
        return state.copy(
            playbackState = if (mustPause) GroupPlaybackState.PAUSED else state.playbackState,
            oneShotReturnToPaused = false,
        )
    }

    fun afterCancellation(state: GroupDirectorState): GroupDirectorState = state.copy(
        playbackState = GroupPlaybackState.PAUSED,
        oneShotNextMemberId = null,
        oneShotReturnToPaused = false,
        skipNextRequested = false,
    )

    fun shouldContinueAfterReply(
        state: GroupDirectorState,
        effectiveStrategy: TurnTakingStrategy,
        isAddressedTurn: Boolean,
        alreadySent: Int,
        configuredLimit: Int,
    ): Boolean {
        if (state.oneShotNextMemberId != null) return true
        if (isAddressedTurn || state.playbackState != GroupPlaybackState.RUNNING) return false
        if (state.oneRoundActive) return state.oneRoundRemainingMemberIds.isNotEmpty()
        if (effectiveStrategy == TurnTakingStrategy.MANUAL) return false
        return shouldContinueGroupAutoReplies(alreadySent, configuredLimit)
    }
}
