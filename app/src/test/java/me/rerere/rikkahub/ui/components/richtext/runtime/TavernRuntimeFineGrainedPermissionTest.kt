package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.TavernRuntimePermissions
import me.rerere.rikkahub.data.model.TavernScriptPermissionGrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernRuntimeFineGrainedPermissionTest {

    // ── 合并语义：布尔取或、域名并集 ─────────────────────────────────

    @Test
    fun `permissions merge takes OR of booleans and union of domains`() {
        val base = TavernRuntimePermissions(
            allowScripts = true,
            allowMessageScripts = true,
            allowBrowserScripts = false,
            allowVariablesWrite = false,
            allowNetwork = true,
            allowedNetworkDomains = listOf("api.example.com"),
        )
        val grant = TavernRuntimePermissions(
            allowScripts = false,
            allowMessageScripts = false,
            allowBrowserScripts = true,
            allowVariablesWrite = true,
            allowNetwork = false,
            allowedNetworkDomains = listOf("cdn.example.com", "api.example.com"),
            allowAssistantWrite = true,
        )

        val merged = base.mergedWith(grant)

        assertTrue(merged.allowScripts)
        assertTrue(merged.allowMessageScripts)
        assertTrue(merged.allowBrowserScripts)
        assertTrue(merged.allowVariablesWrite)
        assertTrue(merged.allowNetwork)
        assertTrue(merged.allowAssistantWrite)
        assertEquals(
            listOf("api.example.com", "cdn.example.com"),
            merged.allowedNetworkDomains,
        )
        // 授权未声明的位保持全局默认
        assertFalse(merged.allowGeneration)
    }

    @Test
    fun `permission store merges grant provider output on every read`() {
        val store = TavernRuntimePermissionStore(
            initial = TavernRuntimePermissions(allowVariablesWrite = false)
        )
        assertFalse(store.current().allowVariablesWrite)

        var grantOn = false
        store.grantProvider = {
            if (grantOn) TavernRuntimePermissions(allowVariablesWrite = true) else null
        }
        assertFalse(store.current().allowVariablesWrite)

        grantOn = true
        assertTrue(store.current().allowVariablesWrite)

        // 全局更新与授权互不覆盖
        store.update(TavernRuntimePermissions(allowGeneration = true))
        val current = store.current()
        assertTrue(current.allowGeneration)
        assertTrue(current.allowVariablesWrite)
    }

    // ── 哈希级授权解析：授予、失效、撤销 ─────────────────────────────

    @Test
    fun `grant resolver returns permissions only while source hash matches`() {
        val settingsGateway = FakeSettingsGateway()
        var currentHash = "hash-v1"
        val resolver = SettingsBackedTavernScriptGrantResolver(settingsGateway) { currentHash }

        assertNull(resolver.resolve("script-1"))

        settingsGateway.grantScriptPermissions(
            scriptId = "script-1",
            sourceSha256 = "hash-v1",
            permissions = TavernRuntimePermissions(allowVariablesWrite = true),
            grantedAt = 123L,
        )
        assertTrue(resolver.resolve("script-1")!!.allowVariablesWrite)

        // 源码变化 → 旧授权自动失效
        currentHash = "hash-v2"
        assertNull(resolver.resolve("script-1"))

        // 哈希源缺失（脚本已删除）→ 失效
        val orphanResolver = SettingsBackedTavernScriptGrantResolver(settingsGateway) { null }
        assertNull(orphanResolver.resolve("script-1"))

        // 撤销
        settingsGateway.revokeScriptPermissions("script-1")
        assertTrue(settingsGateway.current.tavernScriptPermissionGrants.isEmpty())
    }

    @Test
    fun `grants persist in settings keyed by script id`() {
        val settingsGateway = FakeSettingsGateway()
        settingsGateway.grantScriptPermissions(
            scriptId = "a",
            sourceSha256 = "ha",
            permissions = TavernRuntimePermissions(allowNetwork = true),
            grantedAt = 1L,
        )
        settingsGateway.grantScriptPermissions(
            scriptId = "b",
            sourceSha256 = "hb",
            permissions = TavernRuntimePermissions(allowGeneration = true),
            grantedAt = 2L,
        )

        val grants = settingsGateway.current.tavernScriptPermissionGrants
        assertEquals(setOf("a", "b"), grants.keys)
        assertEquals("ha", grants.getValue("a").sourceSha256)
        assertTrue(grants.getValue("a").permissions.allowNetwork)
        assertTrue(grants.getValue("b").permissions.allowGeneration)
        assertEquals(2L, grants.getValue("b").grantedAt)
    }

    // ── 序列化：新字段与授权记录的 JSON 往返 ─────────────────────────

    @Test
    fun `permissions and grants survive json round trip`() {
        val permissions = TavernRuntimePermissions(
            allowMessageScripts = false,
            allowBrowserScripts = false,
            allowAssistantWrite = true,
            allowedNetworkDomains = listOf("a.com", "b.com"),
        )
        val grant = TavernScriptPermissionGrant(
            sourceSha256 = "abc123",
            permissions = permissions,
            grantedAt = 42L,
        )
        val json = me.rerere.rikkahub.utils.JsonInstant.encodeToString(mapOf("s1" to grant))
        val decoded = me.rerere.rikkahub.utils.JsonInstant.decodeFromString<
            Map<String, TavernScriptPermissionGrant>>(json)
        assertEquals(grant, decoded.getValue("s1"))
    }

    // ── controller：character/preset 写受 allowAssistantWrite 门控 ────

    @Test
    fun `assistant scope writes require allowAssistantWrite while other scopes do not`() {
        val gateway = InMemoryTavernRuntimeVariableGateway()
        val controller = TavernRuntimeController(
            permissionStore = TavernRuntimePermissionStore(
                initial = TavernRuntimePermissions(
                    allowVariablesWrite = true,
                    allowAssistantWrite = false,
                )
            ),
            variableGateway = gateway,
        )

        listOf("character", "preset").forEach { scope ->
            val set = dispatchSet(controller, scope, "k", JsonPrimitive(1))
            assertFalse("set on $scope should be denied", set.ok)
            assertEquals("PERMISSION_DENIED", set.error?.code)

            val replace = controller.dispatch(
                TavernRuntimeRequest(
                    id = "r-$scope",
                    method = "variables.replace",
                    params = JsonObject(
                        mapOf(
                            "scope" to JsonPrimitive(scope),
                            "values" to JsonObject(mapOf("k" to JsonPrimitive(1))),
                        )
                    ),
                )
            )
            assertFalse(replace.ok)
            assertEquals("PERMISSION_DENIED", replace.error?.code)
            assertNull(gateway.get(null, scope, "k"))
        }

        // chat/global/message/script 不受 allowAssistantWrite 影响
        listOf("chat", "global", "message", "script").forEach { scope ->
            assertTrue(dispatchSet(controller, scope, "k", JsonPrimitive(1)).ok)
        }

        // 读不受限
        assertTrue(dispatchGet(controller, "character", "k").ok)
    }

    @Test
    fun `assistant scope writes pass when allowAssistantWrite enabled`() {
        val gateway = InMemoryTavernRuntimeVariableGateway()
        val controller = TavernRuntimeController(
            permissionStore = TavernRuntimePermissionStore(
                initial = TavernRuntimePermissions(
                    allowVariablesWrite = true,
                    allowAssistantWrite = true,
                )
            ),
            variableGateway = gateway,
        )

        assertTrue(dispatchSet(controller, "character", "favor", JsonPrimitive(5)).ok)
        assertEquals(5, dispatchGet(controller, "character", "favor").result!!.jsonPrimitive.content.toInt())
    }

    // ── 默认值向后兼容 ────────────────────────────────────────────────

    @Test
    fun `new permission bits default to backward compatible values`() {
        val defaults = TavernRuntimePermissions()
        assertTrue(defaults.allowMessageScripts)
        assertTrue(defaults.allowBrowserScripts)
        assertFalse(defaults.allowAssistantWrite)
        assertTrue(defaults.allowedNetworkDomains.isEmpty())
    }

    // ── 测试辅助 ────────────────────────────────────────────────────

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
