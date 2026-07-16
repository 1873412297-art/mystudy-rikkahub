package me.rerere.rikkahub.data.ai.trace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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
import kotlin.uuid.Uuid

class PromptTraceSessionTest {
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
