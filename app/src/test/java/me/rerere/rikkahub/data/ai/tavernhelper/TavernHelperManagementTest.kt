package me.rerere.rikkahub.data.ai.tavernhelper

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `reorder moves roots and folder children without crossing their container`() {
        val a = script("a", "A", "")
        val b = script("b", "B", "")
        val child1 = script("c1", "C1", "")
        val child2 = script("c2", "C2", "")
        val folder = folder("folder", listOf(child1, child2))

        val roots = reorderTavernHelperNodes(listOf(a, b, folder), "b", -1)
        assertEquals(listOf("b", "a", "folder"), roots.map { it.id })

        val children = reorderTavernHelperNodes(roots, "c2", -1)
        assertEquals(
            listOf("c2", "c1"),
            (children.last() as TavernHelperScriptFolder).scripts.map { it.id },
        )
        assertEquals(children, reorderTavernHelperNodes(children, "c2", -1))
    }

    @Test
    fun `detach finds nested script and keeps its source folder`() {
        val child = script("child", "Child", "")
        val folder = folder("folder", listOf(child))

        val result = detachTavernHelperNode(listOf(folder), "child")

        assertEquals(child, result.node)
        assertTrue((result.remaining.single() as TavernHelperScriptFolder).scripts.isEmpty())
        assertNull(detachTavernHelperNode(result.remaining, "missing").node)
    }

    @Test
    fun `copy for another scope replaces folder and child ids and disables scripts`() {
        val original = folder("folder", listOf(script("child", "Child", "")))
        val ids = ArrayDeque(listOf("folder-copy", "child-copy"))

        val copy = original.copyForTavernHelperTransfer { ids.removeFirst() } as TavernHelperScriptFolder

        assertEquals("folder-copy", copy.id)
        assertEquals("child-copy", copy.scripts.single().id)
        assertNotEquals(original.id, copy.id)
        assertTrue(!copy.enabled && !copy.scripts.single().enabled)
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

    private fun folder(id: String, scripts: List<TavernHelperScript>) = TavernHelperScriptFolder(
        id = id,
        name = id,
        enabled = true,
        icon = "fa-solid fa-folder",
        color = "",
        scripts = scripts,
        compatExtras = JsonObject(emptyMap()),
    )
}
