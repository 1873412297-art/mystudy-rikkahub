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
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.uuid.Uuid

class GroupContextPipelineTest {
    private val memberA = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val memberB = Uuid.parse("00000000-0000-0000-0000-000000000002")
    private val sourceAssistantId = Uuid.parse("00000000-0000-0000-0000-000000000010")

    @Test
    fun `layered directed context retains the addressed user prompt`() {
        val group = Assistant(
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
        val messages = listOf(
            UIMessage.user("Public setup"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                memberId = memberB,
                parts = listOf(UIMessagePart.Text("Public clue")),
            ),
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
}
