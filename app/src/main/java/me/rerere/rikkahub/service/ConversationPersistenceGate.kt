package me.rerere.rikkahub.service

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/** Serializes every full-conversation persistence operation for a conversation. */
internal class ConversationPersistenceGate {
    private val locks = ConcurrentHashMap<Uuid, Mutex>()

    suspend fun <T> withConversation(conversationId: Uuid, block: suspend () -> T): T =
        locks.computeIfAbsent(conversationId) { Mutex() }.withLock { block() }

    fun remove(conversationId: Uuid) {
        locks.remove(conversationId)
    }

    fun clear() {
        locks.clear()
    }
}
