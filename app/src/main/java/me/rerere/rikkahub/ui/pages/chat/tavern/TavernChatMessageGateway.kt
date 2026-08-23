package me.rerere.rikkahub.ui.pages.chat.tavern

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.ai.core.MessageRole

internal data class TavernChatQueryOptions(
    val role: String = "all",
    val hideState: String = "all",
    val includeSwipes: Boolean = false,
)

internal sealed interface TavernChatMutationResult {
    data object Accepted : TavernChatMutationResult
    data class Rejected(val code: String, val message: String) : TavernChatMutationResult
}

internal interface TavernChatMessageGateway {
    fun getChatMessages(range: String, options: TavernChatQueryOptions): JsonArray
    fun setChatMessage(params: JsonObject): TavernChatMutationResult
    fun setChatMessages(params: JsonObject): TavernChatMutationResult
}

internal class TavernConversationMessageGateway(
    private val snapshotProvider: () -> TavernConversationSnapshot,
    private val dispatchGreeting: (index: Int, count: Int, revision: Long) -> Unit = { _, _, _ -> },
) : TavernChatMessageGateway {

    override fun getChatMessages(range: String, options: TavernChatQueryOptions): JsonArray {
        val snapshot = snapshotProvider()
        val indices = normalizeRange(range, snapshot.nodes.lastIndex) ?: return JsonArray(emptyList())
        return buildJsonArray {
            indices.forEach { messageIndex ->
                val node = snapshot.nodes[messageIndex]
                val message = node.selectedMessage
                val role = message.role.toTavernRole()
                val hidden = message.role == MessageRole.SYSTEM || message.role == MessageRole.TOOL
                if (options.role != "all" && options.role != role) return@forEach
                if (options.hideState == "hidden" && !hidden) return@forEach
                if (options.hideState == "unhidden" && hidden) return@forEach

                add(buildJsonObject {
                    put("message_id", messageIndex)
                    put("name", message.name)
                    put("role", role)
                    put("is_hidden", hidden)
                    put("message", message.displayText())
                    putJsonObject("data") {}
                    putJsonObject("extra") {}
                    if (options.includeSwipes) {
                        val opening = snapshot.openingSwipe?.takeIf {
                            messageIndex == 0 && it.swipes.isNotEmpty()
                        }
                        val swipes = opening?.swipes ?: listOf(message.displayText())
                        put("swipe_id", opening?.index ?: node.selectedIndex)
                        putJsonArray("swipes") { swipes.forEach { add(JsonPrimitive(it)) } }
                        putJsonArray("swipes_data") { repeat(swipes.size) { add(JsonObject(emptyMap())) } }
                        putJsonArray("swipes_info") { repeat(swipes.size) { add(JsonObject(emptyMap())) } }
                    }
                })
            }
        }
    }

    override fun setChatMessage(params: JsonObject): TavernChatMutationResult {
        val snapshot = snapshotProvider()
        return when (val validation = validateSingleMutation(params, snapshot, batchShape = false)) {
            is MutationValidation.Invalid -> validation.rejection
            is MutationValidation.Valid -> {
                dispatchGreeting(validation.index, validation.count, validation.revision)
                TavernChatMutationResult.Accepted
            }
        }
    }

    override fun setChatMessages(params: JsonObject): TavernChatMutationResult {
        val messages = params["messages"] as? JsonArray
            ?: return rejected("BAD_REQUEST", "messages must be an array")
        if (messages.isEmpty()) return TavernChatMutationResult.Accepted
        val refresh = (params["options"] as? JsonObject)?.string("refresh") ?: "affected"
        if (refresh !in BATCH_REFRESH_VALUES) return rejected("BAD_REQUEST", "Unsupported refresh value '$refresh'")

        val snapshot = snapshotProvider()
        val validated = messages.map { item ->
            val objectValue = item as? JsonObject
                ?: return rejected("BAD_REQUEST", "Every messages entry must be an object")
            validateSingleMutation(
                params = buildJsonObject {
                    objectValue.forEach { (key, value) -> put(key, value) }
                    putJsonObject("options") {
                        objectValue["swipe_id"]?.let { put("swipe_id", it) }
                        put("refresh", "display_and_render_current")
                    }
                },
                snapshot = snapshot,
                batchShape = true,
            )
        }
        validated.filterIsInstance<MutationValidation.Invalid>().firstOrNull()?.let { return it.rejection }
        val selections = validated.filterIsInstance<MutationValidation.Valid>()
        selections.lastOrNull()?.let { dispatchGreeting(it.index, it.count, it.revision) }
        return TavernChatMutationResult.Accepted
    }

    private fun validateSingleMutation(
        params: JsonObject,
        snapshot: TavernConversationSnapshot,
        batchShape: Boolean,
    ): MutationValidation {
        val messageId = params.int("message_id")
            ?: return invalid("BAD_REQUEST", "message_id must be an integer")
        if (messageId !in snapshot.nodes.indices) return invalid("MESSAGE_NOT_FOUND", "Message $messageId does not exist")
        if (messageId != 0 || snapshot.openingSwipe == null || snapshot.openingSwipe.swipes.isEmpty()) {
            return invalid("UNSUPPORTED_MESSAGE_MUTATION", "Only the active opening swipe can be changed")
        }

        val options = params["options"] as? JsonObject ?: JsonObject(emptyMap())
        val refresh = options.string("refresh") ?: "display_and_render_current"
        if (refresh !in SINGLE_REFRESH_VALUES) return invalid("BAD_REQUEST", "Unsupported refresh value '$refresh'")
        val opening = snapshot.openingSwipe
        val swipeIndex = when (val rawSwipe = options["swipe_id"] ?: if (batchShape) params["swipe_id"] else null) {
            null -> opening.index
            is JsonPrimitive -> rawSwipe.intOrNull ?: rawSwipe.contentOrNull
                ?.takeIf { it == "current" }
                ?.let { opening.index }
                ?: return invalid("BAD_REQUEST", "swipe_id must be an integer or 'current'")
            else -> return invalid("BAD_REQUEST", "swipe_id must be an integer or 'current'")
        }
        if (swipeIndex !in opening.swipes.indices) {
            return invalid("INDEX_OUT_OF_RANGE", "Opening swipe $swipeIndex does not exist")
        }
        val fieldValues = params["field_values"] as? JsonObject
        val suppliedMessage = if (batchShape) params.string("message") else fieldValues?.string("message")
        if (suppliedMessage != null) {
            if (suppliedMessage.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES) {
                return invalid("PAYLOAD_TOO_LARGE", "Message exceeds the 64KB runtime limit")
            }
            if (suppliedMessage != opening.swipes[swipeIndex]) {
                return invalid("MESSAGE_MISMATCH", "Message does not match opening swipe $swipeIndex")
            }
        }
        return MutationValidation.Valid(swipeIndex, opening.count, snapshot.revision)
    }

    private fun normalizeRange(rawRange: String, maximum: Int): IntRange? {
        if (maximum < 0) return null
        fun normalize(raw: Int): Int = (if (raw < 0) maximum + raw + 1 else raw).coerceIn(0, maximum)

        rawRange.trim().toIntOrNull()?.let { index ->
            val normalized = normalize(index)
            return normalized..normalized
        }
        val match = RANGE.matchEntire(rawRange.trim()) ?: return null
        val first = normalize(match.groupValues[1].toIntOrNull() ?: return null)
        val second = normalize(match.groupValues[2].toIntOrNull() ?: return null)
        return minOf(first, second)..maxOf(first, second)
    }

    private companion object {
        val RANGE = Regex("^(-?\\d+)-(-?\\d+)$")
        const val MAX_MESSAGE_BYTES = 64 * 1024
        val SINGLE_REFRESH_VALUES = setOf("none", "display_current", "display_and_render_current", "all")
        val BATCH_REFRESH_VALUES = setOf("none", "affected", "all")
    }
}

private sealed interface MutationValidation {
    data class Valid(val index: Int, val count: Int, val revision: Long) : MutationValidation
    data class Invalid(val rejection: TavernChatMutationResult.Rejected) : MutationValidation
}

private fun invalid(code: String, message: String) = MutationValidation.Invalid(
    TavernChatMutationResult.Rejected(code, message),
)

private fun rejected(code: String, message: String) = TavernChatMutationResult.Rejected(code, message)

private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(name: String): Int? = (get(name) as? JsonPrimitive)?.intOrNull

private fun MessageRole.toTavernRole(): String = when (this) {
    MessageRole.USER -> "user"
    MessageRole.ASSISTANT -> "assistant"
    MessageRole.SYSTEM, MessageRole.TOOL -> "system"
}

private fun TavernConversationMessage.displayText(): String = parts
    .mapNotNull { part -> part.text.takeIf(String::isNotBlank) }
    .joinToString("\n")
