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

class GroupContextBuilderTest {
    private val speakerA = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val speakerB = Uuid.parse("00000000-0000-0000-0000-000000000002")
    private val sourceAssistantId = Uuid.parse("00000000-0000-0000-0000-000000000010")

    private val group = Assistant(
        assistantType = AssistantType.GROUP,
        groupMembers = listOf(
            GroupMember(id = speakerA, assistantId = sourceAssistantId, displayName = "甲"),
            GroupMember(id = speakerB, assistantId = sourceAssistantId, displayName = "乙"),
        ),
    )

    @Test
    fun `builder prepends private viewpoint system message`() {
        val input = GroupContextBuildInput(
            visibleMessages = listOf(UIMessage.user("你们怎么看？")),
            groupAssistant = group,
            effectiveMemberId = speakerA,
            runtimeState = GroupRuntimeState(
                privateNotes = mapOf(speakerA to "甲知道密门在佛堂后方。"),
                scene = GroupSceneState(summary = "众人夜谈。", tension = 5),
            ),
            speakingIntent = GroupSpeakingIntent(
                speakerId = speakerA,
                intent = "hide_secret",
                reason = "User asked about the shrine.",
            ),
        )

        val result = GroupContextBuilder().build(input)

        assertEquals(MessageRole.SYSTEM, result.messages.first().role)
        val system = result.messages.first().toText()
        assertTrue(system.contains("Private viewpoint for 甲"))
        assertTrue(system.contains("甲知道密门在佛堂后方。"))
        assertTrue(system.contains("Scene: 众人夜谈。"))
        assertTrue(system.contains("Speaking intent: hide_secret"))
        assertEquals("你们怎么看？", result.messages.last().toText())
    }

    @Test
    fun `builder includes relationship note for visible participants`() {
        val input = GroupContextBuildInput(
            visibleMessages = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    memberId = speakerB,
                    parts = listOf(UIMessagePart.Text("我怀疑这里有问题。")),
                )
            ),
            groupAssistant = group,
            effectiveMemberId = speakerA,
            runtimeState = GroupRuntimeState(
                relationships = mapOf(
                    GroupRelationshipKey(speakerA, speakerB) to GroupRelationshipState(
                        affinity = -1,
                        tension = 3,
                        note = "甲觉得乙敏锐但危险。",
                    )
                )
            ),
        )

        val result = GroupContextBuilder().build(input)

        val system = result.messages.first().toText()
        assertTrue(system.contains("Relationship notes"))
        assertTrue(system.contains("toward 乙"))
        assertTrue(system.contains("甲觉得乙敏锐但危险。"))
    }
}
