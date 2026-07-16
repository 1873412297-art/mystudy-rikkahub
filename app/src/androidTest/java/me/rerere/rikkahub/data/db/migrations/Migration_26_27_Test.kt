package me.rerere.rikkahub.data.db.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class Migration_26_27_Test {
    private val databaseName = "migration-26-27"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate26To27_addsRuntimeStateWithEmptyObjectDefault() {
        val conversationId = Uuid.random().toString()
        val values = ContentValues().apply {
            put("id", conversationId)
            put("assistant_id", Uuid.random().toString())
            put("title", "Legacy group")
            put("nodes", "[]")
            put("create_at", 1L)
            put("update_at", 1L)
            put("suggestions", "[]")
            put("is_pinned", 0)
        }
        helper.createDatabase(databaseName, 26).apply {
            assertTrue(insert("ConversationEntity", SQLiteDatabase.CONFLICT_NONE, values) > 0)
            close()
        }

        val db = helper.runMigrationsAndValidate(databaseName, 27, true, Migration_26_27)
        db.query(
            "SELECT group_runtime_state FROM ConversationEntity WHERE id = ?",
            arrayOf(conversationId),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("{}", cursor.getString(0))
        }
        db.close()
    }
}
