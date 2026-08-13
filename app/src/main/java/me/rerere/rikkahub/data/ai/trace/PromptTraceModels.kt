package me.rerere.rikkahub.data.ai.trace

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import kotlin.uuid.Uuid

@Serializable
enum class PromptTraceStatus {
    PREPARED,
    STREAMING,
    COMPLETED,
    CANCELLED,
    FAILED,
}

@Serializable
enum class PromptTraceSectionKind {
    ASSISTANT_OR_CARD_SYSTEM,
    CONVERSATION_SYSTEM_OVERRIDE,
    MEMORY,
    TOOL_PROMPT,
    GROUP_LAYERED_CONTEXT,
    MODE_INJECTION,
    LOREBOOK_INJECTION,
    HISTORY_MESSAGE,
    CURRENT_USER_MESSAGE,
    OTHER_TRANSFORMED_CONTENT,
}

@Serializable
enum class PromptInjectionSourceType {
    MODE,
    LOREBOOK,
    AUTHOR_NOTE,
}

@Serializable
enum class PromptInjectionMatchType {
    CONSTANT,
    KEYWORD,
    REGEX,
    STICKY,
}

@Serializable
enum class PromptTraceAttachmentKind {
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
}

@Serializable
data class PromptTraceSourceHint(
    val messageId: Uuid,
    val kind: PromptTraceSectionKind,
    val label: String,
)

data class PromptTraceSeed(
    val conversationId: Uuid,
    val requestAnchorMessageId: Uuid?,
    val assistantId: Uuid,
    val modelId: Uuid,
    val isGroup: Boolean,
    val speakerMemberId: Uuid? = null,
    val speakerName: String? = null,
    val sourceHints: List<PromptTraceSourceHint> = emptyList(),
)

@Serializable
data class PromptTraceMetadata(
    val conversationId: Uuid,
    val assistantId: Uuid,
    val modelId: Uuid,
    val isGroup: Boolean,
    val speakerMemberId: Uuid? = null,
    val speakerName: String? = null,
    val providerName: String? = null,
    val providerStepIndex: Int,
    val requestAnchorMessageId: Uuid? = null,
    val responseMessageId: Uuid? = null,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long? = null,
    val status: PromptTraceStatus = PromptTraceStatus.PREPARED,
    val actualPromptTokens: Int? = null,
    val finalMessageCount: Int = 0,
)

@Serializable
data class PromptTraceSection(
    val kind: PromptTraceSectionKind,
    val label: String,
    val text: String,
    val active: Boolean = true,
    val characterCount: Int = text.length,
    val approximateTokens: Int = PromptTokenEstimator.estimate(text),
    val sourceMessageId: Uuid? = null,
    val targetMessageId: Uuid? = null,
    val targetMessageIndex: Int? = null,
)

@Serializable
data class PromptInjectionMatch(
    val type: PromptInjectionMatchType,
    val matchedTerms: List<String>,
    val scanDepth: Int,
    val scannedMessageIds: List<Uuid>,
    val caseSensitive: Boolean,
    val regexEnabled: Boolean,
    val matchWholeWords: Boolean = false,                 // 是否为整词匹配
    val selective: Boolean = false,                       // 是否为 selective 条目
    val secondaryMatchedTerms: List<String> = emptyList(), // selective 模式下次关键词命中项
    val probability: Int = 100,                            // 条目配置的触发概率
    val recursiveRound: Int = 0,                           // 递归扫描命中轮次（0 = 直接命中）
)

@Serializable
data class PromptInjectionTrace(
    val injectionId: Uuid,
    val injectionName: String,
    val sourceType: PromptInjectionSourceType,
    val lorebookId: Uuid? = null,
    val lorebookName: String? = null,
    val match: PromptInjectionMatch? = null,
    val position: String,
    val role: MessageRole,
    val priority: Int,
    val injectDepth: Int,
    val content: String,
    val approximateTokens: Int = PromptTokenEstimator.estimate(content),
    val targetMessageId: Uuid? = null,
    val targetMessageIndex: Int? = null,
)

@Serializable
data class PromptTraceTextSummary(
    val preview: String,
    val originalLength: Int,
    val sha256: String,
    val truncated: Boolean,
)

@Serializable
data class PromptTraceAttachment(
    val kind: PromptTraceAttachmentKind,
    val uri: String? = null,
    val displayName: String? = null,
    val mimeType: String? = null,
    val byteLength: Long? = null,
    val sha256: String? = null,
)

@Serializable
sealed class PromptTracePart {
    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
        val approximateTokens: Int = PromptTokenEstimator.estimate(text),
    ) : PromptTracePart()

    @Serializable
    @SerialName("reasoning")
    data class Reasoning(
        val text: String,
        val approximateTokens: Int = PromptTokenEstimator.estimate(text),
    ) : PromptTracePart()

    @Serializable
    @SerialName("attachment")
    data class Attachment(
        val value: PromptTraceAttachment,
    ) : PromptTracePart()

    @Serializable
    @SerialName("tool")
    data class Tool(
        val toolCallId: String,
        val toolName: String,
        val approvalState: String,
        val input: PromptTraceTextSummary,
        val outputText: PromptTraceTextSummary?,
        val outputAttachments: List<PromptTraceAttachment>,
    ) : PromptTracePart()
}

@Serializable
data class PromptTraceMessage(
    val id: Uuid,
    val index: Int,
    val role: MessageRole,
    val memberId: Uuid? = null,
    val name: String? = null,
    val parts: List<PromptTracePart>,
    val characterCount: Int,
    val approximateTokens: Int,
)

@Serializable
data class PromptTracePayload(
    val schemaVersion: Int = 1,
    val metadata: PromptTraceMetadata,
    val sections: List<PromptTraceSection>,
    val injectionHits: List<PromptInjectionTrace>,
    val finalMessages: List<PromptTraceMessage>,
)

data class PromptTraceRecord(
    val traceId: Uuid,
    val payload: PromptTracePayload,
    val errorSummary: String? = null,
)

interface PromptTraceRecorder {
    fun recordInjectionHits(hits: List<PromptInjectionTrace>)
}

sealed interface PromptTraceReadResult {
    val traceId: Uuid
    val createdAtEpochMs: Long
    val responseMessageId: Uuid?

    data class Available(
        val record: PromptTraceRecord,
    ) : PromptTraceReadResult {
        override val traceId: Uuid = record.traceId
        override val createdAtEpochMs: Long = record.payload.metadata.startedAtEpochMs
        override val responseMessageId: Uuid? = record.payload.metadata.responseMessageId
    }

    data class Unavailable(
        override val traceId: Uuid,
        override val createdAtEpochMs: Long,
        override val responseMessageId: Uuid?,
        val status: PromptTraceStatus,
        val errorSummary: String?,
    ) : PromptTraceReadResult
}
