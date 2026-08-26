package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperButtonConfig
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperExportWith
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernBrowserScriptSelectionTest {
    @Test
    fun `keeps global then character then assistant order and exposes every script past cap`() {
        val global = (1..31).map { script("global-$it") }
        val character = listOf(script("character-1"), script("character-2"))
        val assistant = listOf(script("assistant-1"))

        val selection = selectTavernBrowserScripts(global, character, assistant)

        assertEquals(32, selection.active.size)
        assertEquals("character-1", selection.active.last().id)
        assertEquals(listOf("character-2", "assistant-1"), selection.overLimit.map { it.id })
    }

    @Test
    fun `does not select disabled scripts`() {
        val selection = selectTavernBrowserScripts(
            global = listOf(script("disabled", enabled = false)),
            character = emptyList(),
            assistant = emptyList(),
        )

        assertTrue(selection.active.isEmpty())
        assertTrue(selection.overLimit.isEmpty())
    }

    private fun script(id: String, enabled: Boolean = true) = TavernHelperScript(
        id = id,
        name = id,
        enabled = enabled,
        content = "",
        info = "",
        button = TavernHelperButtonConfig(true, emptyList(), JsonObject(emptyMap())),
        data = JsonObject(emptyMap()),
        exportWith = TavernHelperExportWith(true, true, JsonObject(emptyMap())),
        compatExtras = JsonObject(emptyMap()),
    )
}
