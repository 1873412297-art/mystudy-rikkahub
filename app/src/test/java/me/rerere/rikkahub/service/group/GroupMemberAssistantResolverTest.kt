package me.rerere.rikkahub.service.group

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.GroupMember
import me.rerere.rikkahub.data.model.normalizedSystemPromptForGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class GroupMemberAssistantResolverTest {
    @Test
    fun `effective member assistant preserves source character card identity`() {
        val modelId = Uuid.random()
        val sourceRegex = AssistantRegex(id = Uuid.random())
        val groupRegex = AssistantRegex(id = Uuid.random())
        val source = Assistant(
            id = Uuid.random(),
            name = "源角色",
            systemPrompt = "You are {{char}} and you speak to {{user}}.",
            tavernCardJson = "{}",
            temperature = 0.9f,
            regexes = listOf(sourceRegex),
        )
        val group = Assistant(
            assistantType = AssistantType.GROUP,
            name = "群组助手",
            systemPrompt = "Group prompt",
            regexes = listOf(groupRegex),
        )
        val member = GroupMember(
            assistantId = source.id,
            displayName = "慈脂佛母",
        )

        val effective = resolveEffectiveGroupMemberAssistant(
            groupAssistant = group,
            sourceAssistant = source,
            member = member,
            resolvedModelId = modelId,
        )

        assertEquals("慈脂佛母", effective.name)
        assertEquals(modelId, effective.chatModelId)
        assertEquals(0.9f, effective.temperature)
        assertNotNull(effective.tavernCardJson)
        assertTrue(effective.regexes.contains(groupRegex))
        assertTrue(effective.regexes.contains(sourceRegex))
        assertEquals(
            "You are 慈脂佛母 and you speak to 小友.",
            effective.normalizedSystemPromptForGeneration(userName = "小友"),
        )
    }
}
