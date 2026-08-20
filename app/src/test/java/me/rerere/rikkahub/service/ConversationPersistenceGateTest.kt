package me.rerere.rikkahub.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationPersistenceGateTest {
    @Test
    fun `same conversation persistence is serialized in arrival order`() = runBlocking {
        val gate = ConversationPersistenceGate()
        val conversationId = Uuid.parse("40000000-0000-4000-8000-000000000001")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val order = mutableListOf<String>()

        val first = async(Dispatchers.Default) {
            gate.withConversation(conversationId) {
                order += "first-start"
                entered.countDown()
                assertTrue(release.await(2, TimeUnit.SECONDS))
                order += "first-end"
            }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val second = async(Dispatchers.Default) {
            gate.withConversation(conversationId) { order += "second" }
        }
        release.countDown()
        first.await()
        second.await()

        assertEquals(listOf("first-start", "first-end", "second"), order)
    }
}
