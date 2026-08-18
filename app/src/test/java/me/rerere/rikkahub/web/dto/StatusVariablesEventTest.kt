package me.rerere.rikkahub.web.dto

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusVariablesEventTest {

    @Test
    fun `serializes status_variables event`() {
        val event = ConversationStatusVariablesEvent(
            seq = 7,
            conversationId = "conv-1",
            variables = buildJsonObject { put("hp", 42) },
            serverTime = 1000L,
        )
        val json = JsonInstant.encodeToString(event)
        assertEquals(
            """{"type":"status_variables","seq":7,"conversationId":"conv-1","variables":{"hp":42},"serverTime":1000}""",
            json
        )
    }
}
