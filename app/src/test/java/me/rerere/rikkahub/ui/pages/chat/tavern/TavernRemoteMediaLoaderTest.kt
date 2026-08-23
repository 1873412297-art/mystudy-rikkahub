package me.rerere.rikkahub.ui.pages.chat.tavern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class TavernRemoteMediaLoaderTest {
    @Test
    fun `only remote image requests are classified`() {
        assertTrue(isLikelyTavernImageRequest("https://files.example/portrait", "image/webp,*/*"))
        assertTrue(isLikelyTavernImageRequest("https://files.example/card.PNG?version=2", null))
        assertFalse(isLikelyTavernImageRequest("https://files.example/app.js", "*/*"))
        assertFalse(isLikelyTavernImageRequest("file:///sdcard/card.png", "image/*"))
        assertFalse(isLikelyTavernImageRequest("javascript:alert(1)", "image/*"))
    }

    @Test
    fun `metadata rejects html and oversized images while allowing streamed lengths`() {
        assertEquals("image/webp", validateTavernImageMetadata("image/webp; charset=binary", 1_024))
        assertEquals("image/png", validateTavernImageMetadata("IMAGE/PNG", 0))
        assertNull(validateTavernImageMetadata("text/html", 1_024))
        assertEquals("image/png", validateTavernImageMetadata("image/png", -1))
        assertNull(validateTavernImageMetadata("image/png", TAVERN_MEDIA_MAX_BYTES + 1))
    }

    @Test
    fun `non image request bypasses fetcher`() {
        val calls = AtomicInteger()
        val loader = TavernRemoteMediaLoader.forTest { _, _ ->
            calls.incrementAndGet()
            null
        }

        assertNull(loader.load("https://files.example/app.js", mapOf("Accept" to "*/*")))
        assertEquals(0, calls.get())
    }

    @Test
    fun `concurrent identical image requests share one fetch`() {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstThread = AtomicReference<Thread>()
        val secondThread = AtomicReference<Thread>()
        val loader = TavernRemoteMediaLoader.forTest { _, _ ->
            calls.incrementAndGet()
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            TavernRemoteMediaPayload("image/png", byteArrayOf(1, 2, 3), mapOf("Cache-Control" to "max-age=60"))
        }
        val pool = Executors.newFixedThreadPool(2)

        try {
            val first = pool.submit<TavernRemoteMediaPayload?> {
                firstThread.set(Thread.currentThread())
                loader.load(REMOTE_IMAGE, mapOf("Accept" to "image/*"))
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val second = pool.submit<TavernRemoteMediaPayload?> {
                secondThread.set(Thread.currentThread())
                loader.load(REMOTE_IMAGE, mapOf("Accept" to "image/*"))
            }
            assertTrue("both callers did not overlap", awaitWaiting(firstThread, secondThread))
            release.countDown()

            assertNotNull(first.get(2, TimeUnit.SECONDS))
            assertNotNull(second.get(2, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            pool.shutdownNow()
            loader.close()
        }
    }

    private companion object {
        const val REMOTE_IMAGE = "https://files.example/portrait.webp"

        fun awaitWaiting(first: AtomicReference<Thread>, second: AtomicReference<Thread>): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (System.nanoTime() < deadline) {
                val states = listOfNotNull(first.get(), second.get()).map(Thread::getState)
                if (states.size == 2 && states.all { it == Thread.State.WAITING || it == Thread.State.TIMED_WAITING }) {
                    return true
                }
                Thread.yield()
            }
            return false
        }
    }
}
