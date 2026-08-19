package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.JsonObject
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
