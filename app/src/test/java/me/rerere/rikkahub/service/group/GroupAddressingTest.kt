package me.rerere.rikkahub.service.group

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class GroupAddressingTest {
    private val memberA = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val memberB = Uuid.parse("00000000-0000-0000-0000-000000000002")
    private val sourceAssistantId = Uuid.parse("00000000-0000-0000-0000-000000000010")
    private val groupAssistant = Assistant(
        assistantType = AssistantType.GROUP,
        groupMembers = listOf(
            GroupMember(id = memberA, assistantId = sourceAssistantId, displayName = "慈脂佛母"),
            GroupMember(id = memberB, assistantId = sourceAssistantId, displayName = "道家仙子美母"),
        ),
    )

    @Test
    fun `resolves direct role name addressing`() {
        val result = resolveAddressedMember(
            groupAssistant = groupAssistant,
            userText = "慈脂佛母，你来说。",
            previousAddressedMemberId = null,
        )

        assertEquals(memberA, result?.memberId)
        assertEquals(AddressingSource.DIRECT_NAME, result?.source)
    }

    @Test
    fun `resolves plain text at mention`() {
        val result = resolveAddressedMember(
            groupAssistant = groupAssistant,
            userText = "@道家仙子美母 你怎么看？",
            previousAddressedMemberId = null,
        )

        assertEquals(memberB, result?.memberId)
        assertEquals(AddressingSource.AT_MENTION, result?.source)
    }

    @Test
    fun `at mention overrides manual reply selection for current send`() {
        val result = resolveManualReplyMemberIds(
            selectedMemberIds = listOf(memberA, memberB),
            addressedMemberId = memberB,
        )

        assertEquals(listOf(memberB), result)
    }

    @Test
    fun `resolves second person continuation when previous addressed target exists`() {
        val result = resolveAddressedMember(
            groupAssistant = groupAssistant,
            userText = "你继续说下去。",
            previousAddressedMemberId = memberB,
        )

        assertEquals(memberB, result?.memberId)
        assertEquals(AddressingSource.CONTINUATION, result?.source)
    }

    @Test
    fun `resolves bare english continuation when previous addressed target exists`() {
        val result = resolveAddressedMember(
            groupAssistant = groupAssistant,
            userText = "continue",
            previousAddressedMemberId = memberB,
        )

        assertEquals(memberB, result?.memberId)
        assertEquals(AddressingSource.CONTINUATION, result?.source)
    }

    @Test
    fun `does not resolve continuation without previous addressed target`() {
        val result = resolveAddressedMember(
            groupAssistant = groupAssistant,
            userText = "你继续说下去。",
            previousAddressedMemberId = null,
        )

        assertNull(result)
    }
}
