package me.rerere.rikkahub.service

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class TavernRuntimeMessageMutationStoreTest {
    @Test
    fun `create persists before emitting a sent event`() = runBlocking {
        val conversationId = Uuid.random()
        val persistence = TestRuntimeMessagePersistence(conversationId)
        val store = TavernRuntimeMessageMutationStore(persistence)

        val created = store.create(conversationId, MessageRole.USER, "hello")

        assertEquals("hello", created.toText())
        assertEquals(listOf(created.id), persistence.persisted.messageNodes.map { it.currentMessage.id })
        assertEquals(listOf("MESSAGE_SENT"), persistence.events)
    }

    @Test
    fun `not ready and eviction between lock acquisition and mutation preserve persisted history`() = runBlocking {
        val conversationId = Uuid.random()
        val historic = UIMessage.user("historic")
        val persistence = TestRuntimeMessagePersistence(
            conversationId = conversationId,
            initial = Conversation.ofId(conversationId, messages = listOf(historic.toMessageNode())),
        )
        val store = TavernRuntimeMessageMutationStore(persistence)

        persistence.ready = false
        val notReady = runCatching { store.create(conversationId, MessageRole.USER, "lost") }
        persistence.ready = true
        persistence.evictBeforeBlock = true
        val evicted = runCatching { store.create(conversationId, MessageRole.USER, "also lost") }

        assertEquals("CONVERSATION_NOT_READY", notReady.exceptionOrNull()?.message)
        assertEquals("CONVERSATION_NOT_READY", evicted.exceptionOrNull()?.message)
        assertEquals(listOf(historic.id), persistence.persisted.messageNodes.map { it.currentMessage.id })
        assertTrue(persistence.events.isEmpty())
    }

    @Test
    fun `parallel creates plus update and delete keep every committed mutation`() = runBlocking {
        val conversationId = Uuid.random()
        val original = UIMessage.user("original")
        val persistence = TestRuntimeMessagePersistence(
            conversationId = conversationId,
            initial = Conversation.ofId(conversationId, messages = listOf(original.toMessageNode())),
        )
        val store = TavernRuntimeMessageMutationStore(persistence)

        val created = List(12) { index ->
            async { store.create(conversationId, MessageRole.USER, "create-$index") }
        }.awaitAll()
        val updated = async { store.update(conversationId, created.first().id, "updated") }
        val deleted = async { store.delete(conversationId, original.id) }
        awaitAll(updated, deleted)

        val messages = persistence.persisted.currentMessages
        assertEquals(12, messages.size)
        assertEquals("updated", messages.single { it.id == created.first().id }.toText())
        assertFalse(messages.any { it.id == original.id })
    }

    @Test
    fun `reloaded persistence retains create update and delete`() = runBlocking {
        val conversationId = Uuid.random()
        val durable = TestRuntimeMessageDatabase(Conversation.ofId(conversationId))
        val store = TavernRuntimeMessageMutationStore(
            TestRuntimeMessagePersistence(conversationId, durable = durable),
        )

        val created = store.create(conversationId, MessageRole.ASSISTANT, "before")
        store.update(conversationId, created.id, "after")
        store.delete(conversationId, created.id)
        val reloaded = TestRuntimeMessagePersistence(conversationId, durable = durable)

        assertTrue(reloaded.persisted.messageNodes.isEmpty())
    }

    @Test
    fun `successful mutations refresh the live flow and emit matching lifecycle events`() = runBlocking {
        val conversationId = Uuid.random()
        val persistence = TestRuntimeMessagePersistence(conversationId)
        val store = TavernRuntimeMessageMutationStore(persistence)

        val user = store.create(conversationId, MessageRole.USER, "user")
        val assistant = store.create(conversationId, MessageRole.ASSISTANT, "assistant")
        store.update(conversationId, assistant.id, "edited")
        store.delete(conversationId, user.id)

        assertEquals("edited", persistence.live.first().currentMessages.single().toText())
        assertEquals(
            listOf("MESSAGE_SENT", "MESSAGE_RECEIVED", "MESSAGE_EDITED", "MESSAGE_DELETED"),
            persistence.events,
        )
        assertEquals(1, persistence.removalPersists)
    }

    @Test
    fun `updateLatest atomically updates the selected message and emits an edit event`() = runBlocking {
        val conversationId = Uuid.random()
        val persistence = TestRuntimeMessagePersistence(conversationId)
        val store = TavernRuntimeMessageMutationStore(persistence)
        store.create(conversationId, MessageRole.USER, "first")
        val current = store.create(conversationId, MessageRole.ASSISTANT, "before")
        persistence.events.clear()

        val updated = store.updateLatest(conversationId, "after")

        assertEquals(current.id, updated?.id)
        assertEquals("after", persistence.persisted.currentMessages.last().toText())
        assertEquals(listOf("MESSAGE_EDITED"), persistence.events)
    }

    @Test
    fun `updateLatest and create interleave without losing either committed message`() = runBlocking {
        val conversationId = Uuid.random()
        val original = UIMessage.assistant("before")
        val persistence = TestRuntimeMessagePersistence(
            conversationId,
            Conversation.ofId(conversationId, messages = listOf(original.toMessageNode())),
        )
        val store = TavernRuntimeMessageMutationStore(persistence)
        val updateEnteredPersist = CompletableDeferred<Unit>()
        val allowUpdatePersist = CompletableDeferred<Unit>()
        persistence.beforePersist = {
            updateEnteredPersist.complete(Unit)
            allowUpdatePersist.await()
            persistence.beforePersist = {}
        }

        val updating = async { store.updateLatest(conversationId, "edited") }
        updateEnteredPersist.await()
        val creating = async { store.create(conversationId, MessageRole.USER, "created") }
        allowUpdatePersist.complete(Unit)
        awaitAll(updating, creating)

        assertEquals(setOf("edited", "created"), persistence.persisted.currentMessages.map { it.toText() }.toSet())
    }

    @Test
    fun `failed create update and delete persistence emits no lifecycle event`() = runBlocking {
        val conversationId = Uuid.random()
        val persistence = TestRuntimeMessagePersistence(conversationId)
        val store = TavernRuntimeMessageMutationStore(persistence)
        val existing = store.create(conversationId, MessageRole.USER, "saved")
        persistence.events.clear()
        persistence.failWrites = true

        val createFailure = runCatching { store.create(conversationId, MessageRole.USER, "unsaved") }
        val updateFailure = runCatching { store.update(conversationId, existing.id, "unsaved edit") }
        val deleteFailure = runCatching { store.delete(conversationId, existing.id) }

        assertEquals("write failed", createFailure.exceptionOrNull()?.message)
        assertEquals("write failed", updateFailure.exceptionOrNull()?.message)
        assertEquals("write failed", deleteFailure.exceptionOrNull()?.message)
        assertTrue(persistence.events.isEmpty())
        assertEquals("saved", persistence.persisted.currentMessages.single().toText())
    }

    @Test
    fun `declined persistence rejects create without an event`() = runBlocking {
        val conversationId = Uuid.random()
        val persistence = TestRuntimeMessagePersistence(conversationId)
        val store = TavernRuntimeMessageMutationStore(persistence)
        persistence.rejectWrites = true

        val failure = runCatching { store.create(conversationId, MessageRole.USER, "not committed") }

        assertEquals("CONVERSATION_NOT_READY", failure.exceptionOrNull()?.message)
        assertTrue(persistence.persisted.messageNodes.isEmpty())
        assertTrue(persistence.events.isEmpty())
    }

    @Test
    fun `update selected branch preserves identity role attachments annotations and metadata without a swipe`() =
        runBlocking {
            val conversationId = Uuid.random()
            val metadata = kotlinx.serialization.json.buildJsonObject { put("format", "markdown") }
            val image = UIMessagePart.Image("file:///image.png")
            val selected = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("old", metadata = metadata), image),
                annotations = listOf(UIMessageAnnotation.UrlCitation("source", "https://example.com")),
            )
            val alternate = UIMessage.assistant("alternate")
            val persistence = TestRuntimeMessagePersistence(
                conversationId = conversationId,
                initial = Conversation.ofId(
                    conversationId,
                    messages = listOf(MessageNode(messages = listOf(selected, alternate), selectIndex = 0)),
                ),
            )
            val store = TavernRuntimeMessageMutationStore(persistence)

            val updated = store.update(conversationId, selected.id, "new")
            val node = persistence.persisted.messageNodes.single()

            assertEquals(selected.id, updated?.id)
            assertEquals(MessageRole.ASSISTANT, updated?.role)
            assertEquals(0, node.selectIndex)
            assertEquals(2, node.messages.size)
            assertEquals("new", (node.currentMessage.parts.first() as UIMessagePart.Text).text)
            assertEquals(selected.annotations, node.currentMessage.annotations)
            assertEquals(metadata, (node.currentMessage.parts.first() as UIMessagePart.Text).metadata)
            assertSame(image, node.currentMessage.parts[1])
        }
}

