package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

/**
 * Replays durable editor-preview effects over full-conversation snapshots that were captured before those effects.
 * A later explicit change to the same field retires the corresponding replay entry.
 */
internal class TavernPreviewMutationRebaser {
    private data class MessageMutation(
        val before: String,
        val after: String,
    )

    private data class JsonValueState(
        val present: Boolean,
        val value: JsonElement?,
    )

    private data class VariableMutation(
        val before: JsonValueState,
        val after: JsonValueState,
    )

    private data class Journal(
        val messages: MutableMap<Uuid, MessageMutation> = linkedMapOf(),
        val variables: MutableMap<String, VariableMutation> = linkedMapOf(),
    )

    private val lock = Any()
    private val journals = mutableMapOf<Uuid, Journal>()

    fun recordMessage(conversationId: Uuid, messageId: Uuid, before: String, after: String) {
        if (before == after) return
        synchronized(lock) {
            val journal = journals.getOrPut(conversationId) { Journal() }
            val existing = journal.messages[messageId]
            journal.messages[messageId] = if (existing?.after == before) {
                existing.copy(after = after)
            } else {
                MessageMutation(before = before, after = after)
            }
        }
    }

    fun recordVariables(conversationId: Uuid, before: JsonObject, after: JsonObject) {
        synchronized(lock) {
            val journal = journals.getOrPut(conversationId) { Journal() }
            (before.keys + after.keys).forEach { key ->
                val beforeState = before.stateOf(key)
                val afterState = after.stateOf(key)
                if (beforeState == afterState) return@forEach
                val existing = journal.variables[key]
                journal.variables[key] = if (existing?.after == beforeState) {
                    existing.copy(after = afterState)
                } else {
                    VariableMutation(before = beforeState, after = afterState)
                }
            }
            removeEmptyJournal(conversationId, journal)
        }
    }

    fun rebase(conversationId: Uuid, conversation: Conversation): Conversation = synchronized(lock) {
        val journal = journals[conversationId] ?: return@synchronized conversation
        var rebased = conversation

        val messageIterator = journal.messages.iterator()
        while (messageIterator.hasNext()) {
            val (messageId, mutation) = messageIterator.next()
            when (rebased.firstTextOf(messageId)) {
                mutation.before -> rebased = rebased.replaceFirstText(messageId, mutation.after)
                mutation.after -> Unit
                else -> messageIterator.remove()
            }
        }

        if (journal.variables.isNotEmpty()) {
            val variables = rebased.statusVariables.toMutableMap()
            val variableIterator = journal.variables.iterator()
            while (variableIterator.hasNext()) {
                val (key, mutation) = variableIterator.next()
                when (JsonObject(variables).stateOf(key)) {
                    mutation.before -> mutation.after.applyTo(variables, key)
                    mutation.after -> Unit
                    else -> variableIterator.remove()
                }
            }
            rebased = rebased.copy(statusVariables = JsonObject(variables))
        }

        removeEmptyJournal(conversationId, journal)
        rebased
    }

    fun clear() = synchronized(lock) {
        journals.clear()
    }

    private fun removeEmptyJournal(conversationId: Uuid, journal: Journal) {
        if (journal.messages.isEmpty() && journal.variables.isEmpty()) {
            journals.remove(conversationId)
        }
    }

    private fun JsonObject.stateOf(key: String): JsonValueState =
        JsonValueState(present = containsKey(key), value = get(key))

    private fun JsonValueState.applyTo(target: MutableMap<String, JsonElement>, key: String) {
        if (present) {
            target[key] = checkNotNull(value)
        } else {
            target.remove(key)
        }
    }

    private fun Conversation.firstTextOf(messageId: Uuid): String? = messageNodes
        .asSequence()
        .flatMap { it.messages.asSequence() }
        .firstOrNull { it.id == messageId }
        ?.parts
        ?.filterIsInstance<UIMessagePart.Text>()
        ?.firstOrNull()
        ?.text

    private fun Conversation.replaceFirstText(messageId: Uuid, replacement: String): Conversation {
        var changed = false
        val nodes = messageNodes.map { node ->
            val messages = node.messages.map { message ->
                if (message.id != messageId) return@map message
                var replaced = false
                val parts = message.parts.map { part ->
                    if (!replaced && part is UIMessagePart.Text) {
                        replaced = true
                        changed = true
                        part.copy(text = replacement)
                    } else {
                        part
                    }
                }
                message.copy(parts = parts)
            }
            node.copy(messages = messages)
        }
        return if (changed) copy(messageNodes = nodes) else this
    }
}
