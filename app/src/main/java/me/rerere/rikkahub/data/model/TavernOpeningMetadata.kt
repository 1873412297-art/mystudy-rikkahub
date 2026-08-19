package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.security.MessageDigest

data class TavernOpeningRef(
    val greetingIndex: Int,
    val contentFingerprint: String,
    val cardFingerprint: String,
)

data class TavernOpeningSlashRegistration(
    val source: String,
    val aliases: List<String> = emptyList(),
    val helpString: String = "",
)

data class TavernOpeningRuntimeState(
    val macros: Map<String, String> = emptyMap(),
    val slashCommands: Map<String, TavernOpeningSlashRegistration> = emptyMap(),
    val sendHookSource: String? = null,
)

fun UIMessagePart.Text.withTavernOpening(ref: TavernOpeningRef): UIMessagePart.Text {
    val existingMetadata = metadata.orEmpty()
    return copy(
        metadata = buildJsonObject {
            existingMetadata.forEach { (key, value) -> put(key, value) }
            put(OPENING_METADATA_KEY, buildJsonObject {
                put(OPENING_KIND_KEY, OPENING_KIND)
                put(OPENING_INDEX_KEY, ref.greetingIndex)
                put(OPENING_CONTENT_FINGERPRINT_KEY, ref.contentFingerprint)
                put(OPENING_CARD_FINGERPRINT_KEY, ref.cardFingerprint)
            })
        },
    )
}

fun UIMessagePart.Text.tavernOpeningRef(): TavernOpeningRef? {
    val openingMetadata = metadata?.get(OPENING_METADATA_KEY) as? JsonObject ?: return null
    if (openingMetadata.stringAt(OPENING_KIND_KEY) != OPENING_KIND) return null
    val greetingIndex = openingMetadata.integerAt(OPENING_INDEX_KEY) ?: return null
    if (greetingIndex < 0) return null
    val contentFingerprint = openingMetadata.stringAt(OPENING_CONTENT_FINGERPRINT_KEY) ?: return null
    val cardFingerprint = openingMetadata.stringAt(OPENING_CARD_FINGERPRINT_KEY) ?: return null
    if (contentFingerprint.isBlank() || cardFingerprint.isBlank()) return null
    return TavernOpeningRef(greetingIndex, contentFingerprint, cardFingerprint)
}

fun UIMessagePart.Text.markTavernOpeningRuntimeExecuted(): UIMessagePart.Text {
    require(tavernOpeningRef() != null) { "Only typed Tavern openings can be marked as executed" }
    return copy(
        metadata = buildJsonObject {
            metadata.orEmpty().forEach { (key, value) -> put(key, value) }
            put(OPENING_RUNTIME_EXECUTED_KEY, true)
        },
    )
}

fun UIMessagePart.Text.withTavernOpeningRuntimeState(state: TavernOpeningRuntimeState): UIMessagePart.Text = copy(
    metadata = buildJsonObject {
        metadata.orEmpty().forEach { (key, value) -> put(key, value) }
        put(OPENING_RUNTIME_STATE_KEY, buildJsonObject {
            put("macros", buildJsonObject { state.macros.forEach { (name, source) -> put(name, source) } })
            put("slashCommands", buildJsonObject {
                state.slashCommands.forEach { (name, registration) ->
                    put(name, buildJsonObject {
                        put("source", registration.source)
                        put("aliases", JsonArray(registration.aliases.map(::JsonPrimitive)))
                        put("helpString", registration.helpString)
                    })
                }
            })
            state.sendHookSource?.let { put("sendHookSource", it) }
        })
    },
)

fun UIMessagePart.Text.tavernOpeningRuntimeState(): TavernOpeningRuntimeState? {
    val state = metadata?.get(OPENING_RUNTIME_STATE_KEY) as? JsonObject ?: return null
    val macros = (state["macros"] as? JsonObject).orEmpty().mapNotNull { (name, value) ->
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { name to it }
    }.toMap()
    val slash = (state["slashCommands"] as? JsonObject).orEmpty().mapNotNull { (name, value) ->
        val entry = value as? JsonObject ?: return@mapNotNull null
        val source = entry.stringAt("source") ?: return@mapNotNull null
        val aliases = (entry["aliases"] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content
        }
        name to TavernOpeningSlashRegistration(source, aliases, entry.stringAt("helpString").orEmpty())
    }.toMap()
    val sendHook = state.stringAt("sendHookSource")
    return TavernOpeningRuntimeState(macros, slash, sendHook)
}

fun UIMessagePart.Text.isTavernOpeningRuntimeExecuted(): Boolean =
    (metadata?.get(OPENING_RUNTIME_EXECUTED_KEY) as? JsonPrimitive)?.content == "true"

fun inferLegacyOpening(message: UIMessage, card: TavernCharacterCard): TavernOpeningRef? {
    if (message.role != MessageRole.ASSISTANT || message.parts.size != 1 || card.firstMes.isBlank()) return null
    val text = message.parts.singleOrNull() as? UIMessagePart.Text ?: return null
    if (text.renderMode != UIMessagePart.RenderMode.HTML || text.text.isBlank() || text.text != card.firstMes) return null
    return TavernOpeningRef(
        greetingIndex = 0,
        contentFingerprint = text.text.sha256(),
        cardFingerprint = card.greetingFingerprint(),
    )
}

/** Builds the stable typed reference for one entry in [TavernCharacterCard.allGreetings]. */
fun TavernCharacterCard.openingRef(greetingIndex: Int): TavernOpeningRef {
    val greeting = allGreetings().getOrNull(greetingIndex)
        ?: throw IndexOutOfBoundsException("No Tavern greeting at index $greetingIndex")
    return TavernOpeningRef(
        greetingIndex = greetingIndex,
        contentFingerprint = greeting.sha256(),
        cardFingerprint = greetingFingerprint(),
    )
}

/** Creates the persisted HTML assistant opening without introducing a Room column. */
fun TavernCharacterCard.openingMessage(greetingIndex: Int): UIMessage {
    val greeting = allGreetings().getOrNull(greetingIndex)
        ?: throw IndexOutOfBoundsException("No Tavern greeting at index $greetingIndex")
    val message = UIMessage.assistantHtml(greeting)
    return message.copy(
        parts = message.parts.map { part ->
            if (part is UIMessagePart.Text) part.withTavernOpening(openingRef(greetingIndex)) else part
        },
    )
}

private fun JsonObject.stringAt(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.integerAt(key: String): Int? {
    val value = this[key] as? JsonPrimitive ?: return null
    return value.takeIf { !it.isString }?.intOrNull
}

private fun TavernCharacterCard.greetingFingerprint(): String =
    allGreetings().joinToString(separator = "\\u0000") { greeting -> "${greeting.length}:$greeting" }.sha256()

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private const val OPENING_METADATA_KEY = "rikkahub_tavern_opening"
private const val OPENING_KIND_KEY = "kind"
private const val OPENING_KIND = "tavern_opening"
private const val OPENING_INDEX_KEY = "greetingIndex"
private const val OPENING_CONTENT_FINGERPRINT_KEY = "contentFingerprint"
private const val OPENING_CARD_FINGERPRINT_KEY = "cardFingerprint"
private const val OPENING_RUNTIME_EXECUTED_KEY = "runtimeExecuted"
private const val OPENING_RUNTIME_STATE_KEY = "runtimeState"
