package me.rerere.rikkahub.service.group

import me.rerere.rikkahub.data.model.TurnTakingStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class GroupDirectorEngineTest {
    private val engine = GroupDirectorEngine()
    private val a = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val b = Uuid.parse("00000000-0000-0000-0000-000000000002")
    private val c = Uuid.parse("00000000-0000-0000-0000-000000000003")
    private val enabled = listOf(a, b, c)

    private fun context(active: Boolean = false) = GroupDirectorCommandContext(
        generationActive = active,
        orderedEnabledMemberIds = enabled,
    )

    @Test
    fun `idle pause is immediate and active pause waits for completion`() {
        val idle = engine.reduce(GroupDirectorState(), GroupDirectorCommand.PauseAfterCurrent, context())
        val active = engine.reduce(GroupDirectorState(), GroupDirectorCommand.PauseAfterCurrent, context(true))

        assertEquals(GroupPlaybackState.PAUSED, idle.state.playbackState)
        assertEquals(GroupPlaybackState.PAUSE_AFTER_CURRENT, active.state.playbackState)
        assertEquals(active.state, engine.reduce(active.state, GroupDirectorCommand.PauseAfterCurrent, context(true)).state)
    }

    @Test
    fun `one round snapshots order and removes each completed speaker once`() {
        val started = engine.reduce(GroupDirectorState(), GroupDirectorCommand.ContinueOneRound, context())
        val afterA = engine.afterReply(started.state, a)
        val duplicateA = engine.afterReply(afterA, a)
        val afterB = engine.afterReply(duplicateA, b)
        val finished = engine.afterReply(afterB, c)

        assertEquals(enabled, started.state.oneRoundRemainingMemberIds)
        assertEquals(listOf(b, c), duplicateA.oneRoundRemainingMemberIds)
        assertFalse(finished.oneRoundActive)
        assertEquals(GroupPlaybackState.PAUSED, finished.playbackState)
    }

    @Test
    fun `restored round stays paused and newly enabled member is excluded`() {
        val restored = engine.sanitize(
            state = GroupDirectorState(
                playbackState = GroupPlaybackState.RUNNING,
                oneRoundActive = true,
                oneRoundRemainingMemberIds = listOf(a, b),
            ),
            enabledMemberIds = listOf(a, b, c),
            generationActive = false,
        )

        assertEquals(GroupPlaybackState.PAUSED, restored.playbackState)
        assertEquals(listOf(a, b), restored.oneRoundRemainingMemberIds)
    }

    @Test
    fun `paused nomination runs once and returns to paused`() {
        val queued = engine.reduce(
            GroupDirectorState(playbackState = GroupPlaybackState.PAUSED),
            GroupDirectorCommand.QueueMemberOnce(b),
            context(),
        )
        val selected = engine.applyCandidate(queued.state, normalCandidateId = a, orderedCandidateMemberIds = enabled)
        val completed = engine.afterReply(selected.state, b)

        assertTrue(queued.shouldStartGeneration)
        assertEquals(b, selected.memberId)
        assertNull(selected.state.oneShotNextMemberId)
        assertEquals(GroupPlaybackState.PAUSED, completed.playbackState)
        assertFalse(completed.oneShotReturnToPaused)
    }

    @Test
    fun `skip consumes one candidate and selects the following queue member`() {
        val pending = engine.reduce(GroupDirectorState(), GroupDirectorCommand.SkipNext, context()).state
        val selected = engine.applyCandidate(pending, normalCandidateId = a, orderedCandidateMemberIds = enabled)

        assertEquals(b, selected.memberId)
        assertFalse(selected.state.skipNextRequested)
    }

    @Test
    fun `single member skip clears request and reports no alternative`() {
        val result = engine.reduce(
            GroupDirectorState(skipNextRequested = true),
            GroupDirectorCommand.SkipNext,
            GroupDirectorCommandContext(false, listOf(a)),
        )

        assertEquals(GroupDirectorCommandStatus.NO_ALTERNATIVE_MEMBER, result.status)
        assertFalse(result.state.skipNextRequested)
    }

    @Test
    fun `manual mode during ordinary generation waits for current reply then blocks chaining`() {
        val state = engine.reduce(
            GroupDirectorState(),
            GroupDirectorCommand.SetMode(TurnTakingStrategy.MANUAL),
            context(true),
        ).state
        val afterCurrent = engine.afterReply(state, a)

        assertEquals(TurnTakingStrategy.MANUAL, engine.effectiveStrategy(state, TurnTakingStrategy.AUTO_MODERATOR))
        assertEquals(GroupPlaybackState.PAUSE_AFTER_CURRENT, state.playbackState)
        assertEquals(GroupPlaybackState.PAUSED, afterCurrent.playbackState)
        assertFalse(engine.shouldContinueAfterReply(afterCurrent, TurnTakingStrategy.MANUAL, false, 1, 3))
    }

    @Test
    fun `manual mode during one round pauses after current and retains remainder`() {
        val state = engine.reduce(
            GroupDirectorState(
                playbackState = GroupPlaybackState.RUNNING,
                oneRoundActive = true,
                oneRoundRemainingMemberIds = enabled,
            ),
            GroupDirectorCommand.SetMode(TurnTakingStrategy.MANUAL),
            context(true),
        ).state
        val afterCurrent = engine.afterReply(state, a)

        assertEquals(GroupPlaybackState.PAUSE_AFTER_CURRENT, state.playbackState)
        assertTrue(state.oneRoundActive)
        assertEquals(GroupPlaybackState.PAUSED, afterCurrent.playbackState)
        assertTrue(afterCurrent.oneRoundActive)
        assertEquals(listOf(b, c), afterCurrent.oneRoundRemainingMemberIds)
        assertFalse(engine.shouldContinueAfterReply(afterCurrent, TurnTakingStrategy.MANUAL, false, 1, 3))
    }

    @Test
    fun `pending one shot continues after current reply even when pause is pending`() {
        val queued = engine.reduce(
            GroupDirectorState(playbackState = GroupPlaybackState.PAUSE_AFTER_CURRENT),
            GroupDirectorCommand.QueueMemberOnce(b),
            context(true),
        ).state
        val afterCurrent = engine.afterReply(queued, a)

        assertTrue(engine.shouldContinueAfterReply(afterCurrent, TurnTakingStrategy.AUTO_ROUND_ROBIN, false, 1, 1))
        assertEquals(b, afterCurrent.oneShotNextMemberId)
    }

    @Test
    fun `active one round continues past ordinary cap while members remain`() {
        val state = GroupDirectorState(
            playbackState = GroupPlaybackState.RUNNING,
            oneRoundActive = true,
            oneRoundRemainingMemberIds = listOf(b, c),
        )

        assertTrue(
            engine.shouldContinueAfterReply(
                state = state,
                effectiveStrategy = TurnTakingStrategy.AUTO_MODERATOR,
                isAddressedTurn = false,
                alreadySent = 9,
                configuredLimit = 1,
            )
        )
    }

    @Test
    fun `ordinary auto mode stops at configured cap`() {
        assertFalse(
            engine.shouldContinueAfterReply(
                state = GroupDirectorState(playbackState = GroupPlaybackState.RUNNING),
                effectiveStrategy = TurnTakingStrategy.AUTO_MODERATOR,
                isAddressedTurn = false,
                alreadySent = 1,
                configuredLimit = 1,
            )
        )
    }

    @Test
    fun `failure pauses explicit director run and clears one shot return`() {
        val failed = engine.afterFailure(
            GroupDirectorState(
                playbackState = GroupPlaybackState.RUNNING,
                oneShotReturnToPaused = true,
            )
        )

        assertEquals(GroupPlaybackState.PAUSED, failed.playbackState)
        assertFalse(failed.oneShotReturnToPaused)
    }

    @Test
    fun `failure keeps ordinary running playback active`() {
        val failed = engine.afterFailure(
            GroupDirectorState(playbackState = GroupPlaybackState.RUNNING)
        )

        assertEquals(GroupPlaybackState.RUNNING, failed.playbackState)
    }

    @Test
    fun `pending pause cancellation becomes paused and clears transient commands`() {
        val cancelled = engine.afterCancellation(
            GroupDirectorState(
                playbackState = GroupPlaybackState.PAUSE_AFTER_CURRENT,
                oneShotNextMemberId = b,
                oneShotReturnToPaused = true,
                skipNextRequested = true,
            )
        )

        assertEquals(GroupPlaybackState.PAUSED, cancelled.playbackState)
        assertNull(cancelled.oneShotNextMemberId)
        assertFalse(cancelled.oneShotReturnToPaused)
        assertFalse(cancelled.skipNextRequested)
    }

    @Test
    fun `ordinary running cancellation becomes paused`() {
        val cancelled = engine.afterCancellation(
            GroupDirectorState(playbackState = GroupPlaybackState.RUNNING)
        )

        assertEquals(GroupPlaybackState.PAUSED, cancelled.playbackState)
    }

    @Test
    fun `one round cancellation pauses and retains remainder`() {
        val cancelled = engine.afterCancellation(
            GroupDirectorState(
                playbackState = GroupPlaybackState.RUNNING,
                oneRoundActive = true,
                oneRoundRemainingMemberIds = listOf(b, c),
            )
        )

        assertEquals(GroupPlaybackState.PAUSED, cancelled.playbackState)
        assertTrue(cancelled.oneRoundActive)
        assertEquals(listOf(b, c), cancelled.oneRoundRemainingMemberIds)
    }

    @Test
    fun `moderator stop ends an active round and keeps it paused`() {
        val stopped = engine.afterNoCandidate(
            GroupDirectorState(
                playbackState = GroupPlaybackState.RUNNING,
                oneRoundActive = true,
                oneRoundRemainingMemberIds = listOf(a, b),
            )
        )

        assertFalse(stopped.oneRoundActive)
        assertEquals(emptyList<Uuid>(), stopped.oneRoundRemainingMemberIds)
        assertEquals(GroupPlaybackState.PAUSED, stopped.playbackState)
    }

    @Test
    fun `manual automatic selection stop normalizes stale running playback`() {
        val stopped = engine.afterNoCandidate(
            state = GroupDirectorState(
                modeOverride = TurnTakingStrategy.MANUAL,
                playbackState = GroupPlaybackState.RUNNING,
            ),
            effectiveStrategy = TurnTakingStrategy.MANUAL,
        )

        assertEquals(GroupPlaybackState.PAUSED, stopped.playbackState)
    }

    @Test
    fun `sanitization removes stale round and one shot members`() {
        val stale = Uuid.parse("00000000-0000-0000-0000-000000000099")
        val sanitized = engine.sanitize(
            state = GroupDirectorState(
                oneShotNextMemberId = stale,
                oneRoundActive = true,
                oneRoundRemainingMemberIds = listOf(a, stale, b, a),
            ),
            enabledMemberIds = listOf(a, b),
            generationActive = true,
        )

        assertNull(sanitized.oneShotNextMemberId)
        assertEquals(listOf(a, b), sanitized.oneRoundRemainingMemberIds)
    }

    @Test
    fun `running nomination consumes once without forcing pause`() {
        val queued = engine.reduce(
            GroupDirectorState(playbackState = GroupPlaybackState.RUNNING),
            GroupDirectorCommand.QueueMemberOnce(c),
            context(active = true),
        )
        val selected = engine.applyCandidate(queued.state, a, enabled)
        val completed = engine.afterReply(selected.state, c)

        assertFalse(queued.shouldStartGeneration)
        assertEquals(c, selected.memberId)
        assertEquals(GroupPlaybackState.RUNNING, completed.playbackState)
    }
}
