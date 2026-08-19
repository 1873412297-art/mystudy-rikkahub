package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.status.StatusVariableStore
import me.rerere.rikkahub.data.ai.status.TavernHostEvent
import me.rerere.rikkahub.data.ai.status.TavernHostEventType
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.TavernRuntimePermissions
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class TavernRuntimeScriptApiTest {

    // ── 变量作用域路由 ──────────────────────────────────────────────

    @Test
    fun `variables route by scope into separate stores`() {
        val controller = writeController()
        dispatchSet(controller, "chat", "favor", JsonPrimitive("1"))
        dispatchSet(controller, "global", "favor", JsonPrimitive("99"))

        val chatValue = dispatchGet(controller, "chat", "favor")
        val globalValue = dispatchGet(controller, "global", "favor")

        assertEquals("1", chatValue.result!!.jsonPrimitive.content)
        assertEquals("99", globalValue.result!!.jsonPrimitive.content)

        val chatList = dispatchList(controller, "chat")
        val globalList = dispatchList(controller, "global")
        assertEquals("1", chatList.result!!.jsonObject.getValue("favor").jsonPrimitive.content)
        assertEquals("99", globalList.result!!.jsonObject.getValue("favor").jsonPrimitive.content)
    }

    @Test
    fun `chat scope persists into StatusVariableStore`() {
        val conversationId = Uuid.parse("00000000-0000-4000-8000-000000000201")
        val store = StatusVariableStore()
        val gateway = StatusStoreTavernVariableGateway(store, FakeSettingsGateway())
        val controller = writeController(conversationId = conversationId, gateway = gateway)

        dispatchSet(controller, "chat", "favor", JsonPrimitive(10))

        // 复用既有持久化链路：ChatService 保存会话时读取 statusVariableStore.getValue
        assertEquals(
            JsonPrimitive(10),
            store.getValue(conversationId)["favor"],
        )

        val deleted = controller.dispatch(
            TavernRuntimeRequest(
                id = "d1",
                method = "variables.delete",
                params = JsonObject(mapOf("key" to JsonPrimitive("favor"))),
            )
        )
        assertTrue(deleted.ok)
        assertTrue(deleted.result!!.jsonPrimitive.content == "true")
        assertFalse(store.getValue(conversationId).containsKey("favor"))
    }

    @Test
    fun `global scope persists into settings tavernGlobalVariables`() {
        val settingsGateway = FakeSettingsGateway()
        val gateway = StatusStoreTavernVariableGateway(StatusVariableStore(), settingsGateway)
        val controller = writeController(gateway = gateway)

        dispatchSet(controller, "global", "theme", JsonPrimitive("dark"))
        assertEquals(
            JsonPrimitive("dark"),
            settingsGateway.current.tavernGlobalVariables["theme"],
        )

        val list = dispatchList(controller, "global")
        assertEquals("dark", list.result!!.jsonObject.getValue("theme").jsonPrimitive.content)

        val deleted = controller.dispatch(
            TavernRuntimeRequest(
                id = "d2",
                method = "variables.delete",
                params = JsonObject(
                    mapOf(
                        "scope" to JsonPrimitive("global"),
                        "key" to JsonPrimitive("theme"),
                    )
                ),
            )
        )
        assertTrue(deleted.ok)
        assertFalse(settingsGateway.current.tavernGlobalVariables.containsKey("theme"))
    }

    @Test
    fun `chat scope without conversation id falls back to ephemeral store`() {
        val gateway = StatusStoreTavernVariableGateway(StatusVariableStore(), FakeSettingsGateway())
        val controller = writeController(conversationId = null, gateway = gateway)

        dispatchSet(controller, "chat", "scratch", JsonPrimitive("v"))
        val value = dispatchGet(controller, "chat", "scratch")

        assertEquals("v", value.result!!.jsonPrimitive.content)
    }

    @Test
    fun `variables get returns JsonNull for missing key`() {
        val response = dispatchGet(writeController(), "chat", "missing")

        assertTrue(response.ok)
        assertEquals(JsonNull, response.result)
    }

    @Test
    fun `unsupported scope returns bad request`() {
        val controller = writeController()
        val response = controller.dispatch(
            TavernRuntimeRequest(
                id = "s1",
                method = "variables.get",
                params = JsonObject(
                    mapOf(
                        "scope" to JsonPrimitive("session"),
                        "key" to JsonPrimitive("k"),
                    )
                ),
            )
        )

        assertFalse(response.ok)
        assertEquals("BAD_REQUEST", response.error!!.code)
    }

    // ── 权限 ───────────────────────────────────────────────────────

    @Test
    fun `variables set denied without write permission`() {
        val controller = TavernRuntimeController(
            permissionStore = TavernRuntimePermissionStore(TavernRuntimePermissions(allowVariablesWrite = false)),
        )
        val response = dispatchSet(controller, "chat", "k", JsonPrimitive("v"))

        assertFalse(response.ok)
        assertEquals("PERMISSION_DENIED", response.error!!.code)
    }

    @Test
    fun `variables delete denied without write permission`() {
        val controller = TavernRuntimeController(
            permissionStore = TavernRuntimePermissionStore(TavernRuntimePermissions(allowVariablesWrite = false)),
        )
        val response = controller.dispatch(
            TavernRuntimeRequest(
                id = "p2",
                method = "variables.delete",
                params = JsonObject(mapOf("key" to JsonPrimitive("k"))),
            )
        )

        assertFalse(response.ok)
        assertEquals("PERMISSION_DENIED", response.error!!.code)
    }

    @Test
    fun `variables read works without write permission`() {
        val gateway = InMemoryTavernRuntimeVariableGateway()
        gateway.set(null, "chat", "k", JsonPrimitive("v"))
        val controller = TavernRuntimeController(
            permissionStore = TavernRuntimePermissionStore(TavernRuntimePermissions(allowVariablesWrite = false)),
            variableGateway = gateway,
        )

        val getResponse = dispatchGet(controller, "chat", "k")
        val listResponse = dispatchList(controller, "chat")

        assertTrue(getResponse.ok)
        assertEquals("v", getResponse.result!!.jsonPrimitive.content)
        assertTrue(listResponse.ok)
    }

    @Test
    fun `events subscribe denied without subscribe permission`() {
        val controller = TavernRuntimeController(
            permissionStore = TavernRuntimePermissionStore(TavernRuntimePermissions(allowEventSubscribe = false)),
        )
        val response = dispatchSubscribe(controller, "subscribe", "MESSAGE_SENDING")

        assertFalse(response.ok)
        assertEquals("PERMISSION_DENIED", response.error!!.code)
    }

    @Test
    fun `events unsubscribe denied without subscribe permission`() {
        val controller = TavernRuntimeController(
            permissionStore = TavernRuntimePermissionStore(TavernRuntimePermissions(allowEventSubscribe = false)),
        )
        val response = dispatchSubscribe(controller, "unsubscribe", "MESSAGE_SENDING")

        assertFalse(response.ok)
        assertEquals("PERMISSION_DENIED", response.error!!.code)
    }

    // ── 体积上限 ────────────────────────────────────────────────────

    @Test
    fun `variable value over 64KB is rejected`() {
        val controller = writeController()
        val bigValue = JsonPrimitive("x".repeat(64 * 1024))
        val response = dispatchSet(controller, "chat", "big", bigValue)

        assertFalse(response.ok)
        assertEquals("VALUE_TOO_LARGE", response.error!!.code)
    }

    @Test
    fun `variable value at exactly 64KB is accepted`() {
        val controller = writeController()
        // JSON 序列化后恰好 64KB（64KB - 2 个引号字符）
        val boundaryValue = JsonPrimitive("x".repeat(64 * 1024 - 2))
        val response = dispatchSet(controller, "chat", "boundary", boundaryValue)

        assertTrue(response.ok)
    }

    @Test
    fun `variables total over 512KB is rejected`() {
        val controller = writeController()
        val chunk = JsonPrimitive("x".repeat(64 * 1024 - 2))
        var successCount = 0
        var failure: TavernRuntimeResponse? = null
        for (i in 0 until 16) {
            val response = dispatchSet(controller, "chat", "k$i", chunk)
            if (response.ok) {
                successCount++
            } else {
                failure = response
                break
            }
        }

        assertTrue("at least several 64KB values fit under the 512KB cap", successCount >= 7)
        assertEquals("QUOTA_EXCEEDED", failure?.error?.code)
    }

    // ── 宿主事件订阅与转发 ──────────────────────────────────────────

    @Test
    fun `host event forwarded after subscribe`() {
        val conversationId = Uuid.parse("00000000-0000-4000-8000-000000000202")
        val harness = HostEventHarness(conversationId)
        dispatchSubscribe(harness.controller, "subscribe", "MESSAGE_SENDING")

        harness.hostFlow.tryEmit(
            TavernHostEvent(
                type = TavernHostEventType.MESSAGE_SENDING,
                conversationId = conversationId,
                payload = buildJsonObject { put("preview", "hello") },
            )
        )

        assertEquals(1, harness.received.size)
        assertEquals("MESSAGE_SENDING", harness.received[0].first)
        assertEquals("hello", harness.received[0].second!!.jsonObject.getValue("preview").jsonPrimitive.content)
    }

    @Test
    fun `host event not forwarded without subscribe`() {
        val conversationId = Uuid.parse("00000000-0000-4000-8000-000000000203")
        val harness = HostEventHarness(conversationId)

        harness.hostFlow.tryEmit(
            TavernHostEvent(TavernHostEventType.MESSAGE_SENDING, conversationId)
        )

        assertTrue(harness.received.isEmpty())
    }

    @Test
    fun `host event filtered by conversation id`() {
        val conversationId = Uuid.parse("00000000-0000-4000-8000-000000000204")
        val otherId = Uuid.parse("00000000-0000-4000-8000-000000000205")
        val harness = HostEventHarness(conversationId)
        dispatchSubscribe(harness.controller, "subscribe", "GENERATION_FINISHED")

        harness.hostFlow.tryEmit(
            TavernHostEvent(TavernHostEventType.GENERATION_FINISHED, otherId)
        )
        assertTrue(harness.received.isEmpty())

        harness.hostFlow.tryEmit(
            TavernHostEvent(TavernHostEventType.GENERATION_FINISHED, conversationId)
        )
        assertEquals(1, harness.received.size)
    }

    @Test
    fun `host event not forwarded after unsubscribe`() {
        val conversationId = Uuid.parse("00000000-0000-4000-8000-000000000206")
        val harness = HostEventHarness(conversationId)
        dispatchSubscribe(harness.controller, "subscribe", "MESSAGE_RENDERED")
        dispatchSubscribe(harness.controller, "unsubscribe", "MESSAGE_RENDERED")

        harness.hostFlow.tryEmit(
            TavernHostEvent(TavernHostEventType.MESSAGE_RENDERED, conversationId)
        )

        assertTrue(harness.received.isEmpty())
    }

    @Test
    fun `script emitted event echoes to outbound without subscription`() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val controller = TavernRuntimeController()
        val received = mutableListOf<Pair<String, JsonElement?>>()
        scope.launch { controller.outboundEvents.collect { received += it } }

        controller.dispatch(
            TavernRuntimeRequest(
                id = "e1",
                method = "events.emit",
                params = JsonObject(
                    mapOf(
                        "name" to JsonPrimitive("custom_event"),
                        "payload" to JsonPrimitive("bar"),
                    )
                ),
            )
        )

        assertEquals(1, received.size)
        assertEquals("custom_event", received[0].first)
        assertEquals(JsonPrimitive("bar"), received[0].second)
    }

    @Test
    fun `event bus notifies and removes listeners`() {
        val bus = TavernRuntimeEventBus()
        val seen = mutableListOf<Pair<String, JsonElement?>>()
        val listener: (String, JsonElement?) -> Unit = { name, payload -> seen += name to payload }

        bus.addListener(listener)
        bus.emit("first", JsonPrimitive(1))
        bus.removeListener(listener)
        bus.emit("second", null)

        assertEquals(1, seen.size)
        assertEquals("first", seen[0].first)
        assertEquals(listOf("first" to JsonPrimitive(1), "second" to null).map { it.first }, bus.recent().map { it.first })
    }

    // ── messages.getCurrent ────────────────────────────────────────

    @Test
    fun `messages getCurrent returns host injected message`() {
        val controller = TavernRuntimeController()
        val before = controller.dispatch(TavernRuntimeRequest(id = "m1", method = "messages.getCurrent"))
        assertEquals(JsonNull, before.result)

        controller.setCurrentMessage(buildJsonObject { put("id", "msg-1") })
        val after = controller.dispatch(TavernRuntimeRequest(id = "m2", method = "messages.getCurrent"))

        assertTrue(after.ok)
        assertEquals("msg-1", after.result!!.jsonObject.getValue("id").jsonPrimitive.content)
    }

    // ── 序列化 ──────────────────────────────────────────────────────

    @Test
    fun `global variables json serialization round trip`() {
        val variables = JsonObject(
            mapOf(
                "hp" to JsonPrimitive(10),
                "name" to JsonPrimitive("rikka"),
                "nested" to buildJsonObject { put("flags", JsonPrimitive(true)) },
            )
        )

        val encoded = JsonInstant.encodeToString(JsonObject.serializer(), variables)
        val decoded = JsonInstant.decodeFromString(JsonObject.serializer(), encoded)

        assertEquals(variables, decoded)
    }

    @Test
    fun `script exposes TH style aliases`() {
        val script = buildTavernRuntimeScript()

        assertTrue(script.contains("getVariables"))
        assertTrue(script.contains("setVariables"))
        assertTrue(script.contains("deleteVariable"))
        assertTrue(script.contains("eventSource"))
        assertTrue(script.contains("variables.delete"))
        assertTrue(script.contains("events.subscribe"))
        assertTrue(script.contains("events.unsubscribe"))
    }

    // ── 测试辅助 ────────────────────────────────────────────────────

    private fun writeController(
        conversationId: Uuid? = null,
        gateway: TavernRuntimeVariableGateway = InMemoryTavernRuntimeVariableGateway(),
    ): TavernRuntimeController {
        return TavernRuntimeController(
            conversationId = conversationId,
            permissionStore = TavernRuntimePermissionStore(
                initial = TavernRuntimePermissions(allowVariablesWrite = true)
            ),
            variableGateway = gateway,
        )
    }

    private fun dispatchSet(
        controller: TavernRuntimeController,
        scope: String,
        key: String,
        value: JsonElement,
    ): TavernRuntimeResponse {
        return controller.dispatch(
            TavernRuntimeRequest(
                id = "set-$scope-$key",
                method = "variables.set",
                params = JsonObject(
                    mapOf(
                        "scope" to JsonPrimitive(scope),
                        "key" to JsonPrimitive(key),
                        "value" to value,
                    )
                ),
            )
        )
    }

    private fun dispatchGet(
        controller: TavernRuntimeController,
        scope: String,
        key: String,
    ): TavernRuntimeResponse {
        return controller.dispatch(
            TavernRuntimeRequest(
                id = "get-$scope-$key",
                method = "variables.get",
                params = JsonObject(
                    mapOf(
                        "scope" to JsonPrimitive(scope),
                        "key" to JsonPrimitive(key),
                    )
                ),
            )
        )
    }

    private fun dispatchList(
        controller: TavernRuntimeController,
        scope: String,
    ): TavernRuntimeResponse {
        return controller.dispatch(
            TavernRuntimeRequest(
                id = "list-$scope",
                method = "variables.list",
                params = JsonObject(mapOf("scope" to JsonPrimitive(scope))),
            )
        )
    }

    private fun dispatchSubscribe(
        controller: TavernRuntimeController,
        verb: String,
        name: String,
    ): TavernRuntimeResponse {
        return controller.dispatch(
            TavernRuntimeRequest(
                id = "$verb-$name",
                method = "events.$verb",
                params = JsonObject(mapOf("name" to JsonPrimitive(name))),
            )
        )
    }

    private class FakeSettingsGateway(
        initial: Settings = Settings(),
    ) : TavernVariableSettingsGateway {
        var current: Settings = initial

        override fun currentSettings(): Settings = current

        override fun updateSettings(transform: (Settings) -> Settings) {
            current = transform(current)
        }
    }

    private class HostEventHarness(
        conversationId: Uuid?,
    ) {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val hostFlow = MutableSharedFlow<TavernHostEvent>(extraBufferCapacity = 8)
        val controller = TavernRuntimeController(
            conversationId = conversationId,
            permissionStore = TavernRuntimePermissionStore(
                initial = TavernRuntimePermissions(allowEventSubscribe = true)
            ),
            hostEventFlow = hostFlow,
            hostEventScope = scope,
        )
        val received = mutableListOf<Pair<String, JsonElement?>>()

        init {
            scope.launch { controller.outboundEvents.collect { received += it } }
        }
    }
}
