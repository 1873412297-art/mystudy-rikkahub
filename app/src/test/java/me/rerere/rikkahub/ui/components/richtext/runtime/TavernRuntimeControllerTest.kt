package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
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
        val setResponse = controller.dispatch(
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
        val getResponse = controller.dispatch(
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
}
