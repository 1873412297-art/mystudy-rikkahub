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
    }
}
