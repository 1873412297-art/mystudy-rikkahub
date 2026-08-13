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
}
