package me.rerere.rikkahub.data.ai.trace

import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class PromptTraceSeedFactoryTest {
    @Test
    fun `eligible group seed keeps conversation speaker assistant model anchor and source hint`() {
        val conversationId = Uuid.random()
        val source = Assistant(name = "甲", tavernCardJson = "{}")
        val member = GroupMember(assistantId = source.id, displayName = "甲")
        val group = Assistant(
            assistantType = AssistantType.GROUP,
            groupMembers = listOf(member),
        )
        val model = Model()
        val oldUser = UIMessage.user("先前问题")
        val latestUser = UIMessage.user("继续")
        val hint = PromptTraceSourceHint(
            messageId = Uuid.random(),
            kind = PromptTraceSectionKind.GROUP_LAYERED_CONTEXT,
            label = "Group layered context",
        )

        val seed = buildPromptTraceSeed(
            conversationId = conversationId,
            conversationAssistant = group,
            generatingAssistant = source,
            model = model,
            visibleMessages = listOf(oldUser, UIMessage.assistant("好"), latestUser),
            allAssistants = listOf(source),
            speakerMemberId = member.id,
            speakerName = member.displayName,
            sourceHints = listOf(hint),
        )

        assertNotNull(seed)
        assertEquals(conversationId, seed?.conversationId)
        assertEquals(latestUser.id, seed?.requestAnchorMessageId)
        assertEquals(source.id, seed?.assistantId)
        assertEquals(model.id, seed?.modelId)
        assertTrue(seed?.isGroup == true)
        assertEquals(member.id, seed?.speakerMemberId)
        assertEquals(member.displayName, seed?.speakerName)
        assertSame(hint, seed?.sourceHints?.single())
    }

    @Test
    fun `eligible solo tavern seed has no group metadata`() {
        val assistant = Assistant(tavernCardJson = "{}")

        val seed = buildPromptTraceSeed(
            conversationId = Uuid.random(),
            conversationAssistant = assistant,
            generatingAssistant = assistant,
            model = Model(),
            visibleMessages = listOf(UIMessage.user("hello")),
            allAssistants = listOf(assistant),
        )

        assertNotNull(seed)
        assertFalse(seed!!.isGroup)
        assertNull(seed.speakerMemberId)
        assertNull(seed.speakerName)
    }

    @Test
    fun `group with only disabled tavern source returns no seed`() {
        val source = Assistant(tavernCardJson = "{}")
        val group = Assistant(
            assistantType = AssistantType.GROUP,
            groupMembers = listOf(GroupMember(assistantId = source.id, enabled = false)),
        )

        assertNull(
            buildPromptTraceSeed(
                conversationId = Uuid.random(),
                conversationAssistant = group,
                generatingAssistant = source,
                model = Model(),
                visibleMessages = listOf(UIMessage.user("hello")),
                allAssistants = listOf(source),
            )
        )
    }

    @Test
    fun `non tavern conversation returns no seed`() {
        assertNull(
            buildPromptTraceSeed(
                conversationId = Uuid.random(),
                conversationAssistant = Assistant(),
                generatingAssistant = Assistant(),
                model = Model(),
                visibleMessages = listOf(UIMessage.user("hello")),
                allAssistants = emptyList(),
            )
        )
    }
}
