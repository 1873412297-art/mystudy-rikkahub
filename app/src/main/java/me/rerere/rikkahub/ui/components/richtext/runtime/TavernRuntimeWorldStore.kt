package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal interface TavernWorldRepository {
    fun listEntries(): List<JsonObject>
    fun upsertEntry(entry: JsonObject): String
    fun deleteEntry(id: String): Boolean
}

internal class TavernRuntimeWorldStore : TavernWorldRepository {
    private val entries = linkedMapOf<String, JsonObject>()

    override fun listEntries(): List<JsonObject> = entries.values.toList()

    override fun upsertEntry(entry: JsonObject): String {
        val id = entry.getString("id") ?: "entry-${entries.size + 1}"
        val normalized = buildJsonObject {
            entry.forEach { (key, value) -> put(key, value) }
            put("id", JsonPrimitive(id))
        }
        entries[id] = normalized
        return id
    }

    override fun deleteEntry(id: String): Boolean = entries.remove(id) != null
}
