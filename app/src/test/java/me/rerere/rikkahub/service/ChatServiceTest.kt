package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AuthorNote
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.ai.status.StatusVariableStore
import me.rerere.rikkahub.data.ai.status.JsonPatchOp
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.TurnTakingStrategy
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.runCommittedConversationCleanup
import me.rerere.rikkahub.service.group.GroupDirectorCommand
import me.rerere.rikkahub.service.group.GroupDirectorCommandContext
import me.rerere.rikkahub.service.group.GroupDirectorEngine
import me.rerere.rikkahub.service.group.GroupDirectorState
import me.rerere.rikkahub.service.group.GroupPlaybackState
import me.rerere.rikkahub.service.group.GroupRuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

class ChatServiceTest {
    private val groupMemberA = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val groupMemberB = Uuid.parse("00000000-0000-0000-0000-000000000002")

    @Test
    fun `ui field merge preserves runtime messages while applying requested conversation fields`() {
        val conversationId = Uuid.random()
        val runtime = UIMessage.assistant("runtime")
        val current = Conversation.ofId(conversationId, messages = listOf(runtime.toMessageNode()))
        val requested = current.copy(
            messageNodes = emptyList(),
            customSystemPrompt = "prompt",
            authorNote = AuthorNote(enabled = true, content = "note"),
            workspaceCwd = "C:/workspace",
        )

        val updated = mergeConversationUiFields(current, requested)

        assertEquals(listOf(runtime.id), updated.currentMessages.map { it.id })
        assertEquals("prompt", updated.customSystemPrompt)
        assertEquals("note", updated.authorNote?.content)
        assertEquals("C:/workspace", updated.workspaceCwd)
    }

    @Test
    fun `compressed history keeps runtime nodes appended after the compression baseline`() {
        val conversationId = Uuid.random()
        val old = UIMessage.user("old")
        val kept = UIMessage.assistant("kept")
        val runtime = UIMessage.user("runtime")
        val baseline = Conversation.ofId(
            conversationId,
            messages = listOf(old.toMessageNode(), kept.toMessageNode()),
        )
        val latest = baseline.copy(messageNodes = baseline.messageNodes + runtime.toMessageNode())

        val updated = applyCompressedConversation(
            baseline = baseline,
            latest = latest,
            compressedSummaries = listOf("summary"),
            keepRecentMessages = 1,
        )

        assertEquals(listOf("summary", "kept", "runtime"), updated.currentMessages.map { it.toText() })
    }

