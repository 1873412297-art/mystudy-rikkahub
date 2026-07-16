package me.rerere.rikkahub.service.group

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.ContextFilter
import me.rerere.rikkahub.data.model.ContextScope
import me.rerere.rikkahub.data.model.GroupContextOptions
import me.rerere.rikkahub.data.model.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.Uuid

class GroupContextPipelineTest {
    private val memberA = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val memberB = Uuid.parse("00000000-0000-0000-0000-000000000002")
    private val sourceAssistantId = Uuid.parse("00000000-0000-0000-0000-000000000010")

    @Test
    fun `solo assistant passes selected messages through unchanged`() {
        val messages = listOf(UIMessage.user("Solo prompt"))

        val result = resolveGroupContextMessages(
            groupAssistant = Assistant(assistantType = AssistantType.SOLO),
            messages = messages,
            effectiveMemberId = memberA,
            runtimeState = GroupRuntimeState(),
        )

        assertSame(messages, result.visibleMessages)
        assertNull(result.dynamicResult)
    }

    @Test
    fun `group without an effective member passes selected messages through unchanged`() {
        val messages = listOf(UIMessage.user("Unassigned group prompt"))

        val result = resolveGroupContextMessages(
            groupAssistant = directedGroup(),
            messages = messages,
            effectiveMemberId = null,
            runtimeState = GroupRuntimeState(),
        )

        assertSame(messages, result.visibleMessages)
        assertNull(result.dynamicResult)
    }

    @Test
    fun `layered context disabled applies the legacy filter once with chronological limiting`() {
        val group = Assistant(
            assistantType = AssistantType.GROUP,
            groupMembers = listOf(
                GroupMember(
                    id = memberA,
                    assistantId = sourceAssistantId,
                    displayName = "Alice",
                    contextFilter = ContextFilter(
                        scope = ContextScope.SELF,
                        maxMessages = 3,
                    ),
                ),
                GroupMember(id = memberB, assistantId = sourceAssistantId, displayName = "Bob"),
            ),
            groupContextOptions = GroupContextOptions(enableLayeredContext = false),
        )
        val messages = listOf(
            UIMessage.user("u1"),
            assistantMessage(memberA, "a1"),
            assistantMessage(memberB, "b1"),
            UIMessage.user("u2"),
            assistantMessage(memberA, "a2"),
        )
        var legacyFilterCalls = 0

        val result = resolveGroupContextMessages(
            groupAssistant = group,
            messages = messages,
            effectiveMemberId = memberA,
            runtimeState = GroupRuntimeState(),
            legacyFilter = { selectedMessages, assistant, effectiveMemberId ->
                legacyFilterCalls += 1
                selectedMessages.applyGroupContextFilter(assistant, effectiveMemberId)
            },
        )

        assertEquals(1, legacyFilterCalls)
        assertNull(result.dynamicResult)
        assertEquals(listOf("a1", "u2", "a2"), result.visibleMessages.map { it.toText() })
    }

    @Test
    fun `layered directed context retains the addressed user prompt`() {
        val group = directedGroup()
        val messages = listOf(
            UIMessage.user("Public setup"),
            assistantMessage(memberB, "Public clue"),
            UIMessage.user("@Alice answer this"),
        )

        val result = resolveGroupContextMessages(
            groupAssistant = group,
            messages = messages,
            effectiveMemberId = memberA,
            runtimeState = GroupRuntimeState(activeAddressedMemberId = memberA),
        )

        assertNotNull(result.dynamicResult)
        assertEquals("@Alice answer this", result.visibleMessages.last().toText())
    }

    @Test
    fun `chat service selection seam sends the raw selected range into the layered resolver`() {
        val messages = listOf(
            UIMessage.user("Outside requested range"),
            UIMessage.user("Public setup"),
            assistantMessage(memberB, "Public clue"),
            UIMessage.user("@Alice answer this"),
        )

        val result = resolveSelectedGroupContextMessages(
            groupAssistant = directedGroup(),
            messages = messages,
            messageRange = 1..3,
            effectiveMemberId = memberA,
            runtimeState = GroupRuntimeState(activeAddressedMemberId = memberA),
        )

        assertNotNull(result.dynamicResult)
        assertEquals("@Alice answer this", result.visibleMessages.last().toText())
    }

    private fun directedGroup() = Assistant(
        assistantType = AssistantType.GROUP,
        groupMembers = listOf(
            GroupMember(
                id = memberA,
                assistantId = sourceAssistantId,
                displayName = "Alice",
                contextFilter = ContextFilter(scope = ContextScope.DIRECTED),
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
