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

class GroupRuntimeStateUpdaterTest {
    private val memberA = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val memberB = Uuid.parse("00000000-0000-0000-0000-000000000002")
    private val sourceAssistantId = Uuid.parse("00000000-0000-0000-0000-000000000010")
    private val group = Assistant(
        assistantType = AssistantType.GROUP,
        groupMembers = listOf(
            GroupMember(id = memberA, assistantId = sourceAssistantId, displayName = "甲"),
            GroupMember(id = memberB, assistantId = sourceAssistantId, displayName = "乙"),
        ),
    )

    @Test
    fun `updates scene summary from latest group reply`() {
        val updated = GroupRuntimeStateUpdater().updateAfterReply(
            previous = GroupRuntimeState(),
            groupAssistant = group,
            messages = listOf(
                UIMessage.user("发生了什么？"),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    memberId = memberA,
                    parts = listOf(UIMessagePart.Text("甲低声说佛堂后方传来异响。")),
                ),
            ),
            speakerId = memberA,
        )

        assertTrue(updated.scene.summary.contains("佛堂后方传来异响"))
    }

    @Test
    fun `increases tension when reply contains conflict markers`() {
        val updated = GroupRuntimeStateUpdater().updateAfterReply(
            previous = GroupRuntimeState(),
            groupAssistant = group,
            messages = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    memberId = memberA,
                    parts = listOf(UIMessagePart.Text("我不相信乙说的话。")),
                ),
            ),
            speakerId = memberA,
        )

        assertEquals(1, updated.scene.tension)
    }

    @Test
    fun `does not append duplicate scene summary lines for repeated replies`() {
        val reply = "小友可是要妾身一人回复吗？妾身在此，自当遵命。"
        val first = GroupRuntimeStateUpdater().updateAfterReply(
            previous = GroupRuntimeState(),
            groupAssistant = group,
            messages = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    memberId = memberA,
                    parts = listOf(UIMessagePart.Text(reply)),
                ),
            ),
            speakerId = memberA,
        )

        val second = GroupRuntimeStateUpdater().updateAfterReply(
            previous = first,
            groupAssistant = group,
            messages = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    memberId = memberA,
                    parts = listOf(UIMessagePart.Text(reply)),
                ),
            ),
            speakerId = memberA,
        )

        assertEquals(first.scene.summary, second.scene.summary)
    }
}
