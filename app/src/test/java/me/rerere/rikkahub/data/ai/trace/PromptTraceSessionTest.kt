package me.rerere.rikkahub.data.ai.trace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

class PromptTraceSessionTest {
    @Test
    fun `concurrent observations serialize binding before newer token update`() = runBlocking {
        val store = BlockingStreamingStore()
        val input = UIMessage.user("hello")
        val response = UIMessage.assistant("hi")
        val session = session(store)
        session.prepare(listOf(input))

        val first = async(Dispatchers.Default) {
            session.observeProviderMessages(listOf(input, response.copy(usage = TokenUsage(promptTokens = 12))))
        }
        withTimeout(5_000) { store.streamingStarted.await() }
        val second = async(Dispatchers.Default) {
            session.observeProviderMessages(listOf(input, response.copy(usage = TokenUsage(promptTokens = 19))))
        }
        delay(100)
        assertFalse(second.isCompleted)

        store.releaseStreaming.complete(Unit)
        first.await()
        second.await()

        assertEquals(listOf("PREPARED", "STREAMING:12", "TOKENS:19"), store.events.toList())
    }

    @Test
    fun `completion waits for in flight observation persistence`() = runBlocking {
        val store = BlockingStreamingStore()
        val input = UIMessage.user("hello")
        val response = UIMessage.assistant("hi")
        val session = session(store)
        session.prepare(listOf(input))

        val observing = async(Dispatchers.Default) {
            session.observeProviderMessages(listOf(input, response))
        }
        withTimeout(5_000) { store.streamingStarted.await() }
        val completing = async(Dispatchers.Default) { session.complete() }
        delay(100)
        assertFalse(completing.isCompleted)

        store.releaseStreaming.complete(Unit)
        observing.await()
        completing.await()

        assertEquals(listOf("PREPARED", "STREAMING:null", "COMPLETED"), store.events.toList())
    }

    @Test
    fun `prepare freeze waits for an in flight non suspending recorder`() = runBlocking {
        val store = SignallingTraceStore()
        val input = UIMessage.user("hello")
        val enteredRecorder = CountDownLatch(1)
        val releaseRecorder = CountDownLatch(1)
        val messages = BlockingMessageList(input, enteredRecorder, releaseRecorder)
        val session = session(store)

        val recording = async(Dispatchers.Default) { session.recordInputMessages(messages) }
        assertTrue(enteredRecorder.await(5, TimeUnit.SECONDS))
        val preparing = async(Dispatchers.Default) { session.prepare(listOf(input)) }
        val insertedBeforeRecorderFinished = try {
            withTimeoutOrNull(200) {
                store.inserted.await()
                true
            } ?: false
        } finally {
            releaseRecorder.countDown()
        }
        recording.await()
        preparing.await()

        assertFalse(insertedBeforeRecorderFinished)
        assertEquals("hello", store.payload?.sections?.single()?.text)
    }

    @Test
    fun `session progresses prepared streaming completed and keeps authoritative prompt usage`() = runBlocking {
        val store = RecordingTraceStore()
        val input = UIMessage.user("hello")
        val session = session(store = store, now = { 100L + store.events.size })

        session.recordInputMessages(listOf(input))
        session.prepare(listOf(input))
        val response = UIMessage.assistant("hi").copy(usage = TokenUsage(promptTokens = 12))
        session.observeProviderMessages(listOf(input, response))
        session.observeProviderMessages(
            listOf(input, response.copy(usage = TokenUsage(promptTokens = 19))),
        )
        session.complete()

        assertEquals(listOf("PREPARED", "STREAMING:12", "TOKENS:19", "COMPLETED"), store.events)
        assertEquals(response.id, store.responseMessageId)
        assertEquals(19, store.actualPromptTokens)
    }

    @Test
    fun `local approximations never become actual provider prompt usage`() = runBlocking {
        val store = RecordingTraceStore()
        val input = UIMessage.user("a local token estimate exists")
        val session = session(store)

        session.recordInputMessages(listOf(input))
        session.prepare(listOf(input))
        session.observeProviderMessages(listOf(input, UIMessage.assistant("response")))

        assertEquals(listOf("PREPARED", "STREAMING:null"), store.events)
        assertNull(store.actualPromptTokens)
        assertTrue(requireNotNull(store.payload).sections.single().approximateTokens > 0)
        assertNull(store.payload?.metadata?.actualPromptTokens)
    }

