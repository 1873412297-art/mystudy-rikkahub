package me.rerere.rikkahub.ui.components.richtext.runtime

import me.rerere.rikkahub.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TavernBrowserRuntimeContextTest {
    @Test
    fun `prefers the active chat assistant over management and settings assistants`() {
        val context = resolveTavernBrowserRuntimeContext(
            backStack = listOf(
                Screen.Chat("older-chat"),
                Screen.Setting,
                Screen.Chat("active-chat"),
                Screen.TavernHelper("assistant-from-management"),
            ),
            conversationAssistantId = "assistant-from-active-chat",
            settingsAssistantId = "assistant-from-settings",
        )

        assertEquals("active-chat", context.conversationId)
        assertEquals("assistant-from-active-chat", context.assistantId)
    }

    @Test
    fun `uses management assistant when no chat context is available`() {
        val context = resolveTavernBrowserRuntimeContext(
            backStack = listOf(Screen.Setting, Screen.TavernHelper("assistant-from-management")),
            conversationAssistantId = null,
            settingsAssistantId = "assistant-from-settings",
        )

        assertNull(context.conversationId)
        assertEquals("assistant-from-management", context.assistantId)
    }

    @Test
    fun `uses settings assistant only without chat or management context`() {
        val context = resolveTavernBrowserRuntimeContext(
            backStack = listOf(Screen.Setting, Screen.History),
            conversationAssistantId = null,
            settingsAssistantId = "assistant-from-settings",
        )

        assertNull(context.conversationId)
        assertEquals("assistant-from-settings", context.assistantId)
    }
}
