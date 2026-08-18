package me.rerere.rikkahub.data.ai.status

import org.junit.Assert.assertEquals
import org.junit.Test

class TavernHostEventTypeTest {

    @Test
    fun `st aligned event names are present alongside legacy names`() {
        val expected = setOf(
            "GENERATION_STARTED", "MESSAGE_SENT", "MESSAGE_RECEIVED",
            "MESSAGE_EDITED", "MESSAGE_DELETED", "MESSAGE_SWIPED",
            "CHARACTER_MESSAGE_RENDERED", "USER_MESSAGE_RENDERED",
            // legacy（保留）
            "MESSAGE_SENDING", "GENERATION_FINISHED", "MESSAGE_RENDERED",
        )
        assertEquals(expected, TavernHostEventType.entries.map { it.name }.toSet())
    }
}