    @Test
    fun `stale initialization token cannot install over a newer live mutation`() {
        val conversationId = Uuid.random()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ConversationSession(conversationId, Conversation.ofId(conversationId), scope, onIdle = {})
        try {
            val token = session.beginInitialization()
            session.recordConversationMutation()

            assertFalse(session.canInstallInitialization(token))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `stale initialization marks the same session ready without replacing its live mutation`() {
        val conversationId = Uuid.random()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ConversationSession(conversationId, Conversation.ofId(conversationId), scope, onIdle = {})
        try {
            val token = session.beginInitialization()
            session.recordConversationMutation()

            assertEquals(
                InitializationInstallAction.MARK_READY,
                initializationInstallAction(session, token, sessionIsCurrent = true, isReady = false),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `superseded initializer does not mark the session ready`() {
        val conversationId = Uuid.random()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ConversationSession(conversationId, Conversation.ofId(conversationId), scope, onIdle = {})
        try {
            val firstInitializer = session.beginInitialization()
            session.beginInitialization()

            assertEquals(
                InitializationInstallAction.SKIP,
                initializationInstallAction(session, firstInitializer, sessionIsCurrent = true, isReady = false),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `superseded initializer does not overwrite variables installed by the winning loader`() {
        val conversationId = Uuid.random()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ConversationSession(conversationId, Conversation.ofId(conversationId), scope, onIdle = {})
        val variables = StatusVariableStore()
        try {
            val firstLoader = session.beginInitialization()
            val secondLoader = session.beginInitialization()
            val oldVariables = buildJsonObject { put("source", "first") }
            val currentVariables = buildJsonObject { put("source", "second") }

            applyInitializedStatusVariables(
                action = initializationInstallAction(session, secondLoader, sessionIsCurrent = true, isReady = false),
                store = variables,
                conversationId = conversationId,
                variables = currentVariables,
            )
            applyInitializedStatusVariables(
                action = initializationInstallAction(session, firstLoader, sessionIsCurrent = true, isReady = false),
                store = variables,
                conversationId = conversationId,
                variables = oldVariables,
            )

            assertEquals(currentVariables, variables.getValue(conversationId))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `same initializer mutation marks ready without overwriting live variables`() {
        val conversationId = Uuid.random()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ConversationSession(conversationId, Conversation.ofId(conversationId), scope, onIdle = {})
        val variables = StatusVariableStore()
        try {
            val loader = session.beginInitialization()
            val liveVariables = buildJsonObject { put("source", "live") }
            variables.set(conversationId, liveVariables)
            session.recordConversationMutation()

            applyInitializedStatusVariables(
                action = initializationInstallAction(session, loader, sessionIsCurrent = true, isReady = false),
                store = variables,
                conversationId = conversationId,
                variables = buildJsonObject { put("source", "stored") },
            )

            assertEquals(liveVariables, variables.getValue(conversationId))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `initialization candidate renders variables in a temporary store before publish`() = runBlocking {
        val conversationId = Uuid.random()
        val globalStore = StatusVariableStore()
        val initialVariables = buildJsonObject { put("hp", 10) }
        globalStore.init(conversationId, initialVariables)

        val candidate = renderInitializationStatusCandidate(conversationId, initialVariables) { temporaryStore ->
            temporaryStore.applyPatch(
                conversationId,
                listOf(JsonPatchOp(op = "replace", path = "/hp", value = JsonPrimitive(20))),
            )
            UIMessage.assistant("<StatusPlaceHolderImpl/>")
        }

        assertEquals("<StatusPlaceHolderImpl/>", candidate.value.toText())
        assertEquals("20", candidate.statusVariables["hp"]!!.jsonPrimitive.content)
        assertEquals(initialVariables, globalStore.getValue(conversationId))

        applyInitializedStatusVariables(
            action = InitializationInstallAction.INSTALL,
            store = globalStore,
            conversationId = conversationId,
            variables = candidate.statusVariables,
        )

        assertEquals(candidate.statusVariables, globalStore.getValue(conversationId))
    }

    @Test
    fun `assistant conversation deletion uses a stable session lock order`() {
        val firstId = Uuid.parse("00000000-0000-4000-8000-000000000003")
        val secondId = Uuid.parse("00000000-0000-4000-8000-000000000001")
        val thirdId = Uuid.parse("00000000-0000-4000-8000-000000000002")

        val ordered = orderedAssistantConversationDeletionIds(listOf(firstId, secondId, thirdId))

        assertEquals(listOf(secondId, thirdId, firstId), ordered)
    }

    @Test
    fun `assistant deletion attempts every ordered conversation after one deletion fails`() = runBlocking {
        val firstId = Uuid.parse("00000000-0000-4000-8000-000000000001")
        val secondId = Uuid.parse("00000000-0000-4000-8000-000000000002")
        val attempted = mutableListOf<Uuid>()

        val result = deleteAssistantConversationIds(
            conversationIds = listOf(firstId, secondId),
            delete = { conversationId ->
                attempted += conversationId
                conversationId != firstId
            },
        )

        assertFalse(result.succeeded)
        assertEquals(listOf(firstId, secondId), attempted)
    }

    @Test
    fun `assistant deletion records a thrown failure and continues later conversations`() = runBlocking {
        val firstId = Uuid.parse("00000000-0000-4000-8000-000000000001")
        val secondId = Uuid.parse("00000000-0000-4000-8000-000000000002")
        val attempted = mutableListOf<Uuid>()

        val result = deleteAssistantConversationIds(
            conversationIds = listOf(firstId, secondId),
            delete = { conversationId ->
                attempted += conversationId
                if (conversationId == firstId) error("delete failed")
                true
            },
        )

        assertEquals(listOf(firstId, secondId), attempted)
        assertEquals(setOf(firstId), result.failedConversationIds)
        assertTrue(result.errors.getValue(firstId).message!!.contains("delete failed"))
    }

    @Test
    fun `assistant deletion rethrows cancellation before later conversations`() = runBlocking {
        val firstId = Uuid.parse("00000000-0000-4000-8000-000000000001")
        val secondId = Uuid.parse("00000000-0000-4000-8000-000000000002")
        val attempted = mutableListOf<Uuid>()
        val cancellation = CancellationException("cancel assistant deletion")

        try {
            deleteAssistantConversationIds(listOf(firstId, secondId)) { conversationId ->
                attempted += conversationId
                if (conversationId == firstId) throw cancellation
                true
            }
            fail("CancellationException should escape assistant deletion")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }

        assertEquals(listOf(firstId), attempted)
    }

    @Test
    fun `assistant deletion treats a moved conversation as a safe skip`() {
        assertTrue(isAssistantBatchDeleteSuccess(ConversationDeleteResult.MOVED))
        assertTrue(isAssistantBatchDeleteSuccess(ConversationDeleteResult.DELETED))
        assertFalse(isAssistantBatchDeleteSuccess(ConversationDeleteResult.NOT_FOUND))
    }

    @Test
    fun `assistant deletion preserves a conversation moved after id collection`() = runBlocking {
        val conversationId = Uuid.random()
        val assistantA = Uuid.random()
        val assistantB = Uuid.random()
        var persisted = Conversation.ofId(conversationId).copy(assistantId = assistantA)
        val collectedIds = listOf(conversationId)

        persisted = persisted.copy(assistantId = assistantB)
        val result = deleteConversationWithExpectedAssistant(
            expectedAssistantId = assistantA,
            load = { persisted },
            delete = { candidate ->
                if (candidate.assistantId != assistantA || persisted.assistantId != assistantA) {
                    false
                } else {
                    persisted = candidate
                    true
                }
            },
        )

        assertEquals(listOf(conversationId), collectedIds)
        assertEquals(ConversationDeleteResult.MOVED, result)
        assertEquals(assistantB, persisted.assistantId)
    }

    @Test
    fun `assistant deletion gate allows moving out and refuses moving in`() {
        val deletingAssistant = Uuid.random()
        val otherAssistant = Uuid.random()
        val deleting = setOf(deletingAssistant)

        assertTrue(canMoveConversationToAssistant(otherAssistant, deleting))
        assertFalse(canMoveConversationToAssistant(deletingAssistant, deleting))
    }

    @Test
    fun `assistant deletion keeps its gate through final assistant cleanup`() = runBlocking {
        val assistantId = Uuid.random()
        val deleting = linkedSetOf<Uuid>()
        var finalized = false

        val result = runAssistantDeletionGate(
            assistantId = assistantId,
            deletingAssistantIds = deleting,
            deleteConversations = {
                assertTrue(assistantId in deleting)
                true
            },
            finalizeAssistantDeletion = {
                assertTrue(assistantId in deleting)
                finalized = true
            },
        )

        assertTrue(result.succeeded)
        assertTrue(finalized)
        assertFalse(assistantId in deleting)
    }

    @Test
    fun `assistant deletion releases its gate after final cleanup failure`() = runBlocking {
        val assistantId = Uuid.random()
        val deleting = linkedSetOf<Uuid>()

        val result = runAssistantDeletionGate(
            assistantId = assistantId,
            deletingAssistantIds = deleting,
            deleteConversations = { true },
            finalizeAssistantDeletion = { error("settings deletion failed") },
        )

        assertFalse(result.succeeded)
        assertTrue(result.finalizeError!!.message!!.contains("settings deletion failed"))
        assertFalse(assistantId in deleting)
    }

    @Test
    fun `restore rejects a deleting or missing assistant`() {
        val assistantId = Uuid.random()

        assertFalse(canRestoreConversation(assistantExists = false, assistantIsDeleting = false))
        assertFalse(canRestoreConversation(assistantExists = true, assistantIsDeleting = true))
        assertTrue(canRestoreConversation(assistantExists = true, assistantIsDeleting = false))
    }

    @Test
    fun `committed deletion keeps later cleanup steps after FTS failure`() = runBlocking {
        var filesCleaned = false
        var statusCleared = false

        val failures = runCommittedConversationCleanup(
            deleteFts = { error("fts unavailable") },
            deleteFiles = { filesCleaned = true },
            clearStatus = { statusCleared = true },
        )

        assertEquals(1, failures.size)
        assertTrue(filesCleaned)
        assertTrue(statusCleared)
    }

    @Test
    fun `initialization publishes variables only after persistence and live publish succeed`() = runBlocking {
        val conversationId = Uuid.random()
        val store = StatusVariableStore()
        val before = buildJsonObject { put("hp", 10) }
        val candidate = buildJsonObject { put("hp", 20) }
        store.init(conversationId, before)

        try {
            persistInitializationThenPublishStatusVariables(
                persistAndPublish = { error("disk unavailable") },
                publishStatusVariables = { store.init(conversationId, candidate) },
            )
            fail("persistence failure should stop initialization")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("disk unavailable"))
        }

        assertEquals(before, store.getValue(conversationId))
    }

    @Test
    fun `initialization does not publish variables when persistence declines installation`() = runBlocking {
        var published = false

        val installed = persistInitializationThenPublishStatusVariables(
            persistAndPublish = { false },
            publishStatusVariables = { published = true },
        )

        assertFalse(installed)
        assertFalse(published)
    }

    @Test
    fun `deleted non-ready conversation cannot be recreated by a normal save`() {
        assertFalse(canPersistConversation(exists = false, isReady = false, allowCreate = false))
        assertTrue(canPersistConversation(exists = false, isReady = true, allowCreate = false))
        assertTrue(canPersistConversation(exists = false, isReady = false, allowCreate = true))
    }

    @Test
    fun `moderator snapshot retains persisted queue order when assistant order differs`() {
        assertEquals(
            listOf(groupMemberB, groupMemberA),
            orderedModeratorEligibleMemberIds(
                persistedQueue = listOf(groupMemberB, groupMemberA),
                eligibleMemberIds = listOf(groupMemberA, groupMemberB),
            ),
        )
    }

    @Test
    fun `conversation mutation lock serializes normal and runtime writes`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = createGroupSession(scope)
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()

        try {
            val normalSave = async(Dispatchers.Default) {
                session.withConversationMutationLock {
                    val current = active.incrementAndGet()
                    maximumActive.accumulateAndGet(current, ::maxOf)
                    delay(10)
                    active.decrementAndGet()
                }
            }
            val runtimeCreate = async(Dispatchers.Default) {
                session.withRuntimeMessageLock {
                    val current = active.incrementAndGet()
                    maximumActive.accumulateAndGet(current, ::maxOf)
                    delay(10)
                    active.decrementAndGet()
                }
            }
            awaitAll(normalSave, runtimeCreate)
        } finally {
            scope.cancel()
        }

        assertEquals(1, maximumActive.get())
    }

    @Test
    fun `cleanup waits for an admitted runtime mutation then rejects a late mutation`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = createGroupSession(scope)
        val entered = CompletableDeferred<Unit>()
        val allowPersist = CompletableDeferred<Unit>()
        try {
            val runtimeMutation = async(Dispatchers.Default) {
                session.withRuntimeMessageLock {
                    entered.complete(Unit)
                    allowPersist.await()
                }
            }
            entered.await()
            val cleanup = async(Dispatchers.Default) { session.closeForCleanup() }
            assertNull(withTimeoutOrNull(50) { cleanup.await(); true })

            allowPersist.complete(Unit)
            runtimeMutation.await()
            cleanup.await()

            try {
                session.withRuntimeMessageLock { fail("closed session accepted a runtime mutation") }
            } catch (_: IllegalStateException) {
                // Expected: cleanup closed the session before the second mutation could enter.
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `normal full save and runtime create cannot overwrite each other`() = runBlocking {
        val conversationId = Uuid.random()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ConversationSession(
            id = conversationId,
            initial = Conversation.ofId(conversationId),
            scope = scope,
            onIdle = {},
        )
        val runtimeEnteredPersist = CompletableDeferred<Unit>()
        val allowRuntimePersist = CompletableDeferred<Unit>()
        val normalRead = CompletableDeferred<Conversation>()
        val allowNormalSave = CompletableDeferred<Unit>()
        val store = TavernRuntimeMessageMutationStore(object : TavernRuntimeMessagePersistenceAdapter {
            override fun isReady(conversationId: Uuid): Boolean = true

            override suspend fun <T> mutate(conversationId: Uuid, block: suspend () -> T): T =
                session.withRuntimeMessageLock(block)

            override fun currentConversation(conversationId: Uuid): Conversation = session.state.value

            override suspend fun persist(conversationId: Uuid, conversation: Conversation) {
                runtimeEnteredPersist.complete(Unit)
                allowRuntimePersist.await()
                session.state.value = conversation
            }

            override suspend fun persistAfterMessageRemoval(
                conversationId: Uuid,
                before: Conversation,
                after: Conversation,
            ) = persist(conversationId, after)

            override fun emit(event: TavernRuntimeMessageMutationEvent) = Unit
        })

        try {
            val runtimeCreate = async(Dispatchers.Default) {
                store.create(conversationId, MessageRole.USER, "runtime")
            }
            runtimeEnteredPersist.await()
            val normalSave = async(Dispatchers.Default) {
                session.withConversationMutationLock {
                    val snapshot = session.state.value
                    normalRead.complete(snapshot)
                    allowNormalSave.await()
                    session.state.value = snapshot.copy(
                        messageNodes = snapshot.messageNodes + UIMessage.user("normal").toMessageNode(),
                    )
                }
            }

            assertNull(withTimeoutOrNull(250) { normalRead.await() })
            allowRuntimePersist.complete(Unit)
            runtimeCreate.await()
            normalRead.await()
            allowNormalSave.complete(Unit)
            normalSave.await()
        } finally {
            scope.cancel()
        }

        assertEquals(setOf("runtime", "normal"), session.state.value.currentMessages.map { it.toText() }.toSet())
    }

    @Test
    fun `stream chunk merge and runtime mutation share one conversation lock`() = runBlocking {
        val conversationId = Uuid.random()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ConversationSession(
            id = conversationId,
            initial = Conversation.ofId(conversationId),
            scope = scope,
            onIdle = {},
        )
        val streamRead = CompletableDeferred<Unit>()
        val allowStreamCommit = CompletableDeferred<Unit>()
        val runtimeRead = CompletableDeferred<Conversation>()

        try {
            val streamChunk = async(Dispatchers.Default) {
                session.withGroupDirectorLock {
                    session.withConversationMutationLock {
                        val snapshot = session.state.value
                        streamRead.complete(Unit)
                        allowStreamCommit.await()
                        session.state.value = snapshot.copy(
                            messageNodes = snapshot.messageNodes + UIMessage.assistant("chunk").toMessageNode(),
                        )
                    }
                }
            }
            streamRead.await()
            val runtimeMutation = async(Dispatchers.Default) {
                session.withRuntimeMessageLock {
                    val snapshot = session.state.value
                    runtimeRead.complete(snapshot)
                    session.state.value = snapshot.copy(
                        messageNodes = snapshot.messageNodes + UIMessage.user("runtime").toMessageNode(),
                    )
                }
            }

            assertNull(withTimeoutOrNull(250) { runtimeRead.await() })
            allowStreamCommit.complete(Unit)
            streamChunk.await()
            runtimeRead.await()
            runtimeMutation.await()
        } finally {
            scope.cancel()
        }

        assertEquals(setOf("chunk", "runtime"), session.state.value.currentMessages.map { it.toText() }.toSet())
    }

    @Test
    fun `production mutateConversation waits before deriving a tool approval update`() = runBlocking {
        val conversationId = Uuid.random()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ConversationSession(
            id = conversationId,
            initial = Conversation.ofId(conversationId),
            scope = scope,
            onIdle = {},
        )
        val runtimeEntered = CompletableDeferred<Unit>()
        val allowRuntimeCommit = CompletableDeferred<Unit>()
        val approvalRead = CompletableDeferred<Conversation>()

        try {
            val runtimeCreate = async(Dispatchers.Default) {
                session.withRuntimeMessageLock {
                    runtimeEntered.complete(Unit)
                    allowRuntimeCommit.await()
                    session.state.value = session.state.value.copy(
                        messageNodes = session.state.value.messageNodes + UIMessage.user("runtime").toMessageNode(),
                    )
                }
            }
            runtimeEntered.await()
            val toolApproval = async(Dispatchers.Default) {
                mutateConversation(session) { current ->
                    approvalRead.complete(current)
                    session.state.value = current.copy(
                        messageNodes = current.messageNodes + UIMessage.assistant("approved").toMessageNode(),
                    )
                }
            }

            assertNull(withTimeoutOrNull(250) { approvalRead.await() })
            allowRuntimeCommit.complete(Unit)
            runtimeCreate.await()
            approvalRead.await()
            toolApproval.await()
        } finally {
            scope.cancel()
        }

        assertEquals(setOf("runtime", "approved"), session.state.value.currentMessages.map { it.toText() }.toSet())
    }

    @Test
    fun `session director lock serializes state commits`() = runBlocking {
        val conversationId = Uuid.parse("00000000-0000-0000-0000-000000000020")
        val assistantId = Uuid.parse("00000000-0000-0000-0000-000000000010")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ConversationSession(
            id = conversationId,
            initial = Conversation(
                id = conversationId,
                assistantId = assistantId,
                messageNodes = emptyList(),
            ),
            scope = scope,
            onIdle = {},
        )
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()

        try {
            List(8) {
                async(Dispatchers.Default) {
                    session.withGroupDirectorLock {
                        val current = active.incrementAndGet()
                        maximumActive.accumulateAndGet(current, ::maxOf)
                        delay(10)
                        active.decrementAndGet()
                    }
                }
            }.awaitAll()
        } finally {
            scope.cancel()
        }

        assertEquals(1, maximumActive.get())
    }

    @Test
    fun `completed superseded job does not clear current generation`() = runBlocking {
        val conversationId = Uuid.parse("00000000-0000-0000-0000-000000000020")
        val assistantId = Uuid.parse("00000000-0000-0000-0000-000000000010")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ConversationSession(
            id = conversationId,
            initial = Conversation(
                id = conversationId,
                assistantId = assistantId,
                messageNodes = emptyList(),
            ),
            scope = scope,
            onIdle = {},
        )
        val started = CompletableDeferred<Unit>()
        val allowCompletion = CompletableDeferred<Unit>()
        val oldJob = scope.launch {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    allowCompletion.await()
                }
            }
        }
        started.await()
        session.setJob(oldJob)
        val currentJob = Job()

        try {
            session.setJob(currentJob)
            allowCompletion.complete(Unit)
            oldJob.join()

            assertSame(currentJob, session.getJob())
        } finally {
            currentJob.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `superseded handoff does not clear successor reply phase`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = createGroupSession(scope)
        val oldJob = Job()
        val successorJob = Job()

        try {
            session.setJob(oldJob)
            session.withGroupDirectorLock {
                session.markGroupReplyStartedLocked(oldJob)
            }
            session.setJob(successorJob)
            session.withGroupDirectorLock {
                session.markGroupReplyStartedLocked(successorJob)
            }

            session.completeGroupReplyHandoff(oldJob) {
                GroupGenerationHandoffResult(Unit, shouldContinue = false)
            }

            session.withGroupDirectorLock {
                assertSame(successorJob, session.getJob())
                assertEquals(true, session.isGroupReplyActiveLocked())
            }
        } finally {
            oldJob.cancel()
            successorJob.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `pause command in completion window releases job with no continuation`() = runBlocking {
        val engine = GroupDirectorEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = createGroupSession(scope)
        val generationJob = Job()
        session.setJob(generationJob)

        try {
            session.withGroupDirectorLock {
                session.markGroupReplyStartedLocked(generationJob)
                val current = session.state.value
                val paused = engine.reduce(
                    state = current.groupRuntimeState.director,
                    command = GroupDirectorCommand.PauseAfterCurrent,
                    context = GroupDirectorCommandContext(
                        generationActive = session.isGroupReplyActiveLocked(),
                        orderedEnabledMemberIds = listOf(groupMemberA, groupMemberB),
                    ),
                ).state
                session.state.value = current.copy(
                    groupRuntimeState = current.groupRuntimeState.copy(director = paused)
                )
            }

            val handoff = session.completeGroupReplyHandoff(generationJob) {
                val current = session.state.value
                val director = engine.afterReply(current.groupRuntimeState.director, groupMemberA)
                val updated = current.copy(
                    groupRuntimeState = current.groupRuntimeState.copy(director = director)
                )
                session.state.value = updated
                GroupGenerationHandoffResult(
                    value = updated,
                    shouldContinue = engine.shouldContinueAfterReply(
                        state = director,
                        effectiveStrategy = TurnTakingStrategy.AUTO_ROUND_ROBIN,
                        isAddressedTurn = false,
                        alreadySent = 1,
                        configuredLimit = 3,
                    ),
                )
            }

            assertEquals(GroupPlaybackState.PAUSED, handoff.value.groupRuntimeState.director.playbackState)
            assertEquals(false, handoff.shouldContinue)
            assertEquals(null, session.getJob())
        } finally {
            generationJob.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `queued member in completion window is consumed exactly once`() = runBlocking {
        val engine = GroupDirectorEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = createGroupSession(
            scope = scope,
            director = GroupDirectorState(playbackState = GroupPlaybackState.PAUSE_AFTER_CURRENT),
        )
        val generationJob = Job()
        session.setJob(generationJob)

        try {
            session.withGroupDirectorLock {
                session.markGroupReplyStartedLocked(generationJob)
                val current = session.state.value
                val queued = engine.reduce(
                    state = current.groupRuntimeState.director,
                    command = GroupDirectorCommand.QueueMemberOnce(groupMemberB),
                    context = GroupDirectorCommandContext(
                        generationActive = session.isGroupReplyActiveLocked(),
                        orderedEnabledMemberIds = listOf(groupMemberA, groupMemberB),
                    ),
                ).state
                session.state.value = current.copy(
                    groupRuntimeState = current.groupRuntimeState.copy(director = queued)
                )
            }

            val firstHandoff = completeDirectorReply(
                session = session,
                generationJob = generationJob,
                engine = engine,
                speakerId = groupMemberA,
            )
            assertEquals(true, firstHandoff.shouldContinue)
            assertSame(generationJob, session.getJob())

            session.withGroupDirectorLock {
                val current = session.state.value
                val selected = engine.applyCandidate(
                    state = current.groupRuntimeState.director,
                    normalCandidateId = groupMemberA,
                    orderedCandidateMemberIds = listOf(groupMemberA, groupMemberB),
                )
                assertEquals(groupMemberB, selected.memberId)
                session.state.value = current.copy(
                    groupRuntimeState = current.groupRuntimeState.copy(director = selected.state)
                )
                session.markGroupReplyStartedLocked(generationJob)
            }

            val secondHandoff = completeDirectorReply(
                session = session,
                generationJob = generationJob,
                engine = engine,
                speakerId = groupMemberB,
            )
            assertEquals(false, secondHandoff.shouldContinue)
            assertEquals(null, secondHandoff.value.groupRuntimeState.director.oneShotNextMemberId)
            assertEquals(GroupPlaybackState.PAUSED, secondHandoff.value.groupRuntimeState.director.playbackState)
            assertEquals(null, session.getJob())
        } finally {
            generationJob.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `continue one round in completion window retains current worker`() = runBlocking {
        val engine = GroupDirectorEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = createGroupSession(
            scope = scope,
            director = GroupDirectorState(playbackState = GroupPlaybackState.PAUSE_AFTER_CURRENT),
        )
        val generationJob = Job()
        session.setJob(generationJob)

        try {
            session.withGroupDirectorLock {
                session.markGroupReplyStartedLocked(generationJob)
                val current = session.state.value
                val continued = engine.reduce(
                    state = current.groupRuntimeState.director,
                    command = GroupDirectorCommand.ContinueOneRound,
                    context = GroupDirectorCommandContext(
                        generationActive = session.isGroupReplyActiveLocked(),
                        orderedEnabledMemberIds = listOf(groupMemberA, groupMemberB),
                    ),
                ).state
                session.state.value = current.copy(
                    groupRuntimeState = current.groupRuntimeState.copy(director = continued)
                )
            }

            val handoff = completeDirectorReply(
                session = session,
                generationJob = generationJob,
                engine = engine,
                speakerId = groupMemberA,
                configuredLimit = 3,
            )

            assertEquals(true, handoff.shouldContinue)
            assertEquals(listOf(groupMemberB), handoff.value.groupRuntimeState.director.oneRoundRemainingMemberIds)
            assertSame(generationJob, session.getJob())
        } finally {
            generationJob.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `manual mode in continuation window blocks an already selected continuation`() = runBlocking {
        val engine = GroupDirectorEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = createGroupSession(scope)
        val generationJob = Job()
        session.setJob(generationJob)

        try {
            session.withGroupDirectorLock {
                session.markGroupReplyStartedLocked(generationJob)
            }
            val handoff = completeDirectorReply(
                session = session,
                generationJob = generationJob,
                engine = engine,
                speakerId = groupMemberA,
                configuredLimit = 3,
            )
            assertEquals(true, handoff.shouldContinue)

            val selection = session.withGroupDirectorLock {
                val current = session.state.value
                val manual = engine.reduce(
                    state = current.groupRuntimeState.director,
                    command = GroupDirectorCommand.SetMode(TurnTakingStrategy.MANUAL),
                    context = GroupDirectorCommandContext(
                        generationActive = session.isGroupReplyActiveLocked(),
                        orderedEnabledMemberIds = listOf(groupMemberA, groupMemberB),
                    ),
                ).state
                val selected = engine.applyCandidate(
                    state = manual,
                    normalCandidateId = groupMemberB,
                    orderedCandidateMemberIds = listOf(groupMemberA, groupMemberB),
                )
                session.state.value = current.copy(
                    groupRuntimeState = current.groupRuntimeState.copy(director = selected.state)
                )
                if (selected.memberId == null) {
                    session.releaseGroupGenerationLocked(generationJob)
                }
                selected
            }

            assertEquals(null, selection.memberId)
            assertEquals(GroupPlaybackState.PAUSED, selection.state.playbackState)
            assertEquals(null, session.getJob())
        } finally {
            generationJob.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `retained round explicitly resumed in manual mode selects and completes remainder`() {
        val engine = GroupDirectorEngine()
        val switchedToManual = engine.reduce(
            state = GroupDirectorState(
                playbackState = GroupPlaybackState.RUNNING,
                oneRoundActive = true,
                oneRoundRemainingMemberIds = listOf(groupMemberA, groupMemberB),
            ),
            command = GroupDirectorCommand.SetMode(TurnTakingStrategy.MANUAL),
            context = GroupDirectorCommandContext(
                generationActive = true,
                orderedEnabledMemberIds = listOf(groupMemberA, groupMemberB),
            ),
        ).state
        val pausedWithRemainder = engine.afterReply(switchedToManual, groupMemberA)
        val resumed = engine.reduce(
            state = pausedWithRemainder,
            command = GroupDirectorCommand.ContinueOneRound,
            context = GroupDirectorCommandContext(
                generationActive = false,
                orderedEnabledMemberIds = listOf(groupMemberA, groupMemberB),
            ),
        )

        val normalSelection = resolveLocalGroupTurnSelection(
            director = resumed.state,
            effectiveStrategy = TurnTakingStrategy.MANUAL,
            persistedQueue = listOf(groupMemberA, groupMemberB),
            persistedIndex = 0,
            activeMemberId = groupMemberA,
            orderedEligibleMemberIds = listOf(groupMemberB),
        )
        val selected = engine.applyCandidate(
            state = resumed.state,
            normalCandidateId = normalSelection?.memberId,
            orderedCandidateMemberIds = normalSelection?.queue ?: listOf(groupMemberB),
        )
        val completed = engine.afterReply(selected.state, groupMemberB)

        assertEquals(GroupPlaybackState.PAUSED, pausedWithRemainder.playbackState)
        assertEquals(listOf(groupMemberB), pausedWithRemainder.oneRoundRemainingMemberIds)
        assertEquals(true, resumed.shouldStartGeneration)
        assertEquals(GroupPlaybackState.RUNNING, resumed.state.playbackState)
        assertEquals(groupMemberB, selected.memberId)
        assertEquals(false, completed.oneRoundActive)
        assertEquals(emptyList<Uuid>(), completed.oneRoundRemainingMemberIds)
        assertEquals(GroupPlaybackState.PAUSED, completed.playbackState)
    }

    @Test
    fun `ordinary manual mode still has no local automatic candidate`() {
        val selection = resolveLocalGroupTurnSelection(
            director = GroupDirectorState(
                modeOverride = TurnTakingStrategy.MANUAL,
                playbackState = GroupPlaybackState.RUNNING,
            ),
            effectiveStrategy = TurnTakingStrategy.MANUAL,
            persistedQueue = listOf(groupMemberA, groupMemberB),
            persistedIndex = 0,
            activeMemberId = groupMemberA,
            orderedEligibleMemberIds = listOf(groupMemberA, groupMemberB),
        )

        assertEquals(null, selection)
    }

    @Test
    fun `pending pause cancellation persists paused state and releases generation`() = runBlocking {
        val engine = GroupDirectorEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = createGroupSession(
            scope = scope,
            director = GroupDirectorState(playbackState = GroupPlaybackState.PAUSE_AFTER_CURRENT),
        )
        val started = CompletableDeferred<Unit>()
        val persisted = CompletableDeferred<Unit>()
        lateinit var generationJob: Job
        generationJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                normalizeCancelledGroupGeneration(session, generationJob, engine) {
                    session.state.value = it
                    persisted.complete(Unit)
                }
            }
        }
        session.setJob(generationJob)
        session.withGroupDirectorLock {
            session.markGroupReplyStartedLocked(generationJob)
        }

        try {
            generationJob.start()
            started.await()
            generationJob.cancelAndJoin()

            assertEquals(true, persisted.isCompleted)
            assertEquals(true, generationJob.isCancelled)
            assertEquals(GroupPlaybackState.PAUSED, session.state.value.groupRuntimeState.director.playbackState)
            assertEquals(null, session.getJob())
            session.withGroupDirectorLock {
                assertEquals(false, session.isGroupReplyActiveLocked())
            }
        } finally {
            generationJob.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `superseded cancellation preserves successor director and ownership`() = runBlocking {
        val engine = GroupDirectorEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = createGroupSession(scope)
        val oldJob = Job()
        val successorJob = Job()
        val successorDirector = GroupDirectorState(
            playbackState = GroupPlaybackState.RUNNING,
            oneShotNextMemberId = groupMemberB,
            skipNextRequested = true,
        )
        val persistCount = AtomicInteger()

        try {
            session.setJob(oldJob)
            session.withGroupDirectorLock {
                session.markGroupReplyStartedLocked(oldJob)
            }
            session.setJob(successorJob)
            session.withGroupDirectorLock {
                val current = session.state.value
                session.state.value = current.copy(
                    groupRuntimeState = current.groupRuntimeState.copy(director = successorDirector)
                )
                session.markGroupReplyStartedLocked(successorJob)
            }

            val result = normalizeCancelledGroupGeneration(session, oldJob, engine) {
                persistCount.incrementAndGet()
                session.state.value = it
            }

            assertEquals(successorDirector, result.groupRuntimeState.director)
            assertEquals(successorDirector, session.state.value.groupRuntimeState.director)
            assertEquals(0, persistCount.get())
            assertSame(successorJob, session.getJob())
            session.withGroupDirectorLock {
                assertEquals(true, session.isGroupReplyActiveLocked())
            }
        } finally {
            oldJob.cancel()
            successorJob.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `superseded cancellation during split ownership clears only stale reply marker`() = runBlocking {
        val engine = GroupDirectorEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = createGroupSession(scope)
        val oldJob = Job()
        val successorJob = Job()
        val successorDirector = GroupDirectorState(
            playbackState = GroupPlaybackState.RUNNING,
            oneShotNextMemberId = groupMemberB,
            skipNextRequested = true,
        )
        val persistCount = AtomicInteger()

        try {
            session.setJob(oldJob)
            session.withGroupDirectorLock {
                session.markGroupReplyStartedLocked(oldJob)
            }
            session.setJob(successorJob)
            session.withGroupDirectorLock {
                val current = session.state.value
                session.state.value = current.copy(
                    groupRuntimeState = current.groupRuntimeState.copy(director = successorDirector)
                )
            }

            val firstResult = normalizeCancelledGroupGeneration(session, oldJob, engine) {
                persistCount.incrementAndGet()
                session.state.value = it
            }

            assertEquals(successorDirector, firstResult.groupRuntimeState.director)
            assertEquals(successorDirector, session.state.value.groupRuntimeState.director)
            assertEquals(0, persistCount.get())
            assertSame(successorJob, session.getJob())

            session.withGroupDirectorLock {
                assertEquals(false, session.isGroupReplyActiveLocked())
                session.releaseGroupGenerationLocked(successorJob)
            }
            val secondResult = normalizeCancelledGroupGeneration(session, oldJob, engine) {
                persistCount.incrementAndGet()
                session.state.value = it
            }

            assertEquals(successorDirector, secondResult.groupRuntimeState.director)
            assertEquals(successorDirector, session.state.value.groupRuntimeState.director)
            assertEquals(0, persistCount.get())
            assertEquals(null, session.getJob())
        } finally {
            oldJob.cancel()
            successorJob.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `one round cancellation persists paused state with remainder`() = runBlocking {
        val engine = GroupDirectorEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = createGroupSession(
            scope = scope,
            director = GroupDirectorState(
                playbackState = GroupPlaybackState.RUNNING,
                oneRoundActive = true,
                oneRoundRemainingMemberIds = listOf(groupMemberB),
            ),
        )
        val generationJob = Job()
        session.setJob(generationJob)
        session.withGroupDirectorLock {
            session.markGroupReplyStartedLocked(generationJob)
        }

        try {
            normalizeCancelledGroupGeneration(session, generationJob, engine) {
                session.state.value = it
            }

            val director = session.state.value.groupRuntimeState.director
            assertEquals(GroupPlaybackState.PAUSED, director.playbackState)
            assertEquals(true, director.oneRoundActive)
            assertEquals(listOf(groupMemberB), director.oneRoundRemainingMemberIds)
            assertEquals(null, session.getJob())
        } finally {
            generationJob.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `moderator selection cancellation normalizes before a member becomes active`() = runBlocking {
        val engine = GroupDirectorEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = createGroupSession(
            scope = scope,
            director = GroupDirectorState(
                playbackState = GroupPlaybackState.RUNNING,
                oneRoundActive = true,
                oneRoundRemainingMemberIds = listOf(groupMemberA, groupMemberB),
            ),
        )
        val generationJob = Job()
        session.setJob(generationJob)

        try {
            session.withGroupDirectorLock {
                assertEquals(false, session.isGroupReplyActiveLocked())
            }
            normalizeCancelledGroupGeneration(session, generationJob, engine) {
                session.state.value = it
            }

            val director = session.state.value.groupRuntimeState.director
            assertEquals(GroupPlaybackState.PAUSED, director.playbackState)
            assertEquals(listOf(groupMemberA, groupMemberB), director.oneRoundRemainingMemberIds)
            assertEquals(null, session.getJob())
        } finally {
            generationJob.cancel()
            scope.cancel()
        }
    }

    private fun createGroupSession(
        scope: CoroutineScope,
        director: GroupDirectorState = GroupDirectorState(),
    ): ConversationSession {
        val conversationId = Uuid.parse("00000000-0000-0000-0000-000000000020")
        val assistantId = Uuid.parse("00000000-0000-0000-0000-000000000010")
        return ConversationSession(
            id = conversationId,
            initial = Conversation(
                id = conversationId,
                assistantId = assistantId,
                messageNodes = emptyList(),
                groupRuntimeState = GroupRuntimeState(director = director),
            ),
            scope = scope,
            onIdle = {},
        )
    }

    private suspend fun completeDirectorReply(
        session: ConversationSession,
        generationJob: Job,
        engine: GroupDirectorEngine,
        speakerId: Uuid,
        configuredLimit: Int = 1,
    ): GroupGenerationHandoffResult<Conversation> = session.completeGroupReplyHandoff(generationJob) {
        val current = session.state.value
        val director = engine.afterReply(current.groupRuntimeState.director, speakerId)
        val updated = current.copy(
            groupRuntimeState = current.groupRuntimeState.copy(director = director)
        )
        session.state.value = updated
        GroupGenerationHandoffResult(
            value = updated,
            shouldContinue = engine.shouldContinueAfterReply(
                state = director,
                effectiveStrategy = TurnTakingStrategy.AUTO_ROUND_ROBIN,
                isAddressedTurn = false,
                alreadySent = 1,
                configuredLimit = configuredLimit,
            ),
        )
    }

    @Test
    fun `background generation params include model custom request configuration`() {
        val headers = listOf(CustomHeader(name = "X-Gateway-Token", value = "test-token"))
        val bodies = listOf(CustomBody(key = "gateway_mode", value = JsonPrimitive("strict")))
        val model = Model(
            modelId = "custom-chat-model",
            customHeaders = headers,
            customBodies = bodies,
        )

        val params = backgroundTextGenerationParams(model)

        assertEquals(model, params.model)
        assertEquals(ReasoningLevel.AUTO, params.reasoningLevel)
        assertEquals(headers, params.customHeaders)
        assertEquals(bodies, params.customBody)
    }

    @Test
    fun `external web search is disabled when assistant preference is disabled`() {
        val assistant = Assistant(enableWebSearch = false)
        val model = Model()

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `external web search is enabled when assistant preference is enabled`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model()

        assertTrue(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `built-in search suppresses enabled external web search`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model(tools = setOf(BuiltInTools.Search))

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `built-in search remains exclusive when external web search is disabled`() {
        val assistant = Assistant(enableWebSearch = false)
        val model = Model(tools = setOf(BuiltInTools.Search))

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `unrelated built-in tools do not suppress external web search`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model(tools = setOf(BuiltInTools.UrlContext))

        assertTrue(shouldUseExternalWebSearch(assistant, model))

    }

    @Test
    fun `preset message macros are expanded without losing html render mode`() {
        val model = Model(modelId = "test-model", displayName = "Test Model")
        val assistant = Assistant(name = "Alice")
        val settings = Settings(
            displaySetting = DisplaySetting(userNickname = "Bob"),
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
        )
        val messages = listOf(
            UIMessage.assistantHtml("<main>{{user}} meets {{char}} on {{model_name}}</main>")
        )

        val rendered = renderPresetMessageMacros(
            messages = messages,
            settings = settings,
            assistant = assistant,
            model = model,
        )

        val text = rendered.single().parts.single() as UIMessagePart.Text
        assertEquals("<main>Bob meets Alice on Test Model</main>", text.text)
        assertEquals(UIMessagePart.RenderMode.HTML, text.renderMode)
    }

    @Test
    fun `generation start keeps group speaker state from resolved conversation`() {
        val assistantId = Uuid.parse("00000000-0000-0000-0000-000000000010")
        val memberA = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val memberB = Uuid.parse("00000000-0000-0000-0000-000000000002")
        val initial = Conversation(
            assistantId = assistantId,
            messageNodes = emptyList(),
            chatSuggestions = listOf("stale suggestion"),
        )
        val resolved = initial.copy(
            activeGroupMemberId = memberA,
            groupMemberQueue = listOf(memberA, memberB),
            groupMemberQueueIndex = 1,
        )

        val result = conversationAtGenerationStart(
            initialConversation = initial,
            resolvedConversation = resolved,
        )

        assertEquals(emptyList<String>(), result.chatSuggestions)
        assertEquals(memberA, result.activeGroupMemberId)
        assertEquals(listOf(memberA, memberB), result.groupMemberQueue)
        assertEquals(1, result.groupMemberQueueIndex)
    }

    @Test
    fun `generation start keeps resolved director state`() {
        val assistantId = Uuid.parse("00000000-0000-0000-0000-000000000010")
        val memberId = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val initial = Conversation(assistantId = assistantId, messageNodes = emptyList())
        val resolved = initial.copy(
            groupRuntimeState = GroupRuntimeState(
                director = GroupDirectorState(
                    playbackState = GroupPlaybackState.PAUSE_AFTER_CURRENT,
                    oneShotNextMemberId = memberId,
                )
            )
        )

        val result = conversationAtGenerationStart(initial, resolved)

        assertEquals(resolved.groupRuntimeState.director, result.groupRuntimeState.director)
    }
}
