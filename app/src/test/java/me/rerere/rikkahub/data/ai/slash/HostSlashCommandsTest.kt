package me.rerere.rikkahub.data.ai.slash

import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.ai.status.StatusVariableStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class HostSlashCommandsTest {

    private val conversationId = Uuid.random()
    private val store = StatusVariableStore()

    @Test
    fun `setvar and getvar round trip`() {
        store.set(conversationId, JsonObject(emptyMap()))
        val r1 = HostSlashCommands.execute("setvar", "gold 42", conversationId, store)!!
        assertTrue(r1.text!!.contains("42"))
        val r2 = HostSlashCommands.execute("getvar", "gold", conversationId, store)!!
        assertTrue(r2.text!!.contains("42"))
    }

    @Test
    fun `add and sub mutate numeric chat variables`() {
        store.set(conversationId, JsonObject(emptyMap()))
        HostSlashCommands.execute("setvar", "hp 10", conversationId, store)
        HostSlashCommands.execute("add", "hp 5", conversationId, store)
        assertEquals("15", StatusVariableStoreAccessor(conversationId, store).get("hp"))
        HostSlashCommands.execute("sub", "hp 3", conversationId, store)
        assertEquals("12", StatusVariableStoreAccessor(conversationId, store).get("hp"))
    }

    @Test
    fun `random picks one of the options`() {
        val result = HostSlashCommands.execute("random", "a,b,c", conversationId, store)!!
        assertTrue(listOf("a", "b", "c").any { result.text!!.contains(it) })
    }

    @Test
    fun `roll returns a number within range`() {
        val result = HostSlashCommands.execute("roll", "2d6", conversationId, store)!!
        val total = result.text!!.trim().toIntOrNull()
        assertTrue(total != null && total in 2..12)
    }

    @Test
    fun `echo returns the input`() {
        assertEquals("hello world", HostSlashCommands.execute("echo", "hello world", conversationId, store)!!.text)
    }

    @Test
    fun `unknown command returns null`() {
        assertNull(HostSlashCommands.execute("nonexistent", "x", conversationId, store))
    }

    @Test
    fun `getvar for missing key reports missing`() {
        store.set(conversationId, JsonObject(emptyMap()))
        val result = HostSlashCommands.execute("getvar", "missing", conversationId, store)!!
        assertTrue(result.error != null || result.text!!.contains("not set", ignoreCase = true))
    }
}
