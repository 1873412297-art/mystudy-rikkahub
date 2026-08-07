package me.rerere.rikkahub.service.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class GroupTurnSchedulerTest {
    private val a = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val b = Uuid.parse("00000000-0000-0000-0000-000000000002")
    private val c = Uuid.parse("00000000-0000-0000-0000-000000000003")
    private val removed = Uuid.parse("00000000-0000-0000-0000-000000000099")

    @Test
    fun `queue repair removes disabled deleted and duplicate members then appends newly enabled members`() {
        assertEquals(
            listOf(b, a, c),
            normalizeGroupMemberQueue(
                persistedQueue = listOf(removed, b, b, a),
                enabledMemberIds = listOf(a, b, c),
            ),
        )
    }

    @Test
    fun `new round robin queue starts at first enabled member and advances`() {
        val first = nextRoundRobinSelection(emptyList(), 0, null, listOf(a, b))
        val second = nextRoundRobinSelection(
            persistedQueue = first!!.queue,
            persistedIndex = first.selectedIndex,
            activeMemberId = first.memberId,
            enabledMemberIds = listOf(a, b),
        )

        assertEquals(a, first.memberId)
        assertEquals(0, first.selectedIndex)
        assertEquals(b, second!!.memberId)
        assertEquals(1, second.selectedIndex)
    }

    @Test
    fun `stale active member does not block selection`() {
        val result = nextRoundRobinSelection(
            persistedQueue = listOf(removed, b),
            persistedIndex = 0,
            activeMemberId = removed,
            enabledMemberIds = listOf(a, b),
        )

        assertEquals(b, result!!.memberId)
        assertEquals(listOf(b, a), result.queue)
    }

    @Test
    fun `stale active member falls back to valid persisted cursor`() {
        val result = nextRoundRobinSelection(
            persistedQueue = listOf(a, b),
            persistedIndex = 1,
            activeMemberId = removed,
            enabledMemberIds = listOf(a, b, c),
        )

        assertEquals(c, result!!.memberId)
        assertEquals(2, result.selectedIndex)
    }

    @Test
    fun `out of range cursor starts at first enabled member`() {
        val result = nextRoundRobinSelection(
            persistedQueue = listOf(a, b),
            persistedIndex = 99,
            activeMemberId = null,
            enabledMemberIds = listOf(a, b),
        )

        assertEquals(a, result!!.memberId)
        assertEquals(0, result.selectedIndex)
    }

    @Test
    fun `active member takes priority when active and cursor conflict`() {
        val result = nextRoundRobinSelection(
            persistedQueue = listOf(a, b, c),
            persistedIndex = 0,
            activeMemberId = b,
            enabledMemberIds = listOf(a, b, c),
        )

        assertEquals(c, result!!.memberId)
        assertEquals(2, result.selectedIndex)
    }

    @Test
    fun `empty enabled members return no selection`() {
        assertNull(nextRoundRobinSelection(listOf(a), 0, a, emptyList()))
    }

    @Test
    fun `different member fallback changes speaker when possible`() {
        assertEquals(b, nextDifferentGroupMember(listOf(a, b), a))
        assertEquals(a, nextDifferentGroupMember(listOf(a), a))
    }

    @Test
    fun `reply limit respects configured value and floors invalid values`() {
        assertEquals(1, resolveGroupAutoReplyLimit(1))
        assertEquals(3, resolveGroupAutoReplyLimit(3))
        assertEquals(1, resolveGroupAutoReplyLimit(0))
        assertEquals(1, resolveGroupAutoReplyLimit(-3))
    }

    @Test
    fun `moderator stop remains no selection`() {
        assertNull(
            selectModeratorTurn(
                persistedQueue = listOf(a, b),
                enabledMemberIds = listOf(a, b),
                activeMemberId = a,
                resolvedMemberId = null,
                allowConsecutiveSameSpeaker = false,
            ),
        )
    }

    @Test
    fun `moderator consecutive speaker option controls same speaker fallback`() {
        val allowed = selectModeratorTurn(
            persistedQueue = listOf(a, b),
            enabledMemberIds = listOf(a, b),
            activeMemberId = a,
            resolvedMemberId = a,
            allowConsecutiveSameSpeaker = true,
        )
        val blocked = selectModeratorTurn(
            persistedQueue = listOf(a, b),
            enabledMemberIds = listOf(a, b),
            activeMemberId = a,
            resolvedMemberId = a,
            allowConsecutiveSameSpeaker = false,
        )
        val onlyMember = selectModeratorTurn(
            persistedQueue = listOf(a),
            enabledMemberIds = listOf(a),
            activeMemberId = a,
            resolvedMemberId = a,
            allowConsecutiveSameSpeaker = false,
        )

        assertEquals(a, allowed!!.memberId)
        assertEquals(b, blocked!!.memberId)
        assertEquals(a, onlyMember!!.memberId)
    }

    @Test
    fun `moderator selection returns matching normalized queue and index`() {
        val result = selectModeratorTurn(
            persistedQueue = listOf(removed, b, b),
            enabledMemberIds = listOf(a, b, c),
            activeMemberId = a,
            resolvedMemberId = c,
            allowConsecutiveSameSpeaker = false,
        )

        assertEquals(c, result!!.memberId)
        assertEquals(listOf(b, a, c), result.queue)
        assertEquals(2, result.selectedIndex)
    }

    @Test
    fun `moderator selection rejects unknown resolved member`() {
        assertNull(
            selectModeratorTurn(
                persistedQueue = listOf(a, b),
                enabledMemberIds = listOf(a, b),
                activeMemberId = a,
                resolvedMemberId = removed,
                allowConsecutiveSameSpeaker = true,
            ),
        )
    }

    @Test
    fun `configured reply cap controls whether auto replies continue`() {
        assertEquals(false, shouldContinueGroupAutoReplies(alreadySent = 1, configuredLimit = 1))
        assertEquals(true, shouldContinueGroupAutoReplies(alreadySent = 0, configuredLimit = 1))
    }
}
