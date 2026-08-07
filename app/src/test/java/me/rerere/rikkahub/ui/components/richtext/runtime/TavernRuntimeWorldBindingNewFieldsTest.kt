package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * TavernRuntimeWorldBinding 对世界书新字段（secondaryKeywords / selective / probability）
 * 的 JSON 双向同步测试
 */
class TavernRuntimeWorldBindingNewFieldsTest {

    private val bookId: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000401")
    private val entryId: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000402")

    private class FakeGateway(initial: Settings) : TavernWorldSettingsGateway {
        var current: Settings = initial

        override fun currentSettings(): Settings = current

        override fun updateSettings(transform: (Settings) -> Settings) {
            current = transform(current)
        }
    }

    private fun repositoryWithEntry(): Pair<SettingsBackedTavernWorldRepository, FakeGateway> {
        val gateway = FakeGateway(
            Settings(
                lorebooks = listOf(
                    Lorebook(
                        id = bookId,
                        name = "World",
                        tokenBudget = 200,
                        recursiveScanning = true,
                        entries = listOf(
                            PromptInjection.RegexInjection(
                                id = entryId,
                                name = "hero",
                                content = "a hero",
                                keywords = listOf("hero"),
                                secondaryKeywords = listOf("sword"),
                                selective = true,
                                probability = 70,
                                sticky = 2,
                                cooldown = 3,
                                delay = 1,
                            )
                        ),
                    )
                )
            )
        )
        return SettingsBackedTavernWorldRepository(gateway) to gateway
    }

    @Test
    fun `listEntries exposes new fields to scripts`() {
        val (repository, _) = repositoryWithEntry()

        val json = repository.listEntries().single()
        assertEquals(
            listOf("sword"),
            json.getValue("secondaryKeywords").jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(json.getValue("selective").jsonPrimitive.boolean)
        assertEquals(70, json.getValue("probability").jsonPrimitive.int)
        assertEquals(2, json.getValue("sticky").jsonPrimitive.int)
        assertEquals(3, json.getValue("cooldown").jsonPrimitive.int)
        assertEquals(1, json.getValue("delay").jsonPrimitive.int)
        assertEquals(200, json.getValue("lorebookTokenBudget").jsonPrimitive.int)
        assertTrue(json.getValue("lorebookRecursiveScanning").jsonPrimitive.boolean)
    }

    @Test
    fun `upsertEntry persists new fields back to model`() {
        val (repository, gateway) = repositoryWithEntry()

        repository.upsertEntry(
            buildJsonObject {
                put("id", JsonPrimitive(entryId.toString()))
                put("lorebookId", JsonPrimitive(bookId.toString()))
                put("name", JsonPrimitive("hero"))
                put("content", JsonPrimitive("a wiser hero"))
                put("keywords", buildJsonArray { add(JsonPrimitive("hero")) })
                put("secondaryKeywords", buildJsonArray { add(JsonPrimitive("shield")) })
                put("selective", JsonPrimitive(false))
                put("probability", JsonPrimitive(25))
                put("sticky", JsonPrimitive(4))
                put("cooldown", JsonPrimitive(2))
                put("delay", JsonPrimitive(1))
            }
        )

        val entry = gateway.current.lorebooks.single().entries.single()
        assertEquals(listOf("shield"), entry.secondaryKeywords)
        assertEquals(false, entry.selective)
        assertEquals(25, entry.probability)
        assertEquals(4, entry.sticky)
        assertEquals(2, entry.cooldown)
        assertEquals(1, entry.delay)
    }

    @Test
    fun `upsertEntry without new fields keeps defaults`() {
        val (repository, gateway) = repositoryWithEntry()

        repository.upsertEntry(
            buildJsonObject {
                put("id", JsonPrimitive(entryId.toString()))
                put("lorebookId", JsonPrimitive(bookId.toString()))
                put("name", JsonPrimitive("hero"))
                put("content", JsonPrimitive("plain"))
            }
        )

        val entry = gateway.current.lorebooks.single().entries.single()
        assertEquals(emptyList<String>(), entry.secondaryKeywords)
        assertEquals(false, entry.selective)
        assertEquals(100, entry.probability)
        assertEquals(0, entry.sticky)
        assertEquals(0, entry.cooldown)
        assertEquals(0, entry.delay)
    }
}
