package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.status.StatusVariableStore
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.TavernRuntimePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class TavernRuntimeVariableScopesTest {

    // ── InMemory 网关：六作用域与 owner 隔离 ─────────────────────────

    @Test
    fun `in-memory gateway isolates all six scopes`() {
        val gateway = InMemoryTavernRuntimeVariableGateway()
        val conversationId = Uuid.parse("00000000-0000-4000-8000-000000000301")

        TAVERN_VARIABLE_SCOPES.forEach { scope ->
            gateway.set(conversationId, scope, "origin", JsonPrimitive(scope), ownerId = "owner")
        }

        TAVERN_VARIABLE_SCOPES.forEach { scope ->
            assertEquals(
                scope,
                gateway.get(conversationId, scope, "origin", ownerId = "owner")!!.jsonPrimitive.content,
            )
            assertEquals(
                scope,
                gateway.list(conversationId, scope, ownerId = "owner").getValue("origin").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `in-memory gateway isolates script and message owners`() {
        val gateway = InMemoryTavernRuntimeVariableGateway()
        val conversationId = Uuid.parse("00000000-0000-4000-8000-000000000302")

        gateway.set(conversationId, TAVERN_VARIABLE_SCOPE_SCRIPT, "k", JsonPrimitive("script-a"), ownerId = "script-a")
        gateway.set(conversationId, TAVERN_VARIABLE_SCOPE_SCRIPT, "k", JsonPrimitive("script-b"), ownerId = "script-b")
        gateway.set(conversationId, TAVERN_VARIABLE_SCOPE_MESSAGE, "k", JsonPrimitive("m1"), ownerId = "m1")
        gateway.set(conversationId, TAVERN_VARIABLE_SCOPE_MESSAGE, "k", JsonPrimitive("m2"), ownerId = "m2")

        assertEquals("script-a", gateway.get(conversationId, "script", "k", "script-a")!!.jsonPrimitive.content)
        assertEquals("script-b", gateway.get(conversationId, "script", "k", "script-b")!!.jsonPrimitive.content)
        assertEquals("m1", gateway.get(conversationId, "message", "k", "m1")!!.jsonPrimitive.content)
        assertEquals("m2", gateway.get(conversationId, "message", "k", "m2")!!.jsonPrimitive.content)

        // 非 owner 作用域忽略 ownerId（chat/global 不受 owner 影响）
        gateway.set(conversationId, TAVERN_VARIABLE_SCOPE_CHAT, "k", JsonPrimitive("chat"), ownerId = "ignored")
        assertEquals("chat", gateway.get(conversationId, "chat", "k", null)!!.jsonPrimitive.content)

        // replace 只影响目标 owner
        gateway.replace(conversationId, "script", JsonObject(mapOf("only" to JsonPrimitive(1))), ownerId = "script-a")
        assertEquals(setOf("only"), gateway.list(conversationId, "script", "script-a").keys)
        assertEquals("script-b", gateway.get(conversationId, "script", "k", "script-b")!!.jsonPrimitive.content)
    }

    // ── StatusStore 网关：作用域 → 真实存储映射 ──────────────────────

    @Test
    fun `character and preset scopes persist into current assistant tavernVariables namespaces`() {
        val assistant = Assistant(name = "Hero")
        val settingsGateway = FakeSettingsGateway(
            Settings(assistantId = assistant.id, assistants = listOf(assistant))
        )
        val gateway = StatusStoreTavernVariableGateway(StatusVariableStore(), settingsGateway)
        val conversationId = Uuid.parse("00000000-0000-4000-8000-000000000303")

        gateway.set(conversationId, TAVERN_VARIABLE_SCOPE_CHARACTER, "favor", JsonPrimitive(10))
        gateway.set(conversationId, TAVERN_VARIABLE_SCOPE_PRESET, "style", JsonPrimitive("poetic"))

        val stored = settingsGateway.current.assistants.single().tavernVariables
        assertEquals(
            10,
            (stored.getValue("character") as JsonObject).getValue("favor").jsonPrimitive.content.toInt(),
        )
        assertEquals(
            "poetic",
            (stored.getValue("preset") as JsonObject).getValue("style").jsonPrimitive.content,
        )

        // 读回与隔离：两个命名空间互不污染，global/chat 不受影响
        assertEquals("poetic", gateway.get(conversationId, "preset", "style")!!.jsonPrimitive.content)
        assertEquals(null, gateway.get(conversationId, "character", "style"))
        assertFalse(settingsGateway.current.tavernGlobalVariables.containsKey("favor"))

        // 删除只移除目标命名空间的键
        assertTrue(gateway.delete(conversationId, "character", "favor"))
        val afterDelete = settingsGateway.current.assistants.single().tavernVariables
        assertFalse((afterDelete.getValue("character") as JsonObject).containsKey("favor"))
        assertTrue((afterDelete.getValue("preset") as JsonObject).containsKey("style"))
    }

    @Test
    fun `script scope persists into settings tavernScriptVariables keyed by scriptId`() {
        val settingsGateway = FakeSettingsGateway()
        val gateway = StatusStoreTavernVariableGateway(StatusVariableStore(), settingsGateway)
        val conversationId = Uuid.parse("00000000-0000-4000-8000-000000000304")

        gateway.set(conversationId, TAVERN_VARIABLE_SCOPE_SCRIPT, "count", JsonPrimitive(1), ownerId = "script-a")
        gateway.set(conversationId, TAVERN_VARIABLE_SCOPE_SCRIPT, "count", JsonPrimitive(2), ownerId = "script-b")

        val stored = settingsGateway.current.tavernScriptVariables
        assertEquals(1, (stored.getValue("script-a") as JsonObject).getValue("count").jsonPrimitive.content.toInt())
        assertEquals(2, (stored.getValue("script-b") as JsonObject).getValue("count").jsonPrimitive.content.toInt())

        // owner 缺失落入 ephemeral 桶（预览等无脚本身份场景）
        gateway.set(conversationId, TAVERN_VARIABLE_SCOPE_SCRIPT, "tmp", JsonPrimitive("x"))
        val ephemeralBucket = settingsGateway.current.tavernScriptVariables["__ephemeral__"] as JsonObject
        assertEquals("x", ephemeralBucket.getValue("tmp").jsonPrimitive.content)
        assertEquals("x", gateway.get(conversationId, "script", "tmp")!!.jsonPrimitive.content)
    }

    @Test
    fun `message scope stays in gateway memory and never touches settings or chat store`() {
        val settingsGateway = FakeSettingsGateway()
        val statusStore = StatusVariableStore()
        val gateway = StatusStoreTavernVariableGateway(statusStore, settingsGateway)
        val conversationId = Uuid.parse("00000000-0000-4000-8000-000000000305")

        gateway.set(conversationId, TAVERN_VARIABLE_SCOPE_MESSAGE, "hp", JsonPrimitive(42), ownerId = "m1")
        gateway.set(conversationId, TAVERN_VARIABLE_SCOPE_MESSAGE, "hp", JsonPrimitive(7), ownerId = "m2")

        assertEquals(42, gateway.get(conversationId, "message", "hp", "m1")!!.jsonPrimitive.content.toInt())
        assertEquals(7, gateway.get(conversationId, "message", "hp", "m2")!!.jsonPrimitive.content.toInt())
        // 已记录偏差：message 作用域无持久化通道，不写 Settings / StatusVariableStore
        assertEquals(Settings().tavernScriptVariables, settingsGateway.current.tavernScriptVariables)
        assertFalse(statusStore.getValue(conversationId).containsKey("hp"))

        gateway.replace(conversationId, "message", JsonObject(mapOf("mp" to JsonPrimitive(3))), ownerId = "m1")
        assertEquals(null, gateway.get(conversationId, "message", "hp", "m1"))
        assertEquals(3, gateway.get(conversationId, "message", "mp", "m1")!!.jsonPrimitive.content.toInt())
        assertEquals(7, gateway.get(conversationId, "message", "hp", "m2")!!.jsonPrimitive.content.toInt())
    }

    // ── Controller：scope 解析、replace/update 语义与权限 ────────────

    @Test
    fun `controller accepts all six scopes and rejects unknown ones`() {
        val controller = writeController()

        TAVERN_VARIABLE_SCOPES.forEach { scope ->
            val response = dispatchSet(controller, scope, "k", JsonPrimitive(scope))
            assertTrue("scope $scope should be accepted", response.ok)
            assertEquals(scope, dispatchGet(controller, scope, "k").result!!.jsonPrimitive.content)
        }

        val bad = dispatchSet(controller, "dungeon", "k", JsonPrimitive("v"))
        assertFalse(bad.ok)
        assertEquals("BAD_REQUEST", bad.error?.code)
    }

    @Test
    fun `variables replace swaps whole scope and update merges into it`() {
        val controller = writeController()

        dispatchSet(controller, "chat", "a", JsonPrimitive(1))
        dispatchSet(controller, "chat", "b", JsonPrimitive(2))

        val replaced = controller.dispatch(
            TavernRuntimeRequest(
                id = "r1",
                method = "variables.replace",
                params = JsonObject(
                    mapOf(
                        "scope" to JsonPrimitive("chat"),
                        "values" to JsonObject(mapOf("c" to JsonPrimitive(3))),
                    )
                ),
            )
        )
        assertTrue(replaced.ok)
        assertEquals(setOf("c"), dispatchList(controller, "chat").result!!.jsonObject.keys)

        val updated = controller.dispatch(
            TavernRuntimeRequest(
                id = "u1",
                method = "variables.update",
                params = JsonObject(
                    mapOf(
                        "scope" to JsonPrimitive("chat"),
                        "values" to JsonObject(
                            mapOf("c" to JsonPrimitive(30), "d" to JsonPrimitive(4))
                        ),
                    )
                ),
            )
        )
        assertTrue(updated.ok)
        val list = dispatchList(controller, "chat").result!!.jsonObject
        assertEquals(30, list.getValue("c").jsonPrimitive.content.toInt())
        assertEquals(4, list.getValue("d").jsonPrimitive.content.toInt())
    }

    @Test
    fun `variables replace and update require values object`() {
        val controller = writeController()

        listOf("variables.replace", "variables.update").forEach { method ->
            val response = controller.dispatch(
                TavernRuntimeRequest(
                    id = "bad-$method",
                    method = method,
                    params = JsonObject(mapOf("scope" to JsonPrimitive("chat"))),
                )
            )
            assertFalse(response.ok)
            assertEquals("BAD_REQUEST", response.error?.code)
        }
    }

    @Test
    fun `variables replace and update honor allowVariablesWrite permission`() {
        val denied = TavernRuntimeController(
            permissionStore = TavernRuntimePermissionStore(
                initial = TavernRuntimePermissions(allowScripts = true, allowVariablesWrite = false)
            ),
            variableGateway = InMemoryTavernRuntimeVariableGateway(),
        )
        listOf("variables.replace", "variables.update").forEach { method ->
            val response = denied.dispatch(
                TavernRuntimeRequest(
                    id = "p-$method",
                    method = method,
                    params = JsonObject(
                        mapOf(
                            "scope" to JsonPrimitive("chat"),
                            "values" to JsonObject(mapOf("k" to JsonPrimitive(1))),
                        )
                    ),
                )
            )
            assertFalse(response.ok)
            assertEquals("PERMISSION_DENIED", response.error?.code)
        }
    }

    @Test
    fun `message scope resolves owner from injected current message id`() {
        val gateway = InMemoryTavernRuntimeVariableGateway()
        val conversationId = Uuid.parse("00000000-0000-4000-8000-000000000306")
        val controller = writeController(conversationId = conversationId, gateway = gateway)
        controller.setCurrentMessage(
            buildJsonObject {
                put("messageId", "m-42")
                put("role", "assistant")
            }
        )

        dispatchSet(controller, "message", "hp", JsonPrimitive(100))

        assertEquals(
            100,
            gateway.get(conversationId, "message", "hp", ownerId = "m-42")!!.jsonPrimitive.content.toInt(),
        )
    }

    @Test
    fun `script scope resolves owner from controller scriptId`() {
        val gateway = InMemoryTavernRuntimeVariableGateway()
        val conversationId = Uuid.parse("00000000-0000-4000-8000-000000000307")
        val controller = TavernRuntimeController(
            conversationId = conversationId,
            permissionStore = TavernRuntimePermissionStore(
                initial = TavernRuntimePermissions(allowVariablesWrite = true)
            ),
            variableGateway = gateway,
            scriptId = "resident-script-1",
        )

        dispatchSet(controller, "script", "state", JsonPrimitive("running"))

        assertEquals(
            "running",
            gateway.get(conversationId, "script", "state", ownerId = "resident-script-1")!!.jsonPrimitive.content,
        )
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
        value: kotlinx.serialization.json.JsonElement,
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

    private class FakeSettingsGateway(
        initial: Settings = Settings(),
    ) : TavernVariableSettingsGateway {
        var current: Settings = initial

        override fun currentSettings(): Settings = current

        override fun updateSettings(transform: (Settings) -> Settings) {
            current = transform(current)
        }
    }
}
