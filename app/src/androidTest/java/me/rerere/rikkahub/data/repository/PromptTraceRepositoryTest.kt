package me.rerere.rikkahub.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.ai.trace.PromptTraceMetadata
import me.rerere.rikkahub.data.ai.trace.PromptTracePart
import me.rerere.rikkahub.data.ai.trace.PromptTracePayload
import me.rerere.rikkahub.data.ai.trace.PromptTraceReadResult
import me.rerere.rikkahub.data.ai.trace.PromptTraceSection
import me.rerere.rikkahub.data.ai.trace.PromptTraceSectionKind
import me.rerere.rikkahub.data.ai.trace.PromptTraceStatus
import me.rerere.rikkahub.data.ai.trace.PromptTraceMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.PromptTraceDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.PromptTraceEntity
import me.rerere.rikkahub.ui.pages.tavern.console.selectDefaultTraceId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class PromptTraceRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: PromptTraceDAO
    private lateinit var repository: PromptTraceRepository
    private val json = Json { encodeDefaults = true }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.promptTraceDao()
        repository = PromptTraceRepository(dao, json)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun validAndMalformedPayloadsReturnTypedResults() = runBlocking {
        val conversationId = insertConversation(Uuid.random().toString())
        val validId = Uuid.random()
        val malformedId = Uuid.random()
        dao.insert(
            traceEntity(
                id = validId.toString(),
                conversationId = conversationId,
                payloadJson = json.encodeToString(payload(conversationId)),
            ),
        )
        dao.insert(
            traceEntity(
                id = malformedId.toString(),
                conversationId = conversationId,
                payloadJson = "{broken",
                createdAt = 2L,
            ),
        )

        val results = repository.observeConversation(Uuid.parse(conversationId)).first()

        assertTrue(results.any { it is PromptTraceReadResult.Available && it.traceId == validId })
        assertTrue(results.any { it is PromptTraceReadResult.Unavailable && it.traceId == malformedId })
    }

    @Test
    fun malformedTraceUuidDoesNotInterruptConversationObservation() = runBlocking {
        val conversationId = insertConversation(Uuid.random().toString())
        val validId = Uuid.random()
        dao.insert(traceEntity(validId.toString(), conversationId, createdAt = 1L))
        dao.insert(traceEntity("not-a-uuid", conversationId, createdAt = 2L))

        val results = repository.observeConversation(Uuid.parse(conversationId)).first()

        assertEquals(listOf(validId), results.map { it.traceId })
    }

    @Test
    fun lifecycleColumnsOverridePayloadMetadataAndActualTokens() = runBlocking {
        val conversationId = insertConversation(Uuid.random().toString())
        val traceId = Uuid.random()
        val responseId = Uuid.random()
        val original = payload(conversationId).copy(
            metadata = payload(conversationId).metadata.copy(
                status = PromptTraceStatus.FAILED,
                actualPromptTokens = 999,
                responseMessageId = Uuid.random(),
                finishedAtEpochMs = 99L,
            ),
        )

        repository.insertPrepared(traceId, original)
        repository.markStreaming(traceId, responseId, null)
        repository.updateActualPromptTokens(traceId, 42)
        repository.markTerminal(traceId, PromptTraceStatus.COMPLETED, null)

        val available = repository.observeConversation(Uuid.parse(conversationId)).first().single()
            as PromptTraceReadResult.Available
        val metadata = available.record.payload.metadata
        assertEquals(PromptTraceStatus.COMPLETED, metadata.status)
        assertEquals(responseId, metadata.responseMessageId)
        assertEquals(42, metadata.actualPromptTokens)
        assertTrue(requireNotNull(metadata.finishedAtEpochMs) >= metadata.startedAtEpochMs)
        assertNull(available.record.errorSummary)
    }

    @Test
    fun terminalRowCannotBeOverwrittenByLateStreamingOrAnotherTerminalEvent() = runBlocking {
        val conversationId = insertConversation(Uuid.random().toString())
        val traceId = Uuid.random()
        repository.insertPrepared(traceId, payload(conversationId))
        repository.markTerminal(traceId, PromptTraceStatus.COMPLETED, null)

        repository.markStreaming(traceId, Uuid.random(), 77)
        repository.markTerminal(traceId, PromptTraceStatus.FAILED, "late failure")

        val available = repository.observeConversation(Uuid.parse(conversationId)).first().single()
            as PromptTraceReadResult.Available
        assertEquals(PromptTraceStatus.COMPLETED, available.record.payload.metadata.status)
        assertNull(available.record.payload.metadata.responseMessageId)
        assertNull(available.record.payload.metadata.actualPromptTokens)
        assertNull(available.record.errorSummary)
    }

    @Test
    fun insertPreparedSerializesPayloadAndRetainsNewestTwenty() = runBlocking {
        val conversationId = insertConversation(Uuid.random().toString())
        val traceIds = List(21) { Uuid.random() }

        repeat(21) { index ->
            repository.insertPrepared(
                traceId = traceIds[index],
                payload = payload(conversationId, startedAt = index.toLong()),
            )
        }

        val results = repository.observeConversation(Uuid.parse(conversationId)).first()
        assertEquals(20, results.size)
        assertTrue(results.all { it is PromptTraceReadResult.Available })
        assertEquals((20 downTo 1).map { it.toLong() }, results.map { it.createdAtEpochMs })
        assertTrue(results.none { it.traceId == traceIds.first() })
        assertEquals(traceIds.drop(1).toSet(), results.map { it.traceId }.toSet())
    }

    @Test
    fun branchSelectionDoesNotDeleteAndBranchRemovalDeletesOnlyBoundTrace() = runBlocking {
        val conversationId = insertConversation(Uuid.random().toString())
        val responseA = Uuid.random()
        val responseB = Uuid.random()
        val traceA = Uuid.random()
        val traceB = Uuid.random()
        dao.insert(traceEntity(traceA.toString(), conversationId, responseMessageId = responseA.toString()))
        dao.insert(
            traceEntity(
                traceB.toString(),
                conversationId,
                responseMessageId = responseB.toString(),
                createdAt = 2L,
            )
        )

        val beforeSelection = repository.observeConversation(Uuid.parse(conversationId)).first()
        assertEquals(traceA, selectDefaultTraceId(beforeSelection, responseA))
        assertEquals(traceB, selectDefaultTraceId(beforeSelection, responseB))
        assertEquals(setOf(traceA, traceB), beforeSelection.map { it.traceId }.toSet())

        repository.deleteForRemovedMessages(Uuid.parse(conversationId), setOf(responseA))

        val remaining = repository.observeConversation(Uuid.parse(conversationId)).first()
        assertEquals(listOf(traceB), remaining.map { it.traceId })
    }

    @Test
    fun regeneratingFromEarlierUserRemovesOnlyTracesBoundToTruncatedTail() = runBlocking {
        val conversationId = insertConversation(Uuid.random().toString())
        val keptResponse = Uuid.random()
        val removedResponse = Uuid.random()
        val removedUnboundAnchor = Uuid.random()
        val keptTrace = Uuid.random()
        dao.insert(traceEntity(keptTrace.toString(), conversationId, responseMessageId = keptResponse.toString()))
        dao.insert(
            traceEntity(
                Uuid.random().toString(),
                conversationId,
                responseMessageId = removedResponse.toString(),
                createdAt = 2L,
            )
        )
        dao.insert(
            traceEntity(
                Uuid.random().toString(),
                conversationId,
                requestAnchorMessageId = removedUnboundAnchor.toString(),
                responseMessageId = null,
                createdAt = 3L,
            )
        )

        repository.deleteForRemovedMessages(
            Uuid.parse(conversationId),
            setOf(removedResponse, removedUnboundAnchor),
        )

        assertEquals(
            listOf(keptTrace),
            repository.observeConversation(Uuid.parse(conversationId)).first().map { it.traceId },
        )
    }

    @Test
    fun unboundAttemptFollowsAnchorAndForkStartsWithoutTraces() = runBlocking {
        val sourceConversationId = insertConversation(Uuid.random().toString())
        val forkConversationId = insertConversation(Uuid.random().toString())
        val anchor = Uuid.random()
        val traceId = Uuid.random()
        dao.insert(
            traceEntity(
                id = traceId.toString(),
                conversationId = sourceConversationId,
                requestAnchorMessageId = anchor.toString(),
                responseMessageId = null,
            )
        )

        assertTrue(repository.observeConversation(Uuid.parse(forkConversationId)).first().isEmpty())
        assertEquals(
            listOf(traceId),
            repository.observeConversation(Uuid.parse(sourceConversationId)).first().map { it.traceId },
        )

        repository.deleteForRemovedMessages(Uuid.parse(sourceConversationId), setOf(Uuid.random()))
        assertEquals(
            listOf(traceId),
            repository.observeConversation(Uuid.parse(sourceConversationId)).first().map { it.traceId },
        )

        repository.deleteForRemovedMessages(Uuid.parse(sourceConversationId), setOf(anchor))
        assertTrue(repository.observeConversation(Uuid.parse(sourceConversationId)).first().isEmpty())
    }

    @Test
    fun clearAndRemovedMessageCleanupStayConversationScoped() = runBlocking {
        val firstConversation = insertConversation(Uuid.random().toString())
        val secondConversation = insertConversation(Uuid.random().toString())
        val removedResponse = Uuid.random()
        val removedAnchor = Uuid.random()
        dao.insert(
            traceEntity(
                id = Uuid.random().toString(),
                conversationId = firstConversation,
                responseMessageId = removedResponse.toString(),
            ),
        )
        dao.insert(
            traceEntity(
                id = Uuid.random().toString(),
                conversationId = firstConversation,
                requestAnchorMessageId = removedAnchor.toString(),
            ),
        )
        dao.insert(traceEntity(Uuid.random().toString(), secondConversation))

        repository.deleteForRemovedMessages(
            conversationId = Uuid.parse(firstConversation),
            messageIds = setOf(removedResponse, removedAnchor),
        )
        repository.deleteForRemovedMessages(Uuid.parse(secondConversation), emptySet())
        assertTrue(repository.observeConversation(Uuid.parse(firstConversation)).first().isEmpty())
        assertEquals(1, repository.observeConversation(Uuid.parse(secondConversation)).first().size)

        repository.clearConversation(Uuid.parse(secondConversation))
        assertTrue(repository.observeConversation(Uuid.parse(secondConversation)).first().isEmpty())
    }

    private suspend fun insertConversation(id: String): String {
        database.conversationDao().insert(
            ConversationEntity(
                id = id,
                assistantId = Uuid.random().toString(),
                title = "Test",
                nodes = "[]",
                createAt = 1L,
                updateAt = 1L,
                chatSuggestions = "[]",
                isPinned = false,
            ),
        )
        return id
    }

    private fun payload(conversationId: String, startedAt: Long = 1L): PromptTracePayload {
        val messageId = Uuid.random()
        return PromptTracePayload(
            metadata = PromptTraceMetadata(
                conversationId = Uuid.parse(conversationId),
                assistantId = Uuid.random(),
                modelId = Uuid.random(),
                isGroup = false,
                providerName = "OpenAI",
                providerStepIndex = 0,
                requestAnchorMessageId = messageId,
                startedAtEpochMs = startedAt,
                finalMessageCount = 1,
            ),
            sections = listOf(
                PromptTraceSection(PromptTraceSectionKind.CURRENT_USER_MESSAGE, "Current user input", "hello"),
            ),
            injectionHits = emptyList(),
            finalMessages = listOf(
                PromptTraceMessage(
                    id = messageId,
                    index = 0,
                    role = MessageRole.USER,
                    parts = listOf(PromptTracePart.Text("hello")),
                    characterCount = 5,
                    approximateTokens = 2,
                ),
            ),
        )
    }

    private fun traceEntity(
        id: String,
        conversationId: String,
        requestAnchorMessageId: String? = null,
        responseMessageId: String? = null,
        payloadJson: String = json.encodeToString(payload(conversationId)),
        createdAt: Long = 1L,
    ) = PromptTraceEntity(
        id = id,
        conversationId = conversationId,
        requestAnchorMessageId = requestAnchorMessageId,
        responseMessageId = responseMessageId,
        assistantId = Uuid.random().toString(),
        modelId = Uuid.random().toString(),
        speakerMemberId = null,
        providerStepIndex = 0,
        status = PromptTraceStatus.PREPARED.name,
        actualPromptTokens = null,
        errorSummary = null,
        payloadJson = payloadJson,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}
