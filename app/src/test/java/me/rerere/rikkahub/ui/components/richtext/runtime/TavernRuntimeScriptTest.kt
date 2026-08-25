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
    fun `script exposes worldbook CRUD and TavernHelper style worldbook aliases`() {
        val script = buildTavernRuntimeScript()

        assertTrue(script.contains("world.listBooks"))
        assertTrue(script.contains("world.getBook"))
        assertTrue(script.contains("world.createBook"))
        assertTrue(script.contains("world.updateBook"))
        assertTrue(script.contains("world.deleteBook"))
        assertTrue(script.contains("getWorldbookNames"))
        assertTrue(script.contains("getWorldbook"))
        assertTrue(script.contains("createWorldbook"))
        assertTrue(script.contains("replaceWorldbook"))
        assertTrue(script.contains("updateWorldbookWith"))
        assertTrue(script.contains("deleteWorldbook"))
    }

    @Test
    fun `unsupported host capability shims remain callable and dispatch structured RPC methods`() {
        val script = buildTavernRuntimeScript()

        assertTrue(script.contains("window.RikkaHubTavern"))
        assertTrue(script.contains("extensions.install"))
        assertTrue(script.contains("extensions.uninstall"))
        assertTrue(script.contains("extensions.update"))
        assertTrue(script.contains("server.getAdminStatus"))
        assertTrue(script.contains("server.filesystem.read"))
        assertTrue(script.contains("dom.jquery.queryTopLevel"))
        assertTrue(script.contains("backend.st.request"))
        assertTrue(script.contains("return call("))
        assertTrue(!script.contains("Android"))
        assertTrue(!script.contains("Kotlin"))
    }
}