    @Test
    fun `failure before first response preserves null binding and sanitized summary`() = runBlocking {
        val store = RecordingTraceStore()
        val session = session(store)
        session.prepare(listOf(UIMessage.user("hello")))

        session.fail(IllegalStateException("token=secret https://example.com/a?key=1"))

        assertNull(store.responseMessageId)
        assertEquals("FAILED", store.events.last())
        assertEquals("token=[redacted] https://example.com/a", store.errorSummary)
    }

    @Test
    fun `injection hits merge into payload sections and remain serializable`() = runBlocking {
        val store = RecordingTraceStore()
        val first = injection("mode", PromptInjectionSourceType.MODE)
        val second = injection("lore", PromptInjectionSourceType.LOREBOOK)
        val session = session(store)
        val input = UIMessage.user("token=secret")

        session.recordInputMessages(listOf(input))
        session.recordInjectionHits(listOf(first))
        session.recordInjectionHits(listOf(second))
        session.prepare(listOf(input))

        val payload = requireNotNull(store.payload)
        assertEquals(listOf(first, second), payload.injectionHits)
        assertEquals(
            listOf(PromptTraceSectionKind.MODE_INJECTION, PromptTraceSectionKind.LOREBOOK_INJECTION),
            payload.sections.map { it.kind }.filter {
                it == PromptTraceSectionKind.MODE_INJECTION || it == PromptTraceSectionKind.LOREBOOK_INJECTION
            },
        )
        val roundTrip = Json.decodeFromString<PromptTracePayload>(Json.encodeToString(payload))
        assertEquals(payload, roundTrip)
        assertEquals("token=secret", (roundTrip.finalMessages.single().parts.single() as PromptTracePart.Text).text)
    }

    @Test
    fun `prepare sanitizes provider payload attachments before persistence`() = runBlocking {
        val store = RecordingTraceStore()
        val session = session(store)
        val message = UIMessage.user("hello").copy(
            parts = listOf(me.rerere.ai.ui.UIMessagePart.Image("https://example.com/image.png?token=secret")),
        )

        session.prepare(listOf(message))

        val attachment = requireNotNull(store.payload)
            .finalMessages.single().parts.single() as PromptTracePart.Attachment
        assertEquals("https://example.com/image.png", attachment.value.uri)
        assertFalse(requireNotNull(store.serializedPayload).contains("secret"))
    }

    @Test
    fun `illegal or duplicate lifecycle calls are no ops`() = runBlocking {
        val store = RecordingTraceStore()
        val input = UIMessage.user("hello")
        val response = UIMessage.assistant("hi")
        val session = session(store)

        session.observeProviderMessages(listOf(input, response))
        session.complete()
        session.prepare(listOf(input))
        session.prepare(listOf(input))
        session.observeProviderMessages(listOf(input, response))
        session.complete()
        session.fail(IllegalStateException("late"))
        session.cancel()

        assertEquals(listOf("PREPARED", "STREAMING:null", "COMPLETED"), store.events)
    }

    @Test
    fun `non cancellation store failures are swallowed for every lifecycle operation`() = runBlocking {
        val input = UIMessage.user("hello")
        val response = UIMessage.assistant("hi").copy(usage = TokenUsage(promptTokens = 2))
        val session = session(ThrowingTraceStore())

        session.prepare(listOf(input))
        session.observeProviderMessages(listOf(input, response))
        session.observeProviderMessages(listOf(input, response.copy(usage = TokenUsage(promptTokens = 3))))
        session.complete()
    }

    @Test
    fun `store errors propagate instead of being treated as observability failures`() = runBlocking {
        val failure = AssertionError("fatal trace failure")
        val session = session(ErrorTraceStore(failure))

        try {
            session.prepare(listOf(UIMessage.user("hello")))
            fail("Expected AssertionError")
        } catch (actual: AssertionError) {
            assertSame(failure, actual)
        }
    }

