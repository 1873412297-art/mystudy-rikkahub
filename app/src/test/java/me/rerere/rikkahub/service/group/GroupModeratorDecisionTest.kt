package me.rerere.rikkahub.service.group

import me.rerere.rikkahub.data.model.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class GroupModeratorDecisionTest {
    @Test
    fun `returns null for stop only when stop is allowed`() {
        val fallback = Uuid.random()

        assertNull(
            parseGroupModeratorDecision(
                responseText = "STOP",
                enabledMembers = emptyList(),
                localFallback = fallback,
                allowStop = true,
            )
        )

        assertEquals(
            fallback,
            parseGroupModeratorDecision(
                responseText = "STOP",
                enabledMembers = emptyList(),
                localFallback = fallback,
                allowStop = false,
            )
        )
    }

    @Test
    fun `returns mentioned member id from moderator response`() {
        val memberId = Uuid.random()
        val member = GroupMember(id = memberId, assistantId = Uuid.random(), displayName = "甲")

        val result = parseGroupModeratorDecision(
            responseText = "Next: $memberId",
            enabledMembers = listOf(member),
            localFallback = null,
            allowStop = true,
        )

        assertEquals(memberId, result)
    }
}
