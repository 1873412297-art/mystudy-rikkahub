package me.rerere.rikkahub.data.ai.tavernhelper

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernHelperManagementTest {
    @Test
    fun `strips one outer javascript markdown fence`() {
        assertEquals("console.log('ok')", stripOuterScriptFence("```javascript\nconsole.log('ok')\n```"))
        assertEquals("const fence = '```';", stripOuterScriptFence("const fence = '```';"))
    }

    @Test
    fun `search supports plain text and regex errors`() {
        val nodes = listOf(script("a", "Alpha", "const hp = 1"), script("b", "Beta", "const mp = 2"))

        assertEquals(listOf("a"), searchTavernHelperNodes(nodes, "hp").nodes.map { it.id })
        assertEquals(listOf("b"), searchTavernHelperNodes(nodes, "/^beta$/i").nodes.map { it.id })
        assertTrue(searchTavernHelperNodes(nodes, "/[/").error!!.isNotBlank())
    }

    private fun script(id: String, name: String, content: String) = TavernHelperScript(
        id = id,
        name = name,
        enabled = false,
        content = content,
        info = "",
        button = TavernHelperButtonConfig(true, emptyList(), JsonObject(emptyMap())),
        data = JsonObject(emptyMap()),
        exportWith = TavernHelperExportWith(true, true, JsonObject(emptyMap())),
        compatExtras = JsonObject(emptyMap()),
    )
}