    @Test
    fun `failed response binding retries the same observation`() = runBlocking {
        val store = FailOnceBindingStore()
        val input = UIMessage.user("hello")
        val response = UIMessage.assistant("hi").copy(usage = TokenUsage(promptTokens = 12))
        val session = session(store)
        session.prepare(listOf(input))

        session.observeProviderMessages(listOf(input, response))
        session.observeProviderMessages(listOf(input, response))

        assertEquals(2, store.bindingAttempts)
        assertEquals(response.id, store.responseMessageId)
    }

    @Test
    fun `terminal retries a failed authoritative token update before finalizing`() = runBlocking {
        val store = FailOnceTokenUpdateStore()
        val input = UIMessage.user("hello")
        val response = UIMessage.assistant("hi")
        val session = session(store)
        session.prepare(listOf(input))
        session.observeProviderMessages(
            listOf(input, response.copy(usage = TokenUsage(promptTokens = 12))),
        )
        session.observeProviderMessages(
            listOf(input, response.copy(usage = TokenUsage(promptTokens = 19))),
        )

        session.complete()

        assertEquals(2, store.tokenUpdateAttempts)
        assertEquals(19, store.actualPromptTokens)
        assertEquals(19, store.promptTokensWhenTerminalPersisted)
        assertEquals(PromptTraceStatus.COMPLETED, store.persistedStatus)
    }

    @Test
    fun `failed terminal persistence retries the same terminal event`() = runBlocking {
        val store = FailOnceTerminalStore()
        val session = session(store)
        session.prepare(listOf(UIMessage.user("hello")))

        session.complete()
        session.complete()

        assertEquals(2, store.terminalAttempts)
        assertEquals(PromptTraceStatus.COMPLETED, store.persistedStatus)
    }

    @Test
    fun `usage updates only follow the initially bound response id`() = runBlocking {
        val store = RecordingTraceStore()
        val input = UIMessage.user("hello")
        val bound = UIMessage.assistant("first").copy(usage = TokenUsage(promptTokens = 12))
        val unrelated = UIMessage.assistant("second").copy(usage = TokenUsage(promptTokens = 99))
        val session = session(store)
        session.prepare(listOf(input))

        session.observeProviderMessages(listOf(input, bound))
        session.observeProviderMessages(listOf(input, bound, unrelated))

        assertEquals(bound.id, store.responseMessageId)
        assertEquals(12, store.actualPromptTokens)
        assertFalse(store.events.contains("TOKENS:99"))
    }

    @Test
    fun `first observation with multiple new assistants binds the last assistant`() = runBlocking {
        val store = RecordingTraceStore()
        val input = UIMessage.user("hello")
        val earlier = UIMessage.assistant("earlier").copy(usage = TokenUsage(promptTokens = 7))
        val last = UIMessage.assistant("last").copy(usage = TokenUsage(promptTokens = 13))
        val session = session(store)
        session.prepare(listOf(input))

        session.observeProviderMessages(listOf(input, earlier, last))

        assertEquals(last.id, store.responseMessageId)
        assertEquals(13, store.actualPromptTokens)
    }

