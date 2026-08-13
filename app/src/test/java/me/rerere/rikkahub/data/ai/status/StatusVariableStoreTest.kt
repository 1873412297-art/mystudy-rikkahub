package me.rerere.rikkahub.data.ai.status

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * [StatusVariableStore] 的单元测试，重点覆盖会话级变量存储的读写与生命周期清理。
 */
class StatusVariableStoreTest {

    private val convId = Uuid.random()

    @Test
    fun `uninitialized conversation returns empty object`() {
        val store = StatusVariableStore()
        assertEquals(JsonObject(emptyMap()), store.getValue(convId))
    }

    @Test
    fun `init stores initial variables and getValue returns them`() {
        val store = StatusVariableStore()
        val initial = buildJsonObject { put("hp", JsonPrimitive("80")) }
        store.init(convId, initial)
        assertEquals(initial, store.getValue(convId))
    }

    @Test
    fun `applyPatch updates state and returns updated object`() {
        val store = StatusVariableStore()
        store.init(convId, buildJsonObject { put("hp", JsonPrimitive("80")) })

        val updated = store.applyPatch(
            convId,
            listOf(JsonPatchOp(op = "replace", path = "/hp", value = JsonPrimitive("30"))),
        )

        assertEquals(JsonPrimitive("30"), updated["hp"])
        assertEquals(JsonPrimitive("30"), store.getValue(convId)["hp"])
    }

    @Test
    fun `set overwrites entire state`() {
        val store = StatusVariableStore()
        store.init(convId, buildJsonObject { put("a", JsonPrimitive("1")) })
        store.set(convId, buildJsonObject { put("b", JsonPrimitive("2")) })
        assertEquals(setOf("b"), store.getValue(convId).keys)
    }

    @Test
    fun `remove clears the entry and getValue falls back to empty`() {
        val store = StatusVariableStore()
        store.init(convId, buildJsonObject { put("hp", JsonPrimitive("80")) })
        assertTrue(store.getValue(convId).isNotEmpty())

        store.remove(convId)

        assertEquals(JsonObject(emptyMap()), store.getValue(convId))
    }

    @Test
    fun `remove is idempotent for unknown conversation`() {
        val store = StatusVariableStore()
        // 不抛异常即可
        store.remove(convId)
        store.remove(convId)
        assertEquals(JsonObject(emptyMap()), store.getValue(convId))
    }

    @Test
    fun `remove does not affect other conversations`() {
        val store = StatusVariableStore()
        val other = Uuid.random()
        store.init(convId, buildJsonObject { put("a", JsonPrimitive("1")) })
        store.init(other, buildJsonObject { put("b", JsonPrimitive("2")) })

        store.remove(convId)

        assertEquals(setOf("b"), store.getValue(other).keys)
        assertTrue(store.getValue(convId).isEmpty())
    }

    @Test
    fun `toJsObject converts stored state to plain map`() {
        val store = StatusVariableStore()
        store.init(convId, buildJsonObject { put("hp", JsonPrimitive("80")) })
        val js = store.toJsObject(convId)
        assertEquals("80", js["hp"])
    }
}
