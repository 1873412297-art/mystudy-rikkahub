package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import kotlin.uuid.Uuid

private const val RUNTIME_LOREBOOK_NAME = "Tavern Helper Runtime"
private val RUNTIME_LOREBOOK_ID: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000042")

internal interface TavernWorldSettingsGateway {
    fun currentSettings(): Settings
    fun updateSettings(transform: (Settings) -> Settings)
}

internal class SettingsStoreTavernWorldGateway(
    private val settingsStore: SettingsStore,
) : TavernWorldSettingsGateway {
    override fun currentSettings(): Settings = settingsStore.settingsFlow.value

    override fun updateSettings(transform: (Settings) -> Settings) {
        runBlocking {
            settingsStore.update(transform)
        }
    }
}

internal class SettingsBackedTavernWorldRepository(
    private val gateway: TavernWorldSettingsGateway,
) : TavernWorldRepository {
    override fun listEntries(): List<JsonObject> {
        val settings = gateway.currentSettings()
        return settings.lorebooks.flatMap { lorebook ->
            lorebook.entries.map { entry -> entry.toWorldJson(lorebook) }
        }
    }

    override fun upsertEntry(entry: JsonObject): String {
        val existingId = entry.getString("id")?.takeIf { it.isNotBlank() } ?: Uuid.random().toString()
        val targetBookId = entry.getString("lorebookId")?.toUuidOrNull()
        gateway.updateSettings { settings ->
            settings.copy(
                lorebooks = settings.lorebooks.upsertWorldEntry(
                    targetBookId = targetBookId,
                    entry = entry,
                    entryId = existingId,
                )
            )
        }
        return existingId
    }

    override fun deleteEntry(id: String): Boolean {
        var deleted = false
        gateway.updateSettings { settings ->
            val updated = settings.lorebooks.deleteWorldEntry(id)
            deleted = updated.second
            settings.copy(lorebooks = updated.first)
        }
        return deleted
    }
}

private fun List<Lorebook>.upsertWorldEntry(
    targetBookId: Uuid?,
    entry: JsonObject,
    entryId: String,
): List<Lorebook> {
    val entryModel = entry.toRegexInjection(entryId)
    val desiredBookId = targetBookId ?: entry.getString("lorebookId")?.toUuidOrNull() ?: RUNTIME_LOREBOOK_ID
    val desiredName = entry.getString("lorebookName") ?: RUNTIME_LOREBOOK_NAME
    val desiredDescription = entry.getString("lorebookDescription") ?: "Runtime world entries exposed to Tavern Helper scripts"

    val existingIndex = indexOfFirst { it.id == desiredBookId || it.name == desiredName }
    return if (existingIndex >= 0) {
        mapIndexed { index, lorebook ->
            if (index != existingIndex) {
                lorebook
            } else {
                val entries = lorebook.entries.toMutableList()
                val existingEntryIndex = entries.indexOfFirst { it.id.toString() == entryId }
                if (existingEntryIndex >= 0) {
                    entries[existingEntryIndex] = entryModel
                } else {
                    entries.add(entryModel)
                }
                lorebook.copy(entries = entries)
            }
        }
    } else {
        plus(
            Lorebook(
                id = desiredBookId,
                name = desiredName,
                description = desiredDescription,
                enabled = true,
                entries = listOf(entryModel),
            )
        )
    }
}

private fun List<Lorebook>.deleteWorldEntry(id: String): Pair<List<Lorebook>, Boolean> {
    var deleted = false
    val updated = mapNotNull { lorebook ->
        val filtered = lorebook.entries.filterNot { entry ->
            val matches = entry.id.toString() == id
            if (matches) deleted = true
            matches
        }
        if (filtered.isEmpty() && (lorebook.id == RUNTIME_LOREBOOK_ID || lorebook.name == RUNTIME_LOREBOOK_NAME)) {
            if (deleted) null else lorebook
        } else if (filtered.size != lorebook.entries.size) {
            lorebook.copy(entries = filtered)
        } else {
            lorebook
        }
    }
    return updated to deleted
}

private fun PromptInjection.RegexInjection.toWorldJson(lorebook: Lorebook): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(id.toString()))
    put("lorebookId", JsonPrimitive(lorebook.id.toString()))
    put("lorebookName", JsonPrimitive(lorebook.name))
    put("lorebookDescription", JsonPrimitive(lorebook.description))
    put("name", JsonPrimitive(name))
    put("enabled", JsonPrimitive(enabled))
    put("priority", JsonPrimitive(priority))
    put("position", JsonPrimitive(position.name.lowercase()))
    put("content", JsonPrimitive(content))
    put("injectDepth", JsonPrimitive(injectDepth))
    put("role", JsonPrimitive(role.name.lowercase()))
    put("keywords", JsonArray(keywords.map { JsonPrimitive(it) }))
    put("useRegex", JsonPrimitive(useRegex))
    put("caseSensitive", JsonPrimitive(caseSensitive))
    put("scanDepth", JsonPrimitive(scanDepth))
    put("constantActive", JsonPrimitive(constantActive))
}

private fun JsonObject.toRegexInjection(entryId: String): PromptInjection.RegexInjection {
    val parsedId = getString("id")?.toUuidOrNull() ?: Uuid.parse(entryId)
    return PromptInjection.RegexInjection(
        id = parsedId,
        name = getString("name").orEmpty(),
        enabled = getBoolean("enabled", true),
        priority = getInt("priority", 0),
        position = getPosition("position"),
        content = getString("content").orEmpty(),
        injectDepth = getInt("injectDepth", 4),
        role = getRole("role"),
        keywords = getJsonStrings("keywords"),
        useRegex = getBoolean("useRegex", false),
        caseSensitive = getBoolean("caseSensitive", false),
        scanDepth = getInt("scanDepth", 4),
        constantActive = getBoolean("constantActive", false),
    )
}

private fun JsonObject.getBoolean(name: String, default: Boolean): Boolean {
    return (this[name] as? JsonPrimitive)?.booleanOrNull ?: default
}

private fun JsonObject.getInt(name: String, default: Int): Int {
    return (this[name] as? JsonPrimitive)?.intOrNull ?: default
}

private fun JsonObject.getJsonStrings(name: String): List<String> {
    val raw = this[name] as? JsonArray ?: return emptyList()
    return raw.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}

private fun JsonObject.getPosition(name: String): InjectionPosition {
    return when (getString(name)?.lowercase()) {
        "before_system_prompt" -> InjectionPosition.BEFORE_SYSTEM_PROMPT
        "after_system_prompt" -> InjectionPosition.AFTER_SYSTEM_PROMPT
        "top_of_chat" -> InjectionPosition.TOP_OF_CHAT
        "bottom_of_chat" -> InjectionPosition.BOTTOM_OF_CHAT
        "at_depth" -> InjectionPosition.AT_DEPTH
        else -> InjectionPosition.AFTER_SYSTEM_PROMPT
    }
}

private fun JsonObject.getRole(name: String): MessageRole {
    return when (getString(name)?.lowercase()) {
        "assistant" -> MessageRole.ASSISTANT
        "system" -> MessageRole.SYSTEM
        "tool" -> MessageRole.TOOL
        else -> MessageRole.USER
    }
}

private fun String?.toUuidOrNull(): Uuid? {
    return runCatching { this?.let(Uuid::parse) }.getOrNull()
}
