package me.rerere.rikkahub.data.ai.trace

import android.util.Log
import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.repository.PromptTraceRepository
import kotlin.uuid.Uuid

interface PromptTraceStore {
    suspend fun insertPrepared(traceId: Uuid, payload: PromptTracePayload)
    suspend fun markStreaming(traceId: Uuid, responseMessageId: Uuid, actualPromptTokens: Int?)
    suspend fun updateActualPromptTokens(traceId: Uuid, actualPromptTokens: Int)
    suspend fun markTerminal(traceId: Uuid, status: PromptTraceStatus, errorSummary: String?)
}

interface PromptTraceSessionFactory {
    fun create(
        seed: PromptTraceSeed,
        providerStepIndex: Int,
        providerName: String?,
    ): PromptTraceSession
}

class DefaultPromptTraceSessionFactory(
    private val repository: PromptTraceRepository,
) : PromptTraceSessionFactory {
    override fun create(
        seed: PromptTraceSeed,
        providerStepIndex: Int,
        providerName: String?,
    ): PromptTraceSession = PromptTraceSession(
        seed = seed,
        providerStepIndex = providerStepIndex,
        providerName = providerName,
        store = repository,
    )
}

class PromptTraceSession(
    private val seed: PromptTraceSeed,
    private val providerStepIndex: Int,
    private val providerName: String?,
    private val store: PromptTraceStore,
    private val now: () -> Long = System::currentTimeMillis,
) : PromptTraceRecorder {
    val traceId: Uuid = Uuid.random()

    private val startedAt = now()
    private val sections = mutableListOf<PromptTraceSection>()
    private val injectionHits = mutableListOf<PromptInjectionTrace>()
    private val responseBaselineMessageIds = mutableSetOf<Uuid>()
    private val sourceInputMessageIds = mutableSetOf<Uuid>()
    private val inputTextById = mutableMapOf<Uuid, String>()
    private var responseMessageId: Uuid? = null
    private var actualPromptTokens: Int? = null
    private var state = State.COLLECTING

    fun recordSection(section: PromptTraceSection) {
        if (state == State.COLLECTING) sections += section
    }

    fun recordResponseBaseline(messages: List<UIMessage>) {
        if (state == State.COLLECTING) responseBaselineMessageIds += messages.map(UIMessage::id)
    }

    fun recordInputMessages(messages: List<UIMessage>) {
        if (state != State.COLLECTING) return
        val hints = seed.sourceHints.associateBy(PromptTraceSourceHint::messageId)
        messages.forEach { message ->
            responseBaselineMessageIds += message.id
            sourceInputMessageIds += message.id
            val text = message.toText()
            inputTextById[message.id] = text
            val hint = hints[message.id]
            val kind = when {
                hint != null -> hint.kind
                message.id == seed.requestAnchorMessageId -> PromptTraceSectionKind.CURRENT_USER_MESSAGE
                else -> PromptTraceSectionKind.HISTORY_MESSAGE
            }
            if (text.isNotEmpty()) {
                sections += PromptTraceSection(
                    kind = kind,
                    label = hint?.label ?: if (kind == PromptTraceSectionKind.CURRENT_USER_MESSAGE) {
                        "Current user input"
                    } else {
                        "History message"
                    },
                    text = text,
                    sourceMessageId = message.id,
                    targetMessageId = message.id,
                )
            }
        }
    }

    override fun recordInjectionHits(hits: List<PromptInjectionTrace>) {
        if (state != State.COLLECTING) return
        injectionHits += hits
        hits.forEach { hit ->
            sections += PromptTraceSection(
                kind = if (hit.sourceType == PromptInjectionSourceType.MODE) {
                    PromptTraceSectionKind.MODE_INJECTION
                } else {
                    PromptTraceSectionKind.LOREBOOK_INJECTION
                },
                label = hit.injectionName.ifBlank { hit.injectionId.toString() },
                text = hit.content,
                targetMessageId = hit.targetMessageId,
                targetMessageIndex = hit.targetMessageIndex,
            )
        }
    }

    suspend fun prepare(finalMessages: List<UIMessage>) {
        if (state != State.COLLECTING) return
        state = State.PREPARED
        responseBaselineMessageIds += finalMessages.map(UIMessage::id)
        val sanitized = PromptTraceSanitizer.sanitizeMessages(finalMessages)
        val knownIds = sourceInputMessageIds + sections.mapNotNull(PromptTraceSection::targetMessageId)
        finalMessages.forEachIndexed { index, message ->
            val finalText = message.toText()
            val inputText = inputTextById[message.id]
            val isNewMessage = message.id !in knownIds
            val existingMessageChanged = inputText != null && inputText != finalText
            if ((isNewMessage || existingMessageChanged) && finalText.isNotEmpty()) {
                sections += PromptTraceSection(
                    kind = PromptTraceSectionKind.OTHER_TRANSFORMED_CONTENT,
                    label = if (existingMessageChanged) "Transformed message" else "Transformer output",
                    text = finalText,
                    sourceMessageId = message.id.takeIf { existingMessageChanged },
                    targetMessageId = message.id,
                    targetMessageIndex = index,
                )
            }
        }
        val payload = PromptTracePayload(
            metadata = PromptTraceMetadata(
                conversationId = seed.conversationId,
                assistantId = seed.assistantId,
                modelId = seed.modelId,
                isGroup = seed.isGroup,
                speakerMemberId = seed.speakerMemberId,
                speakerName = seed.speakerName,
                providerName = providerName,
                providerStepIndex = providerStepIndex,
                requestAnchorMessageId = seed.requestAnchorMessageId,
                startedAtEpochMs = startedAt,
                finalMessageCount = sanitized.size,
            ),
            sections = sections.toList(),
            injectionHits = injectionHits.toList(),
            finalMessages = sanitized,
        )
        bestEffort { store.insertPrepared(traceId, payload) }
    }

    suspend fun observeProviderMessages(messages: List<UIMessage>) {
        if (state != State.PREPARED && state != State.STREAMING) return
        val response = messages.lastOrNull { message ->
            message.id !in responseBaselineMessageIds && message.role == MessageRole.ASSISTANT
        }
        val promptTokens = response?.usage?.promptTokens?.takeIf { it > 0 }
        if (response != null && responseMessageId == null) {
            responseMessageId = response.id
            actualPromptTokens = promptTokens
            state = State.STREAMING
            bestEffort { store.markStreaming(traceId, response.id, promptTokens) }
        }
        if (promptTokens != null && promptTokens != actualPromptTokens) {
            actualPromptTokens = promptTokens
            bestEffort { store.updateActualPromptTokens(traceId, promptTokens) }
        }
    }

    suspend fun complete() {
        markTerminal(PromptTraceStatus.COMPLETED, null)
    }

    suspend fun cancel() {
        markTerminal(PromptTraceStatus.CANCELLED, null)
    }

    suspend fun fail(error: Throwable) {
        markTerminal(PromptTraceStatus.FAILED, PromptTraceSanitizer.sanitizeError(error))
    }

    private suspend fun markTerminal(status: PromptTraceStatus, errorSummary: String?) {
        if (state != State.PREPARED && state != State.STREAMING) return
        state = State.TERMINAL
        bestEffort { store.markTerminal(traceId, status, errorSummary) }
    }

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            logPersistenceFailure(error)
        }
    }

    private fun logPersistenceFailure(error: Throwable) {
        try {
            Log.w("PromptTraceSession", "Trace persistence failed", error)
        } catch (_: RuntimeException) {
            // android.util.Log is a stub in local JVM tests.
        }
    }

    private enum class State {
        COLLECTING,
        PREPARED,
        STREAMING,
        TERMINAL,
    }
}
