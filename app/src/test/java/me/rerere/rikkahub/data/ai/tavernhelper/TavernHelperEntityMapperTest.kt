package me.rerere.rikkahub.data.ai.tavernhelper

import java.nio.file.Files
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernHelperEntityMapperTest {
    @Test
    fun `folder tree round trips through normalized entities and external source`() {
        val root = Files.createTempDirectory("tavern-helper-mapper").toFile()
        try {
            val mapper = TavernHelperEntityMapper(
                fileStore = TavernHelperFileStore(root, inlineThresholdBytes = 8),
                now = { 123L },
            )
            val script = TavernHelperScript(
                id = "script",
                name = "脚本",
                enabled = true,
                content = "console.log('large source')",
                info = "说明",
                button = TavernHelperButtonConfig(true, emptyList(), JsonObject(emptyMap())),
                data = JsonObject(emptyMap()),
                exportWith = TavernHelperExportWith(true, true, JsonObject(emptyMap())),
                compatExtras = JsonObject(emptyMap()),
            )
            val folder = TavernHelperScriptFolder(
                id = "folder",
                name = "文件夹",
                enabled = true,
                icon = "fa-solid fa-folder",
                color = "#abcdef",
                scripts = listOf(script),
                compatExtras = JsonObject(emptyMap()),
            )
            val scope = TavernHelperScope(TavernHelperScopeType.CHARACTER, "card-key")

            val entities = mapper.toEntities(folder, scope, topLevelOrder = 4)

            assertEquals(2, entities.size)
            assertEquals(4, entities.first { it.id == "folder" }.sortOrder)
            val scriptEntity = entities.first { it.id == "script" }
            assertEquals("folder", scriptEntity.parentId)
            assertNull(scriptEntity.sourceInline)
            assertTrue(scriptEntity.sourcePath!!.startsWith("source/"))
            assertEquals(listOf(folder), mapper.toTrees(entities))
        } finally {
            root.deleteRecursively()
        }
    }
}
