package me.rerere.rikkahub.service.group

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class GroupMessageTransportRewriteTest {
    @Test
    fun `current member assistant ending keeps message count unchanged`() {
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

        assertEquals(2, result.size)
        assertEquals(MessageRole.USER, result[0].role)
        assertEquals(MessageRole.ASSISTANT, result[1].role)
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

    @Test
    fun `storable group generated messages keep only new assistant replies`() {
        val memberId = Uuid.random()
        val originalMessage = UIMessage.user("你好")
        val assistantReply = UIMessage.assistant("吾在。")
        val userEcho = UIMessage.user("请继续以[慈脂佛母]身份回复。不要重复上文，只输出该成员的下一句回应。")

        val result = listOf(originalMessage, assistantReply, userEcho).toStorableGroupGeneratedMessages(
            originalMessageIds = setOf(originalMessage.id),
            effectiveMemberId = memberId,
            memberName = "慈脂佛母",
        )

        assertEquals(1, result.size)
        assertEquals(MessageRole.ASSISTANT, result.single().role)
        assertEquals(memberId, result.single().memberId)
        assertEquals("慈脂佛母", result.single().name)
        assertEquals("吾在。", result.single().toText())
    }

    @Test
    fun `storable group generated messages drop leaked continuation nudge`() {
        val memberId = Uuid.random()
        val leakedAssistantPrompt = UIMessage.assistant(
            "请继续以[慈脂佛母]身份回复。不要重复上文，只输出该成员的下一句回应。"
        )

        val result = listOf(leakedAssistantPrompt).toStorableGroupGeneratedMessages(
            originalMessageIds = emptySet(),
            effectiveMemberId = memberId,
            memberName = "慈脂佛母",
        )

        assertEquals(emptyList<UIMessage>(), result)
    }

    private fun groupAssistant(vararg members: GroupMember): Assistant {
        return Assistant(
            assistantType = AssistantType.GROUP,
            groupMembers = members.toList(),
        )
    }
}