    @Test
    fun `store cancellation remains observable`() = runBlocking {
        val cancellation = CancellationException("stop")
        val session = session(CancellingTraceStore(cancellation))

        try {
            session.prepare(listOf(UIMessage.user("hello")))
            fail("Expected CancellationException")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun `recording after prepare does not mutate persisted payload`() = runBlocking {
        val store = RecordingTraceStore()
        val session = session(store)
        session.recordSection(PromptTraceSection(PromptTraceSectionKind.MEMORY, "Memory", "before"))
        session.prepare(listOf(UIMessage.user("hello")))

        session.recordSection(PromptTraceSection(PromptTraceSectionKind.MEMORY, "Memory", "after"))
        session.recordInjectionHits(listOf(injection("late", PromptInjectionSourceType.MODE)))
        session.recordInputMessages(listOf(UIMessage.user("late")))

        assertEquals(
            listOf("before"),
            store.payload?.sections?.filter { it.kind == PromptTraceSectionKind.MEMORY }?.map { it.text },
        )
        assertTrue(store.payload?.injectionHits?.isEmpty() == true)
    }

    private fun session(
        store: PromptTraceStore,
        now: () -> Long = { 1L },
    ) = PromptTraceSession(
        seed = seed(),
        providerStepIndex = 1,
        providerName = "OpenAI",
        store = store,
        now = now,
    )

    private fun seed() = PromptTraceSeed(
        conversationId = Uuid.random(),
        requestAnchorMessageId = Uuid.random(),
        assistantId = Uuid.random(),
        modelId = Uuid.random(),
        isGroup = false,
    )

    private fun injection(name: String, source: PromptInjectionSourceType) = PromptInjectionTrace(
        injectionId = Uuid.random(),
        injectionName = name,
        sourceType = source,
        position = "AFTER_SYSTEM_PROMPT",
        role = MessageRole.SYSTEM,
        priority = 1,
        injectDepth = 0,
        content = "$name content",
    )
}

private class RecordingTraceStore : PromptTraceStore {
    val events = mutableListOf<String>()
    var payload: PromptTracePayload? = null
    var serializedPayload: String? = null
    var responseMessageId: Uuid? = null
    var actualPromptTokens: Int? = null
    var errorSummary: String? = null

    override suspend fun insertPrepared(traceId: Uuid, payload: PromptTracePayload) {
        this.payload = payload
        serializedPayload = Json.encodeToString(payload)
        events += "PREPARED"
    }

    override suspend fun markStreaming(traceId: Uuid, responseMessageId: Uuid, actualPromptTokens: Int?) {
        this.responseMessageId = responseMessageId
        this.actualPromptTokens = actualPromptTokens
        events += "STREAMING:${actualPromptTokens ?: "null"}"
    }

    override suspend fun updateActualPromptTokens(traceId: Uuid, actualPromptTokens: Int) {
        this.actualPromptTokens = actualPromptTokens
        events += "TOKENS:$actualPromptTokens"
    }

    override suspend fun markTerminal(traceId: Uuid, status: PromptTraceStatus, errorSummary: String?) {
        this.errorSummary = errorSummary
        events += status.name
    }
}

private class ThrowingTraceStore : PromptTraceStore {
    override suspend fun insertPrepared(traceId: Uuid, payload: PromptTracePayload) = error("trace store failure")
    override suspend fun markStreaming(traceId: Uuid, responseMessageId: Uuid, actualPromptTokens: Int?) =
        error("trace store failure")
    override suspend fun updateActualPromptTokens(traceId: Uuid, actualPromptTokens: Int) = error("trace store failure")
    override suspend fun markTerminal(traceId: Uuid, status: PromptTraceStatus, errorSummary: String?) =
        error("trace store failure")
}

private class CancellingTraceStore(
    private val cancellation: CancellationException,
) : PromptTraceStore {
    override suspend fun insertPrepared(traceId: Uuid, payload: PromptTracePayload): Unit = throw cancellation
    override suspend fun markStreaming(traceId: Uuid, responseMessageId: Uuid, actualPromptTokens: Int?): Unit =
        throw cancellation
    override suspend fun updateActualPromptTokens(traceId: Uuid, actualPromptTokens: Int): Unit = throw cancellation
    override suspend fun markTerminal(traceId: Uuid, status: PromptTraceStatus, errorSummary: String?): Unit =
        throw cancellation
}

private class BlockingStreamingStore : PromptTraceStore {
    val streamingStarted = CompletableDeferred<Unit>()
    val releaseStreaming = CompletableDeferred<Unit>()
    val events = java.util.Collections.synchronizedList(mutableListOf<String>())

    override suspend fun insertPrepared(traceId: Uuid, payload: PromptTracePayload) {
        events += "PREPARED"
    }

    override suspend fun markStreaming(traceId: Uuid, responseMessageId: Uuid, actualPromptTokens: Int?) {
        streamingStarted.complete(Unit)
        releaseStreaming.await()
        events += "STREAMING:${actualPromptTokens ?: "null"}"
    }

    override suspend fun updateActualPromptTokens(traceId: Uuid, actualPromptTokens: Int) {
        events += "TOKENS:$actualPromptTokens"
    }

    override suspend fun markTerminal(traceId: Uuid, status: PromptTraceStatus, errorSummary: String?) {
        events += status.name
    }
}

private class SignallingTraceStore : PromptTraceStore {
    val inserted = CompletableDeferred<Unit>()
    var payload: PromptTracePayload? = null

    override suspend fun insertPrepared(traceId: Uuid, payload: PromptTracePayload) {
        this.payload = payload
        inserted.complete(Unit)
    }

    override suspend fun markStreaming(traceId: Uuid, responseMessageId: Uuid, actualPromptTokens: Int?) = Unit
    override suspend fun updateActualPromptTokens(traceId: Uuid, actualPromptTokens: Int) = Unit
    override suspend fun markTerminal(traceId: Uuid, status: PromptTraceStatus, errorSummary: String?) = Unit
}

private class BlockingMessageList(
    private val message: UIMessage,
    private val entered: CountDownLatch,
    private val release: CountDownLatch,
) : AbstractList<UIMessage>() {
    override val size: Int = 1

    override fun get(index: Int): UIMessage {
        require(index == 0)
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS))
        return message
    }
}

