package me.rerere.rikkahub.data.ai.slash

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.status.StatusVariableStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class StatusVariableStoreAccessorTest {

    @Test
    fun `reads and writes single keys against chat variables`() {
        val store = StatusVariableStore()
        val conversationId = Uuid.random()
        store.set(conversationId, buildJsonObject { put("hp", 100) })
        val accessor = StatusVariableStoreAccessor(conversationId, store)

        assertEquals("100", accessor.get("hp"))
        accessor.set("mp", "50")
        assertEquals("50", accessor.get("mp"))
        accessor.delete("hp")
        assertNull(accessor.get("hp"))
        assertEquals(1, accessor.all().size)
    }

    @Test
    fun `returns null for missing key and missing conversation`() {
        val store = StatusVariableStore()
        val accessor = StatusVariableStoreAccessor(null, store)
        assertNull(accessor.get("anything"))
        assertEquals(0, accessor.all().size)
    }

    @Test
    fun `numeric values survive round trip as strings`() {
        val store = StatusVariableStore()
        val conversationId = Uuid.random()
        val accessor = StatusVariableStoreAccessor(conversationId, store)
        accessor.set("gold", "42")
        assertEquals("42", accessor.get("gold"))
    }
}
