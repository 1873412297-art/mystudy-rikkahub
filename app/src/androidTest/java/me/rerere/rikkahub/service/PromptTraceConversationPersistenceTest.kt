package me.rerere.rikkahub.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.trace.PromptTraceCleanup
import me.rerere.rikkahub.data.ai.trace.PromptTraceMessage
import me.rerere.rikkahub.data.ai.trace.PromptTraceMetadata
import me.rerere.rikkahub.data.ai.trace.PromptTracePart
import me.rerere.rikkahub.data.ai.trace.PromptTracePayload
import me.rerere.rikkahub.data.ai.trace.PromptTraceStatus
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.PromptTraceRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class PromptTraceConversationPersistenceTest {
    private val insertMessageNodeSql =
        "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) " +
            "VALUES (?, ?, ?, ?, ?)"

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
    fun userRegenerationBuilderAndProductionPersistenceRemoveComputedTailTraces() = runBlocking {
        val keptAnchor = UIMessage.user("keep")
        val removedResponse = UIMessage.assistant("remove response")
        val removedAnchor = UIMessage.user("remove anchor")
        val removedTail = UIMessage.assistant("remove tail")
        val before = conversation(
            messages = listOf(keptAnchor, removedResponse, removedAnchor, removedTail),
        )
        insertConversation(before)
        val keptTrace = insertTrace(before.id, requestAnchor = keptAnchor.id)
        insertTrace(before.id, responseId = removedResponse.id)
        insertTrace(before.id, requestAnchor = removedAnchor.id)
        insertTrace(before.id, responseId = removedTail.id)

        val after = buildConversationAfterUserRegeneration(before, keptAnchor.id)
        persistConversationAndCleanupPromptTraces(
            conversationId = before.id,
            conversation = after,
            promptTraceCleanup = PromptTraceCleanup.RemovedMessages(before),
            promptTraceRepository = repository,
            persistConversation = { persisted -> updateConversationEntity(persisted) },
        )

        assertEquals(listOf(keptAnchor.id), after.currentMessages.map { it.id })
        assertEquals(
            listOf(keptTrace),
            repository.observeConversation(before.id).first().map { it.traceId },
        )
        assertEquals(after.messageNodes.size, storedNodeCount(after.id))
    }

    @Test
    fun forkBuilderAndProductionPersistenceKeepSourceRowsAndStartForkWithoutRows() = runBlocking {
        val user = UIMessage.user("hello")
        val response = UIMessage.assistant("reply")
        val source = conversation(messages = listOf(user, response))
        insertConversation(source)
        val sourceTrace = insertTrace(source.id, responseId = response.id)

        val fork = buildForkConversationAtMessage(
            currentConversation = source,
            messageId = response.id,
            copyPart = { it },
        )
        persistConversationAndCleanupPromptTraces(
            conversationId = fork.id,
            conversation = fork,
            promptTraceCleanup = PromptTraceCleanup.None,
            promptTraceRepository = repository,
            persistConversation = { persisted -> insertConversation(persisted) },
        )

        assertNotEquals(source.id, fork.id)
        assertEquals(source.currentMessages.map { it.id }, fork.currentMessages.map { it.id })
        assertEquals(listOf(sourceTrace), repository.observeConversation(source.id).first().map { it.traceId })
        assertTrue(repository.observeConversation(fork.id).first().isEmpty())
        assertEquals(fork.messageNodes.size, storedNodeCount(fork.id))
    }

    private fun conversation(messages: List<UIMessage>) = Conversation(
        id = Uuid.random(),
        assistantId = Uuid.random(),
        title = "Fixture",
        messageNodes = messages.map(MessageNode::of),
    )

    private suspend fun insertTrace(
        conversationId: Uuid,
        requestAnchor: Uuid? = null,
        responseId: Uuid? = null,
    ): Uuid {
        val traceId = Uuid.random()
        val message = PromptTraceMessage(
            id = requestAnchor ?: responseId ?: Uuid.random(),
            index = 0,
            role = MessageRole.USER,
            parts = listOf(PromptTracePart.Text("fixture")),
            characterCount = 7,
            approximateTokens = 2,
        )
        repository.insertPrepared(
            traceId,
            PromptTracePayload(
                metadata = PromptTraceMetadata(
                    conversationId = conversationId,
                    assistantId = Uuid.random(),
                    modelId = Uuid.random(),
                    isGroup = false,
                    providerStepIndex = 0,
                    requestAnchorMessageId = requestAnchor,
                    startedAtEpochMs = System.nanoTime(),
                ),
                sections = emptyList(),
                injectionHits = emptyList(),
                finalMessages = listOf(message),
            ),
        )
        if (responseId != null) {
            repository.markStreaming(traceId, responseId, null)
        }
        repository.markTerminal(traceId, PromptTraceStatus.COMPLETED, null)
        return traceId
    }

    private suspend fun insertConversation(conversation: Conversation) {
        database.conversationDao().insert(conversationEntity(conversation))
        conversation.messageNodes.forEachIndexed { index, _ ->
            database.openHelper.writableDatabase.execSQL(
                insertMessageNodeSql,
                arrayOf<Any?>(Uuid.random().toString(), conversation.id.toString(), index, "[]", 0),
            )
        }
    }

    private suspend fun updateConversationEntity(conversation: Conversation) {
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM message_node WHERE conversation_id = ?",
            arrayOf(conversation.id.toString()),
        )
        conversation.messageNodes.forEachIndexed { index, _ ->
            database.openHelper.writableDatabase.execSQL(
                insertMessageNodeSql,
                arrayOf<Any?>(Uuid.random().toString(), conversation.id.toString(), index, "[]", 0),
            )
        }
    }

    private fun storedNodeCount(conversationId: Uuid): Int = database.openHelper.readableDatabase.query(
        "SELECT COUNT(*) FROM message_node WHERE conversation_id = ?",
        arrayOf(conversationId.toString()),
    ).use { cursor ->
        cursor.moveToFirst()
        cursor.getInt(0)
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
