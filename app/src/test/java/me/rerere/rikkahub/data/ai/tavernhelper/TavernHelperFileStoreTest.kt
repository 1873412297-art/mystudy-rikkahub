package me.rerere.rikkahub.data.ai.tavernhelper

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernHelperFileStoreTest {
    @Test
    fun `small content stays inline and large content is atomically externalized`() {
        val root = Files.createTempDirectory("tavern-helper-store").toFile()
        try {
            val store = TavernHelperFileStore(root)
            val small = store.store(TavernHelperFileKind.SOURCE, "small", "hello")
            val largeText = "脚本".repeat(30_000)
            val large = store.store(TavernHelperFileKind.SOURCE, "large", largeText)

            assertEquals("hello", small.inline)
            assertNull(small.relativePath)
            assertNull(large.inline)
            assertNotNull(large.relativePath)
            assertTrue(large.relativePath!!.startsWith("source/"))
            assertEquals(largeText, store.read(large))
            assertTrue(root.walkTopDown().none { it.name.endsWith(".tmp") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test(expected = TavernHelperContentCorruptException::class)
    fun `hash mismatch disables use of corrupted external content`() {
        val root = Files.createTempDirectory("tavern-helper-corrupt").toFile()
        try {
            val store = TavernHelperFileStore(root, inlineThresholdBytes = 1)
            val stored = store.store(TavernHelperFileKind.DATA, "data", "{\"value\":1}")
            root.resolve(stored.relativePath!!).writeText("corrupted")

            store.read(stored)
        } finally {
            root.deleteRecursively()
        }
    }
}
