package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import me.rerere.rikkahub.data.model.TavernRuntimePermissions

class TavernRuntimeControllerTest {
    private val controller = TavernRuntimeController()

    @Test
    fun `ping returns pong`() {
        val response = controller.dispatch(
            TavernRuntimeRequest(id = "1", method = "runtime.ping")
        )

        assertTrue(response.ok)
        assertEquals("pong", response.result!!.jsonPrimitive.content)
    }

    @Test
    fun `unknown method returns unsupported error`() {
        val response = controller.dispatch(
            TavernRuntimeRequest(id = "2", method = "unknown.method")
        )

        assertFalse(response.ok)
        assertEquals("UNSUPPORTED", response.error!!.code)
    }

    @Test
    fun `variables set then get returns value`() {
        val writeController = TavernRuntimeController(
            permissionStore = TavernRuntimePermissionStore(
                initial = TavernRuntimePermissions(allowVariablesWrite = true)
            )
        )
        val setResponse = writeController.dispatch(
            TavernRuntimeRequest(
                id = "3",
                method = "variables.set",
                params = JsonObject(
                    mapOf(
                        "scope" to JsonPrimitive("chat"),
                        "key" to JsonPrimitive("favor"),
                        "value" to JsonPrimitive("1"),
                    )
                ),
            )
        )
        val getResponse = writeController.dispatch(
            TavernRuntimeRequest(
                id = "4",
                method = "variables.get",
                params = JsonObject(
                    mapOf(
                        "scope" to JsonPrimitive("chat"),
                        "key" to JsonPrimitive("favor"),
                    )
                ),
            )
        )

        assertTrue(setResponse.ok)
        assertEquals("1", getResponse.result!!.jsonPrimitive.content)
    }

    @Test
    fun `slash help lists supported commands`() {
        val response = controller.dispatch(
            TavernRuntimeRequest(
                id = "5",
                method = "slash.run",
                params = JsonObject(mapOf("command" to JsonPrimitive("/th help"))),
            )
        )

        assertTrue(response.ok)
        assertTrue(response.result!!.jsonPrimitive.content.contains("/th help"))
    }

    @Test
    fun `unknown slash command returns unsupported`() {
        val response = controller.dispatch(
            TavernRuntimeRequest(
                id = "6",
                method = "slash.run",
                params = JsonObject(mapOf("command" to JsonPrimitive("/unknown"))),
            )
        )

        assertFalse(response.ok)
        assertEquals("UNSUPPORTED_SLASH_COMMAND", response.error!!.code)
    }

    @Test
    fun `events emit records event payload`() {
        val response = controller.dispatch(
            TavernRuntimeRequest(
                id = "7",
                method = "events.emit",
                params = JsonObject(
                    mapOf(
                        "name" to JsonPrimitive("message_rendered"),
                        "payload" to JsonPrimitive("ok"),
                    )
                ),
            )
        )

        assertTrue(response.ok)
        assertEquals("message_rendered", response.result!!.jsonPrimitive.content)
    }

    @Test
    fun `world write denied when permission disallows it`() {
        val deniedController = TavernRuntimeController(
            permissionStore = TavernRuntimePermissionStore(
                initial = TavernRuntimePermissions(allowScripts = true, allowWorldWrite = false)
            )
        )
        val response = deniedController.dispatch(
            TavernRuntimeRequest(
                id = "8",
                method = "world.upsertEntry",
                params = JsonObject(mapOf("entry" to JsonObject(mapOf("id" to JsonPrimitive("x"))))),
            )
        )

        assertFalse(response.ok)
        assertEquals("PERMISSION_DENIED", response.error!!.code)
    }

    @Test
    fun `scripts disabled blocks non ping methods`() {
        val deniedController = TavernRuntimeController(
            permissionStore = TavernRuntimePermissionStore(
                initial = TavernRuntimePermissions(allowScripts = false)
            )
        )
        val response = deniedController.dispatch(
            TavernRuntimeRequest(
                id = "9",
                method = "slash.run",
                params = JsonObject(mapOf("command" to JsonPrimitive("/th ping"))),
            )
        )

        assertFalse(response.ok)
        assertEquals("PERMISSION_DENIED", response.error!!.code)
    }

    @Test
    fun `ping still works when scripts are disabled`() {
        val deniedController = TavernRuntimeController(
            permissionStore = TavernRuntimePermissionStore(
                initial = TavernRuntimePermissions(allowScripts = false)
            )
        )
        val response = deniedController.dispatch(
            TavernRuntimeRequest(id = "10", method = "runtime.ping")
        )

        assertTrue(response.ok)
        assertEquals("pong", response.result!!.jsonPrimitive.content)
    }

    @Test
    fun `setContext emits context_updated event and dedupes unchanged context`() = runBlocking {
        val controller = TavernRuntimeController(
            conversationId = Uuid.random(),
            permissionStore = TavernRuntimePermissionStore(
                TavernRuntimePermissions().copy(allowScripts = true)
            ),
        )
        val received = mutableListOf<Pair<String, JsonElement?>>()
        val job = launch {
            controller.outboundEvents.collect { received.add(it) }
        }
        yield()
        val ctx = buildJsonObject {
            put("chat", JsonArray(emptyList()))
            put("conversationId", "c1")
        }
        controller.setContext(ctx)
        yield()
        controller.setContext(ctx) // 相同内容 → 去重，不再发
        yield()
        job.cancel()
        assertEquals(1, received.count { it.first == "context_updated" })
    }

    @Test
    fun `messages getCurrent returns current chat entry from context when set`() {
        val controller = TavernRuntimeController(
            conversationId = Uuid.random(),
            permissionStore = TavernRuntimePermissionStore(
                TavernRuntimePermissions().copy(allowScripts = true)
            ),
        )
        val m1 = buildJsonObject {
            put("role", "user")
            put("text", "hello")
            put("messageId", "m1")
            put("isCurrent", false)
        }
        val m2 = buildJsonObject {
            put("role", "assistant")
            put("text", "hi")
            put("messageId", "m2")
            put("isCurrent", true)
        }
        controller.setContext(
            buildJsonObject {
                put("chat", JsonArray(listOf(m1, m2)))
                put("conversationId", "c1")
            }
        )
        val response = controller.dispatch(
            TavernRuntimeRequest(id = "1", method = "messages.getCurrent", params = JsonObject(emptyMap()))
        )
        assertTrue(response.ok)
        assertEquals("m2", response.result!!.jsonObject["messageId"]!!.jsonPrimitive.content)
    }
}
