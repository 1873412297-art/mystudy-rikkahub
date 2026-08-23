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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import me.rerere.rikkahub.data.model.TavernRuntimePermissions
import me.rerere.rikkahub.data.ai.slash.TavernScriptRegistry
import me.rerere.rikkahub.ui.pages.chat.tavern.TavernChatMessageGateway
import me.rerere.rikkahub.ui.pages.chat.tavern.TavernChatMutationResult
import me.rerere.rikkahub.ui.pages.chat.tavern.TavernChatQueryOptions

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
    fun `messages getChatMessages delegates SillyTavern query options`() {
        var observedRange = ""
        var observedOptions = TavernChatQueryOptions()
        val gateway = object : TavernChatMessageGateway {
            override fun getChatMessages(range: String, options: TavernChatQueryOptions): JsonArray {
                observedRange = range
                observedOptions = options
                return JsonArray(listOf(JsonPrimitive("message")))
            }

            override fun setChatMessage(params: JsonObject) = TavernChatMutationResult.Accepted
            override fun setChatMessages(params: JsonObject) = TavernChatMutationResult.Accepted
        }
        val response = TavernRuntimeController(chatMessageGateway = gateway).dispatch(
            TavernRuntimeRequest(
                id = "chat-query",
                method = "messages.getChatMessages",
                params = buildJsonObject {
                    put("range", "-2--1")
                    put("options", buildJsonObject {
                        put("role", "assistant")
                        put("hide_state", "unhidden")
                        put("include_swipes", true)
                    })
                },
            ),
        )

        assertTrue(response.ok)
        assertEquals("message", response.result!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("-2--1", observedRange)
        assertEquals(TavernChatQueryOptions("assistant", "unhidden", true), observedOptions)
    }

    @Test
    fun `messages getChatMessages accepts singular include swipe used by Tavern cards`() {
        var observedOptions = TavernChatQueryOptions()
        val gateway = object : TavernChatMessageGateway {
            override fun getChatMessages(range: String, options: TavernChatQueryOptions): JsonArray {
                observedOptions = options
                return JsonArray(emptyList())
            }

            override fun setChatMessage(params: JsonObject) = TavernChatMutationResult.Accepted
            override fun setChatMessages(params: JsonObject) = TavernChatMutationResult.Accepted
        }

        val response = TavernRuntimeController(chatMessageGateway = gateway).dispatch(
            TavernRuntimeRequest(
                id = "chat-query-singular-swipe",
                method = "messages.getChatMessages",
                params = buildJsonObject {
                    put("range", "0")
                    put("options", buildJsonObject { put("include_swipe", true) })
                },
            ),
        )

        assertTrue(response.ok)
        assertTrue(observedOptions.includeSwipes)
    }

    @Test
    fun `messages setChatMessage requires message write permission`() {
        var called = false
        val gateway = object : TavernChatMessageGateway {
            override fun getChatMessages(range: String, options: TavernChatQueryOptions) = JsonArray(emptyList())
            override fun setChatMessage(params: JsonObject): TavernChatMutationResult {
                called = true
                return TavernChatMutationResult.Accepted
            }
            override fun setChatMessages(params: JsonObject) = TavernChatMutationResult.Accepted
        }
        val response = TavernRuntimeController(
            permissionStore = TavernRuntimePermissionStore(
                TavernRuntimePermissions(allowScripts = true, allowMessageWrite = false),
            ),
            chatMessageGateway = gateway,
        ).dispatch(TavernRuntimeRequest("chat-write", "messages.setChatMessage", buildJsonObject {}))

        assertFalse(response.ok)
        assertEquals("PERMISSION_DENIED", response.error!!.code)
        assertFalse(called)
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
    fun `every document ready republishes unchanged context`() = runBlocking {
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
        val context = buildJsonObject {
            put("conversationId", "c1")
            put("chat", JsonArray(emptyList()))
        }
        val current = buildJsonObject { put("messageId", "m1") }

        controller.onDocumentReady(context, current)
        yield()
        controller.onDocumentReady(context, current)
        yield()

        assertEquals(2, received.count { it.first == "context_updated" })
        val currentResponse = controller.dispatch(
            TavernRuntimeRequest(id = "ready-current", method = "messages.getCurrent")
        )
        assertEquals(current, currentResponse.result)
        job.cancel()
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

    @Test
    fun `setContext is dropped when scripts are disabled`() = runBlocking {
        val controller = TavernRuntimeController(
            conversationId = Uuid.random(),
            permissionStore = TavernRuntimePermissionStore(
                TavernRuntimePermissions().copy(allowScripts = false)
            ),
        )
        val received = mutableListOf<Pair<String, JsonElement?>>()
        val job = launch {
            controller.outboundEvents.collect { received.add(it) }
        }
        yield()
        controller.setContext(
            buildJsonObject {
                put("chat", JsonArray(emptyList()))
                put("conversationId", "c1")
            }
        )
        yield()
        job.cancel()
        assertEquals(0, received.count { it.first == "context_updated" })
    }

    @Test
    fun `macros register requires allowMacroRegister permission`() {
        val controller = TavernRuntimeController(
            conversationId = Uuid.random(),
            permissionStore = TavernRuntimePermissionStore(
                TavernRuntimePermissions().copy(allowScripts = true, allowMacroRegister = false)
            ),
            scriptRegistry = TavernScriptRegistry(),
        )
        val response = controller.dispatch(
            TavernRuntimeRequest(
                id = "1", method = "macros.register",
                params = buildJsonObject {
                    put("name", "m")
                    put("source", "function macro(args){ return ''; }")
                },
            )
        )
        assertFalse(response.ok)
        assertEquals("PERMISSION_DENIED", response.error?.code)
    }

    @Test
    fun `macros register succeeds with permission`() {
        val registry = TavernScriptRegistry()
        val controller = TavernRuntimeController(
            conversationId = Uuid.random(),
            permissionStore = TavernRuntimePermissionStore(
                TavernRuntimePermissions().copy(allowScripts = true, allowMacroRegister = true)
            ),
            scriptRegistry = registry,
        )
        val response = controller.dispatch(
            TavernRuntimeRequest(
                id = "1", method = "macros.register",
                params = buildJsonObject {
                    put("name", "m")
                    put("source", "function macro(args){ return 'ok'; }")
                },
            )
        )
        assertTrue(response.ok)
        assertEquals(listOf("m"), registry.listMacros())
    }

    @Test
    fun `macros list returns registered macro names`() {
        val registry = TavernScriptRegistry()
        registry.registerMacro("m1", "function macro(args){ return ''; }")
        val controller = TavernRuntimeController(
            conversationId = Uuid.random(),
            permissionStore = TavernRuntimePermissionStore(
                TavernRuntimePermissions().copy(allowScripts = true, allowMacroRegister = true)
            ),
            scriptRegistry = registry,
        )
        val response = controller.dispatch(
            TavernRuntimeRequest(id = "1", method = "macros.list", params = JsonObject(emptyMap()))
        )
        assertTrue(response.ok)
        assertEquals(listOf("m1"), response.result!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `macros remove deletes registered macro`() {
        val registry = TavernScriptRegistry()
        registry.registerMacro("m1", "function macro(args){ return ''; }")
        val controller = TavernRuntimeController(
            conversationId = Uuid.random(),
            permissionStore = TavernRuntimePermissionStore(
                TavernRuntimePermissions().copy(allowScripts = true, allowMacroRegister = true)
            ),
            scriptRegistry = registry,
        )
        val response = controller.dispatch(
            TavernRuntimeRequest(
                id = "1", method = "macros.remove",
                params = buildJsonObject { put("name", "m1") },
            )
        )
        assertTrue(response.ok)
        assertTrue(registry.listMacros().isEmpty())
    }

    @Test
    fun `macros remove requires allowMacroRegister permission`() {
        val controller = TavernRuntimeController(
            conversationId = Uuid.random(),
            permissionStore = TavernRuntimePermissionStore(
                TavernRuntimePermissions().copy(allowScripts = true, allowMacroRegister = false)
            ),
            scriptRegistry = TavernScriptRegistry(),
        )
        val response = controller.dispatch(
            TavernRuntimeRequest(
                id = "1", method = "macros.remove",
                params = buildJsonObject { put("name", "m1") },
            )
        )
        assertFalse(response.ok)
        assertEquals("PERMISSION_DENIED", response.error?.code)
    }

    @Test
    fun `slash register stores command with aliases and help`() {
        val registry = TavernScriptRegistry()
        val controller = TavernRuntimeController(
            conversationId = Uuid.random(),
            permissionStore = TavernRuntimePermissionStore(
                TavernRuntimePermissions().copy(allowScripts = true, allowMacroRegister = true)
            ),
            scriptRegistry = registry,
        )
        val response = controller.dispatch(
            TavernRuntimeRequest(
                id = "1", method = "slash.register",
                params = buildJsonObject {
                    put("name", "flip")
                    put("source", "function callback(args){ return args; }")
                    put("aliases", JsonArray(listOf(JsonPrimitive("f"))))
                    put("helpString", "flip text")
                },
            )
        )
        assertTrue(response.ok)
        val info = registry.listSlashCommands().single()
        assertEquals("flip", info.name)
        assertEquals(listOf("f"), info.aliases)
        assertEquals("flip text", info.helpString)
    }

    @Test
    fun `slash register requires allowMacroRegister permission`() {
        val controller = TavernRuntimeController(
            conversationId = Uuid.random(),
            permissionStore = TavernRuntimePermissionStore(
                TavernRuntimePermissions().copy(allowScripts = true, allowMacroRegister = false)
            ),
            scriptRegistry = TavernScriptRegistry(),
        )
        val response = controller.dispatch(
            TavernRuntimeRequest(
                id = "1", method = "slash.register",
                params = buildJsonObject {
                    put("name", "flip")
                    put("source", "function callback(args){ return args; }")
                },
            )
        )
        assertFalse(response.ok)
        assertEquals("PERMISSION_DENIED", response.error?.code)
    }

    @Test
    fun `slash unregister removes command`() {
        val registry = TavernScriptRegistry()
        registry.registerSlashCommand("flip", "function callback(args){ return args; }", emptyList(), "")
        val controller = TavernRuntimeController(
            conversationId = Uuid.random(),
            permissionStore = TavernRuntimePermissionStore(
                TavernRuntimePermissions().copy(allowScripts = true, allowMacroRegister = true)
            ),
            scriptRegistry = registry,
        )
        val response = controller.dispatch(
            TavernRuntimeRequest(
                id = "1", method = "slash.unregister",
                params = buildJsonObject { put("name", "flip") },
            )
        )
        assertTrue(response.ok)
        assertTrue(registry.listSlashCommands().isEmpty())
    }

    @Test
    fun `requestHeaders get requires allowRequestHeaders`() {
        val controller = TavernRuntimeController(
            conversationId = Uuid.random(),
            permissionStore = TavernRuntimePermissionStore(
                TavernRuntimePermissions().copy(allowScripts = true, allowRequestHeaders = false)
            ),
        )
        val response = controller.dispatch(
            TavernRuntimeRequest(id = "1", method = "requestHeaders.get", params = JsonObject(emptyMap()))
        )
        assertFalse(response.ok)
    }

    @Test
    fun `requestHeaders get returns injected headers when allowed`() {
        val controller = TavernRuntimeController(
            conversationId = Uuid.random(),
            permissionStore = TavernRuntimePermissionStore(
                TavernRuntimePermissions().copy(allowScripts = true, allowRequestHeaders = true)
            ),
            headerSource = { listOf("Authorization" to "Bearer secret", "X-Custom" to "v") },
        )
        val response = controller.dispatch(
            TavernRuntimeRequest(id = "1", method = "requestHeaders.get", params = JsonObject(emptyMap()))
        )
        assertTrue(response.ok)
        val headers = response.result!!.jsonArray
        assertEquals(2, headers.size)
        assertEquals("Authorization", headers[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("Bearer secret", headers[0].jsonObject["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sendHook register requires allowMacroRegister permission`() {
        val controller = TavernRuntimeController(
            conversationId = Uuid.random(),
            permissionStore = TavernRuntimePermissionStore(
                TavernRuntimePermissions().copy(allowScripts = true, allowMacroRegister = false)
            ),
        )
        val response = controller.dispatch(
            TavernRuntimeRequest(
                id = "1", method = "sendHook.register",
                params = buildJsonObject { put("source", "function macro(args){ return args; }") },
            )
        )
        assertFalse(response.ok)
        assertEquals("PERMISSION_DENIED", response.error?.code)
    }

    @Test
    fun `sendHook register accepts source and mutateOutgoing falls back without engine`() = runBlocking {
        val registry = TavernScriptRegistry()
        val controller = TavernRuntimeController(
            conversationId = Uuid.random(),
            permissionStore = TavernRuntimePermissionStore(
                TavernRuntimePermissions().copy(allowScripts = true, allowMacroRegister = true)
            ),
            scriptRegistry = registry,
        )
        val response = controller.dispatch(
            TavernRuntimeRequest(
                id = "1", method = "sendHook.register",
                params = buildJsonObject { put("source", "function macro(args){ return '[' + args + ']'; }") },
            )
        )
        assertTrue(response.ok)
        // JVM 环境无 QuickJS 原生库 → registry 降级无引擎模式，展开失败原样返回（best-effort）
        assertEquals("hello", controller.mutateOutgoing("hello"))
    }

    @Test
    fun `mutateOutgoing returns text unchanged when no hook registered`() = runBlocking {
        val controller = TavernRuntimeController(
            permissionStore = TavernRuntimePermissionStore(
                TavernRuntimePermissions().copy(allowScripts = true, allowMacroRegister = true)
            ),
        )
        assertEquals("hello", controller.mutateOutgoing("hello"))
    }

    @Test
    fun `mutateOutgoing passes brace-heavy text through unharmed without engine`() = runBlocking {
        // 回归（Task 5 Important 修复）：sendHook 经单宏直调执行，文本含 }} 不再被
        // {{}} 包装正则截断——无引擎环境（JVM 单测）下 best-effort 原样返回完整文本。
        val registry = TavernScriptRegistry()
        val controller = TavernRuntimeController(
            conversationId = Uuid.random(),
            permissionStore = TavernRuntimePermissionStore(
                TavernRuntimePermissions().copy(allowScripts = true, allowMacroRegister = true)
            ),
            scriptRegistry = registry,
        )
        controller.dispatch(
            TavernRuntimeRequest(
                id = "1", method = "sendHook.register",
                params = buildJsonObject { put("source", "function macro(args){ return args; }") },
            )
        )
        val text = "closing}} braces {{ and \"quoted\"\nnewline {{__rikkahub_send_hook::x}}"
        assertEquals(text, controller.mutateOutgoing(text))
    }
}
