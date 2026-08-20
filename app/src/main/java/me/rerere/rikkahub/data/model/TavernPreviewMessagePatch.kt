package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.toLocalDateTime

/** Applies the narrow messages.updateCurrent payload to the selected branch's current persisted message. */
internal fun applyTavernPreviewMessagePatch(
    conversation: Conversation,
    patch: JsonElement,
): Conversation {
    val replacement = when (patch) {
        is JsonPrimitive -> patch.content
        is JsonObject -> (patch["text"] as? JsonPrimitive)?.content
        else -> null
    } ?: return conversation
    val nodeIndex = conversation.messageNodes.indexOfLast { it.messages.isNotEmpty() }
    if (nodeIndex < 0) return conversation
    val node = conversation.messageNodes[nodeIndex]
    val messageIndex = node.selectIndex.takeIf { it in node.messages.indices } ?: return conversation
    val message = node.messages[messageIndex]
    var replaced = false
    val parts = message.parts.map { part ->
        if (!replaced && part is UIMessagePart.Text) {
            replaced = true
            part.copy(text = replacement)
        } else {
            part
        }
    }
    if (!replaced) return conversation
    val messages = node.messages.toMutableList().apply { this[messageIndex] = message.copy(parts = parts) }
    val nodes = conversation.messageNodes.toMutableList().apply { this[nodeIndex] = node.copy(messages = messages) }
    return conversation.copy(messageNodes = nodes)
}

internal fun Conversation.tavernPreviewTargetLabel(): String {
    val normalizedTitle = title.ifBlank { "未命名对话" }
    val rawId = id.toString()
    val shortId = "${rawId.take(8)}…${rawId.takeLast(4)}"
    return "$normalizedTitle · $shortId · ${updateAt.toLocalDateTime()}"
}
