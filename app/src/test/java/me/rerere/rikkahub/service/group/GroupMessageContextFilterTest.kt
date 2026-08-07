package me.rerere.rikkahub.service.group

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.ContextFilter
import me.rerere.rikkahub.data.model.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class GroupMessageContextFilterTest {
    private val memberA = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val memberB = Uuid.parse("00000000-0000-0000-0000-000000000002")
    private val sourceAssistantId = Uuid.parse("00000000-0000-0000-0000-000000000010")

    @Test
    fun `max messages keeps the last messages in chronological order`() {
        val group = groupWithMaxMessages(3)
        val messages = listOf(
            UIMessage.user("u1"),
            assistantMessage(memberA, "a1"),
            UIMessage.user("u2"),
            assistantMessage(memberB, "b1"),
            UIMessage.user("u3"),
        )

        val result = messages.applyGroupContextFilter(group, memberA)

        assertEquals(listOf("u2", "b1", "u3"), result.map { it.toText() })
    }

    @Test
    fun `max messages remains a strict bound when user messages exceed it`() {
        val group = groupWithMaxMessages(2)
        val messages = listOf(
            UIMessage.user("u1"),
            UIMessage.user("u2"),
            UIMessage.user("u3"),
        )

        val result = messages.applyGroupContextFilter(group, memberA)

        assertEquals(2, result.size)
        assertEquals(listOf("u2", "u3"), result.map { it.toText() })
    }

    private fun groupWithMaxMessages(maxMessages: Int): Assistant = Assistant(
        assistantType = AssistantType.GROUP,
        groupMembers = listOf(
            GroupMember(
                id = memberA,
                assistantId = sourceAssistantId,
                displayName = "Alice",
                contextFilter = ContextFilter(maxMessages = maxMessages),
            ),
            GroupMember(id = memberB, assistantId = sourceAssistantId, displayName = "Bob"),
        ),
    )

    private fun assistantMessage(memberId: Uuid, text: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        memberId = memberId,
        parts = listOf(UIMessagePart.Text(text)),
    )
}
