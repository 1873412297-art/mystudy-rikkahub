package me.rerere.rikkahub.ui.components.richtext.runtime

import org.junit.Assert.assertTrue
import org.junit.Test

class TavernRuntimeScriptTest {
    @Test
    fun `script exposes compat namespace and helper methods`() {
        val script = buildTavernRuntimeScript()

        assertTrue(script.contains("window.TavernHelperCompat"))
        assertTrue(script.contains("variables.get"))
        assertTrue(script.contains("variables.set"))
        assertTrue(script.contains("slash.run"))
        assertTrue(script.contains("events: {"))
        assertTrue(script.contains("on: function"))
        assertTrue(script.contains("world.getEntries"))
    }

    @Test
    fun `script exposes event types constants and SillyTavern getContext`() {
        val script = buildTavernRuntimeScript()

        assertTrue(script.contains("window.event_types"))
        assertTrue(script.contains("GENERATION_STARTED"))
        assertTrue(script.contains("MESSAGE_RECEIVED"))
        assertTrue(script.contains("window.SillyTavern"))
        assertTrue(script.contains("getContext"))
        assertTrue(script.contains("context_updated"))
    }

    @Test
    fun scriptExposesMacroHelperAndSlashCommandParserShims() {
        val script = buildTavernRuntimeScript()

        assertTrue(script.contains("window.MacroHelper"))
        assertTrue(script.contains("registerMacro"))
        assertTrue(script.contains("window.SlashCommandParser"))
        assertTrue(script.contains("'add'"))
        assertTrue(script.contains("requestHeaders.get"))
        assertTrue(script.contains("sendHook.register"))
    }

    @Test
    fun `script exposes TavernHelper and global chat message compatibility APIs`() {
        val script = buildTavernRuntimeScript()

        assertTrue(script.contains("messages.getChatMessages"))
        assertTrue(script.contains("messages.setChatMessage"))
        assertTrue(script.contains("messages.setChatMessages"))
        assertTrue(script.contains("window.getChatMessages = api.messages.getChatMessages"))
        assertTrue(script.contains("window.setChatMessage = api.messages.setChatMessage"))
        assertTrue(script.contains("window.setChatMessages = api.messages.setChatMessages"))
    }

    @Test
    fun `script exposes MVU status card compatibility globals`() {
        val script = buildTavernRuntimeScript()

        assertTrue(script.contains("window.waitGlobalInitialized"))
        assertTrue(script.contains("window.errorCatched"))
        assertTrue(script.contains("window.eventOn"))
        assertTrue(script.contains("window.getAllVariables"))
        assertTrue(script.contains("window.Mvu"))
        assertTrue(script.contains("window._"))
    }
}
