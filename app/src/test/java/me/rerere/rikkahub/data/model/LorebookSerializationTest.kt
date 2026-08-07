package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 世界书新字段（secondaryKeywords / selective / probability / tokenBudget / recursiveScanning）
 * 的 JSON 序列化向后兼容测试
 */
class LorebookSerializationTest {

    private val oldLorebookJson = """
        {
          "id": "00000000-0000-4000-8000-000000000201",
          "name": "old book",
          "description": "legacy",
          "enabled": true,
          "entries": [
            {
              "id": "00000000-0000-4000-8000-000000000202",
              "name": "old entry",
              "enabled": true,
              "priority": 3,
              "position": "after_system_prompt",
              "content": "legacy content",
              "injectDepth": 4,
              "role": "user",
              "keywords": ["dragon"],
              "useRegex": false,
              "caseSensitive": false,
              "scanDepth": 4,
              "constantActive": false
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `old json without new fields deserializes with defaults`() {
        val book = JsonInstant.decodeFromString(Lorebook.serializer(), oldLorebookJson)

        assertEquals("old book", book.name)
        assertEquals(0, book.tokenBudget)
        assertFalse(book.recursiveScanning)
        val entry = book.entries.single()
        assertEquals("old entry", entry.name)
        assertEquals(emptyList<String>(), entry.secondaryKeywords)
        assertFalse(entry.selective)
        assertEquals(100, entry.probability)
        // 旧字段不受影响
        assertEquals(3, entry.priority)
        assertEquals(listOf("dragon"), entry.keywords)
    }

    @Test
    fun `new fields round trip through json`() {
        val book = Lorebook(
            id = Uuid.parse("00000000-0000-4000-8000-000000000203"),
            name = "new book",
            tokenBudget = 500,
            recursiveScanning = true,
            entries = listOf(
                PromptInjection.RegexInjection(
                    id = Uuid.parse("00000000-0000-4000-8000-000000000204"),
                    name = "entry",
                    keywords = listOf("dragon"),
                    secondaryKeywords = listOf("fire", "wing"),
                    selective = true,
                    probability = 65,
                )
            ),
        )

        val json = JsonInstant.encodeToString(Lorebook.serializer(), book)
        val decoded = JsonInstant.decodeFromString(Lorebook.serializer(), json)

        assertEquals(book, decoded)
        // encodeDefaults = true，新字段应出现在 JSON 中
        val root = JsonInstant.parseToJsonElement(json).jsonObject
        assertEquals(500, root.getValue("tokenBudget").jsonPrimitive.content.toInt())
        assertEquals(true, root.getValue("recursiveScanning").jsonPrimitive.content.toBoolean())
        val entryJson = root.getValue("entries").jsonArray.single().jsonObject
        assertEquals(
            listOf("fire", "wing"),
            entryJson.getValue("secondaryKeywords").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(JsonPrimitive(true), entryJson.getValue("selective"))
        assertEquals("65", entryJson.getValue("probability").jsonPrimitive.content)
    }
}
