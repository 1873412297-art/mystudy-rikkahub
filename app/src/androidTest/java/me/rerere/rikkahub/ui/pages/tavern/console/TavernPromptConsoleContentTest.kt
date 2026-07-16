package me.rerere.rikkahub.ui.pages.tavern.console

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.trace.PromptTraceMessage
import me.rerere.rikkahub.data.ai.trace.PromptTraceMetadata
import me.rerere.rikkahub.data.ai.trace.PromptTracePart
import me.rerere.rikkahub.data.ai.trace.PromptTracePayload
import me.rerere.rikkahub.data.ai.trace.PromptTraceReadResult
import me.rerere.rikkahub.data.ai.trace.PromptTraceRecord
import me.rerere.rikkahub.data.ai.trace.PromptTraceStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class TavernPromptConsoleContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun noTracesShowsEmptyState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        setConsoleContent(TavernPromptConsoleUiState(loading = false))

        composeRule
            .onNodeWithText(context.getString(R.string.tavern_prompt_console_no_traces))
            .assertIsDisplayed()
    }

    @Test
    fun unavailableSelectedTraceShowsDegradedState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unavailable = PromptTraceReadResult.Unavailable(
            traceId = Uuid.random(),
            createdAtEpochMs = 2L,
            responseMessageId = null,
            status = PromptTraceStatus.FAILED,
            errorSummary = "malformed",
        )
        setConsoleContent(
            TavernPromptConsoleUiState(
                loading = false,
                assistantName = "Tavern group",
                traces = listOf(unavailable),
                selectedTraceId = unavailable.traceId,
                selectedTrace = unavailable,
            )
        )

        composeRule
            .onNodeWithText(context.getString(R.string.tavern_prompt_console_payload_unavailable))
            .assertIsDisplayed()
    }

    @Test
    fun previewTabShowsA2State() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val trace = availableTrace()
        var state by mutableStateOf(availableState(trace))
        composeRule.setContent {
            MaterialTheme {
                TavernPromptConsoleContent(
                    state = state,
                    onBack = {},
                    onSelectTrace = {},
                    onSelectTab = { state = state.copy(selectedTab = it) },
                    onCopyAll = {},
                    onCopyMessage = {},
                    onClear = {},
                )
            }
        }

        composeRule
            .onNodeWithText(context.getString(R.string.tavern_prompt_console_preview))
            .performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.tavern_prompt_console_preview_a2))
            .assertIsDisplayed()
    }

    @Test
    fun availableGroupTraceShowsSpeakerName() {
        val trace = availableTrace(speakerName = "Aileen")
        setConsoleContent(availableState(trace))

        composeRule.onAllNodesWithText("Speaker: Aileen")[0].assertIsDisplayed()
    }

    @Test
    fun clearRequiresConfirmationBeforeCallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var clears = 0
        val trace = availableTrace()
        setConsoleContent(state = availableState(trace), onClear = { clears++ })

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.tavern_prompt_console_clear))
            .performClick()
        assertEquals(0, clears)
        composeRule
            .onNodeWithText(context.getString(R.string.tavern_prompt_console_clear_title))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.tavern_prompt_console_clear), useUnmergedTree = true)
            .performClick()
        assertEquals(1, clears)
    }

    @Test
    fun copyFullDispatchesForAvailableTrace() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var copies = 0
        val trace = availableTrace()
        setConsoleContent(
            state = availableState(trace).copy(selectedTab = TavernPromptConsoleTab.SENT_MESSAGES),
            onCopyAll = { copies++ },
        )
        composeRule
            .onNodeWithText(context.getString(R.string.tavern_prompt_console_copy_all))
            .performClick()
        assertEquals(1, copies)
    }

    @Test
    fun copyFullDoesNotExistForUnavailableTrace() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var copies = 0
        val unavailable = PromptTraceReadResult.Unavailable(
            traceId = Uuid.random(),
            createdAtEpochMs = 3L,
            responseMessageId = null,
            status = PromptTraceStatus.FAILED,
            errorSummary = "bad payload",
        )
        setConsoleContent(
            state = TavernPromptConsoleUiState(
                loading = false,
                traces = listOf(unavailable),
                selectedTraceId = unavailable.traceId,
                selectedTrace = unavailable,
                selectedTab = TavernPromptConsoleTab.SENT_MESSAGES,
            ),
            onCopyAll = { copies++ },
        )
        composeRule
            .onNodeWithText(context.getString(R.string.tavern_prompt_console_copy_all))
            .assertDoesNotExist()
        assertEquals(0, copies)
    }

    private fun setConsoleContent(
        state: TavernPromptConsoleUiState,
        onSelectTab: (TavernPromptConsoleTab) -> Unit = {},
        onCopyAll: () -> Unit = {},
        onClear: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                TavernPromptConsoleContent(
                    state = state,
                    onBack = {},
                    onSelectTrace = {},
                    onSelectTab = onSelectTab,
                    onCopyAll = onCopyAll,
                    onCopyMessage = {},
                    onClear = onClear,
                )
            }
        }
    }

    private fun availableState(trace: PromptTraceReadResult.Available) = TavernPromptConsoleUiState(
        loading = false,
        conversationTitle = "Test conversation",
        assistantName = "Tavern group",
        traces = listOf(trace),
        selectedTraceId = trace.traceId,
        selectedTrace = trace,
        selectedBranchHasTrace = true,
    )

    private fun availableTrace(speakerName: String? = null): PromptTraceReadResult.Available {
        val traceId = Uuid.random()
        val messageText = "Hello from the provider call"
        return PromptTraceReadResult.Available(
            PromptTraceRecord(
                traceId = traceId,
                payload = PromptTracePayload(
                    metadata = PromptTraceMetadata(
                        conversationId = Uuid.random(),
                        assistantId = Uuid.random(),
                        modelId = Uuid.random(),
                        isGroup = speakerName != null,
                        speakerName = speakerName,
                        providerName = "Provider",
                        providerStepIndex = 0,
                        startedAtEpochMs = 1L,
                        status = PromptTraceStatus.COMPLETED,
                        finalMessageCount = 1,
                    ),
                    sections = emptyList(),
                    injectionHits = emptyList(),
                    finalMessages = listOf(
                        PromptTraceMessage(
                            id = Uuid.random(),
                            index = 0,
                            role = MessageRole.USER,
                            parts = listOf(PromptTracePart.Text(messageText)),
                            characterCount = messageText.length,
                            approximateTokens = 8,
                        )
                    ),
                ),
            )
        )
    }
}
