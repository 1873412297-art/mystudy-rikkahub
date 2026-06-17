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

class DynamicGroupContextResolverTest {
    private val memberA = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val memberB = Uuid.parse("00000000-0000-0000-0000-000000000002")
    private val memberC = Uuid.parse("00000000-0000-0000-0000-000000000003")
    private val sourceAssistantId = Uuid.parse("00000000-0000-0000-0000-000000000010")
    private val groupAssistant = Assistant(
        assistantType = AssistantType.GROUP,
        groupMembers = listOf(
            GroupMember(id = memberA, assistantId = sourceAssistantId, displayName = "慈脂佛母"),
            GroupMember(id = memberB, assistantId = sourceAssistantId, displayName = "道家仙子美母"),
            GroupMember(id = memberC, assistantId = sourceAssistantId, displayName = "孟秋娘"),
        ),
    )

    @Test
    fun `addressed target becomes core and only sees latest user message when isolated history is disallowed`() {
        val messages = listOf(
            UIMessage.user("昨天后山发生了什么？"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                memberId = memberB,
                parts = listOf(UIMessagePart.Text("后山有异香。")),
            ),
            UIMessage.user("@慈脂佛母 你来说。"),
        )

        val result = DynamicGroupContextResolver().resolve(
            groupAssistant = groupAssistant,
            messages = messages,
            effectiveMemberId = memberA,
            runtimeState = GroupRuntimeState(activeAddressedMemberId = memberA),
        )

        assertEquals(GroupContextLayer.CORE, result.layer)
        assertEquals(memberA, result.debugState.speakerId)
        assertEquals("@慈脂佛母 你来说。", result.visibleMessages.last().toText())
    }

    @Test
    fun `low relevance member is isolated to latest user message`() {
        val messages = listOf(
            UIMessage.user("去佛堂看看那把玉钥匙到底藏在哪。"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                memberId = memberA,
                parts = listOf(UIMessagePart.Text("那里有危险，不要告诉别人。")),
            ),
            UIMessage.user("继续。"),
        )

        val result = DynamicGroupContextResolver().resolve(
            groupAssistant = groupAssistant,
            messages = messages,
            effectiveMemberId = memberC,
            runtimeState = GroupRuntimeState(),
        )

        assertEquals(GroupContextLayer.ISOLATED, result.layer)
        assertEquals(1, result.visibleMessages.size)
        assertEquals("继续。", result.visibleMessages.single().toText())
        assertTrue(result.adjustedRuntimeState.scene.summary.isBlank())
    }
}
