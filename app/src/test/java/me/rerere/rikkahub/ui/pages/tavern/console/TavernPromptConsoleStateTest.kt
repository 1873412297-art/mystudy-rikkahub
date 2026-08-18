package me.rerere.rikkahub.ui.pages.tavern.console

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.trace.PromptTraceMessage
import me.rerere.rikkahub.data.ai.trace.PromptTraceMetadata
import me.rerere.rikkahub.data.ai.trace.PromptTracePart
import me.rerere.rikkahub.data.ai.trace.PromptTracePayload
import me.rerere.rikkahub.data.ai.trace.PromptTraceReadResult
import me.rerere.rikkahub.data.ai.trace.PromptTraceRecord
import me.rerere.rikkahub.data.ai.trace.PromptTraceStatus
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class TavernPromptConsoleStateTest {
    @Test
    fun `selected reply branch wins over newest trace`() {
        val selectedReply = Uuid.random()
        val newest = unavailable(created = 20, response = Uuid.random())
        val branch = unavailable(created = 10, response = selectedReply)

        assertEquals(branch.traceId, selectDefaultTraceId(listOf(newest, branch), selectedReply))
    }

    @Test
    fun `newest trace is fallback and empty list has no selection`() {
        val newest = unavailable(created = 20, response = null)
        val older = unavailable(created = 10, response = null)

        assertEquals(newest.traceId, selectDefaultTraceId(listOf(newest, older), Uuid.random()))
        assertNull(selectDefaultTraceId(emptyList(), null))
    }

    @Test
    fun `observed trace list exposes selected unavailable detail and conversation labels`() {
        val assistant = Assistant(name = "Card assistant")
        val selectedReply = assistantMessage()
        val conversation = conversation(
            assistantId = assistant.id,
            title = "Current conversation",
            messages = listOf(selectedReply),
        )
        val newest = available(created = 20, response = Uuid.random(), text = "newest")
        val branch = unavailable(created = 10, response = selectedReply.id)
        val traces = MutableStateFlow<List<PromptTraceReadResult>>(emptyList())
        val vm = vm(conversation = conversation, traces = traces, assistants = listOf(assistant))

        traces.value = listOf(newest, branch)

        assertFalse(vm.uiState.value.loading)
        assertEquals("Current conversation", vm.uiState.value.conversationTitle)
        assertEquals("Card assistant", vm.uiState.value.assistantName)
        assertEquals(listOf(newest, branch), vm.uiState.value.traces)
        assertEquals(branch.traceId, vm.uiState.value.selectedTraceId)
        assertSame(branch, vm.uiState.value.selectedTrace)
        assertTrue(vm.uiState.value.selectedBranchHasTrace)
        assertNull(vm.copySelectedTrace())
        assertNull(vm.copyMessage(0))
    }

    @Test
    fun `explicit current selection drives full and message copy`() {
        val conversation = conversation(messages = listOf(assistantMessage()))
        val first = available(created = 20, response = null, text = "first payload")
        val second = available(created = 10, response = null, text = "selected payload")
        val vm = vm(conversation = conversation, traces = MutableStateFlow(listOf(first, second)))

        vm.selectTrace(second.traceId)

        assertSame(second, vm.uiState.value.selectedTrace)
        assertTrue(vm.copySelectedTrace()!!.contains("selected payload"))
        assertTrue(vm.copyMessage(0)!!.contains("selected payload"))
        assertNull(vm.copyMessage(1))
    }

    @Test
    fun `selection follows current conversation branch when observed list changes`() {
        val selectedReply = assistantMessage()
        val conversation = conversation(messages = listOf(selectedReply))
        val oldSelection = available(created = 30, response = Uuid.random(), text = "old")
        val traces = MutableStateFlow<List<PromptTraceReadResult>>(listOf(oldSelection))
        val vm = vm(conversation = conversation, traces = traces)
        vm.selectTrace(oldSelection.traceId)
        val otherNewest = available(created = 50, response = Uuid.random(), text = "new")
        val currentBranch = available(created = 40, response = selectedReply.id, text = "branch")

        traces.value = listOf(otherNewest, currentBranch)

        assertEquals(currentBranch.traceId, vm.uiState.value.selectedTraceId)
        assertSame(currentBranch, vm.uiState.value.selectedTrace)
        assertTrue(vm.copySelectedTrace()!!.contains("branch"))
    }

    @Test
    fun `conversation scoped instances observe and clear only their own id`() {
        val firstId = Uuid.random()
        val secondId = Uuid.random()
        val observed = mutableListOf<Uuid>()
        val cleared = mutableListOf<Uuid>()
        val firstTrace = available(created = 2, response = null, text = "first")
        val secondTrace = available(created = 1, response = null, text = "second")
        val tracesById = mapOf(
            firstId to MutableStateFlow<List<PromptTraceReadResult>>(listOf(firstTrace)),
            secondId to MutableStateFlow<List<PromptTraceReadResult>>(listOf(secondTrace)),
        )
        val conversations = mapOf(
            firstId to conversation(id = firstId, title = "First"),
            secondId to conversation(id = secondId, title = "Second"),
        )

        val first = vm(
            conversationId = firstId,
            observeTraces = { id -> observed += id; tracesById.getValue(id) },
            loadConversation = conversations::get,
            clearTraces = cleared::add,
        )
        val second = vm(
            conversationId = secondId,
            observeTraces = { id -> observed += id; tracesById.getValue(id) },
            loadConversation = conversations::get,
            clearTraces = cleared::add,
        )

        assertEquals(listOf(firstId, secondId), observed)
        assertEquals("First", first.uiState.value.conversationTitle)
        assertSame(firstTrace, first.uiState.value.selectedTrace)
        assertEquals("Second", second.uiState.value.conversationTitle)
        assertSame(secondTrace, second.uiState.value.selectedTrace)

        second.clearConversationTraces()

        assertEquals(listOf(secondId), cleared)
    }

    @Test
    fun `missing conversation and trace-free ineligible conversation have no detail or copy`() {
        val missing = vm(conversation = null, traces = MutableStateFlow(emptyList()))
        val noTraces = vm(conversation = conversation(), traces = MutableStateFlow(emptyList()))

        assertFalse(missing.uiState.value.loading)
        assertNull(missing.uiState.value.selectedTraceId)
        assertFalse(noTraces.uiState.value.loading)
        assertNull(noTraces.uiState.value.selectedTraceId)
        assertNull(noTraces.uiState.value.selectedTrace)
        assertFalse(noTraces.uiState.value.selectedBranchHasTrace)
        assertNull(noTraces.copySelectedTrace())
        assertNull(noTraces.copyMessage(0))
    }

    @Test
    fun `selected tab is retained in ui state`() {
        val vm = vm(conversation = conversation(), traces = MutableStateFlow(emptyList()))

        vm.selectTab(TavernPromptConsoleTab.SENT_MESSAGES)

        assertEquals(TavernPromptConsoleTab.SENT_MESSAGES, vm.uiState.value.selectedTab)
    }

    private fun vm(
        conversation: Conversation?,
        traces: MutableStateFlow<List<PromptTraceReadResult>>,
        assistants: List<Assistant> = emptyList(),
    ) = vm(
        conversationId = conversation?.id ?: Uuid.random(),
        observeTraces = { traces },
        loadConversation = { conversation },
        settings = MutableStateFlow(Settings.dummy().copy(assistants = assistants)),
    )

    private fun vm(
        conversationId: Uuid,
        observeTraces: (Uuid) -> MutableStateFlow<List<PromptTraceReadResult>>,
        loadConversation: suspend (Uuid) -> Conversation?,
        settings: MutableStateFlow<Settings> = MutableStateFlow(Settings.dummy()),
        clearTraces: suspend (Uuid) -> Unit = {},
    ) = TavernPromptConsoleVM(
        conversationId = conversationId.toString(),
        observeTraces = observeTraces,
        loadConversation = loadConversation,
        settings = settings,
        clearTraces = clearTraces,
        stateScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        sharingStarted = SharingStarted.Eagerly,
    )

    private fun conversation(
        id: Uuid = Uuid.random(),
        assistantId: Uuid = Uuid.random(),
        title: String = "",
        messages: List<UIMessage> = emptyList(),
    ) = Conversation(
        id = id,
        assistantId = assistantId,
        title = title,
        messageNodes = messages.map(MessageNode::of),
    )

    private fun assistantMessage() = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text("reply")),
    )

    private fun available(created: Long, response: Uuid?, text: String): PromptTraceReadResult.Available {
        val traceId = Uuid.random()
        return PromptTraceReadResult.Available(
            PromptTraceRecord(
                traceId = traceId,
                payload = PromptTracePayload(
                    metadata = PromptTraceMetadata(
                        conversationId = Uuid.random(),
                        assistantId = Uuid.random(),
                        modelId = Uuid.random(),
                        isGroup = false,
                        providerStepIndex = 0,
                        responseMessageId = response,
                        startedAtEpochMs = created,
                    ),
                    sections = emptyList(),
                    injectionHits = emptyList(),
                    finalMessages = listOf(
                        PromptTraceMessage(
                            id = Uuid.random(),
                            index = 0,
                            role = MessageRole.SYSTEM,
                            parts = listOf(PromptTracePart.Text(text)),
                            characterCount = text.length,
                            approximateTokens = 1,
                        ),
                    ),
                ),
            ),
        )
    }

    private fun unavailable(created: Long, response: Uuid?) = PromptTraceReadResult.Unavailable(
        traceId = Uuid.random(),
        createdAtEpochMs = created,
        responseMessageId = response,
        status = PromptTraceStatus.COMPLETED,
        errorSummary = null,
    )
}
