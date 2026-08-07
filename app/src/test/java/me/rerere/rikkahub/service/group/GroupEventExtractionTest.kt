package me.rerere.rikkahub.service.group

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.GroupMember
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class GroupEventExtractionTest {
    private val memberA = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val sourceAssistantId = Uuid.parse("00000000-0000-0000-0000-000000000010")
    private val groupAssistant = Assistant(
        assistantType = AssistantType.GROUP,
        groupMembers = listOf(
            GroupMember(id = memberA, assistantId = sourceAssistantId, displayName = "慈脂佛母"),
        ),
    )

    @Test
    fun `extracts event focus tags from local message window`() {
        val messages = listOf(
            UIMessage.user("去佛堂看看那把玉钥匙到底藏在哪。"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                memberId = memberA,
                parts = listOf(
                    UIMessagePart.Text("慈脂佛母低声警告：不要把真相告诉别人，那里有危险。"),
                ),
            ),
        )

        val focus = GroupEventExtractor().extractFocus(
            groupAssistant = groupAssistant,
            messages = messages,
            runtimeState = GroupRuntimeState(),
        )

        assertTrue(focus.locations.contains("佛堂"))
        assertTrue(focus.items.contains("玉钥匙"))
        assertTrue(focus.events.contains("警告"))
        assertTrue(focus.secrets.any { it.contains("真相") || it.contains("不要告诉") })
        assertTrue(focus.conflicts.contains("危险"))
    }
}
