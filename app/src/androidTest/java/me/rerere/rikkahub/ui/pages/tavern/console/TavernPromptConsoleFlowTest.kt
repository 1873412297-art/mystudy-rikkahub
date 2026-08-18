package me.rerere.rikkahub.ui.pages.tavern.console

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.trace.PromptInjectionSourceType
import me.rerere.rikkahub.data.ai.trace.PromptInjectionTrace
import me.rerere.rikkahub.data.ai.trace.PromptTraceMessage
import me.rerere.rikkahub.data.ai.trace.PromptTraceMetadata
import me.rerere.rikkahub.data.ai.trace.PromptTracePart
import me.rerere.rikkahub.data.ai.trace.PromptTracePayload
import me.rerere.rikkahub.data.ai.trace.PromptTraceSection
import me.rerere.rikkahub.data.ai.trace.PromptTraceSectionKind
import me.rerere.rikkahub.data.ai.trace.PromptTraceStatus
import me.rerere.rikkahub.data.ai.trace.isTavernPromptTraceEligible
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.PromptTraceRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class TavernPromptConsoleFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var database: AppDatabase
    private lateinit var repository: PromptTraceRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PromptTraceRepository(database.promptTraceDao(), Json { encodeDefaults = true })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun realVmRepositoryEntryHistoryCopyAndClearKeepConversationBranchUnchanged() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val conversationId = Uuid.random()
        val assistant = Assistant(name = "Character", tavernCardJson = "{}")
        val responseA = UIMessage.assistant("alternative A")
        val responseB = UIMessage.assistant("alternative B")
        val responseNode = MessageNode(
            messages = listOf(responseA, responseB),
            selectIndex = 1,
        )
        val originalConversation = Conversation(
            id = conversationId,
            assistantId = assistant.id,
            title = "Tavern test",
            messageNodes = listOf(responseNode),
        )
        val conversationSnapshot = originalConversation.copy(
            messageNodes = originalConversation.messageNodes.map { node ->
                node.copy(messages = node.messages.map { message -> message.copy() })
            },
        )
        database.conversationDao().insert(conversationEntity(originalConversation))

        val historicalTraceId = insertCompletedTrace(
            payload = tracePayload(
                conversationId = conversationId,
                responseId = responseA.id,
                createdAt = 10L,
                label = "Historical prompt",
                finalText = "historical provider message",
            ),
            responseId = responseA.id,
        )
        val selectedBranchTraceId = insertCompletedTrace(
            payload = tracePayload(
                conversationId = conversationId,
                responseId = responseB.id,
                createdAt = 20L,
                label = "Selected branch prompt",
                finalText = "selected provider message",
            ),
            responseId = responseB.id,
        )

        val stateScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val vm = TavernPromptConsoleVM(
            conversationId = conversationId.toString(),
            observeTraces = repository::observeConversation,
            loadConversation = { id -> originalConversation.takeIf { it.id == id } },
            settings = MutableStateFlow(Settings.dummy().copy(assistants = listOf(assistant))),
            clearTraces = repository::clearConversation,
            stateScope = stateScope,
            sharingStarted = SharingStarted.Eagerly,
        )
        composeRule.waitUntil(5_000) { vm.uiState.value.traces.size == 2 }
        assertEquals(selectedBranchTraceId, vm.uiState.value.selectedTraceId)

        var openedConversationId by mutableStateOf<Uuid?>(null)
        var copiedMessage: String? = null
        var copiedTrace: String? = null
        composeRule.setContent {
            MaterialTheme {
                if (openedConversationId == null) {
                    TavernPromptConsoleEntry(
                        visible = assistant.isTavernPromptTraceEligible(listOf(assistant)),
                        onOpen = { openedConversationId = conversationId },
                    )
                } else {
                    val state by vm.uiState.collectAsState()
                    TavernPromptConsoleContent(
                        state = state,
                        onBack = {},
                        onSelectTrace = vm::selectTrace,
                        onSelectTab = vm::selectTab,
                        onCopyAll = { copiedTrace = vm.copySelectedTrace() },
                        onCopyMessage = { index -> copiedMessage = vm.copyMessage(index) },
                        onClear = vm::clearConversationTraces,
                    )
                }
            }
        }

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.tavern_prompt_console_open))
            .performClick()
        composeRule.waitForIdle()
        assertEquals(conversationId, openedConversationId)
        composeRule.onNodeWithText("Selected branch prompt").assertIsDisplayed()

        composeRule
            .onNodeWithText(context.getString(R.string.tavern_prompt_console_hits))
            .performClick()
        composeRule.onNodeWithText("Lore hit").assertIsDisplayed()

        composeRule
            .onNodeWithText(
                context.getString(
                    R.string.tavern_prompt_console_trace_call_status,
                    2,
                    PromptTraceStatus.COMPLETED.name,
                )
            )
            .performClick()
        composeRule.waitUntil(5_000) { vm.uiState.value.selectedTraceId == historicalTraceId }

        composeRule
            .onNodeWithText(context.getString(R.string.tavern_prompt_console_messages))
            .performClick()
        composeRule.onNodeWithText("historical provider message").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.tavern_prompt_console_copy_message))
            .performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.tavern_prompt_console_copy_all))
            .performClick()
        assertTrue(requireNotNull(copiedMessage).contains("historical provider message"))
        assertTrue(requireNotNull(copiedTrace).contains("historical provider message"))

        composeRule
            .onNodeWithText(context.getString(R.string.tavern_prompt_console_preview))
            .performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.tavern_prompt_console_preview_a2))
            .assertIsDisplayed()

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.tavern_prompt_console_clear))
            .performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.tavern_prompt_console_clear), useUnmergedTree = true)
            .performClick()
        composeRule.waitUntil(5_000) { vm.uiState.value.traces.isEmpty() }

        assertTrue(repository.observeConversation(conversationId).first().isEmpty())
        assertEquals(conversationSnapshot, originalConversation)
        assertEquals(1, originalConversation.messageNodes.single().selectIndex)
        assertEquals(listOf(responseA, responseB), originalConversation.messageNodes.single().messages)
        assertNotNull(database.conversationDao().getConversationById(conversationId.toString()))
        stateScope.cancel()
    }

    private suspend fun insertCompletedTrace(payload: PromptTracePayload, responseId: Uuid): Uuid {
        val traceId = Uuid.random()
        repository.insertPrepared(traceId, payload)
        repository.markStreaming(traceId, responseId, actualPromptTokens = 7)
        repository.markTerminal(traceId, PromptTraceStatus.COMPLETED, null)
        return traceId
    }

    private fun tracePayload(
        conversationId: Uuid,
        responseId: Uuid,
        createdAt: Long,
        label: String,
        finalText: String,
    ): PromptTracePayload {
        val message = PromptTraceMessage(
            id = responseId,
            index = 0,
            role = MessageRole.ASSISTANT,
            parts = listOf(PromptTracePart.Text(finalText)),
            characterCount = finalText.length,
            approximateTokens = 2,
        )
        return PromptTracePayload(
            metadata = PromptTraceMetadata(
                conversationId = conversationId,
                assistantId = Uuid.random(),
                modelId = Uuid.random(),
                isGroup = false,
                providerName = "Provider",
                providerStepIndex = 0,
                responseMessageId = responseId,
                startedAtEpochMs = createdAt,
                status = PromptTraceStatus.COMPLETED,
                finalMessageCount = 1,
            ),
            sections = listOf(
                PromptTraceSection(
                    kind = PromptTraceSectionKind.CURRENT_USER_MESSAGE,
                    label = label,
                    text = "hello",
                )
            ),
            injectionHits = listOf(
                PromptInjectionTrace(
                    injectionId = Uuid.random(),
                    injectionName = "Lore hit",
                    sourceType = PromptInjectionSourceType.LOREBOOK,
                    position = "AFTER_SYSTEM_PROMPT",
                    role = MessageRole.USER,
                    priority = 1,
                    injectDepth = 0,
                    content = "lore content",
                )
            ),
            finalMessages = listOf(message),
        )
    }

    private fun conversationEntity(conversation: Conversation) = ConversationEntity(
        id = conversation.id.toString(),
        assistantId = conversation.assistantId.toString(),
        title = conversation.title,
        nodes = "[]",
        createAt = 1L,
        updateAt = 1L,
        chatSuggestions = "[]",
        isPinned = false,
    )
}
