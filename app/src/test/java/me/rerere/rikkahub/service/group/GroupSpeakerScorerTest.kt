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

class GroupSpeakerScorerTest {
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
    fun `mentioned member receives highest score`() {
        val result = GroupSpeakerScorer().score(
            groupAssistant = group,
            messages = listOf(UIMessage.user("乙，你怎么看？")),
            runtimeState = GroupRuntimeState(),
            activeMemberId = memberA,
        )

        assertEquals(memberB, result.first().memberId)
        assertEquals("answer_user", result.first().intent)
        assertTrue(result.first().score > result.last().score)
    }

    @Test
    fun `high tension relationship increases challenge intent`() {
        val result = GroupSpeakerScorer().score(
            groupAssistant = group,
            messages = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    memberId = memberA,
                    parts = listOf(UIMessagePart.Text("我不相信你。")),
                )
            ),
            runtimeState = GroupRuntimeState(
                relationships = mapOf(
                    GroupRelationshipKey(memberB, memberA) to GroupRelationshipState(
                        affinity = -2,
                        tension = 8,
                        note = "乙认为甲在隐瞒。",
                    )
                )
            ),
            activeMemberId = memberA,
        )

        assertEquals(memberB, result.first().memberId)
        assertEquals("challenge", result.first().intent)
    }
}
