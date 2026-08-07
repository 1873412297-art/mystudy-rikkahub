package me.rerere.rikkahub.service.group

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.ContextFilter
import me.rerere.rikkahub.data.model.ContextScope
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
            GroupMember(id = memberA, assistantId = sourceAssistantId, displayName = "Alice"),
            GroupMember(id = memberB, assistantId = sourceAssistantId, displayName = "Bob"),
            GroupMember(id = memberC, assistantId = sourceAssistantId, displayName = "Cora"),
        ),
    )

    @Test
    fun `addressed target becomes core and sees latest user prompt`() {
        val messages = listOf(
            UIMessage.user("What happened behind the hall yesterday?"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                memberId = memberB,
                parts = listOf(UIMessagePart.Text("There was a strange smell.")),
            ),
            UIMessage.user("@Alice answer this"),
        )

        val result = DynamicGroupContextResolver().resolve(
            groupAssistant = groupAssistant,
            messages = messages,
            effectiveMemberId = memberA,
            runtimeState = GroupRuntimeState(activeAddressedMemberId = memberA),
        )

        assertEquals(GroupContextLayer.CORE, result.layer)
        assertEquals(memberA, result.debugState.speakerId)
        assertEquals("@Alice answer this", result.visibleMessages.last().toText())
    }

    @Test
    fun `low relevance member is isolated to latest user message`() {
        val messages = listOf(
            UIMessage.user("Check the hidden key."),
            UIMessage(
                role = MessageRole.ASSISTANT,
                memberId = memberA,
                parts = listOf(UIMessagePart.Text("That place is dangerous.")),
            ),
            UIMessage.user("Continue."),
        )

        val result = DynamicGroupContextResolver().resolve(
            groupAssistant = groupAssistant,
            messages = messages,
            effectiveMemberId = memberC,
            runtimeState = GroupRuntimeState(),
        )

        assertEquals(GroupContextLayer.ISOLATED, result.layer)
        assertEquals(1, result.visibleMessages.size)
        assertEquals("Continue.", result.visibleMessages.single().toText())
        assertTrue(result.adjustedRuntimeState.scene.summary.isBlank())
    }

    @Test
    fun `addressed member keeps latest user prompt when directed filter is narrow`() {
        val group = groupAssistant.copy(
            groupMembers = groupAssistant.groupMembers.map { member ->
                if (member.id == memberA) {
                    member.copy(contextFilter = ContextFilter(scope = ContextScope.DIRECTED))
                } else {
                    member
                }
            },
        )
        val messages = listOf(
            UIMessage.user("Public setup"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                memberId = memberB,
                parts = listOf(UIMessagePart.Text("Public clue")),
            ),
            UIMessage.user("@Alice answer this"),
        )

        val result = DynamicGroupContextResolver().resolve(
            groupAssistant = group,
            messages = messages,
            effectiveMemberId = memberA,
            runtimeState = GroupRuntimeState(activeAddressedMemberId = memberA),
        )

        assertEquals(GroupContextLayer.CORE, result.layer)
        assertEquals("@Alice answer this", result.visibleMessages.last().toText())
    }

    @Test
    fun `weakly related member keeps latest public reply as context anchor`() {
        val messages = listOf(
            UIMessage.user("What happened at the bridge?"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                memberId = memberC,
                parts = listOf(UIMessagePart.Text("I noticed the bridge earlier.")),
            ),
            UIMessage(
                role = MessageRole.ASSISTANT,
                memberId = memberA,
                parts = listOf(UIMessagePart.Text("The bridge is blocked now.")),
            ),
            UIMessage.user("Continue."),
        )

        val result = DynamicGroupContextResolver().resolve(
            groupAssistant = groupAssistant,
            messages = messages,
            effectiveMemberId = memberC,
            runtimeState = GroupRuntimeState(),
        )

        assertEquals(GroupContextLayer.WEAKLY_RELATED, result.layer)
        assertEquals("The bridge is blocked now.", result.visibleMessages[result.visibleMessages.lastIndex - 1].toText())
        assertEquals("Continue.", result.visibleMessages.last().toText())
    }

    @Test
    fun `relationship drama focus recalls recent messages from involved characters`() {
        val messages = listOf(
            UIMessage.user("Earlier scene."),
            UIMessage(
                role = MessageRole.ASSISTANT,
                memberId = memberB,
                parts = listOf(UIMessagePart.Text("Alice kept our affair secret.")),
            ),
            UIMessage.user("Filler turn one."),
            UIMessage(
                role = MessageRole.ASSISTANT,
                memberId = memberA,
                parts = listOf(UIMessagePart.Text("I do not want Cora to know.")),
            ),
            UIMessage.user("Filler turn two."),
            UIMessage(
                role = MessageRole.ASSISTANT,
                memberId = memberC,
                parts = listOf(UIMessagePart.Text("I only saw them arguing.")),
            ),
            UIMessage.user("The jealousy is getting worse. What do you notice?"),
        )

        val result = DynamicGroupContextResolver().resolve(
            groupAssistant = groupAssistant,
            messages = messages,
            effectiveMemberId = memberC,
            runtimeState = GroupRuntimeState(
                eventState = GroupEventState(
                    activeFocus = GroupEventFocus(
                        characterIds = listOf(memberA, memberB),
                        emotions = listOf("jealousy"),
                        conflicts = listOf("affair"),
                    ),
                ),
            ),
        )

        assertTrue(result.visibleMessages.any { it.toText() == "Alice kept our affair secret." })
        assertEquals("The jealousy is getting worse. What do you notice?", result.visibleMessages.last().toText())
    }

    @Test
    fun `relationship drama keywords are extracted from latest conversation`() {
        val focus = GroupEventExtractor().extractFocus(
            groupAssistant = groupAssistant,
            messages = listOf(UIMessage.user("This affair made Alice jealous of Bob, and the rival is nearby.")),
            runtimeState = GroupRuntimeState(),
        )

        assertTrue(focus.conflicts.contains("affair"))
        assertTrue(focus.conflicts.contains("rival"))
        assertTrue(focus.emotions.contains("jealous"))
        assertEquals(listOf(memberA, memberB), focus.characterIds)
    }
}
