package me.rerere.rikkahub.data.db.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class Migration_27_28_Test {
    private val databaseName = "migration-27-28"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate27To28_preservesConversationAndCreatesTraceSchema() {
        val conversationId = Uuid.random().toString()
        val nodeId = Uuid.random().toString()
        helper.createDatabase(databaseName, 27).apply {
            insert(
                "ConversationEntity",
                SQLiteDatabase.CONFLICT_NONE,
                ContentValues().apply {
                    put("id", conversationId)
                    put("assistant_id", Uuid.random().toString())
                    put("title", "Legacy")
                    put("nodes", "[]")
                    put("create_at", 1L)
                    put("update_at", 1L)
                    put("suggestions", "[]")
                    put("is_pinned", 0)
                },
            )
            insert(
                "message_node",
                SQLiteDatabase.CONFLICT_NONE,
                ContentValues().apply {
                    put("id", nodeId)
                    put("conversation_id", conversationId)
                    put("node_index", 0)
                    put("messages", "[]")
                    put("select_index", 0)
                },
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(databaseName, 28, true, Migration_27_28)

        db.query("SELECT COUNT(*) FROM ConversationEntity").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM message_node").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }

        val expectedColumns = mapOf(
            "id" to Column("TEXT", notNull = true, primaryKeyPosition = 1),
            "conversation_id" to Column("TEXT", notNull = true),
            "request_anchor_message_id" to Column("TEXT", notNull = false),
            "response_message_id" to Column("TEXT", notNull = false),
            "assistant_id" to Column("TEXT", notNull = true),
            "model_id" to Column("TEXT", notNull = true),
            "speaker_member_id" to Column("TEXT", notNull = false),
            "provider_step_index" to Column("INTEGER", notNull = true),
            "status" to Column("TEXT", notNull = true),
            "actual_prompt_tokens" to Column("INTEGER", notNull = false),
            "error_summary" to Column("TEXT", notNull = false),
            "payload_json" to Column("TEXT", notNull = true),
            "created_at" to Column("INTEGER", notNull = true),
            "updated_at" to Column("INTEGER", notNull = true),
        )
        val actualColumns = buildMap {
            db.query("PRAGMA table_info(`prompt_trace`)").use { cursor ->
                val name = cursor.getColumnIndexOrThrow("name")
                val type = cursor.getColumnIndexOrThrow("type")
                val notNull = cursor.getColumnIndexOrThrow("notnull")
                val defaultValue = cursor.getColumnIndexOrThrow("dflt_value")
                val primaryKey = cursor.getColumnIndexOrThrow("pk")
                while (cursor.moveToNext()) {
                    assertNull(cursor.getString(defaultValue))
                    put(
                        cursor.getString(name),
                        Column(
                            type = cursor.getString(type),
                            notNull = cursor.getInt(notNull) == 1,
                            primaryKeyPosition = cursor.getInt(primaryKey),
                        ),
                    )
                }
            }
        }
        assertEquals(expectedColumns, actualColumns)

        val indices = buildSet {
            db.query("PRAGMA index_list(`prompt_trace`)").use { cursor ->
                val name = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(name))
            }
        }
        assertTrue("index_prompt_trace_conversation_id" in indices)
        assertTrue("index_prompt_trace_response_message_id" in indices)
        assertTrue("index_prompt_trace_conversation_id_created_at" in indices)

        db.query("PRAGMA foreign_key_list(`prompt_trace`)").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("ConversationEntity", cursor.getString(cursor.getColumnIndexOrThrow("table")))
            assertEquals("conversation_id", cursor.getString(cursor.getColumnIndexOrThrow("from")))
            assertEquals("id", cursor.getString(cursor.getColumnIndexOrThrow("to")))
            assertEquals("CASCADE", cursor.getString(cursor.getColumnIndexOrThrow("on_delete")))
        }
        db.close()
    }

    private data class Column(
        val type: String,
        val notNull: Boolean,
        val primaryKeyPosition: Int = 0,
    )
}
