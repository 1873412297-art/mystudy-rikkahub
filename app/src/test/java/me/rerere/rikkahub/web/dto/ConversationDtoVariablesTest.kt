package me.rerere.rikkahub.web.dto

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDtoVariablesTest {

    @Test
    fun `serializes statusVariables in conversation dto`() {
        val variables = buildJsonObject {
            put("hp", 42)
            put("mood", "happy")
        }
        val dto = ConversationDto(
            id = "conv-1",
            assistantId = "assistant-1",
            title = "t",
            messages = emptyList(),
            chatSuggestions = emptyList(),
            isPinned = false,
            createAt = 0L,
            updateAt = 0L,
            statusVariables = variables,
        )
        val json = JsonInstant.encodeToString(ConversationSnapshotEvent(seq = 1, conversation = dto))
        assertTrue(json.contains("\"statusVariables\""))
        assertTrue(json.contains("\"hp\":42"))
    }

    @Test
    fun `serializes null statusVariables`() {
        val dto = ConversationDto(
            id = "conv-1",
            assistantId = "assistant-1",
            title = "t",
            messages = emptyList(),
            chatSuggestions = emptyList(),
            isPinned = false,
            createAt = 0L,
            updateAt = 0L,
        )
        val json = JsonInstant.encodeToString(ConversationSnapshotEvent(seq = 1, conversation = dto))
        assertTrue(json.contains("\"statusVariables\":null"))
    }
}
