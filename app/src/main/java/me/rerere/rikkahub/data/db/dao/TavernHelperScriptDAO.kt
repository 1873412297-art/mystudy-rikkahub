package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.TavernHelperScriptEntity

@Dao
interface TavernHelperScriptDAO {
    @Query(
        """
        SELECT * FROM tavern_helper_script
        WHERE scope = :scope AND scope_id = :scopeId AND tombstone = 0
        ORDER BY parent_id IS NOT NULL, parent_id, sort_order, updated_at, id
        """,
    )
    fun observeScope(scope: String, scopeId: String): Flow<List<TavernHelperScriptEntity>>

    @Query(
        "SELECT * FROM tavern_helper_script WHERE scope = :scope AND scope_id = :scopeId AND tombstone = 0",
    )
    suspend fun getScope(scope: String, scopeId: String): List<TavernHelperScriptEntity>

    @Query("SELECT * FROM tavern_helper_script WHERE tombstone = 0 ORDER BY scope, scope_id, sort_order")
    fun observeAll(): Flow<List<TavernHelperScriptEntity>>

    @Query("SELECT * FROM tavern_helper_script WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TavernHelperScriptEntity?

    @Query("SELECT id FROM tavern_helper_script")
    suspend fun getAllIds(): List<String>

    @Query(
        "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM tavern_helper_script " +
            "WHERE scope = :scope AND scope_id = :scopeId AND parent_id IS NULL AND tombstone = 0",
    )
    suspend fun nextTopLevelOrder(scope: String, scopeId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TavernHelperScriptEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<TavernHelperScriptEntity>)

    @Query("UPDATE tavern_helper_script SET enabled = :enabled, updated_at = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, updatedAt: Long)

    @Query("UPDATE tavern_helper_script SET tombstone = 1, enabled = 0, updated_at = :updatedAt WHERE id = :id OR parent_id = :id")
    suspend fun markDeleted(id: String, updatedAt: Long)

    @Query(
        "UPDATE tavern_helper_script SET tombstone = 1, enabled = 0, updated_at = :updatedAt " +
            "WHERE scope = :scope AND scope_id = :scopeId",
    )
    suspend fun markScopeDeleted(scope: String, scopeId: String, updatedAt: Long)

    @Query("SELECT * FROM tavern_helper_script WHERE tombstone = 1")
    suspend fun getTombstones(): List<TavernHelperScriptEntity>

    @Query("DELETE FROM tavern_helper_script WHERE tombstone = 1")
    suspend fun purgeTombstones()
}
