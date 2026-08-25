package me.rerere.rikkahub.data.sync

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 酒馆助手脚本文件（filesDir/tavern-helper 下的 source/ 与 data/）的备份与恢复。
 * WebDAV 与 S3 两条同步链路共用；脚本数据库记录随 rikka_hub.db 整体备份，
 * 这里只负责文件体外溢部分（>64KB 的源码/数据）。
 */
internal object TavernHelperBackupIO {
    const val ROOT_DIRECTORY = "tavern-helper"
    const val ENTRY_PREFIX = "$ROOT_DIRECTORY/"

    /** 把 tavern-helper 目录整体写入 zip（保持相对路径），目录不存在时跳过。 */
    fun backup(filesDir: File, zipOut: ZipOutputStream) {
        val root = File(filesDir, ROOT_DIRECTORY)
        if (!root.isDirectory) {
            return
        }
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(root).invariantSeparatorsPath
            FileInputStream(file).use { input ->
                zipOut.putNextEntry(ZipEntry("$ENTRY_PREFIX$relative"))
                input.copyTo(zipOut)
                zipOut.closeEntry()
            }
        }
    }

    /**
     * 恢复单条 tavern-helper zip 条目。返回 true 表示该条目已被消费。
     * 拒绝路径越界（.. / 绝对路径）条目，防止 zip 滑出目标目录。
     */
    fun restoreEntry(filesDir: File, zipIn: ZipInputStream, entryName: String): Boolean {
        if (!entryName.startsWith(ENTRY_PREFIX)) return false
        val relative = entryName.removePrefix(ENTRY_PREFIX)
        if (relative.isEmpty() || relative.endsWith("/")) return true
        val segments = relative.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            return true
        }
        val root = File(filesDir, ROOT_DIRECTORY).apply { mkdirs() }
        val target = File(root, relative)
        if (!target.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) {
            return true
        }
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { output ->
            zipIn.copyTo(output)
        }
        return true
    }
}
