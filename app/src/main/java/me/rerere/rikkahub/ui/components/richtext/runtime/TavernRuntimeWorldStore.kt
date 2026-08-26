package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

internal interface TavernWorldRepository {
    fun listEntries(): List<JsonObject>
    fun upsertEntry(entry: JsonObject): String
    fun deleteEntry(id: String): Boolean
    fun listBooks(): List<JsonObject>
    fun getBook(nameOrId: String): JsonObject?
    fun createBook(name: String, entries: List<JsonObject>): JsonObject?
    fun updateBook(nameOrId: String, patch: JsonObject): JsonObject?
    fun deleteBook(nameOrId: String): Boolean
}

internal class TavernRuntimeWorldStore : TavernWorldRepository {
    private class Book(
        val id: String,
        var name: String,
        var description: String = "",
        var enabled: Boolean = true,
        var tokenBudget: Int = 0,
        var recursiveScanning: Boolean = false,
        val entries: LinkedHashMap<String, JsonObject> = linkedMapOf(),
    ) {
        fun toJson(includeEntries: Boolean): JsonObject = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("name", JsonPrimitive(name))
            put("description", JsonPrimitive(description))
            put("enabled", JsonPrimitive(enabled))
            put("tokenBudget", JsonPrimitive(tokenBudget))
            put("recursiveScanning", JsonPrimitive(recursiveScanning))
            put("entryCount", JsonPrimitive(entries.size))
            if (includeEntries) {
                put("entries", JsonArray(entries.values.toList()))
            }
        }
    }

    private val books = linkedMapOf<String, Book>()
    private var entrySeq = 0
    private var bookSeq = 0

    override fun listEntries(): List<JsonObject> =
        books.values.flatMap { book -> book.entries.values.toList() }

    override fun upsertEntry(entry: JsonObject): String {
        val id = entry.getString("id")?.takeIf { it.isNotBlank() } ?: "entry-${++entrySeq}"
        val bookKey = entry.getString("lorebookId") ?: entry.getString("lorebookName")
        val target = resolveBook(bookKey) ?: run {
            if (!bookKey.isNullOrBlank()) {
                // 与 Settings 实现一致：条目指向不存在的世界书时按名创建
                val name = entry.getString("lorebookName")?.takeIf { it.isNotBlank() } ?: bookKey
                Book(id = bookKey, name = name).also { books[name] = it }
            } else {
                defaultBook()
            }
        }
        val normalized = buildJsonObject {
            entry.forEach { (key, value) -> put(key, value) }
            put("id", JsonPrimitive(id))
        }
        // 条目可能跨书移动：先从所有书中移除同 id 条目
        books.values.forEach { it.entries.remove(id) }
        target.entries[id] = normalized
        return id
    }

    override fun deleteEntry(id: String): Boolean {
        var deleted = false
        books.values.forEach { book ->
            if (book.entries.remove(id) != null) deleted = true
        }
        return deleted
    }

    override fun listBooks(): List<JsonObject> = books.values.map { it.toJson(includeEntries = false) }

    override fun getBook(nameOrId: String): JsonObject? = resolveBook(nameOrId)?.toJson(includeEntries = true)

    override fun createBook(name: String, entries: List<JsonObject>): JsonObject? {
        if (name.isBlank() || resolveBook(name) != null) return null
        val book = Book(id = "book-${++bookSeq}", name = name)
        books[name] = book
        entries.forEach { entry -> upsertInto(book, entry) }
        return book.toJson(includeEntries = true)
    }

    override fun updateBook(nameOrId: String, patch: JsonObject): JsonObject? {
        val book = resolveBook(nameOrId) ?: return null
        val newName = patch.getString("name")?.takeIf { it.isNotBlank() }
        if (newName != null && newName != book.name) {
            // 重命名撞名：返回 null 由调用方区分 ALREADY_EXISTS
            if (resolveBook(newName) != null) return null
            books.remove(book.name)
            book.name = newName
            books[newName] = book
        }
        patch.getString("description")?.let { book.description = it }
        (patch["enabled"] as? JsonPrimitive)?.booleanOrNull?.let { book.enabled = it }
        (patch["tokenBudget"] as? JsonPrimitive)?.intOrNull?.let { book.tokenBudget = it }
        (patch["recursiveScanning"] as? JsonPrimitive)?.booleanOrNull?.let { book.recursiveScanning = it }
        (patch["entries"] as? JsonArray)?.let { array ->
            book.entries.clear()
            array.mapNotNull { it as? JsonObject }.forEach { entry -> upsertInto(book, entry) }
        }
        return book.toJson(includeEntries = true)
    }

    override fun deleteBook(nameOrId: String): Boolean {
        val book = resolveBook(nameOrId) ?: return false
        books.remove(book.name)
        return true
    }

    private fun upsertInto(book: Book, entry: JsonObject): String {
        val id = entry.getString("id")?.takeIf { it.isNotBlank() } ?: "entry-${++entrySeq}"
        book.entries[id] = buildJsonObject {
            entry.forEach { (key, value) -> put(key, value) }
            put("id", JsonPrimitive(id))
        }
        return id
    }

    private fun defaultBook(): Book {
        return books.getOrPut(DEFAULT_WORLD_BOOK_NAME) {
            Book(id = DEFAULT_WORLD_BOOK_ID, name = DEFAULT_WORLD_BOOK_NAME)
        }
    }

    private fun resolveBook(nameOrId: String?): Book? {
        if (nameOrId.isNullOrBlank()) return null
        return books.values.firstOrNull { it.id == nameOrId } ?: books[nameOrId]
    }

    private companion object {
        const val DEFAULT_WORLD_BOOK_ID = "00000000-0000-4000-8000-000000000042"
        const val DEFAULT_WORLD_BOOK_NAME = "Tavern Helper Runtime"
    }
}
