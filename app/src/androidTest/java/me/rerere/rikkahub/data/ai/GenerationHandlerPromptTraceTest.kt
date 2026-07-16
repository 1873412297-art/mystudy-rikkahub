package me.rerere.rikkahub.data.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.trace.DefaultPromptTraceSessionFactory
import me.rerere.rikkahub.data.ai.trace.PromptInjectionSourceType
import me.rerere.rikkahub.data.ai.trace.PromptInjectionTrace
import me.rerere.rikkahub.data.ai.trace.PromptTracePayload
import me.rerere.rikkahub.data.ai.trace.PromptTracePart
import me.rerere.rikkahub.data.ai.trace.PromptTraceReadResult
import me.rerere.rikkahub.data.ai.trace.PromptTraceSectionKind
import me.rerere.rikkahub.data.ai.trace.PromptTraceSeed
import me.rerere.rikkahub.data.ai.trace.PromptTraceSession
import me.rerere.rikkahub.data.ai.trace.PromptTraceSessionFactory
import me.rerere.rikkahub.data.ai.trace.PromptTraceStatus
import me.rerere.rikkahub.data.ai.trace.PromptTraceStore
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.PromptTraceRepository
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class GenerationHandlerPromptTraceTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: PromptTraceRepository
    private lateinit var providerManager: ProviderManager
    private lateinit var model: Model
    private lateinit var providerSetting: ProviderSetting.OpenAI
    private val json = Json { encodeDefaults = true }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PromptTraceRepository(database.promptTraceDao(), json)
        providerManager = ProviderManager(OkHttpClient(), context)
        model = Model(modelId = "recording-model", displayName = "Recording model")
        providerSetting = ProviderSetting.OpenAI(name = "Recorded OpenAI", models = listOf(model))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun eligibleStreamingCallCapturesExactProviderMessagesSectionsRecorderResponseAndUsage() = runBlocking {
        val conversationId = insertConversation()
        val history = UIMessage.assistant("history")
        val current = UIMessage.user("hello")
        val recorderTransformer = AppendingRecordingTransformer()
        val provider = RecordingOpenAIProvider(
            chunks = listOf(
                responseChunk(text = "hel"),
                responseChunk(text = "lo", usage = TokenUsage(promptTokens = 17, completionTokens = 2)),
            ),
        )
        providerManager.registerProvider("openai", provider)
        val assistant = Assistant(
            name = "Card",
            systemPrompt = "card system",
            tavernCardJson = "{}",
            streamOutput = true,
            enableMemory = true,
        )
        val tool = Tool(
            name = "fixture_tool",
            description = "fixture",
            systemPrompt = { _, _ -> "tool prompt" },
            execute = { emptyList() },
        )

        val chunks = handler(DefaultPromptTraceSessionFactory(repository)).generateText(
            settings = Settings(providers = listOf(providerSetting)),
            model = model,
            messages = listOf(history, current),
            inputTransformers = listOf(recorderTransformer),
            assistant = assistant,
            memories = listOf(AssistantMemory(1, "memory-1")),
            tools = listOf(tool),
            maxSteps = 1,
            conversationId = conversationId,
            promptTraceSeed = seed(conversationId, assistant.id, current.id),
        ).toList()

        val record = repository.observeConversation(conversationId).first().single()
            as PromptTraceReadResult.Available
        val payload = record.record.payload
        assertEquals(
            provider.capturedMessages.map { Triple(it.id, it.role, it.toText()) },
            payload.finalMessages.map { message ->
                Triple(
                    message.id,
                    message.role,
                    message.parts.filterIsInstance<PromptTracePart.Text>().joinToString("\n") { it.text },
                )
            },
        )
        assertEquals(PromptTraceStatus.COMPLETED, payload.metadata.status)
        assertEquals(17, payload.metadata.actualPromptTokens)
        val finalMessages = (chunks.last() as GenerationChunk.Messages).messages
        assertEquals(finalMessages.last().id, payload.metadata.responseMessageId)
        assertEquals("hello transformed", provider.capturedMessages.last().toText())
        assertTrue(provider.capturedMessages.first().toText().contains("card system"))
        assertTrue(provider.capturedMessages.first().toText().contains("memory-1"))
        assertTrue(provider.capturedMessages.first().toText().contains("tool prompt"))

        assertEquals(
            "card system",
            payload.sections.single { it.kind == PromptTraceSectionKind.ASSISTANT_OR_CARD_SYSTEM }.text,
        )
        assertTrue(payload.sections.single { it.kind == PromptTraceSectionKind.MEMORY }.text.contains("memory-1"))
        assertTrue(
            payload.sections.any {
                it.kind == PromptTraceSectionKind.TOOL_PROMPT &&
                    it.label == "Tool prompt: fixture_tool" &&
                    it.text == "tool prompt"
            },
        )
        assertTrue(
            payload.sections.any {
                it.kind == PromptTraceSectionKind.HISTORY_MESSAGE && it.sourceMessageId == history.id
            },
        )
        assertTrue(
            payload.sections.any {
                it.kind == PromptTraceSectionKind.CURRENT_USER_MESSAGE && it.sourceMessageId == current.id
            },
        )
        assertTrue(
            payload.sections.any {
                it.kind == PromptTraceSectionKind.OTHER_TRANSFORMED_CONTENT && it.text == "hello transformed"
            },
        )
        assertTrue(recorderTransformer.sawRecorder)
        assertEquals("transform fixture", payload.injectionHits.single().injectionName)
    }

    @Test
    fun ineligibleCallWithoutSeedGeneratesNormallyAndStoresNoTrace() = runBlocking {
        val conversationId = insertConversation()
        val provider = RecordingOpenAIProvider(listOf(responseChunk("plain")))
        providerManager.registerProvider("openai", provider)

        val output = handler(DefaultPromptTraceSessionFactory(repository)).generateText(
            settings = Settings(providers = listOf(providerSetting)),
            model = model,
            messages = listOf(UIMessage.user("hello")),
            assistant = Assistant(streamOutput = true),
            maxSteps = 1,
            conversationId = conversationId,
            promptTraceSeed = null,
        ).toList()

        assertEquals(1, provider.callCount)
        assertEquals("plain", (output.last() as GenerationChunk.Messages).messages.last().toText())
        assertTrue(repository.observeConversation(conversationId).first().isEmpty())
    }

    @Test
    fun providerFailureMarksTraceFailedAndRethrowsOriginalFailure() = runBlocking {
        val conversationId = insertConversation()
        val failure = IllegalStateException("provider failed")
        val provider = RecordingOpenAIProvider(failure = failure)
        providerManager.registerProvider("openai", provider)
        val assistant = Assistant(systemPrompt = "system", tavernCardJson = "{}")

        try {
            handler(DefaultPromptTraceSessionFactory(repository)).generateText(
                settings = Settings(providers = listOf(providerSetting)),
                model = model,
                messages = listOf(UIMessage.user("hello")),
                assistant = assistant,
                maxSteps = 1,
                promptTraceSeed = seed(conversationId, assistant.id),
            ).toList()
            fail("Expected provider failure")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }

        val record = repository.observeConversation(conversationId).first().single()
            as PromptTraceReadResult.Available
        assertEquals(PromptTraceStatus.FAILED, record.record.payload.metadata.status)
        assertTrue(requireNotNull(record.record.errorSummary).contains("provider failed"))
    }

    @Test
    fun providerCancellationMarksTraceCancelledAndRethrowsOriginalCancellation() = runBlocking {
        val conversationId = insertConversation()
        val cancellation = CancellationException("provider cancelled")
        val provider = RecordingOpenAIProvider(failure = cancellation)
        providerManager.registerProvider("openai", provider)
        val assistant = Assistant(systemPrompt = "system", tavernCardJson = "{}")

        try {
            handler(DefaultPromptTraceSessionFactory(repository)).generateText(
                settings = Settings(providers = listOf(providerSetting)),
                model = model,
                messages = listOf(UIMessage.user("hello")),
                assistant = assistant,
                maxSteps = 1,
                promptTraceSeed = seed(conversationId, assistant.id),
            ).toList()
            fail("Expected provider cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }

        val record = repository.observeConversation(conversationId).first().single()
            as PromptTraceReadResult.Available
        assertEquals(PromptTraceStatus.CANCELLED, record.record.payload.metadata.status)
    }

    @Test
    fun ordinaryTraceStoreExceptionDoesNotChangeNonStreamingGeneration() = runBlocking {
        val conversationId = insertConversation()
        val provider = RecordingOpenAIProvider(listOf(responseChunk("plain")))
        providerManager.registerProvider("openai", provider)

        val output = handler(throwingFactory(IllegalStateException("trace store failed"))).generateText(
            settings = Settings(providers = listOf(providerSetting)),
            model = model,
            messages = listOf(UIMessage.user("hello")),
            assistant = Assistant(streamOutput = false),
            maxSteps = 1,
            promptTraceSeed = seed(conversationId),
        ).toList()

        assertEquals(1, provider.callCount)
        assertEquals("plain", (output.last() as GenerationChunk.Messages).messages.last().toText())
    }

    @Test
    fun traceStoreCancellationIsNotSwallowed() = runBlocking {
        val cancellation = CancellationException("trace cancelled")
        assertTraceThrowableIsPropagated(cancellation)
    }

    @Test
    fun fatalTraceStoreErrorIsNotSwallowed() = runBlocking {
        val fatal = AssertionError("fatal trace failure")
        assertTraceThrowableIsPropagated(fatal)
    }

    private suspend fun assertTraceThrowableIsPropagated(expected: Throwable) {
        val conversationId = insertConversation()
        val provider = RecordingOpenAIProvider(listOf(responseChunk("unused")))
        providerManager.registerProvider("openai", provider)

        try {
            handler(throwingFactory(expected)).generateText(
                settings = Settings(providers = listOf(providerSetting)),
                model = model,
                messages = listOf(UIMessage.user("hello")),
                assistant = Assistant(),
                maxSteps = 1,
                promptTraceSeed = seed(conversationId),
            ).toList()
            fail("Expected trace throwable")
        } catch (actual: Throwable) {
            assertSame(expected, actual)
        }
        assertEquals(0, provider.callCount)
    }

    private fun handler(factory: PromptTraceSessionFactory) = GenerationHandler(
        context = context,
        providerManager = providerManager,
        json = json,
        memoryRepo = MemoryRepository(database.memoryDao()),
        promptTraceSessionFactory = factory,
    )

    private fun seed(
        conversationId: Uuid,
        assistantId: Uuid = Uuid.random(),
        requestAnchorMessageId: Uuid? = null,
    ) = PromptTraceSeed(
        conversationId = conversationId,
        requestAnchorMessageId = requestAnchorMessageId,
        assistantId = assistantId,
        modelId = model.id,
        isGroup = false,
    )

    private suspend fun insertConversation(): Uuid {
        val conversationId = Uuid.random()
        database.conversationDao().insert(
            ConversationEntity(
                id = conversationId.toString(),
                assistantId = Uuid.random().toString(),
                title = "Test",
                nodes = "[]",
                createAt = 1L,
                updateAt = 1L,
                chatSuggestions = "[]",
                isPinned = false,
            ),
        )
        return conversationId
    }

    private fun throwingFactory(throwable: Throwable) = object : PromptTraceSessionFactory {
        override fun create(
            seed: PromptTraceSeed,
            providerStepIndex: Int,
            providerName: String?,
        ) = PromptTraceSession(
            seed = seed,
            providerStepIndex = providerStepIndex,
            providerName = providerName,
            store = ThrowingPromptTraceStore(throwable),
        )
    }

    private fun responseChunk(text: String, usage: TokenUsage? = null) = MessageChunk(
        id = Uuid.random().toString(),
        model = model.modelId,
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage.assistant(text),
                message = null,
                finishReason = null,
            ),
        ),
        usage = usage,
    )

    private class RecordingOpenAIProvider(
        private val chunks: List<MessageChunk> = emptyList(),
        private val failure: Throwable? = null,
    ) : Provider<ProviderSetting.OpenAI> {
        var capturedMessages: List<UIMessage> = emptyList()
        var callCount: Int = 0

        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()

        override suspend fun generateText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): MessageChunk {
            recordCall(messages)
            failure?.let { throw it }
            return chunks.last()
        }

        override suspend fun streamText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> = flow {
            recordCall(messages)
            failure?.let { throw it }
            chunks.forEach { emit(it) }
        }

        private fun recordCall(messages: List<UIMessage>) {
            callCount++
            capturedMessages = messages
        }
    }

    private class AppendingRecordingTransformer : InputMessageTransformer {
        var sawRecorder = false

        override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
            val recorder = requireNotNull(ctx.promptTraceSession)
            sawRecorder = true
            recorder.recordInjectionHits(
                listOf(
                    PromptInjectionTrace(
                        injectionId = Uuid.random(),
                        injectionName = "transform fixture",
                        sourceType = PromptInjectionSourceType.MODE,
                        position = "AFTER_SYSTEM_PROMPT",
                        role = MessageRole.USER,
                        priority = 0,
                        injectDepth = 0,
                        content = "fixture content",
                    ),
                ),
            )
            return messages.mapIndexed { index, message ->
                if (index == messages.lastIndex) {
                    message.copy(parts = listOf(UIMessagePart.Text(message.toText() + " transformed")))
                } else {
                    message
                }
            }
        }
    }

    private class ThrowingPromptTraceStore(
        private val throwable: Throwable,
    ) : PromptTraceStore {
        override suspend fun insertPrepared(traceId: Uuid, payload: PromptTracePayload) {
            throw throwable
        }

        override suspend fun markStreaming(
            traceId: Uuid,
            responseMessageId: Uuid,
            actualPromptTokens: Int?,
        ) = Unit

        override suspend fun updateActualPromptTokens(traceId: Uuid, actualPromptTokens: Int) = Unit

        override suspend fun markTerminal(
            traceId: Uuid,
            status: PromptTraceStatus,
            errorSummary: String?,
        ) = Unit
    }
}
