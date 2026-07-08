package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class TavernRuntimeWorldBindingTest {
    @Test
    fun `repository reads and writes real lorebook data`() {
        val entryId = Uuid.parse("00000000-0000-4000-8000-000000000101")
        val lorebookId = Uuid.parse("00000000-0000-4000-8000-000000000102")
        val gateway = FakeGateway(
            Settings(
                lorebooks = listOf(
                    Lorebook(
                        id = lorebookId,
                        name = "Main World",
                        description = "primary",
                        enabled = true,
                        entries = listOf(
                            PromptInjection.RegexInjection(
                                id = entryId,
                                name = "hero",
                                content = "A brave hero",
                                position = InjectionPosition.AFTER_SYSTEM_PROMPT,
                                role = MessageRole.USER,
                                keywords = listOf("hero"),
                                scanDepth = 4,
                            )
                        ),
                    )
                )
            )
        )
        val repository = SettingsBackedTavernWorldRepository(gateway)

        val entries = repository.listEntries()
        assertEquals(1, entries.size)
        assertEquals(entryId.toString(), entries.first().getValue("id").jsonPrimitive.content)
        assertEquals(lorebookId.toString(), entries.first().getValue("lorebookId").jsonPrimitive.content)

        val updatedId = repository.upsertEntry(
            buildJsonObject {
                put("id", JsonPrimitive(entryId.toString()))
                put("lorebookId", JsonPrimitive(lorebookId.toString()))
                put("name", JsonPrimitive("hero"))
                put("content", JsonPrimitive("A wiser hero"))
                put("keywords", buildJsonArray { add(JsonPrimitive("hero")); add(JsonPrimitive("ally")) })
                put("position", JsonPrimitive("after_system_prompt"))
            }
        )

        assertEquals(entryId.toString(), updatedId)
        assertEquals("A wiser hero", gateway.current.lorebooks.single().entries.single().content)
        assertEquals(listOf("hero", "ally"), gateway.current.lorebooks.single().entries.single().keywords)

        assertTrue(repository.deleteEntry(entryId.toString()))
        assertTrue(repository.listEntries().isEmpty())
        assertTrue(gateway.current.lorebooks.flatMap { it.entries }.isEmpty())

        val createdId = repository.upsertEntry(
            buildJsonObject {
                put("name", JsonPrimitive("runtime"))
                put("content", JsonPrimitive("new entry"))
            }
        )

        assertFalse(createdId.isBlank())
        assertTrue(
            gateway.current.lorebooks.any { book ->
                book.name == "Tavern Helper Runtime" &&
                    book.entries.size == 1 &&
                    book.entries.single().content == "new entry"
            }
        )
    }

    private class FakeGateway(initial: Settings) : TavernWorldSettingsGateway {
        var current: Settings = initial

        override fun currentSettings(): Settings = current

        override fun updateSettings(transform: (Settings) -> Settings) {
            current = transform(current)
        }
    }
}
