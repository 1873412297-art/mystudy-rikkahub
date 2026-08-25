package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.TavernRuntimePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class TavernRuntimeGenerationRpcTest {
    private val conversationId: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000201")

    private class FakeGateway(
        private val started: Boolean = true,
    ) : TavernRuntimeGenerationGateway {
        val startedParams = mutableListOf<TavernGenerationParams>()
        val cancelledIds = mutableListOf<String>()
        var cancelAllCalls = 0
        private val pendingCallbacks = mutableMapOf<String, (TavernGenerationOutcome) -> Unit>()

        override fun generate(
            params: TavernGenerationParams,
            callback: (TavernGenerationOutcome) -> Unit,
        ): Boolean {
            if (!started) return false
            startedParams += params
            pendingCallbacks[params.requestId] = callback
            return true
        }

        fun complete(requestId: String, outcome: TavernGenerationOutcome) {
            (pendingCallbacks.remove(requestId) ?: error("no pending generation $requestId"))(outcome)
        }

        override fun cancel(requestId: String): Boolean {
            cancelledIds += requestId
            return pendingCallbacks.containsKey(requestId)
        }

        override fun cancelAll(): Int {
            cancelAllCalls++
            return pendingCallbacks.size
        }
    }

    private fun permissions(allowGeneration: Boolean) = TavernRuntimePermissionStore(
        initial = TavernRuntimePermissions(allowScripts = true, allowGeneration = allowGeneration)
    )

    private fun seededMessageGateway() = InMemoryTavernRuntimeMessageGateway(
        initialMessages = mapOf(
            conversationId to listOf(
                TavernRuntimeMessage("m1", MessageRole.USER, "hello", isCurrent = false),
                TavernRuntimeMessage("m2", MessageRole.ASSISTANT, "hi there", isCurrent = true),
            )
        )
    )

    @Test
    fun `generate requires allowGeneration permission`() {
        val gateway = FakeGateway()
        val controller = TavernRuntimeController(
            permissionStore = permissions(allowGeneration = false),
            generationGateway = gateway,
        )
        var response: TavernRuntimeResponse? = null
        controller.dispatchGeneration(
            TavernRuntimeRequest(
                id = "g1",
                method = "generation.generate",
                params = buildJsonObject { put("prompt", JsonPrimitive("hi")) },
            )
        ) { response = it }

        assertFalse(response!!.ok)
        assertEquals("PERMISSION_DENIED", response!!.error!!.code)
        assertTrue(gateway.startedParams.isEmpty())
    }

    @Test
    fun `generate delivers async success payload through callback`() {
        val gateway = FakeGateway()
        val controller = TavernRuntimeController(
            permissionStore = permissions(allowGeneration = true),
            generationGateway = gateway,
        )
        var response: TavernRuntimeResponse? = null
        controller.dispatchGeneration(
            TavernRuntimeRequest(
                id = "g2",
                method = "generation.generate",
                params = buildJsonObject {
                    put("prompt", JsonPrimitive("tell me a story"))
                    put("temperature", JsonPrimitive(0.7))
                    put("maxTokens", JsonPrimitive(128))
                },
            )
        ) { response = it }

        // 异步：网关完成前不应有响应
        assertNull(response)
        val params = gateway.startedParams.single()
        assertEquals("g2", params.requestId)
        assertEquals(listOf(TavernGenerationMessage("user", "tell me a story")), params.messages)
        assertEquals(0.7f, params.temperature!!, 0.0001f)
        assertEquals(128, params.maxTokens)

        gateway.complete(
            "g2",
            TavernGenerationOutcome.Success(
                buildJsonObject {
                    put("text", JsonPrimitive("once upon a time"))
                    put("model", JsonPrimitive("mock-1"))
                }
            ),
        )
        assertTrue(response!!.ok)
        assertEquals("once upon a time", (response!!.result as JsonObject).getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `generate injects chat history unless raw or useChat disabled`() {
        val gateway = FakeGateway()
        val controller = TavernRuntimeController(
            conversationId = conversationId,
            permissionStore = permissions(allowGeneration = true),
            messageGateway = seededMessageGateway(),
            generationGateway = gateway,
        )

        fun dispatch(id: String, method: String, extra: JsonObject = buildJsonObject {}) {
            controller.dispatchGeneration(
                TavernRuntimeRequest(
                    id = id,
                    method = method,
                    params = buildJsonObject {
                        put("prompt", JsonPrimitive("continue"))
                        extra.forEach { (key, value) -> put(key, value) }
                    },
                )
            ) {}
        }

        dispatch("h1", "generation.generate")
        assertEquals(
            listOf(
                TavernGenerationMessage("user", "hello"),
                TavernGenerationMessage("assistant", "hi there"),
                TavernGenerationMessage("user", "continue"),
            ),
            gateway.startedParams.last().messages,
        )

        dispatch("h2", "generation.generateRaw")
        assertEquals(
            listOf(TavernGenerationMessage("user", "continue")),
            gateway.startedParams.last().messages,
        )

        dispatch("h3", "generation.generate", buildJsonObject { put("useChat", JsonPrimitive(false)) })
        assertEquals(
            listOf(TavernGenerationMessage("user", "continue")),
            gateway.startedParams.last().messages,
        )
    }

    @Test
    fun `generate accepts explicit messages and rejects empty input`() {
        val gateway = FakeGateway()
        val controller = TavernRuntimeController(
            permissionStore = permissions(allowGeneration = true),
            generationGateway = gateway,
        )

        controller.dispatchGeneration(
            TavernRuntimeRequest(
                id = "m1",
                method = "generation.generateRaw",
                params = buildJsonObject {
                    put(
                        "messages",
                        buildJsonArray {
                            add(buildJsonObject {
                                put("role", JsonPrimitive("system"))
                                put("text", JsonPrimitive("be terse"))
                            })
                            add(buildJsonObject {
                                put("role", JsonPrimitive("user"))
                                put("text", JsonPrimitive("2+2?"))
                            })
                            // 非法角色被丢弃
                            add(buildJsonObject {
                                put("role", JsonPrimitive("tool"))
                                put("text", JsonPrimitive("dropped"))
                            })
                        }
                    )
                },
            )
        ) {}
        assertEquals(
            listOf(
                TavernGenerationMessage("system", "be terse"),
                TavernGenerationMessage("user", "2+2?"),
            ),
            gateway.startedParams.single().messages,
        )

        var response: TavernRuntimeResponse? = null
        controller.dispatchGeneration(
            TavernRuntimeRequest(id = "m2", method = "generation.generate")
        ) { response = it }
        assertFalse(response!!.ok)
        assertEquals("BAD_REQUEST", response!!.error!!.code)
    }

    @Test
    fun `duplicate in-flight generation id is rejected and reusable after completion`() {
        val gateway = FakeGateway()
        val controller = TavernRuntimeController(
            permissionStore = permissions(allowGeneration = true),
            generationGateway = gateway,
        )
        val request = TavernRuntimeRequest(
            id = "dup",
            method = "generation.generate",
            params = buildJsonObject { put("prompt", JsonPrimitive("x")) },
        )

        var first: TavernRuntimeResponse? = null
        controller.dispatchGeneration(request) { first = it }
        assertNull(first)

        var second: TavernRuntimeResponse? = null
        controller.dispatchGeneration(request) { second = it }
        assertFalse(second!!.ok)
        assertEquals("GENERATION_IN_PROGRESS", second!!.error!!.code)

        gateway.complete("dup", TavernGenerationOutcome.Success(buildJsonObject { put("text", JsonPrimitive("done")) }))
        assertTrue(first!!.ok)

        var third: TavernRuntimeResponse? = null
        controller.dispatchGeneration(request) { third = it }
        assertNull(third)
        assertEquals(2, gateway.startedParams.size)
    }

    @Test
    fun `unsupported gateway and failure outcomes surface structured errors`() {
        val unsupported = TavernRuntimeController(
            permissionStore = permissions(allowGeneration = true),
            generationGateway = FakeGateway(started = false),
        )
        var response: TavernRuntimeResponse? = null
        unsupported.dispatchGeneration(
            TavernRuntimeRequest(
                id = "u1",
                method = "generation.generate",
                params = buildJsonObject { put("prompt", JsonPrimitive("x")) },
            )
        ) { response = it }
        assertFalse(response!!.ok)
        assertEquals("UNSUPPORTED_HOST_CAPABILITY", response!!.error!!.code)

        val gateway = FakeGateway()
        val controller = TavernRuntimeController(
            permissionStore = permissions(allowGeneration = true),
            generationGateway = gateway,
        )
        var failed: TavernRuntimeResponse? = null
        controller.dispatchGeneration(
            TavernRuntimeRequest(
                id = "u2",
                method = "generation.generate",
                params = buildJsonObject { put("prompt", JsonPrimitive("x")) },
            )
        ) { failed = it }
        gateway.complete("u2", TavernGenerationOutcome.Failure("NO_CHAT_MODEL", "No chat model is configured"))
        assertFalse(failed!!.ok)
        assertEquals("NO_CHAT_MODEL", failed!!.error!!.code)
        assertEquals("No chat model is configured", failed!!.error!!.message)
    }

    @Test
    fun `cancel and cancelAll route to gateway behind permission`() {
        val gateway = FakeGateway()
        val controller = TavernRuntimeController(
            permissionStore = permissions(allowGeneration = true),
            generationGateway = gateway,
        )

        controller.dispatchGeneration(
            TavernRuntimeRequest(
                id = "c1",
                method = "generation.generate",
                params = buildJsonObject { put("prompt", JsonPrimitive("x")) },
            )
        ) {}

        var cancelled: TavernRuntimeResponse? = null
        controller.dispatchGeneration(
            TavernRuntimeRequest(
                id = "c2",
                method = "generation.cancel",
                params = buildJsonObject { put("id", JsonPrimitive("c1")) },
            )
        ) { cancelled = it }
        assertTrue(cancelled!!.ok)
        assertTrue(cancelled!!.result!!.jsonPrimitive.boolean)
        assertEquals(listOf("c1"), gateway.cancelledIds)

        var missing: TavernRuntimeResponse? = null
        controller.dispatchGeneration(
            TavernRuntimeRequest(
                id = "c3",
                method = "generation.cancel",
                params = buildJsonObject { put("id", JsonPrimitive("nobody")) },
            )
        ) { missing = it }
        assertTrue(missing!!.ok)
        assertFalse(missing!!.result!!.jsonPrimitive.boolean)

        var all: TavernRuntimeResponse? = null
        controller.dispatchGeneration(
            TavernRuntimeRequest(id = "c4", method = "generation.cancelAll")
        ) { all = it }
        assertTrue(all!!.ok)
        assertEquals(1, all!!.result!!.jsonPrimitive.content.toInt())
        assertEquals(1, gateway.cancelAllCalls)

        // 无权限时被拒
        val denied = TavernRuntimeController(
            permissionStore = permissions(allowGeneration = false),
            generationGateway = gateway,
        )
        var deniedResponse: TavernRuntimeResponse? = null
        denied.dispatchGeneration(
            TavernRuntimeRequest(id = "c5", method = "generation.cancelAll")
        ) { deniedResponse = it }
        assertFalse(deniedResponse!!.ok)
        assertEquals("PERMISSION_DENIED", deniedResponse!!.error!!.code)
    }

    @Test
    fun `sync dispatch rejects generate methods with async dispatch required`() {
        val controller = TavernRuntimeController(
            permissionStore = permissions(allowGeneration = true),
            generationGateway = FakeGateway(),
        )
        val response = controller.dispatch(
            TavernRuntimeRequest(
                id = "s1",
                method = "generation.generate",
                params = buildJsonObject { put("prompt", JsonPrimitive("x")) },
            )
        )
        assertFalse(response.ok)
        assertEquals("ASYNC_DISPATCH_REQUIRED", response.error!!.code)
    }
}
