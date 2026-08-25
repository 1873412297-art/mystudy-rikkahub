package me.rerere.rikkahub.ui.components.richtext.runtime

import me.rerere.rikkahub.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TavernBrowserRuntimeContextTest {
    @Test
    fun `uses the most recent chat while preserving the current assistant selection`() {
        val context = resolveTavernBrowserRuntimeContext(
            backStack = listOf(
                Screen.Chat("older-chat"),
                Screen.Setting,
                Screen.Chat("active-chat"),
                Screen.TavernHelper("assistant-from-management"),
            ),
            assistantId = "current-assistant",
        )

        assertEquals("active-chat", context.conversationId)
        assertEquals("current-assistant", context.assistantId)
    }

    @Test
    fun `keeps no conversation when the navigation stack has no chat`() {
        val context = resolveTavernBrowserRuntimeContext(
            backStack = listOf(Screen.Setting, Screen.History),
            assistantId = "current-assistant",
        )

        assertNull(context.conversationId)
        assertEquals("current-assistant", context.assistantId)
    }
}
