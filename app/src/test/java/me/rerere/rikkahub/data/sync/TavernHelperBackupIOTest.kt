package me.rerere.rikkahub.data.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperEntityMapper
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperFileStore
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperScriptRepository
import me.rerere.rikkahub.data.db.dao.TavernHelperScriptDAO
import me.rerere.rikkahub.data.db.entity.TavernHelperScriptEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernHelperBackupIOTest {

    @Test
    fun `backup then restore reproduces the tavern-helper tree byte for byte`() {
        val sourceDir = Files.createTempDirectory("tavern-backup-src").toFile()
        val targetDir = Files.createTempDirectory("tavern-backup-dst").toFile()
        try {
            val root = sourceDir.resolve(TavernHelperBackupIO.ROOT_DIRECTORY)
            root.resolve("source").mkdirs()
            root.resolve("data").mkdirs()
            val scriptText = "console.log('备份')".repeat(100)
            val dataText = "{\"hp\":100}"
            root.resolve("source/s1-abcdef0123456789.js").writeText(scriptText)
            root.resolve("data/s1-abcdef0123456789.json").writeText(dataText)

            val zipBytes = ByteArrayOutputStream().use { buffer ->
                ZipOutputStream(buffer).use { zipOut ->
                    TavernHelperBackupIO.backup(sourceDir, zipOut)
                }
                buffer.toByteArray()
            }

            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zipIn ->
                while (true) {
                    val entry = zipIn.nextEntry ?: break
                    assertTrue(TavernHelperBackupIO.restoreEntry(targetDir, zipIn, entry.name))
                    zipIn.closeEntry()
                }
            }
            val restoredRoot = targetDir.resolve(TavernHelperBackupIO.ROOT_DIRECTORY)
            assertEquals(scriptText, restoredRoot.resolve("source/s1-abcdef0123456789.js").readText())
            assertEquals(dataText, restoredRoot.resolve("data/s1-abcdef0123456789.json").readText())
        } finally {
            sourceDir.deleteRecursively()
            targetDir.deleteRecursively()
        }
    }

    @Test
    fun `backup skips missing folder and restore rejects foreign or escaping entries`() {
        val filesDir = Files.createTempDirectory("tavern-backup-guard").toFile()
        try {
            // 目录不存在：备份为 no-op
            ZipOutputStream(ByteArrayOutputStream()).use { zipOut ->
                TavernHelperBackupIO.backup(filesDir, zipOut)
            }

            val foreign = "upload/picture.png"
            assertFalse(
                TavernHelperBackupIO.restoreEntry(filesDir, ZipInputStream(ByteArrayInputStream(byteArrayOf())), foreign)
            )

            // 路径越界条目被拒绝且不写出文件
            val escaping = "${TavernHelperBackupIO.ENTRY_PREFIX}../evil.js"
            val payload = "evil".toByteArray()
            ZipInputStream(ByteArrayInputStream(payload)).use { zipIn ->
                assertTrue(TavernHelperBackupIO.restoreEntry(filesDir, zipIn, escaping))
            }
            assertFalse(filesDir.resolve("evil.js").exists())
            assertFalse(
                filesDir.resolve(TavernHelperBackupIO.ROOT_DIRECTORY).resolve("..").resolve("evil.js").canonicalFile.let {
                    it.exists() && it.readText() == "evil"
                }
            )
        } finally {
            filesDir.deleteRecursively()
        }
    }
}

class TavernHelperIntegrityAuditTest {

