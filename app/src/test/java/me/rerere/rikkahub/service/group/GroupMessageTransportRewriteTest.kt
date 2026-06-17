package me.rerere.rikkahub.service.group

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class GroupMessageTransportRewriteTest {
    @Test
    fun `current member assistant ending appends non blank continuation user message`() {
        val currentMemberId = Uuid.random()
        val assistant = groupAssistant(
            GroupMember(
                id = currentMemberId,
                assistantId = Uuid.random(),
                displayName = "慈脂佛母",
            )
        )
        val messages = listOf(
            UIMessage.user("你好"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("吾已知晓。")),
                memberId = currentMemberId,
            ),
        )

        val result = messages.applyGroupApiRewrite(assistant, currentMemberId)

        assertEquals(MessageRole.USER, result.last().role)
        assertTrue(result.last().toText().isNotBlank())
        assertTrue(result.last().toText().contains("慈脂佛母"))
    }

    @Test
    fun `other member assistant messages become user messages with speaker prefix`() {
        val currentMemberId = Uuid.random()
        val otherMemberId = Uuid.random()
        val assistant = groupAssistant(
            GroupMember(
                id = currentMemberId,
                assistantId = Uuid.random(),
                displayName = "道家仙子美母",
            ),
            GroupMember(
                id = otherMemberId,
                assistantId = Uuid.random(),
                displayName = "慈脂佛母",
            ),
        )
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("善哉。")),
                memberId = otherMemberId,
            ),
        )

        val result = messages.applyGroupApiRewrite(assistant, currentMemberId)

        assertEquals(MessageRole.USER, result.single().role)
        assertEquals("[慈脂佛母] 善哉。", result.single().toText())
    }

    private fun groupAssistant(vararg members: GroupMember): Assistant {
        return Assistant(
            assistantType = AssistantType.GROUP,
            groupMembers = members.toList(),
        )
    }
}
