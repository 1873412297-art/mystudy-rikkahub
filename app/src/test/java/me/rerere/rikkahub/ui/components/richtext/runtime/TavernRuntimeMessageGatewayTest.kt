package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.service.TavernRuntimeMessageService
import me.rerere.rikkahub.data.model.TavernRuntimePermissions
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class TavernRuntimeMessageGatewayTest {
    @Test
    fun `production gateway maps an unavailable atomic read to conversation not ready`() {
        val conversationId = Uuid.random()
        val service = object : TavernRuntimeMessageService {
            var atomicReads = 0

            override fun isTavernRuntimeConversationReady(conversationId: Uuid): Boolean = true

            override fun getTavernRuntimeMessages(conversationId: Uuid): List<UIMessage> =
                error("Gateway must use the atomic runtime snapshot")

            override suspend fun readTavernRuntimeMessageSnapshot(conversationId: Uuid): List<UIMessage>? {
                atomicReads++
                return null
            }

            override suspend fun createTavernRuntimeMessage(
                conversationId: Uuid,
                role: MessageRole,
                text: String,
            ): UIMessage = error("not used")

            override suspend fun updateTavernRuntimeMessageText(
                conversationId: Uuid,
                messageId: Uuid,
                text: String,
            ): UIMessage? = error("not used")

            override suspend fun deleteTavernRuntimeMessage(conversationId: Uuid, messageId: Uuid): Boolean =
                error("not used")
        }
        val controller = TavernRuntimeController(
            conversationId = conversationId,
            messageGateway = ChatServiceTavernRuntimeMessageGateway(service),
        )

        val response = controller.dispatch(request("messages.list"))

        assertFalse(response.ok)
        assertEquals("CONVERSATION_NOT_READY", response.error!!.code)
        assertTrue(service.atomicReads > 0)
    }

    @Test
    fun `production gateway maps the committed ChatService message state`() {
        val conversationId = Uuid.random()
        val service = object : TavernRuntimeMessageService {
            val message = UIMessage.assistant("persisted")
            val newer = UIMessage.user("newer")
            override fun isTavernRuntimeConversationReady(conversationId: Uuid): Boolean = true
            override fun getTavernRuntimeMessages(conversationId: Uuid): List<UIMessage> = listOf(message, newer)
            override suspend fun createTavernRuntimeMessage(
                conversationId: Uuid,
                role: MessageRole,
                text: String,
            ): UIMessage = message

            override suspend fun updateTavernRuntimeMessageText(
                conversationId: Uuid,
                messageId: Uuid,
                text: String,
            ): UIMessage? = message

            override suspend fun deleteTavernRuntimeMessage(conversationId: Uuid, messageId: Uuid): Boolean = true
        }
        val gateway = ChatServiceTavernRuntimeMessageGateway(service)

        val created = gateway.create(conversationId, MessageRole.ASSISTANT, "ignored")

        assertEquals(service.message.id.toString(), created.messageId)
        assertEquals("assistant", created.role)
        assertFalse(created.isCurrent)
    }

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

        val updatedReal = controller.dispatch(
            request("messages.updateCurrent", "patch" to buildJsonObject { put("text", "new") }),
        )
        controller.setCurrentMessage(buildJsonObject { put("messageId", "injected"); put("text", "before") })
        val injected = controller.dispatch(request("messages.getCurrent"))

        assertEquals("new", updatedReal.result!!.jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("injected", injected.result!!.jsonObject.getValue("messageId").jsonPrimitive.content)
    }

    @Test
    fun `updateCurrent delegates one atomic latest update without a snapshot read`() {
        val conversationId = Uuid.random()
        var snapshotReads = 0
        var latestUpdates = 0
        val gateway = object : TavernRuntimeMessageGateway {
            override fun readSnapshot(conversationId: Uuid): List<TavernRuntimeMessage>? {
                snapshotReads++
                return error("updateCurrent must not pre-read the latest message")
            }

            override fun list(conversationId: Uuid): List<TavernRuntimeMessage> = emptyList()

            override fun get(conversationId: Uuid, messageId: String): TavernRuntimeMessage? = null

            override fun create(conversationId: Uuid, role: MessageRole, text: String): TavernRuntimeMessage =
                error("not used")

            override fun update(conversationId: Uuid, messageId: String, text: String): TavernRuntimeMessage? =
                error("updateCurrent must not use id-based update")

            override fun updateLatest(conversationId: Uuid, text: String): TavernRuntimeMessage? {
                latestUpdates++
                return TavernRuntimeMessage("latest", MessageRole.ASSISTANT, text, isCurrent = true)
            }

            override fun delete(conversationId: Uuid, messageId: String): Boolean = false
        }
        val controller = TavernRuntimeController(
            conversationId = conversationId,
            permissionStore = TavernRuntimePermissionStore(TavernRuntimePermissions(allowMessageWrite = true)),
            messageGateway = gateway,
        )

        val updated = controller.dispatch(
            request("messages.updateCurrent", "patch" to buildJsonObject { put("text", "new") }),
        )

        assertTrue(updated.ok)
        assertEquals(1, latestUpdates)
        assertEquals(0, snapshotReads)
        assertEquals("latest", updated.result!!.jsonObject.getValue("messageId").jsonPrimitive.content)
    }

    @Test
    fun `serializer injected old message persists updateCurrent through its exact id`() {
        val conversationId = Uuid.random()
        var persisted = UIMessage.assistant("before")
        var latest = UIMessage.assistant("latest")
        var updatedId: String? = null
        var latestUpdates = 0
        val gateway = object : TavernRuntimeMessageGateway {
            override fun list(conversationId: Uuid): List<TavernRuntimeMessage> = listOf(
                TavernRuntimeMessage(
                    persisted.id.toString(),
                    persisted.role,
                    persisted.toText(),
                    isCurrent = false,
                ),
                TavernRuntimeMessage(latest.id.toString(), latest.role, latest.toText(), isCurrent = true),
            )

            override fun get(conversationId: Uuid, messageId: String): TavernRuntimeMessage? =
                list(conversationId).singleOrNull { it.messageId == messageId }

            override fun create(conversationId: Uuid, role: MessageRole, text: String): TavernRuntimeMessage =
                error("not used")

            override fun update(
                conversationId: Uuid,
                messageId: String,
                text: String,
            ): TavernRuntimeMessage? {
                updatedId = messageId
                if (messageId != persisted.id.toString()) return null
                persisted = persisted.copy(parts = listOf(me.rerere.ai.ui.UIMessagePart.Text(text)))
                return get(conversationId, messageId)
            }

            override fun updateLatest(conversationId: Uuid, text: String): TavernRuntimeMessage? {
                latestUpdates++
                latest = latest.copy(parts = listOf(me.rerere.ai.ui.UIMessagePart.Text(text)))
                return get(conversationId, latest.id.toString())
            }

            override fun delete(conversationId: Uuid, messageId: String): Boolean = false
        }
        val controller = TavernRuntimeController(
            conversationId = conversationId,
            messageGateway = gateway,
            permissionStore = TavernRuntimePermissionStore(TavernRuntimePermissions(allowMessageWrite = true)),
        )
        controller.setCurrentMessage(JsonInstant.encodeToJsonElement(UIMessage.serializer(), persisted))

        val updated = controller.dispatch(
            request("messages.updateCurrent", "patch" to buildJsonObject { put("text", "after") }),
        )
        val current = controller.dispatch(request("messages.getCurrent"))

        assertEquals(persisted.id.toString(), updatedId)
        assertEquals(0, latestUpdates)
        assertEquals("after", persisted.toText())
        assertEquals("latest", latest.toText())
        assertEquals(persisted.id.toString(), updated.result!!.jsonObject.getValue("messageId").jsonPrimitive.content)
        assertEquals("after", updated.result!!.jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(updated.result, current.result)
    }

    @Test
    fun `message APIs require an active conversation`() {
        val response = TavernRuntimeController().dispatch(request("messages.list"))

        assertFalse(response.ok)
        assertEquals("NO_ACTIVE_CONVERSATION", response.error!!.code)
    }

    @Test
    fun `ready conversation without messages returns not found for getCurrent`() {
        val controller = TavernRuntimeController(conversationId = Uuid.random())

        val response = controller.dispatch(request("messages.getCurrent"))

        assertFalse(response.ok)
        assertEquals("NOT_FOUND", response.error!!.code)
    }

    @Test
    fun `rebind clears injected current message before reading the new conversation`() {
        val firstConversationId = Uuid.random()
        val secondConversationId = Uuid.random()
        val gateway = InMemoryTavernRuntimeMessageGateway(
            mapOf(secondConversationId to listOf(
                TavernRuntimeMessage("second", MessageRole.ASSISTANT, "persisted", true),
            )),
        )
        val controller = TavernRuntimeController(conversationId = firstConversationId, messageGateway = gateway)
        controller.setCurrentMessage(buildJsonObject { put("messageId", "injected"); put("text", "old") })

        controller.updateConversationId(secondConversationId)
        val response = controller.dispatch(request("messages.getCurrent"))

        assertTrue(response.ok)
        assertEquals("second", response.result!!.jsonObject.getValue("messageId").jsonPrimitive.content)
    }

    @Test
    fun `create response rereads committed current status`() {
        val conversationId = Uuid.random()
        val created = TavernRuntimeMessage("created", MessageRole.USER, "new", true)
        val gateway = object : TavernRuntimeMessageGateway {
            override fun list(conversationId: Uuid): List<TavernRuntimeMessage> = emptyList()
            override fun get(conversationId: Uuid, messageId: String): TavernRuntimeMessage? =
                created.copy(isCurrent = false)

            override fun create(conversationId: Uuid, role: MessageRole, text: String): TavernRuntimeMessage = created
            override fun update(conversationId: Uuid, messageId: String, text: String): TavernRuntimeMessage? = null
            override fun delete(conversationId: Uuid, messageId: String): Boolean = false
        }
        val controller = TavernRuntimeController(
            conversationId = conversationId,
            messageGateway = gateway,
            permissionStore = TavernRuntimePermissionStore(TavernRuntimePermissions(allowMessageWrite = true)),
        )

        val response = controller.dispatch(request("messages.create", "role" to "user", "text" to "new"))

        assertTrue(response.ok)
        assertFalse(response.result!!.jsonObject.getValue("isCurrent").jsonPrimitive.boolean)
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
        assertTrue(
            script.contains("create: function(role, text){ return call('messages.create', { role: role, text: text })"),
        )
        assertTrue(
            script.contains("update: function(id, text){ return call('messages.update', { id: id, text: text })"),
        )
        assertTrue(
            script.contains("updateCurrent: function(patch){ return call('messages.updateCurrent', { patch: patch })"),
        )
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
