package me.rerere.rikkahub.service

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class TavernRuntimeConversationReadinessTest {
    @Test
    fun `cleanup makes a recreated conversation not ready and preserves loaded history`() = runBlocking {
        val conversationId = Uuid.random()
        val readiness = TavernRuntimeConversationReadiness()
        val history = Conversation.ofId(conversationId, messages = listOf(UIMessage.user("historic").toMessageNode()))
        var persisted = history
        val store = TavernRuntimeMessageMutationStore(object : TavernRuntimeMessagePersistenceAdapter {
            override fun isReady(conversationId: Uuid): Boolean = readiness.isReady(conversationId)

            override suspend fun <T> mutate(conversationId: Uuid, block: suspend () -> T): T = block()

            override fun currentConversation(conversationId: Uuid): Conversation = persisted

            override suspend fun persist(conversationId: Uuid, conversation: Conversation) {
                persisted = conversation
            }

            override suspend fun persistAfterMessageRemoval(
                conversationId: Uuid,
                before: Conversation,
                after: Conversation,
            ) = persist(conversationId, after)

            override fun emit(event: TavernRuntimeMessageMutationEvent) = Unit
        })

        readiness.markReady(conversationId)
        readiness.clearAll()
        val rejected = runCatching { store.create(conversationId, MessageRole.USER, "lost") }

        assertFalse(readiness.isReady(conversationId))
        assertEquals("CONVERSATION_NOT_READY", rejected.exceptionOrNull()?.message)
        assertEquals("historic", persisted.currentMessages.single().toText())
        assertTrue(persisted === history)
    }
}
