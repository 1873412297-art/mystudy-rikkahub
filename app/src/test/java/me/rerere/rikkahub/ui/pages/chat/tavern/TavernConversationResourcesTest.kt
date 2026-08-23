package me.rerere.rikkahub.ui.pages.chat.tavern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TavernConversationResourcesTest {
    @Test
    fun `resource token authorizes one value then expires`() {
        var now = 1_000L
        val store = ExpiringResourceTokenStore<String>(
            nowMillis = { now },
            tokenFactory = { "token" },
            ttlMillis = 500L,
        )

        assertEquals("token", store.put("content://allowed"))
        assertEquals("content://allowed", store.get("token"))
        assertNull(store.get("unknown"))
        now = 1_500L
        assertNull(store.get("token"))
    }

    @Test
    fun `file authorization rejects sibling and traversal targets`() {
        val root = File("C:/app/files").canonicalFile
        assertTrue(isPathWithinRoots(File(root, "media/image.png").canonicalFile, listOf(root)))
        assertFalse(isPathWithinRoots(File(root, "../secret.txt").canonicalFile, listOf(root)))
        assertFalse(isPathWithinRoots(File("C:/app/files-evil/image.png").canonicalFile, listOf(root)))
    }
}