private class ErrorTraceStore(
    private val failure: AssertionError,
) : PromptTraceStore {
    override suspend fun insertPrepared(traceId: Uuid, payload: PromptTracePayload): Unit = throw failure
    override suspend fun markStreaming(traceId: Uuid, responseMessageId: Uuid, actualPromptTokens: Int?) = Unit
    override suspend fun updateActualPromptTokens(traceId: Uuid, actualPromptTokens: Int) = Unit
    override suspend fun markTerminal(traceId: Uuid, status: PromptTraceStatus, errorSummary: String?) = Unit
}

private class FailOnceBindingStore : PromptTraceStore {
    var bindingAttempts = 0
    var responseMessageId: Uuid? = null

    override suspend fun insertPrepared(traceId: Uuid, payload: PromptTracePayload) = Unit

    override suspend fun markStreaming(traceId: Uuid, responseMessageId: Uuid, actualPromptTokens: Int?) {
        bindingAttempts++
        if (bindingAttempts == 1) throw IllegalStateException("first binding failed")
        this.responseMessageId = responseMessageId
    }

    override suspend fun updateActualPromptTokens(traceId: Uuid, actualPromptTokens: Int) = Unit
    override suspend fun markTerminal(traceId: Uuid, status: PromptTraceStatus, errorSummary: String?) = Unit
}

private class FailOnceTerminalStore : PromptTraceStore {
    var terminalAttempts = 0
    var persistedStatus: PromptTraceStatus? = null

    override suspend fun insertPrepared(traceId: Uuid, payload: PromptTracePayload) = Unit
    override suspend fun markStreaming(traceId: Uuid, responseMessageId: Uuid, actualPromptTokens: Int?) = Unit
    override suspend fun updateActualPromptTokens(traceId: Uuid, actualPromptTokens: Int) = Unit

    override suspend fun markTerminal(traceId: Uuid, status: PromptTraceStatus, errorSummary: String?) {
        terminalAttempts++
        if (terminalAttempts == 1) throw IllegalStateException("first terminal failed")
        persistedStatus = status
    }
}

private class FailOnceTokenUpdateStore : PromptTraceStore {
    var tokenUpdateAttempts = 0
    var actualPromptTokens: Int? = null
    var promptTokensWhenTerminalPersisted: Int? = null
    var persistedStatus: PromptTraceStatus? = null

    override suspend fun insertPrepared(traceId: Uuid, payload: PromptTracePayload) = Unit

    override suspend fun markStreaming(traceId: Uuid, responseMessageId: Uuid, actualPromptTokens: Int?) {
        this.actualPromptTokens = actualPromptTokens
    }

    override suspend fun updateActualPromptTokens(traceId: Uuid, actualPromptTokens: Int) {
        tokenUpdateAttempts++
        if (tokenUpdateAttempts == 1) throw IllegalStateException("first token update failed")
        this.actualPromptTokens = actualPromptTokens
    }

    override suspend fun markTerminal(traceId: Uuid, status: PromptTraceStatus, errorSummary: String?) {
        promptTokensWhenTerminalPersisted = actualPromptTokens
        persistedStatus = status
    }
}
