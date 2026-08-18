package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "prompt_trace",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("conversation_id"),
        Index("response_message_id"),
        Index(value = ["conversation_id", "created_at"]),
    ],
)
data class PromptTraceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("conversation_id") val conversationId: String,
    @ColumnInfo("request_anchor_message_id") val requestAnchorMessageId: String?,
    @ColumnInfo("response_message_id") val responseMessageId: String?,
    @ColumnInfo("assistant_id") val assistantId: String,
    @ColumnInfo("model_id") val modelId: String,
    @ColumnInfo("speaker_member_id") val speakerMemberId: String?,
    @ColumnInfo("provider_step_index") val providerStepIndex: Int,
    @ColumnInfo("status") val status: String,
    @ColumnInfo("actual_prompt_tokens") val actualPromptTokens: Int?,
    @ColumnInfo("error_summary") val errorSummary: String?,
    @ColumnInfo("payload_json") val payloadJson: String,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long,
)
