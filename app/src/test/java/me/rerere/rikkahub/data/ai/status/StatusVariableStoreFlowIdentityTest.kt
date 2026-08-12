package me.rerere.rikkahub.data.ai.status

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 回归测试：StatusVariableStore.init 必须原地更新 value，保持 StateFlow 身份稳定。
 *
 * 背景：web 层 SSE stream 订阅 getState 返回的 StateFlow；若 init 替换 map 条目（新 MutableStateFlow），
 * 已订阅的流将成为孤儿——此后 applyPatch 写入新条目、订阅者永远收不到更新
 * （web-ui 的 status_variables 实时重渲染在首次发送消息后失效）。
 */
class StatusVariableStoreFlowIdentityTest {

    @Test
    fun `init updates value in place without replacing flow`() {
        val store = StatusVariableStore()
        val id = Uuid.random()
        val before = store.getState(id)

        store.init(id, JsonObject(mapOf("hp" to JsonPrimitive(1))))

        val after = store.getState(id)
        assertSame(before, after)
        assertEquals(1L, after.value["hp"]?.jsonPrimitive?.long)
    }

    @Test
    fun `subscriber keeps receiving updates after init`() = runBlocking {
        val store = StatusVariableStore()
        val id = Uuid.random()
        val flow = store.getState(id)
        val received = mutableListOf<JsonObject>()

        val job = launch {
            flow.collect { received.add(it) }
        }
        yield()

        store.init(id, JsonObject(mapOf("hp" to JsonPrimitive(1))))
        yield()
        store.set(id, JsonObject(mapOf("hp" to JsonPrimitive(2))))
        yield()

        job.cancel()
        assertEquals(2L, received.last()["hp"]?.jsonPrimitive?.long)
    }
}
