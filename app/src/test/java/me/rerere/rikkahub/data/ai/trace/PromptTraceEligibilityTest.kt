package me.rerere.rikkahub.data.ai.trace

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.GroupMember
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTraceEligibilityTest {
    @Test
    fun `solo requires a tavern card json value`() {
        assertTrue(Assistant(tavernCardJson = "{}").isTavernPromptTraceEligible(emptyList()))
        assertFalse(Assistant(tavernCardJson = null).isTavernPromptTraceEligible(emptyList()))
    }

    @Test
    fun `group is eligible when an enabled source member has a tavern card`() {
        val tavern = Assistant(tavernCardJson = "{}")
        val group = Assistant(
            assistantType = AssistantType.GROUP,
            groupMembers = listOf(GroupMember(assistantId = tavern.id, enabled = true)),
        )

        assertTrue(group.isTavernPromptTraceEligible(listOf(tavern)))
    }

    @Test
    fun `disabled tavern member does not make group eligible`() {
        val tavern = Assistant(tavernCardJson = "{}")
        val group = Assistant(
            assistantType = AssistantType.GROUP,
            groupMembers = listOf(GroupMember(assistantId = tavern.id, enabled = false)),
        )

        assertFalse(group.isTavernPromptTraceEligible(listOf(tavern)))
    }

    @Test
    fun `status renderer alone does not make assistant eligible`() {
        assertFalse(
            Assistant(statusRenderJs = "function renderStatus() {}")
                .isTavernPromptTraceEligible(emptyList())
        )
    }
}
