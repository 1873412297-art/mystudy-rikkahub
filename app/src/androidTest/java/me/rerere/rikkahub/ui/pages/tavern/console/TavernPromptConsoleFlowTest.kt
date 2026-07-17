package me.rerere.rikkahub.ui.pages.tavern.console

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.trace.PromptInjectionSourceType
import me.rerere.rikkahub.data.ai.trace.PromptInjectionTrace
import me.rerere.rikkahub.data.ai.trace.PromptTraceMessage
import me.rerere.rikkahub.data.ai.trace.PromptTraceMetadata
import me.rerere.rikkahub.data.ai.trace.PromptTracePart
import me.rerere.rikkahub.data.ai.trace.PromptTracePayload
import me.rerere.rikkahub.data.ai.trace.PromptTraceReadResult
import me.rerere.rikkahub.data.ai.trace.PromptTraceRecord
import me.rerere.rikkahub.data.ai.trace.PromptTraceSection
import me.rerere.rikkahub.data.ai.trace.PromptTraceSectionKind
import me.rerere.rikkahub.data.ai.trace.PromptTraceStatus
import me.rerere.rikkahub.data.ai.trace.isTavernPromptTraceEligible
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class TavernPromptConsoleFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun eligibleEntryConsoleTabsHistoryCopyAndClearDoNotMutateConversationBranch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val conversationId = Uuid.random()
        val assistant = Assistant(name = "Character", tavernCardJson = "{}")
        val responseA = UIMessage.assistant("alternative A")
        val responseB = UIMessage.assistant("alternative B")
        val responseNode = MessageNode(
            messages = listOf(responseA, responseB),
            selectIndex = 1,
        )
        val originalMessages = responseNode.messages
        val originalSelectIndex = responseNode.selectIndex
        val historicalTrace = availableTrace(
            conversationId = conversationId,
            responseId = responseA.id,
            createdAt = 10L,
            finalText = "historical provider message",
        )
        val selectedBranchTrace = availableTrace(
            conversationId = conversationId,
            responseId = responseB.id,
            createdAt = 20L,
            finalText = "selected provider message",
        )
        val traces = listOf(historicalTrace, selectedBranchTrace)
        val defaultTraceId = selectDefaultTraceId(traces, responseNode.currentMessage.id)
        assertEquals(selectedBranchTrace.traceId, defaultTraceId)

        var openedConversationId by mutableStateOf<Uuid?>(null)
        var selectedTab by mutableStateOf(TavernPromptConsoleTab.OVERVIEW)
        var selectedTraceId by mutableStateOf(defaultTraceId)
        var copiedMessages = 0
        var copiedTraces = 0
        var cleared = 0

        composeRule.setContent {
            MaterialTheme {
                if (openedConversationId == null) {
                    TavernPromptConsoleEntry(
                        visible = assistant.isTavernPromptTraceEligible(listOf(assistant)),
                        onOpen = { openedConversationId = conversationId },
                    )
                } else {
                    val selectedTrace = traces.firstOrNull { it.traceId == selectedTraceId }
                    TavernPromptConsoleContent(
                        state = TavernPromptConsoleUiState(
                            loading = false,
                            conversationTitle = "Tavern test",
                            assistantName = assistant.name,
                            traces = traces,
                            selectedTraceId = selectedTraceId,
                            selectedTrace = selectedTrace,
                            selectedTab = selectedTab,
                            selectedBranchHasTrace = selectedBranchTrace.traceId == defaultTraceId,
                        ),
                        onBack = {},
                        onSelectTrace = { selectedTraceId = it },
                        onSelectTab = { selectedTab = it },
                        onCopyAll = { copiedTraces++ },
                        onCopyMessage = { copiedMessages++ },
                        onClear = { cleared++ },
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
            .onNodeWithText(context.getString(R.string.tavern_prompt_console_messages))
            .performClick()
        composeRule.onNodeWithText("selected provider message").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.tavern_prompt_console_copy_message))
            .performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.tavern_prompt_console_copy_all))
            .performClick()

        composeRule
            .onNodeWithText(context.getString(R.string.tavern_prompt_console_preview))
            .performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.tavern_prompt_console_preview_a2))
            .assertIsDisplayed()

        composeRule
            .onNodeWithText(
                context.getString(
                    R.string.tavern_prompt_console_trace_call_status,
                    2,
                    PromptTraceStatus.COMPLETED.name,
                )
            )
            .performClick()
        assertEquals(historicalTrace.traceId, selectedTraceId)
        assertEquals(originalSelectIndex, responseNode.selectIndex)

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.tavern_prompt_console_clear))
            .performClick()
        assertEquals(0, cleared)
        composeRule
            .onNodeWithText(context.getString(R.string.tavern_prompt_console_clear), useUnmergedTree = true)
            .performClick()

        assertEquals(1, copiedMessages)
        assertEquals(1, copiedTraces)
        assertEquals(1, cleared)
        assertEquals(originalSelectIndex, responseNode.selectIndex)
        assertEquals(originalMessages, responseNode.messages)
        assertTrue(assistant.isTavernPromptTraceEligible(listOf(assistant)))
    }

    private fun availableTrace(
        conversationId: Uuid,
        responseId: Uuid,
        createdAt: Long,
        finalText: String,
    ): PromptTraceReadResult.Available {
        val message = PromptTraceMessage(
            id = responseId,
            index = 0,
            role = MessageRole.ASSISTANT,
            parts = listOf(PromptTracePart.Text(finalText)),
            characterCount = finalText.length,
            approximateTokens = 2,
        )
        return PromptTraceReadResult.Available(
            PromptTraceRecord(
                traceId = Uuid.random(),
                payload = PromptTracePayload(
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
                            label = if (createdAt == 20L) "Selected branch prompt" else "Historical prompt",
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
                ),
            )
        )
    }
}
