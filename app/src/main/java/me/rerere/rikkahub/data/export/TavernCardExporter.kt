package me.rerere.rikkahub.data.export

import android.graphics.Bitmap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.TavernCardData
import me.rerere.rikkahub.data.model.TavernCardV2

/**
 * Exports an Assistant as a SillyTavern character card (V2 JSON format).
 */
object TavernCardExporter {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /**
     * Export an Assistant to SillyTavern V2 JSON string.
     *
     * If the assistant was originally imported from a character card (tavernCardJson is not null),
     * this will preserve the original card structure while updating fields that may have been
     * modified in the assistant settings.
     */
    fun exportToJson(assistant: Assistant): String {
        val originalJson = assistant.tavernCardJson

        return if (originalJson != null) {
            // Preserve original card structure, update modified fields
            updateOriginalCard(originalJson, assistant)
        } else {
            // Build a new card from assistant fields
            buildNewCard(assistant)
        }
    }

    /**
     * Export as PNG with embedded tEXt chunks (SillyTavern-compatible format).
     * Uses the provided bitmap as the card image.
     */
    fun exportToPng(assistant: Assistant, bitmap: Bitmap): ByteArray {
        val jsonStr = exportToJson(assistant)
        return PngCardWriter.write(jsonStr, bitmap)
    }

    /**
     * Update an original character card with modified assistant fields.
     */
    private fun updateOriginalCard(originalJson: String, assistant: Assistant): String {
        return try {
            val root = json.parseToJsonElement(originalJson).jsonObject
            val spec = root["spec"]?.jsonPrimitive?.content ?: "chara_card_v2"

            when (spec) {
                "chara_card_v2", "chara_card_v3" -> {
                    val data = root["data"]?.jsonObject ?: JsonObject(emptyMap())
                    val updatedData = buildJsonObject {
                        // Copy all original fields
                        data.forEach { (key, value) -> put(key, value) }
                        // Update fields that may have been modified
                        put("name", assistant.name)
                        put("system_prompt", assistant.systemPrompt)
                        // Try to extract description/personality/scenario from system prompt
                        val extracted = extractFieldsFromPrompt(assistant.systemPrompt)
                        extracted.description?.let { put("description", it) }
                        extracted.personality?.let { put("personality", it) }
                        extracted.scenario?.let { put("scenario", it) }
                        extracted.mesExample?.let { put("mes_example", it) }
                        extracted.postHistoryInstructions?.let { put("post_history_instructions", it) }
                    }

                    val updatedRoot = buildJsonObject {
                        root.forEach { (key, value) ->
                            if (key == "data") put("data", updatedData)
                            else put(key, value)
                        }
                    }
                    json.encodeToString(JsonObject.serializer(), updatedRoot)
                }

                else -> buildNewCard(assistant)
            }
        } catch (_: Exception) {
            buildNewCard(assistant)
        }
    }

    /**
     * Build a new SillyTavern V2 character card from assistant fields.
     */
    private fun buildNewCard(assistant: Assistant): String {
        val extracted = extractFieldsFromPrompt(assistant.systemPrompt)

        val card = TavernCardV2(
            data = TavernCardData(
                name = assistant.name,
                description = extracted.description ?: "",
                personality = extracted.personality ?: "",
                scenario = extracted.scenario ?: "",
                firstMes = assistant.presetMessages
                    .firstOrNull { it.role.name == "ASSISTANT" }
                    ?.parts
                    ?.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
                    ?.joinToString("") { it.text }
                    ?: "",
                mesExample = extracted.mesExample ?: "",
                creatorNotes = "",
                systemPrompt = assistant.systemPrompt,
                postHistoryInstructions = extracted.postHistoryInstructions ?: "",
                alternateGreetings = emptyList(),
                characterBook = null,
                tags = emptyList(),
                creator = "",
                characterVersion = "",
                extensions = null,
            )
        )

        return json.encodeToString(TavernCardV2.serializer(), card)
    }

    /**
     * Extract SillyTavern fields from the system prompt that was built by buildAssistantPrompt().
     */
    private fun extractFieldsFromPrompt(prompt: String): ExtractedFields {
        if (prompt.isBlank()) return ExtractedFields()

        val description = extractSection(prompt, "## Description of the character", "## ")
        val personality = extractSection(prompt, "## Personality of the character", "## ")
        val scenario = extractSection(prompt, "## Scenario", "## ")
        val mesExample = extractSection(prompt, "## Example Dialogue", "## ")
        val postHistory = extractSection(prompt, "## Post-history Instructions", "## ")

        return ExtractedFields(
            description = description,
            personality = personality,
            scenario = scenario,
            mesExample = mesExample,
            postHistoryInstructions = postHistory
        )
    }

    /**
     * Extract a section from the prompt text.
     *
     * @param text The full prompt text
     * @param startMarker The section header to find
     * @param endMarker The next section header that marks the end (or end of string)
     */
    private fun extractSection(text: String, startMarker: String, endMarker: String): String? {
        val startIndex = text.indexOf(startMarker)
        if (startIndex == -1) return null

        val contentStart = startIndex + startMarker.length
        val endIndex = text.indexOf(endMarker, contentStart)

        val content = if (endIndex == -1) {
            text.substring(contentStart)
        } else {
            text.substring(contentStart, endIndex)
        }

        return content.trim().takeIf { it.isNotBlank() }
    }

    private data class ExtractedFields(
        val description: String? = null,
        val personality: String? = null,
        val scenario: String? = null,
        val mesExample: String? = null,
        val postHistoryInstructions: String? = null,
    )
}
