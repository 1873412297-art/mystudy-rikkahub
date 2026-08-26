package me.rerere.rikkahub.data.ai.tavernhelper

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal enum class TavernHelperFileKind(val directory: String, val extension: String) {
    SOURCE("source", "js"),
    DATA("data", "json"),
}

internal data class TavernHelperStoredContent(
    val inline: String?,
    val relativePath: String?,
    val sha256: String,
    val bytes: Long,
)

internal class TavernHelperContentCorruptException(message: String) : IllegalStateException(message)

internal class TavernHelperFileStore(
    private val root: File,
    private val inlineThresholdBytes: Int = 64 * 1024,
) {
    fun store(kind: TavernHelperFileKind, id: String, content: String): TavernHelperStoredContent {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val sha = bytes.sha256()
        if (bytes.size <= inlineThresholdBytes) {
            return TavernHelperStoredContent(content, null, sha, bytes.size.toLong())
        }

        val directory = root.resolve(kind.directory).apply { mkdirs() }
        val safeId = id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifEmpty { "script" }
        val filename = "$safeId-${sha.take(16)}.${kind.extension}"
        val target = directory.resolve(filename)
        val temporary = directory.resolve(".$filename.${System.nanoTime()}.tmp")
        Files.write(temporary.toPath(), bytes)
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
        return TavernHelperStoredContent(
            inline = null,
            relativePath = "${kind.directory}/$filename",
            sha256 = sha,
            bytes = bytes.size.toLong(),
        )
    }

    fun read(stored: TavernHelperStoredContent): String {
        val inline = stored.inline
        val bytes = if (inline != null) {
            inline.toByteArray(Charsets.UTF_8)
        } else {
            val relativePath = stored.relativePath
                ?: throw TavernHelperContentCorruptException("内容既没有内联值也没有文件路径")
            val file = root.resolve(relativePath)
            val rootPath = root.canonicalFile.toPath()
            val filePath = file.canonicalFile.toPath()
            if (!filePath.startsWith(rootPath)) {
                throw TavernHelperContentCorruptException("脚本文件路径越界")
            }
            if (!file.isFile) {
                throw TavernHelperContentCorruptException("脚本文件不存在: $relativePath")
            }
            Files.readAllBytes(filePath)
        }
        if (bytes.size.toLong() != stored.bytes || bytes.sha256() != stored.sha256) {
            throw TavernHelperContentCorruptException("脚本内容校验失败")
        }
        return bytes.toString(Charsets.UTF_8)
    }
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { "%02x".format(it) }
