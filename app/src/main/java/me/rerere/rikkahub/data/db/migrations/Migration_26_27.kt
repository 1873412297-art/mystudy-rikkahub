package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (!db.hasColumn26To27("ConversationEntity", "group_runtime_state")) {
            db.execSQL(
                "ALTER TABLE ConversationEntity " +
                    "ADD COLUMN group_runtime_state TEXT NOT NULL DEFAULT '{}'"
            )
        }
    }
}

private fun SupportSQLiteDatabase.hasColumn26To27(table: String, column: String): Boolean {
    query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == column) return true
        }
    }
    return false
}