    @Test
    fun `audit disables corrupted scripts and keeps intact ones enabled`() {
        val root = Files.createTempDirectory("tavern-audit").toFile()
        try {
            val fileStore = TavernHelperFileStore(root, inlineThresholdBytes = 1)
            val mapper = TavernHelperEntityMapper(fileStore)
            val dao = FakeDao()
            val repository = TavernHelperScriptRepository(dao = dao, mapper = mapper, now = { 7L })

            // 完好脚本：内联阈值 1 → 全部外溢为文件，内容与哈希一致
            val intact = fileStore.store(
                me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperFileKind.SOURCE, "ok", "alert(1)"
            )
            val intactData = fileStore.store(
                me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperFileKind.DATA, "ok", "{}"
            )
            dao.entities += scriptEntity(
                id = "ok", name = "Intact", enabled = true,
                source = intact, data = intactData,
            )

            // 损坏脚本：文件内容被改写，哈希不再匹配
            val broken = fileStore.store(
                me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperFileKind.SOURCE, "bad", "alert(2)"
            )
            val brokenData = fileStore.store(
                me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperFileKind.DATA, "bad", "{}"
            )
            root.resolve(broken.relativePath!!).writeText("tampered")
            dao.entities += scriptEntity(
                id = "bad", name = "Broken", enabled = true,
                source = broken, data = brokenData,
            )

            // 文件夹不参与审计
            dao.entities += folderEntity(id = "folder-1")

            val corrupted = kotlinx.coroutines.runBlocking { repository.auditContentIntegrity() }

            assertEquals(listOf("bad"), corrupted.map { it.id })
            assertEquals("Broken", corrupted.single().name)
            // 损坏项被强制禁用且时间戳来自注入的时钟；完好项不受影响
            assertEquals(false, dao.entities.single { it.id == "bad" }.enabled)
            assertEquals(7L, dao.entities.single { it.id == "bad" }.updatedAt)
            assertEquals(true, dao.entities.single { it.id == "ok" }.enabled)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun scriptEntity(
        id: String,
        name: String,
        enabled: Boolean,
        source: me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperStoredContent,
        data: me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperStoredContent,
    ): TavernHelperScriptEntity {
        return TavernHelperScriptEntity(
            id = id,
            kind = "SCRIPT",
            scope = "GLOBAL",
            scopeId = "",
            parentId = null,
            sortOrder = 0,
            enabled = enabled,
            name = name,
            info = "",
            sourceInline = source.inline,
            sourcePath = source.relativePath,
            sourceSha256 = source.sha256,
            sourceBytes = source.bytes,
            buttonJson = "{}",
            dataInline = data.inline,
            dataPath = data.relativePath,
            dataSha256 = data.sha256,
            dataBytes = data.bytes,
            exportJson = "{}",
            compatJson = "{}",
            icon = null,
            color = null,
            tombstone = false,
            updatedAt = 0L,
        )
    }

    private fun folderEntity(id: String): TavernHelperScriptEntity {
        return TavernHelperScriptEntity(
            id = id,
            kind = "FOLDER",
            scope = "GLOBAL",
            scopeId = "",
            parentId = null,
            sortOrder = 0,
            enabled = true,
            name = "Folder",
            info = "",
            sourceInline = null,
            sourcePath = null,
            sourceSha256 = null,
            sourceBytes = 0,
            buttonJson = "{}",
            dataInline = null,
            dataPath = null,
            dataSha256 = null,
            dataBytes = 0,
            exportJson = "{}",
            compatJson = "{}",
            icon = null,
            color = null,
            tombstone = false,
            updatedAt = 0L,
        )
    }

    private class FakeDao : TavernHelperScriptDAO {
        val entities = mutableListOf<TavernHelperScriptEntity>()

        override fun observeScope(scope: String, scopeId: String): Flow<List<TavernHelperScriptEntity>> =
            flowOf(entities.filter { it.scope == scope && it.scopeId == scopeId && !it.tombstone })

        override suspend fun getScope(scope: String, scopeId: String): List<TavernHelperScriptEntity> =
            entities.filter { it.scope == scope && it.scopeId == scopeId && !it.tombstone }

        override fun observeAll(): Flow<List<TavernHelperScriptEntity>> = flowOf(entities.filter { !it.tombstone })

        override suspend fun getAll(): List<TavernHelperScriptEntity> = entities.filter { !it.tombstone }

        override suspend fun getById(id: String): TavernHelperScriptEntity? = entities.find { it.id == id }

        override suspend fun getChildren(parentId: String): List<TavernHelperScriptEntity> =
            entities.filter { it.parentId == parentId && !it.tombstone }

        override suspend fun getAllIds(): List<String> = entities.map { it.id }

        override suspend fun nextTopLevelOrder(scope: String, scopeId: String): Int = entities.size

        override suspend fun upsert(entity: TavernHelperScriptEntity) {
            entities.removeAll { it.id == entity.id }
            entities += entity
        }

        override suspend fun upsertAll(entities: List<TavernHelperScriptEntity>) {
            entities.forEach { upsert(it) }
        }

        override suspend fun setEnabled(id: String, enabled: Boolean, updatedAt: Long) {
            val index = entities.indexOfFirst { it.id == id }
            if (index >= 0) entities[index] = entities[index].copy(enabled = enabled, updatedAt = updatedAt)
        }

        override suspend fun markDeleted(id: String, updatedAt: Long) {
            val index = entities.indexOfFirst { it.id == id }
            if (index >= 0) entities[index] = entities[index].copy(tombstone = true, updatedAt = updatedAt)
        }

        override suspend fun markScopeDeleted(scope: String, scopeId: String, updatedAt: Long) {
            entities.replaceAll {
                if (it.scope == scope && it.scopeId == scopeId) it.copy(tombstone = true, updatedAt = updatedAt) else it
            }
        }

        override suspend fun getTombstones(): List<TavernHelperScriptEntity> = entities.filter { it.tombstone }

        override suspend fun purgeTombstones() {
            entities.removeAll { it.tombstone }
        }

        override suspend fun transferNode(
            deletedId: String?,
            updatedAt: Long,
            sourceEntities: List<TavernHelperScriptEntity>,
            targetEntities: List<TavernHelperScriptEntity>,
        ) = Unit
    }
}
