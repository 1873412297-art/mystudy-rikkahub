package me.rerere.rikkahub.data.ai.status

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val cardJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Recovers SillyTavern's visual replacement for `<StatusPlaceHolderImpl/>`.
 * Many cards store their complete image-rich status UI in `extensions.regex_scripts`
 * instead of the older `extensions.regex` or a `status_script` field.
 */
fun extractTavernCardStatusTemplate(tavernCardJson: String?): String? {
    if (tavernCardJson.isNullOrBlank()) return null
    return runCatching {
        val root = cardJson.parseToJsonElement(tavernCardJson).jsonObject
        val data = (root["data"] as? JsonObject) ?: root
        extractTavernCardStatusTemplate(data["extensions"] as? JsonObject)
    }.getOrNull()
}

fun extractTavernCardStatusTemplate(extensions: JsonObject?): String? {
    val scripts = extensions?.get("regex_scripts") as? JsonArray ?: return null
    return scripts.firstNotNullOfOrNull { element ->
        val script = element as? JsonObject ?: return@firstNotNullOfOrNull null
        if (script.boolean("disabled") == true || script.boolean("promptOnly") == true) {
            return@firstNotNullOfOrNull null
        }
        if (script.boolean("markdownOnly") == false) return@firstNotNullOfOrNull null
        val pattern = script.string("findRegex") ?: return@firstNotNullOfOrNull null
        if (!pattern.contains("StatusPlaceHolderImpl", ignoreCase = true)) {
            return@firstNotNullOfOrNull null
        }
        script.string("replaceString")
            ?.takeIf { it.isNotBlank() }
            ?.stripOuterMarkdownFence()
    }
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.boolean(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

private fun String.stripOuterMarkdownFence(): String {
    val trimmed = trim()
    if (!trimmed.startsWith("```")) return trimmed
    val firstLineEnd = trimmed.indexOf('\n')
    if (firstLineEnd < 0) return trimmed
    val closingFence = trimmed.lastIndexOf("```")
    if (closingFence <= firstLineEnd) return trimmed
    return trimmed.substring(firstLineEnd + 1, closingFence).trim()
}