private class TestRuntimeMessageDatabase(initial: Conversation) {
    val live = MutableStateFlow(initial)
}

private class TestRuntimeMessagePersistence(
    private val conversationId: Uuid,
    initial: Conversation = Conversation.ofId(conversationId),
    private val durable: TestRuntimeMessageDatabase = TestRuntimeMessageDatabase(initial),
) : TavernRuntimeMessagePersistenceAdapter {
    var ready = true
    var evictBeforeBlock = false
    var failWrites = false
    var rejectWrites = false
    var removalPersists = 0
    var beforePersist: suspend () -> Unit = {}
    val events = mutableListOf<String>()
    private val mutex = Mutex()

    val persisted: Conversation get() = durable.live.value
    val live get() = durable.live

    override fun isReady(conversationId: Uuid): Boolean = conversationId == this.conversationId && ready

    override suspend fun <T> mutate(conversationId: Uuid, block: suspend () -> T): T = mutex.withLock {
        if (evictBeforeBlock) {
            ready = false
            evictBeforeBlock = false
        }
        block()
    }

    override fun currentConversation(conversationId: Uuid): Conversation = persisted

    override suspend fun persist(conversationId: Uuid, conversation: Conversation): Boolean {
        beforePersist()
        check(!failWrites) { "write failed" }
        if (rejectWrites) return false
        durable.live.value = conversation
        return true
    }

    override suspend fun persistAfterMessageRemoval(
        conversationId: Uuid,
        before: Conversation,
        after: Conversation,
    ): Boolean {
        removalPersists++
        return persist(conversationId, after)
    }

    override fun emit(event: TavernRuntimeMessageMutationEvent) {
        events += event.type.name
    }
}
