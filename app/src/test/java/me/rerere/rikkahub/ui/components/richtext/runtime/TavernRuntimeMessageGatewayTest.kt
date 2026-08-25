package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.TavernRuntimePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class TavernRuntimeMessageGatewayTest {
    @Test
    fun `in memory gateway returns selected branch in node order`() {
        val conversationId = Uuid.parse("00000000-0000-4000-8000-000000000301")
        val gateway = InMemoryTavernRuntimeMessageGateway(
            initialMessages = mapOf(conversationId to listOf(
                TavernRuntimeMessage("one", MessageRole.USER, "first", true),
                TavernRuntimeMessage("two", MessageRole.ASSISTANT, "selected", true),
            )),
        )

        val messages = gateway.list(conversationId)

        assertEquals(listOf("one", "two"), messages.map { it.messageId })
        assertEquals(listOf("user", "assistant"), messages.map { it.role })
        assertEquals(listOf(false, true), messages.map { it.isCurrent })
        assertEquals("selected", gateway.get(conversationId, "two")?.text)
    }

    @Test
    fun `message RPC reads creates updates and deletes the bound conversation`() {
        val conversationId = Uuid.parse("00000000-0000-4000-8000-000000000302")
        val gateway = InMemoryTavernRuntimeMessageGateway(
            initialMessages = mapOf(conversationId to listOf(
                TavernRuntimeMessage("user-1", MessageRole.USER, "hello", true),
                TavernRuntimeMessage("assistant-1", MessageRole.ASSISTANT, "old", true),
            )),
        )
        val controller = TavernRuntimeController(
            conversationId = conversationId,
            permissionStore = TavernRuntimePermissionStore(
                TavernRuntimePermissions(allowMessageWrite = true),
            ),
            messageGateway = gateway,
        )

        val listed = controller.dispatch(request("messages.list"))
        val updated = controller.dispatch(request("messages.update", "id" to "assistant-1", "text" to "new"))
        val created = controller.dispatch(request("messages.create", "role" to "system", "text" to "note"))
        val deleted = controller.dispatch(request("messages.delete", "id" to "user-1"))

        assertEquals(listOf("user-1", "assistant-1"), listed.result!!.jsonArray.map {
            it.jsonObject.getValue("messageId").jsonPrimitive.content
        })
        assertEquals("new", updated.result!!.jsonObject.getValue("text").jsonPrimitive.content)
        val createdMessage = created.result!!.jsonObject
        assertEquals("system", createdMessage.getValue("role").jsonPrimitive.content)
        assertTrue(deleted.ok)
        assertEquals(listOf("assistant-1", createdMessage.getValue("messageId").jsonPrimitive.content),
            gateway.list(conversationId).map { it.messageId })
    }

    @Test
    fun `message writes deny mutations without permission and missing data is structured`() {
        val conversationId = Uuid.parse("00000000-0000-4000-8000-000000000303")
        val gateway = InMemoryTavernRuntimeMessageGateway(
            mapOf(conversationId to listOf(TavernRuntimeMessage("one", MessageRole.USER, "original", true))),
        )
        val denied = TavernRuntimeController(conversationId = conversationId, messageGateway = gateway)

        val write = denied.dispatch(request("messages.update", "id" to "one", "text" to "changed"))
        val missing = denied.dispatch(request("messages.get"))

        assertFalse(write.ok)
        assertEquals("PERMISSION_DENIED", write.error!!.code)
        assertEquals("original", gateway.get(conversationId, "one")!!.text)
        assertFalse(missing.ok)
        assertEquals("BAD_REQUEST", missing.error!!.code)
    }

    @Test
    fun `message current favors injected message otherwise updates real latest message`() {
        val conversationId = Uuid.parse("00000000-0000-4000-8000-000000000304")
        val gateway = InMemoryTavernRuntimeMessageGateway(
            mapOf(conversationId to listOf(TavernRuntimeMessage("one", MessageRole.ASSISTANT, "old", true))),
        )
        val controller = TavernRuntimeController(
            conversationId = conversationId,
            permissionStore = TavernRuntimePermissionStore(TavernRuntimePermissions(allowMessageWrite = true)),
            messageGateway = gateway,
        )

        val updatedReal = controller.dispatch(request("messages.updateCurrent", "patch" to buildJsonObject { put("text", "new") }))
        controller.setCurrentMessage(buildJsonObject { put("messageId", "injected"); put("text", "before") })
        val injected = controller.dispatch(request("messages.getCurrent"))

        assertEquals("new", updatedReal.result!!.jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("injected", injected.result!!.jsonObject.getValue("messageId").jsonPrimitive.content)
    }

    @Test
    fun `message APIs require an active conversation`() {
        val response = TavernRuntimeController().dispatch(request("messages.list"))

        assertFalse(response.ok)
        assertEquals("NO_ACTIVE_CONVERSATION", response.error!!.code)
    }

    @Test
    fun `getCurrent without injected message requires an active conversation`() {
        val response = TavernRuntimeController().dispatch(request("messages.getCurrent"))

        assertFalse(response.ok)
        assertEquals("NO_ACTIVE_CONVERSATION", response.error!!.code)
    }

    @Test
    fun `runtime script exposes every TavernHelper message function through TH`() {
        val script = buildTavernRuntimeScript()

        assertTrue(script.contains("list: function(){ return call('messages.list', {})"))
        assertTrue(script.contains("get: function(id){ return call('messages.get', { id: id })"))
        assertTrue(script.contains("create: function(role, text){ return call('messages.create', { role: role, text: text })"))
        assertTrue(script.contains("update: function(id, text){ return call('messages.update', { id: id, text: text })"))
        assertTrue(script.contains("updateCurrent: function(patch){ return call('messages.updateCurrent', { patch: patch })"))
        assertTrue(script.contains("delete: function(id){ return call('messages.delete', { id: id })"))
        assertTrue(script.contains("window.TH = window.TH || api"))
    }

    private fun request(method: String, vararg params: Pair<String, Any>) = TavernRuntimeRequest(
        id = method,
        method = method,
        params = buildJsonObject {
            params.forEach { (key, value) ->
                when (value) {
                    is String -> put(key, value)
                    is kotlinx.serialization.json.JsonElement -> put(key, value)
                    else -> error("Unsupported test parameter: $key")
                }
            }
        },
    )
}
