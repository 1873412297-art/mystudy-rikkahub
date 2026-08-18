package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `prompt_trace` (
                `id` TEXT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `request_anchor_message_id` TEXT,
                `response_message_id` TEXT,
                `assistant_id` TEXT NOT NULL,
                `model_id` TEXT NOT NULL,
                `speaker_member_id` TEXT,
                `provider_step_index` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `actual_prompt_tokens` INTEGER,
                `error_summary` TEXT,
                `payload_json` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_prompt_trace_conversation_id` " +
                "ON `prompt_trace` (`conversation_id`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_prompt_trace_response_message_id` " +
                "ON `prompt_trace` (`response_message_id`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_prompt_trace_conversation_id_created_at` " +
                "ON `prompt_trace` (`conversation_id`, `created_at`)"
        )
    }
}
