package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Lorebook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernRuntimeWorldBookTest {

    // ── SettingsBackedTavernWorldRepository：书本级 CRUD ──

    @Test
    fun `settings repository creates reads updates and deletes books`() {
        val gateway = FakeGateway(Settings(lorebooks = emptyList()))
        val repository = SettingsBackedTavernWorldRepository(gateway)

        val created = repository.createBook(
            "Arcane World",
            listOf(
                buildJsonObject {
                    put("name", JsonPrimitive("hero"))
                    put("content", JsonPrimitive("A brave hero"))
                    put("keywords", buildJsonArray { add(JsonPrimitive("hero")) })
                }
            ),
        )
        assertNotNull(created)
        created!!
        assertEquals("Arcane World", created.getValue("name").jsonPrimitive.content)
        assertEquals(1, created.getValue("entryCount").jsonPrimitive.int)
        assertEquals(1, created.getValue("entries").jsonArray.size)
        val entryId = created.getValue("entries").jsonArray.single()
            .jsonObject.getValue("id").jsonPrimitive.content
        assertTrue(entryId.isNotBlank())

        // listBooks 不携带 entries，仅元数据
        val books = repository.listBooks()
        assertEquals(1, books.size)
        assertEquals("Arcane World", books.single().getValue("name").jsonPrimitive.content)
        assertFalse(books.single().containsKey("entries"))

        // getBook 支持按名与按 id
        val bookId = created.getValue("id").jsonPrimitive.content
        assertNotNull(repository.getBook("Arcane World"))
        assertNotNull(repository.getBook(bookId))
        assertNull(repository.getBook("missing"))

        // updateBook：改名 + 元数据补丁
        val renamed = repository.updateBook(
            "Arcane World",
            buildJsonObject {
                put("name", JsonPrimitive("Mystic World"))
                put("description", JsonPrimitive("rewritten"))
                put("enabled", JsonPrimitive(false))
                put("tokenBudget", JsonPrimitive(512))
                put("recursiveScanning", JsonPrimitive(true))
            },
        )
        assertNotNull(renamed)
        renamed!!
        assertEquals("Mystic World", renamed.getValue("name").jsonPrimitive.content)
        assertEquals("rewritten", renamed.getValue("description").jsonPrimitive.content)
        assertFalse(renamed.getValue("enabled").jsonPrimitive.boolean)
        assertEquals(512, renamed.getValue("tokenBudget").jsonPrimitive.int)
        assertTrue(renamed.getValue("recursiveScanning").jsonPrimitive.boolean)
        assertEquals("Mystic World", gateway.current.lorebooks.single().name)
        assertFalse(gateway.current.lorebooks.single().enabled)

        // updateBook：替换条目
        val replaced = repository.updateBook(
            "Mystic World",
            buildJsonObject {
                put(
                    "entries",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("name", JsonPrimitive("villain"))
                                put("content", JsonPrimitive("A cunning villain"))
                            }
                        )
                        add(
                            buildJsonObject {
                                put("name", JsonPrimitive("mentor"))
                                put("content", JsonPrimitive("A wise mentor"))
                            }
                        )
                    }
                )
            },
        )
        assertNotNull(replaced)
        assertEquals(2, replaced!!.getValue("entryCount").jsonPrimitive.int)
        assertEquals(2, gateway.current.lorebooks.single().entries.size)
        assertEquals("villain", gateway.current.lorebooks.single().entries.first().name)

        // deleteBook
        assertTrue(repository.deleteBook("Mystic World"))
        assertTrue(repository.listBooks().isEmpty())
        assertTrue(gateway.current.lorebooks.isEmpty())
        assertFalse(repository.deleteBook("Mystic World"))
    }

    @Test
    fun `settings repository rejects duplicate book names without mutating settings`() {
        val gateway = FakeGateway(
            Settings(lorebooks = listOf(Lorebook(name = "Existing", entries = emptyList())))
        )
        val repository = SettingsBackedTavernWorldRepository(gateway)

        assertNull(repository.createBook("Existing", emptyList()))
        assertEquals(1, gateway.current.lorebooks.size)

        assertNull(repository.createBook("", emptyList()))

        // 重命名为已占用的名字：返回 null 且原书不变
        repository.createBook("Second", emptyList())
        assertNull(repository.updateBook("Second", buildJsonObject { put("name", JsonPrimitive("Existing")) }))
        assertEquals("Second", gateway.current.lorebooks.first { it.name != "Existing" }.name)
    }

    @Test
    fun `settings repository entries remain interoperable with book operations`() {
        val gateway = FakeGateway(Settings(lorebooks = emptyList()))
        val repository = SettingsBackedTavernWorldRepository(gateway)

        repository.createBook("Shared", emptyList())
        val bookId = gateway.current.lorebooks.single().id.toString()

        // 旧条目 API 写入指定书后，getBook 能读到同一条目
        val entryId = repository.upsertEntry(
            buildJsonObject {
                put("lorebookId", JsonPrimitive(bookId))
                put("name", JsonPrimitive("hero"))
                put("content", JsonPrimitive("via legacy upsert"))
            }
        )
        val book = repository.getBook("Shared")
        assertNotNull(book)
        val entries = book!!.getValue("entries").jsonArray
        assertEquals(1, entries.size)
        assertEquals(entryId, entries.single().jsonObject.getValue("id").jsonPrimitive.content)

        // 平面 listEntries 与书本视图一致
        assertEquals(1, repository.listEntries().size)
    }

    // ── TavernRuntimeWorldStore（内存实现）：书本级 CRUD ──

    @Test
    fun `in-memory store supports book lifecycle and entry routing`() {
        val store = TavernRuntimeWorldStore()

        assertNull(store.createBook("", emptyList()))
        val created = store.createBook(
            "World A",
            listOf(buildJsonObject { put("content", JsonPrimitive("first")) }),
        )
        assertNotNull(created)
        assertEquals(1, created!!.getValue("entryCount").jsonPrimitive.int)
        assertNull(store.createBook("World A", emptyList()))

        // 无书字段的条目进入默认运行时书
        val defaultEntryId = store.upsertEntry(buildJsonObject { put("content", JsonPrimitive("loose")) })
        val defaultBook = store.getBook("Tavern Helper Runtime")
        assertNotNull(defaultBook)
        assertEquals(
            defaultEntryId,
            defaultBook!!.getValue("entries").jsonArray.single()
                .jsonObject.getValue("id").jsonPrimitive.content,
        )

        // 指定不存在书名的条目会按名创建书（与 Settings 实现一致）
        store.upsertEntry(
            buildJsonObject {
                put("lorebookName", JsonPrimitive("World B"))
                put("content", JsonPrimitive("auto-created"))
            }
        )
        assertNotNull(store.getBook("World B"))

        // 平面列表聚合所有书的条目
        assertEquals(3, store.listEntries().size)

        // updateBook 重命名撞名返回 null
        store.createBook("World C", emptyList())
        assertNull(store.updateBook("World C", buildJsonObject { put("name", JsonPrimitive("World A")) }))
        assertNotNull(store.getBook("World C"))

        // updateBook 替换条目
        val updated = store.updateBook(
            "World A",
            buildJsonObject {
                put(
                    "entries",
                    buildJsonArray {
                        add(buildJsonObject { put("content", JsonPrimitive("replaced")) })
                    }
                )
            },
        )
        assertNotNull(updated)
        assertEquals(1, updated!!.getValue("entryCount").jsonPrimitive.int)

        // deleteBook 连带删除条目
        assertTrue(store.deleteBook("World A"))
        assertNull(store.getBook("World A"))
        assertFalse(store.deleteBook("World A"))
        assertEquals(2, store.listEntries().size)
    }

    private class FakeGateway(initial: Settings) : TavernWorldSettingsGateway {
        var current: Settings = initial

        override fun currentSettings(): Settings = current

        override fun updateSettings(transform: (Settings) -> Settings) {
            current = transform(current)
        }
    }
}
