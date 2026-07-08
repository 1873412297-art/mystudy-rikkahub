package me.rerere.rikkahub.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernCharacterCardTest {
    @Test
    fun `build system prompt expands common SillyTavern macros across all prompt fields`() {
        val card = TavernCharacterCard(
            name = "慈脂佛母",
            description = "{{char}}看向{{user}}。{{// hidden note }}",
            personality = "{{char}}温和而神秘。",
            scenario = "{{user}}进入佛堂。",
            firstMes = "",
            mesExample = "<START>\n{{char}}: 善哉，{{user}}。",
            creatorNotes = "",
            systemPrompt = "Always stay as {{char}}.",
            postHistoryInstructions = "{{char}} should answer {{user}} directly.",
            alternateGreetings = emptyList(),
            characterBook = null,
            tags = emptyList(),
            creator = "",
            characterVersion = "",
            extensions = null,
            spec = "chara_card_v2",
            specVersion = "2.0",
        )

        val prompt = card.buildSystemPrompt(userName = "小友", charName = "慈脂佛母")

        assertTrue(prompt.contains("Always stay as 慈脂佛母."))
        assertTrue(prompt.contains("慈脂佛母看向小友。"))
        assertTrue(prompt.contains("小友进入佛堂。"))
        assertTrue(prompt.contains("慈脂佛母: 善哉，小友。"))
        assertFalse(prompt.contains("{{user}}"))
        assertFalse(prompt.contains("{{char}}"))
        assertFalse(prompt.contains("hidden note"))
    }

    @Test
    fun `assistant normalizes tavern system prompt at generation time`() {
        val assistant = Assistant(
            name = "慈脂佛母",
            systemPrompt = "You are {{char}} speaking to {{user}}. {{// private note }}",
            tavernCardJson = "{}",
        )

        val prompt = assistant.normalizedSystemPromptForGeneration(userName = "小友")

        assertTrue(prompt.contains("You are 慈脂佛母 speaking to 小友."))
        assertFalse(prompt.contains("{{char}}"))
        assertFalse(prompt.contains("{{user}}"))
        assertFalse(prompt.contains("private note"))
    }
}
