package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tavern_helper_script",
    indices = [
        Index(value = ["scope", "scope_id", "parent_id", "sort_order"]),
        Index(value = ["scope", "scope_id", "tombstone"]),
        Index("parent_id"),
    ],
)
data class TavernHelperScriptEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val scope: String,
    @ColumnInfo("scope_id") val scopeId: String,
    @ColumnInfo("parent_id") val parentId: String?,
    @ColumnInfo("sort_order") val sortOrder: Int,
    val enabled: Boolean,
    val name: String,
    val info: String,
    @ColumnInfo("source_inline") val sourceInline: String?,
    @ColumnInfo("source_path") val sourcePath: String?,
    @ColumnInfo("source_sha256") val sourceSha256: String?,
    @ColumnInfo("source_bytes") val sourceBytes: Long,
    @ColumnInfo("button_json") val buttonJson: String,
    @ColumnInfo("data_inline") val dataInline: String?,
    @ColumnInfo("data_path") val dataPath: String?,
    @ColumnInfo("data_sha256") val dataSha256: String?,
    @ColumnInfo("data_bytes") val dataBytes: Long,
    @ColumnInfo("export_json") val exportJson: String,
    @ColumnInfo("compat_json") val compatJson: String,
    val icon: String?,
    val color: String?,
    val tombstone: Boolean,
    @ColumnInfo("updated_at") val updatedAt: Long,
)
