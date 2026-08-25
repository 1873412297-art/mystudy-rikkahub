package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TavernRuntimeBridgeTest {
    @Test
    fun `legacy trailing lambda bridge constructor remains callable`() {
        val emitted = mutableListOf<Pair<String, String>>()
        val bridge = TavernRuntimeBridge(TavernRuntimeController()) { callback, response ->
            emitted += callback to response
        }

        bridge.call("{\"id\":\"legacy\",\"method\":\"runtime.ping\"}", "safeCallback")

        assertEquals(1, emitted.size)
    }

    @Test
    fun `oversized request returns structured error to a safe callback`() {
        val emitted = mutableListOf<Pair<String, String>>()
        val bridge = TavernRuntimeBridge(
            controller = TavernRuntimeController(),
            emitResult = { callback, response -> emitted += callback to response },
        )

        bridge.call("x".repeat(256_001), "safeCallback")

        assertEquals(1, emitted.size)
        assertEquals("safeCallback", emitted.single().first)
        val response = Json.decodeFromString<TavernRuntimeResponse>(emitted.single().second)
        assertFalse(response.ok)
        assertEquals("REQUEST_TOO_LARGE", response.error!!.code)
    }

    @Test
    fun `bridge caps an oversized controller response`() {
        val emitted = mutableListOf<Pair<String, String>>()
        val controller = TavernRuntimeController().apply {
            setCurrentMessage(JsonPrimitive("x".repeat(1_100_000)))
        }
        val bridge = TavernRuntimeBridge(
            controller = controller,
            emitResult = { callback, response -> emitted += callback to response },
        )

        bridge.call("{\"id\":\"large\",\"method\":\"messages.getCurrent\"}", "safeCallback")

        val response = Json.decodeFromString<TavernRuntimeResponse>(emitted.single().second)
        assertFalse(response.ok)
        assertEquals("RESPONSE_TOO_LARGE", response.error!!.code)
        assertTrue(emitted.single().second.toByteArray().size <= 1_000_000)
    }

    @Test
    fun `browser script RPC completion is recorded with method duration and sanitized error`() {
        val diagnostics = TavernScriptDiagnosticsStore { 42L }
        val emitted = mutableListOf<Pair<String, String>>()
        val bridge = bridgeWithScriptDiagnostics(
            controller = TavernRuntimeController(),
            emitResult = { callback, response -> emitted += callback to response },
            scriptId = "browser-script",
            diagnostics = diagnostics,
        )

        bridge.call(
            "{\"id\":\"rpc-1\",\"method\":\"unknown.authorization: Bearer secret-value\"}",
            "safeCallback",
        )

        assertEquals(1, emitted.size)
        val entry = diagnostics.entries("browser-script").single()
        assertEquals("rpc", entry.category)
        assertEquals("unknown.authorization: Bearer secret-value", entry.rpcMethod)
        assertTrue(entry.durationMs != null)
        val error = entry.error.orEmpty()
        assertTrue(error.contains("[已隐藏]"))
        assertTrue(!error.contains("secret-value"))
    }

    @Test
    fun `message frontend RPC is not attributed without script identity`() {
        val diagnostics = TavernScriptDiagnosticsStore()
        val bridge = bridgeWithScriptDiagnostics(
            controller = TavernRuntimeController(),
            emitResult = { _, _ -> },
            scriptId = null,
            diagnostics = diagnostics,
        )

        bridge.call("{\"id\":\"ping\",\"method\":\"runtime.ping\"}", "safeCallback")

        assertTrue(diagnostics.entries("browser-script").isEmpty())
    }

    @Test
    fun `malformed request emits bad request only for a safe callback`() {
        val emitted = mutableListOf<Pair<String, String>>()
        val bridge = TavernRuntimeBridge(
            controller = TavernRuntimeController(),
            emitResult = { callback, response -> emitted += callback to response },
        )

        bridge.call("not-json", "safeCallback")
        bridge.call("not-json", "unsafe-callback()")

        assertEquals(1, emitted.size)
        val response = Json.decodeFromString<TavernRuntimeResponse>(emitted.single().second)
        assertEquals("BAD_REQUEST", response.error!!.code)
    }

    @Suppress("UNCHECKED_CAST")
    private fun bridgeWithScriptDiagnostics(
        controller: TavernRuntimeController,
        emitResult: (String, String) -> Unit,
        scriptId: String?,
        diagnostics: TavernScriptDiagnosticsStore,
    ): TavernRuntimeBridge {
        val constructor = TavernRuntimeBridge::class.java.declaredConstructors.singleOrNull {
            it.parameterTypes.size == 4
        } ?: run {
            fail("Runtime bridge must accept optional script identity and diagnostics")
            error("unreachable")
        }
        constructor.isAccessible = true
        return constructor.newInstance(controller, scriptId, diagnostics, emitResult) as TavernRuntimeBridge
    }
}
