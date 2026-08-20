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
    internal class PreparedRebase internal constructor(
        val conversation: Conversation,
        private val commitAction: () -> Unit,
    ) {
        private val commitLock = Any()
        private var committed = false

        fun commit() {
            val shouldCommit = synchronized(commitLock) {
                if (committed) {
                    false
                } else {
                    committed = true
                    true
                }
            }
            if (shouldCommit) commitAction()
        }
    }

    private data class MessageMutation(
        val before: String,
        val after: String,
        val previewRevision: Long,
    )

    private data class JsonValueState(
        val present: Boolean,
        val value: JsonElement?,
    )

    private data class VariableMutation(
        val before: JsonValueState,
        val after: JsonValueState,
        val previewRevision: Long,
    )

    private data class Journal(
        val messages: MutableMap<Uuid, MessageMutation> = linkedMapOf(),
        val variables: MutableMap<String, VariableMutation> = linkedMapOf(),
    )

    private val lock = Any()
    private val journals = mutableMapOf<Uuid, Journal>()

    fun recordMessage(
        conversationId: Uuid,
        messageId: Uuid,
        before: String,
        after: String,
        previewRevision: Long,
    ) {
        if (before == after) return
        synchronized(lock) {
            val journal = journals.getOrPut(conversationId) { Journal() }
            val existing = journal.messages[messageId]
            val composed = if (existing?.after == before) {
                existing.copy(after = after, previewRevision = previewRevision)
            } else {
                MessageMutation(before = before, after = after, previewRevision = previewRevision)
            }
            if (composed.before == composed.after) {
                journal.messages.remove(messageId)
            } else {
                journal.messages[messageId] = composed
            }
            removeEmptyJournal(conversationId, journal)
        }
    }

    fun recordVariables(
        conversationId: Uuid,
        before: JsonObject,
        after: JsonObject,
        previewRevision: Long,
    ) {
        synchronized(lock) {
            val journal = journals.getOrPut(conversationId) { Journal() }
            (before.keys + after.keys).forEach { key ->
                val beforeState = before.stateOf(key)
                val afterState = after.stateOf(key)
                if (beforeState == afterState) return@forEach
                val existing = journal.variables[key]
                val composed = if (existing?.after == beforeState) {
                    existing.copy(after = afterState, previewRevision = previewRevision)
                } else {
                    VariableMutation(
                        before = beforeState,
                        after = afterState,
                        previewRevision = previewRevision,
                    )
                }
                if (composed.before == composed.after) {
                    journal.variables.remove(key)
                } else {
                    journal.variables[key] = composed
                }
            }
            removeEmptyJournal(conversationId, journal)
        }
    }

    fun prepareRebase(conversationId: Uuid, conversation: Conversation): PreparedRebase = synchronized(lock) {
        val journal = journals[conversationId]
            ?: return@synchronized PreparedRebase(conversation = conversation, commitAction = {})
        var rebased = conversation
        val retiringMessages = linkedMapOf<Uuid, MessageMutation>()
        val retiringVariables = linkedMapOf<String, VariableMutation>()

        journal.messages.forEach { (messageId, mutation) ->
            val current = rebased.firstTextOf(messageId)
            if (rebased.stateRevision >= mutation.previewRevision) {
                if (current != mutation.after) retiringMessages[messageId] = mutation
            } else {
                if (current == null) {
                    throw StaleTavernPreviewSnapshotException(
                        "Stale conversation snapshot no longer contains preview message $messageId",
                    )
                }
                if (current != mutation.after) {
                    rebased = rebased.replaceFirstText(messageId, mutation.after)
                }
            }
        }

        if (journal.variables.isNotEmpty()) {
            val variables = rebased.statusVariables.toMutableMap()
            journal.variables.forEach { (key, mutation) ->
                val current = JsonObject(variables).stateOf(key)
                if (rebased.stateRevision >= mutation.previewRevision) {
                    if (current != mutation.after) retiringVariables[key] = mutation
                } else {
                    mutation.after.applyTo(variables, key)
                }
            }
            rebased = rebased.copy(statusVariables = JsonObject(variables))
        }

        PreparedRebase(conversation = rebased) {
            commitRetirements(conversationId, journal, retiringMessages, retiringVariables)
        }
    }

    fun clear() = synchronized(lock) {
        journals.clear()
    }

    private fun removeEmptyJournal(conversationId: Uuid, journal: Journal) {
        if (journal.messages.isEmpty() && journal.variables.isEmpty()) {
            journals.remove(conversationId)
        }
    }

    private fun commitRetirements(
        conversationId: Uuid,
        expectedJournal: Journal,
        messages: Map<Uuid, MessageMutation>,
        variables: Map<String, VariableMutation>,
    ) = synchronized(lock) {
        val journal = journals[conversationId] ?: return@synchronized
        if (journal !== expectedJournal) return@synchronized
        messages.forEach { (messageId, mutation) ->
            if (journal.messages[messageId] == mutation) {
                journal.messages.remove(messageId)
            }
        }
        variables.forEach { (key, mutation) ->
            if (journal.variables[key] == mutation) {
                journal.variables.remove(key)
            }
        }
        removeEmptyJournal(conversationId, journal)
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

internal fun advanceConversationRevision(current: Conversation, incoming: Conversation): Conversation =
    incoming.copy(stateRevision = maxOf(current.stateRevision, incoming.stateRevision) + 1L)

internal class StaleTavernPreviewSnapshotException(message: String) : IllegalStateException(message)
