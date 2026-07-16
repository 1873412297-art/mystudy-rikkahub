package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.PromptTraceEntity

@Dao
interface PromptTraceDAO {
    @Query(
        """
        SELECT * FROM prompt_trace
        WHERE conversation_id = :conversationId
        ORDER BY created_at DESC, provider_step_index DESC
        """
    )
    fun observeByConversation(conversationId: String): Flow<List<PromptTraceEntity>>

    @Query("SELECT * FROM prompt_trace WHERE id = :traceId LIMIT 1")
    suspend fun getById(traceId: String): PromptTraceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PromptTraceEntity)

    @Query(
        """
        UPDATE prompt_trace
        SET response_message_id = :responseMessageId,
            status = 'STREAMING',
            actual_prompt_tokens = COALESCE(:actualPromptTokens, actual_prompt_tokens),
            updated_at = :updatedAt
        WHERE id = :traceId
          AND status NOT IN ('COMPLETED', 'CANCELLED', 'FAILED')
        """
    )
    suspend fun markStreaming(
        traceId: String,
        responseMessageId: String,
        actualPromptTokens: Int?,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE prompt_trace
        SET actual_prompt_tokens = :actualPromptTokens,
            updated_at = :updatedAt
        WHERE id = :traceId
          AND status NOT IN ('COMPLETED', 'CANCELLED', 'FAILED')
        """
    )
    suspend fun updateActualPromptTokens(traceId: String, actualPromptTokens: Int, updatedAt: Long)

    @Query(
        """
        UPDATE prompt_trace
        SET status = :status,
            error_summary = :errorSummary,
            updated_at = :updatedAt
        WHERE id = :traceId
          AND status NOT IN ('COMPLETED', 'CANCELLED', 'FAILED')
        """
    )
    suspend fun markTerminal(traceId: String, status: String, errorSummary: String?, updatedAt: Long)

    @Query(
        """
        DELETE FROM prompt_trace
        WHERE conversation_id = :conversationId
          AND id NOT IN (
              SELECT id FROM prompt_trace
              WHERE conversation_id = :conversationId
              ORDER BY created_at DESC, provider_step_index DESC
              LIMIT :keep
          )
        """
    )
    suspend fun pruneConversation(conversationId: String, keep: Int)

    @Query(
        """
        DELETE FROM prompt_trace
        WHERE conversation_id = :conversationId
          AND (
              response_message_id IN (:messageIds)
              OR (
                  response_message_id IS NULL
                  AND request_anchor_message_id IN (:messageIds)
              )
          )
        """
    )
    suspend fun deleteForRemovedMessages(conversationId: String, messageIds: List<String>)

    @Query("DELETE FROM prompt_trace WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Transaction
    suspend fun insertAndPrune(entity: PromptTraceEntity, keep: Int = 20) {
        insert(entity)
        pruneConversation(entity.conversationId, keep)
    }

    @Transaction
    suspend fun finalizeAndPrune(
        traceId: String,
        status: String,
        errorSummary: String?,
        updatedAt: Long,
        keep: Int = 20,
    ) {
        val row = getById(traceId)
        markTerminal(traceId, status, errorSummary, updatedAt)
        row?.let { pruneConversation(it.conversationId, keep) }
    }
}
