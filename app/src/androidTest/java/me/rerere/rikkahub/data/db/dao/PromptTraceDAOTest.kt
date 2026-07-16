package me.rerere.rikkahub.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.PromptTraceEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PromptTraceDAOTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: PromptTraceDAO

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.promptTraceDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertAndPrune_keepsNewestTwentyInObservationOrderAndSupportsRead() = runBlocking {
        val conversationId = "conversation-retention"
        insertConversation(conversationId)

        (1..21).forEach { index ->
            dao.insertAndPrune(trace(conversationId, index))
        }

        val observed = dao.observeByConversation(conversationId).first()
        assertEquals((21 downTo 2).map { "trace-$it" }, observed.map { it.id })
        assertNull(dao.getById("trace-1"))
        assertEquals("trace-21", dao.getById("trace-21")?.id)
    }

    @Test
    fun lifecycleUpdates_preserveExistingTokensThenStoreTerminalState() = runBlocking {
        val conversationId = "conversation-lifecycle"
        insertConversation(conversationId)
        dao.insert(
            trace(conversationId, 1).copy(
                responseMessageId = null,
                status = "PENDING",
                actualPromptTokens = 11,
            )
        )

        dao.markStreaming(
            traceId = "trace-1",
            responseMessageId = "assistant-stream",
            actualPromptTokens = null,
            updatedAt = 100L,
        )
        val streaming = requireNotNull(dao.getById("trace-1"))
        assertEquals("assistant-stream", streaming.responseMessageId)
        assertEquals("STREAMING", streaming.status)
        assertEquals(11, streaming.actualPromptTokens)
        assertEquals(100L, streaming.updatedAt)

        dao.updateActualPromptTokens("trace-1", actualPromptTokens = 27, updatedAt = 200L)
        dao.markTerminal("trace-1", status = "FAILED", errorSummary = "provider error", updatedAt = 300L)

        val terminal = requireNotNull(dao.getById("trace-1"))
        assertEquals(27, terminal.actualPromptTokens)
        assertEquals("FAILED", terminal.status)
        assertEquals("provider error", terminal.errorSummary)
        assertEquals(300L, terminal.updatedAt)
    }

    @Test
    fun deletingConversation_cascadesToTraces() = runBlocking {
        val conversationId = "conversation-cascade"
        insertConversation(conversationId)
        dao.insert(trace(conversationId, 1))

        database.conversationDao().deleteById(conversationId)

        assertTrue(dao.observeByConversation(conversationId).first().isEmpty())
    }

    @Test
    fun deleteForRemovedMessages_removesMatchingResponseAndOnlyUnboundMatchingAnchor() = runBlocking {
        val conversationId = "conversation-cleanup"
        insertConversation(conversationId)
        val traces = listOf(
            trace(conversationId, 1).copy(
                id = "matching-response",
                requestAnchorMessageId = "user-kept",
                responseMessageId = "removed-response",
            ),
            trace(conversationId, 2).copy(
                id = "matching-unbound-anchor",
                requestAnchorMessageId = "removed-user",
                responseMessageId = null,
            ),
            trace(conversationId, 3).copy(
                id = "bound-matching-anchor",
                requestAnchorMessageId = "removed-user",
                responseMessageId = "assistant-kept",
            ),
            trace(conversationId, 4).copy(id = "unrelated"),
        )
        traces.forEach { dao.insert(it) }

        dao.deleteForRemovedMessages(conversationId, listOf("removed-response", "removed-user"))

        assertEquals(
            setOf("bound-matching-anchor", "unrelated"),
            dao.observeByConversation(conversationId).first().map { it.id }.toSet(),
        )

        dao.deleteByConversation(conversationId)
        assertTrue(dao.observeByConversation(conversationId).first().isEmpty())
    }

    private suspend fun insertConversation(id: String) {
        database.conversationDao().insert(
            ConversationEntity(
                id = id,
                assistantId = "assistant",
                title = "Test",
                nodes = "[]",
                createAt = 1L,
                updateAt = 1L,
                chatSuggestions = "[]",
                isPinned = false,
            )
        )
    }

    private fun trace(conversationId: String, index: Int) = PromptTraceEntity(
        id = "trace-$index",
        conversationId = conversationId,
        requestAnchorMessageId = "user-$index",
        responseMessageId = "assistant-$index",
        assistantId = "assistant",
        modelId = "model",
        speakerMemberId = null,
        providerStepIndex = index,
        status = "COMPLETED",
        actualPromptTokens = index,
        errorSummary = null,
        payloadJson = "{}",
        createdAt = index.toLong(),
        updatedAt = index.toLong(),
    )
}
