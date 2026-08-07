package me.rerere.rikkahub.data.export

import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LorebookSerializer 对 ST 世界书字段（secondary_keys / keysecondary / selective / probability）
 * 与原生格式的双向映射测试
 */
class LorebookSerializerTest {

    @Test
    fun `sillytavern import maps trigger decorators`() {
        val stJson = """
            {
              "entries": {
                "0": {
                  "key": ["dragon"],
                  "content": "dragons breathe fire",
                  "comment": "dragon lore",
                  "position": 1,
                  "sticky": 2,
                  "cooldown": 3,
                  "delay": 1
                },
                "1": {
                  "key": ["inn"],
                  "content": "a cozy inn",
                  "position": 1
                }
              }
            }
        """.trimIndent()

        val book = LorebookSerializer.tryImportSillyTavern(stJson, "imported")
        assertNotNull(book)
        val dragon = book!!.entries.first { it.keywords == listOf("dragon") }
        assertEquals(2, dragon.sticky)
        assertEquals(3, dragon.cooldown)
        assertEquals(1, dragon.delay)
        // 缺席时回退默认值 0（关闭）
        val inn = book.entries.first { it.keywords == listOf("inn") }
        assertEquals(0, inn.sticky)
        assertEquals(0, inn.cooldown)
        assertEquals(0, inn.delay)
    }

    @Test
    fun `sillytavern import maps secondary selective and probability`() {
        val stJson = """
            {
              "entries": {
                "0": {
                  "key": ["dragon"],
                  "keysecondary": ["fire"],
                  "selective": true,
                  "probability": 60,
                  "content": "dragons breathe fire",
                  "comment": "dragon lore",
                  "constant": false,
                  "position": 1,
                  "order": 50,
                  "disable": false,
                  "depth": 3,
                  "scanDepth": 5,
                  "caseSensitive": true
                }
              }
            }
        """.trimIndent()

        val book = LorebookSerializer.tryImportSillyTavern(stJson, "imported")
        assertNotNull(book)
        val entry = book!!.entries.single()
        assertEquals(listOf("dragon"), entry.keywords)
        assertEquals(listOf("fire"), entry.secondaryKeywords)
        assertTrue(entry.selective)
        assertEquals(60, entry.probability)
        // 既有字段映射不回退
        assertEquals("dragon lore", entry.name)
        assertEquals(50, entry.priority)
        assertEquals(5, entry.scanDepth)
        assertTrue(entry.caseSensitive)
    }

    @Test
    fun `sillytavern import accepts secondary_keys spelling`() {
        val stJson = """
            {
              "entries": {
                "0": {
                  "key": ["dragon"],
                  "secondary_keys": ["wing"],
                  "selective": true,
                  "probability": 30,
                  "content": "dragon wings"
                }
              }
            }
        """.trimIndent()

        val entry = LorebookSerializer.tryImportSillyTavern(stJson, null)!!.entries.single()
        assertEquals(listOf("wing"), entry.secondaryKeywords)
        assertTrue(entry.selective)
        assertEquals(30, entry.probability)
    }

    @Test
    fun `sillytavern import without new fields uses defaults`() {
        val stJson = """
            {
              "entries": {
                "0": {
                  "key": ["dragon"],
                  "content": "legacy entry"
                }
              }
            }
        """.trimIndent()

        val entry = LorebookSerializer.tryImportSillyTavern(stJson, null)!!.entries.single()
        assertEquals(emptyList<String>(), entry.secondaryKeywords)
        assertFalse(entry.selective)
        assertEquals(100, entry.probability)
    }

    @Test
    fun `native export import round trip preserves new fields`() {
        val book = Lorebook(
            name = "native book",
            tokenBudget = 300,
            recursiveScanning = true,
            entries = listOf(
                PromptInjection.RegexInjection(
                    name = "entry",
                    keywords = listOf("a"),
                    secondaryKeywords = listOf("b"),
                    selective = true,
                    probability = 45,
                )
            ),
        )

        val json = LorebookSerializer.exportToJson(book)
        val imported = LorebookSerializer.tryImportNative(json)

        assertNotNull(imported)
        assertEquals("native book", imported!!.name)
        assertEquals(300, imported.tokenBudget)
        assertTrue(imported.recursiveScanning)
        val entry = imported.entries.single()
        assertEquals(listOf("b"), entry.secondaryKeywords)
        assertTrue(entry.selective)
        assertEquals(45, entry.probability)
    }

    @Test
    fun `native import of old json without new fields uses defaults`() {
        val oldJson = """
            {
              "version": 1,
              "type": "lorebook",
              "data": {
                "id": "00000000-0000-4000-8000-000000000301",
                "name": "old book",
                "description": "",
                "enabled": true,
                "entries": [
                  {
                    "id": "00000000-0000-4000-8000-000000000302",
                    "name": "e",
                    "enabled": true,
                    "priority": 0,
                    "position": "after_system_prompt",
                    "content": "c",
                    "injectDepth": 4,
                    "role": "user",
                    "keywords": ["k"],
                    "useRegex": false,
                    "caseSensitive": false,
                    "scanDepth": 4,
                    "constantActive": false
                  }
                ]
              }
            }
        """.trimIndent()

        val book = LorebookSerializer.tryImportNative(oldJson)
        assertNotNull(book)
        assertEquals(0, book!!.tokenBudget)
        assertFalse(book.recursiveScanning)
        val entry = book.entries.single()
        assertEquals(emptyList<String>(), entry.secondaryKeywords)
        assertFalse(entry.selective)
        assertEquals(100, entry.probability)
    }
}
