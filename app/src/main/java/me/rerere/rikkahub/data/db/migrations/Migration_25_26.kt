package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (!db.hasColumn("ConversationEntity", "folder_id")) {
            db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN folder_id TEXT NOT NULL DEFAULT ''")
        }

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversation_folder (
                id TEXT NOT NULL PRIMARY KEY,
                assistant_id TEXT NOT NULL,
                name TEXT NOT NULL,
                sort_index INTEGER NOT NULL DEFAULT 0,
                create_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_conversation_folder_assistant_id ON conversation_folder (assistant_id)"
        )
    }
}

private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean {
    query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == column) {
                return true
            }
        }
    }
    return false
}
