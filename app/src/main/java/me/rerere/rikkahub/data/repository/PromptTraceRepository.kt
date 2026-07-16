package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.ai.trace.PromptTracePayload
import me.rerere.rikkahub.data.ai.trace.PromptTraceReadResult
import me.rerere.rikkahub.data.ai.trace.PromptTraceRecord
import me.rerere.rikkahub.data.ai.trace.PromptTraceStatus
import me.rerere.rikkahub.data.ai.trace.PromptTraceStore
import me.rerere.rikkahub.data.db.dao.PromptTraceDAO
import me.rerere.rikkahub.data.db.entity.PromptTraceEntity
import kotlin.uuid.Uuid

class PromptTraceRepository(
    private val dao: PromptTraceDAO,
    baseJson: Json,
) : PromptTraceStore {
    private val json = Json(baseJson) {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun observeConversation(conversationId: Uuid): Flow<List<PromptTraceReadResult>> {
        return dao.observeByConversation(conversationId.toString()).map { rows ->
            rows.map(::decode)
        }
    }

    suspend fun clearConversation(conversationId: Uuid) {
        dao.deleteByConversation(conversationId.toString())
    }

    suspend fun deleteForRemovedMessages(conversationId: Uuid, messageIds: Set<Uuid>) {
        if (messageIds.isEmpty()) return
        dao.deleteForRemovedMessages(conversationId.toString(), messageIds.map(Uuid::toString))
    }

    override suspend fun insertPrepared(traceId: Uuid, payload: PromptTracePayload) {
        val metadata = payload.metadata
        dao.insertAndPrune(
            PromptTraceEntity(
                id = traceId.toString(),
                conversationId = metadata.conversationId.toString(),
                requestAnchorMessageId = metadata.requestAnchorMessageId?.toString(),
                responseMessageId = null,
                assistantId = metadata.assistantId.toString(),
                modelId = metadata.modelId.toString(),
                speakerMemberId = metadata.speakerMemberId?.toString(),
                providerStepIndex = metadata.providerStepIndex,
                status = PromptTraceStatus.PREPARED.name,
                actualPromptTokens = null,
                errorSummary = null,
                payloadJson = json.encodeToString(payload),
                createdAt = metadata.startedAtEpochMs,
                updatedAt = metadata.startedAtEpochMs,
            ),
            keep = RETENTION_LIMIT,
        )
    }

    override suspend fun markStreaming(traceId: Uuid, responseMessageId: Uuid, actualPromptTokens: Int?) {
        dao.markStreaming(
            traceId = traceId.toString(),
            responseMessageId = responseMessageId.toString(),
            actualPromptTokens = actualPromptTokens,
            updatedAt = System.currentTimeMillis(),
        )
    }

    override suspend fun updateActualPromptTokens(traceId: Uuid, actualPromptTokens: Int) {
        dao.updateActualPromptTokens(
            traceId = traceId.toString(),
            actualPromptTokens = actualPromptTokens,
            updatedAt = System.currentTimeMillis(),
        )
    }

    override suspend fun markTerminal(traceId: Uuid, status: PromptTraceStatus, errorSummary: String?) {
        val row = dao.getById(traceId.toString())
        dao.markTerminal(
            traceId = traceId.toString(),
            status = status.name,
            errorSummary = errorSummary,
            updatedAt = System.currentTimeMillis(),
        )
        row?.let { dao.pruneConversation(it.conversationId, RETENTION_LIMIT) }
    }

    private fun decode(entity: PromptTraceEntity): PromptTraceReadResult {
        val traceId = Uuid.parse(entity.id)
        return runCatching {
            val payload = json.decodeFromString<PromptTracePayload>(entity.payloadJson)
            val metadata = payload.metadata.copy(
                responseMessageId = entity.responseMessageId?.let(Uuid::parse),
                finishedAtEpochMs = if (entity.status in TERMINAL_STATUSES) entity.updatedAt else null,
                status = PromptTraceStatus.valueOf(entity.status),
                actualPromptTokens = entity.actualPromptTokens,
            )
            PromptTraceReadResult.Available(
                PromptTraceRecord(
                    traceId = traceId,
                    payload = payload.copy(metadata = metadata),
                    errorSummary = entity.errorSummary,
                ),
            )
        }.getOrElse {
            PromptTraceReadResult.Unavailable(
                traceId = traceId,
                createdAtEpochMs = entity.createdAt,
                responseMessageId = entity.responseMessageId?.let { value -> runCatching { Uuid.parse(value) }.getOrNull() },
                status = runCatching { PromptTraceStatus.valueOf(entity.status) }
                    .getOrDefault(PromptTraceStatus.FAILED),
                errorSummary = entity.errorSummary,
            )
        }
    }

    private companion object {
        const val RETENTION_LIMIT = 20
        val TERMINAL_STATUSES = setOf(
            PromptTraceStatus.COMPLETED.name,
            PromptTraceStatus.CANCELLED.name,
            PromptTraceStatus.FAILED.name,
        )
    }
}
