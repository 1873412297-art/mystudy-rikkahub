package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.slash.MacroExpandContext
import me.rerere.rikkahub.data.ai.slash.TavernScriptRegistry
import me.rerere.rikkahub.data.ai.status.TavernHostEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid

/** 单个变量值序列化后的体积上限（UTF-8 字节） */
private const val MAX_VARIABLE_VALUE_BYTES = 64 * 1024

/** 同一作用域下变量总量序列化后的体积上限（UTF-8 字节） */
private const val MAX_VARIABLE_TOTAL_BYTES = 512 * 1024

/** sendHook 注册在宿主注册表中的特殊宏名 */
/** sendHook 单次执行超时（best-effort：超时原样返回） */
private const val SEND_HOOK_TIMEOUT_MS = 500L

internal class TavernRuntimeController(
    conversationId: Uuid? = null,
    private val eventBus: TavernRuntimeEventBus = TavernRuntimeEventBus(),
    private val worldRepository: TavernWorldRepository = TavernRuntimeWorldStore(),
    private val permissionStore: TavernRuntimePermissionStore = TavernRuntimePermissionStore(),
    private val variableGateway: TavernRuntimeVariableGateway = InMemoryTavernRuntimeVariableGateway(),
    hostEventFlow: SharedFlow<TavernHostEvent>? = null,
    hostEventScope: CoroutineScope? = null,
    private val scriptRegistry: TavernScriptRegistry = TavernScriptRegistry(),
    private val headerSource: (() -> List<Pair<String, String>>)? = null,
) {
    @Volatile
    private var conversationId: Uuid? = conversationId

    // dispatch 在 WebView JavaBridge 线程上读，setContext/setCurrentMessage 在宿主线程上写
    @Volatile
    private var currentMessage: JsonElement = JsonNull

    /** 宿主推送的上下文快照（SillyTavern.getContext 数据源） */
    @Volatile
    private var contextSnapshot: JsonObject? = null

    /** 上次推送的上下文内容哈希（去重用） */
    @Volatile
    private var lastContextHash: Int? = null

    /** 脚本通过 events.subscribe 显式订阅的宿主事件名 */
    private val subscribedHostEvents = ConcurrentHashMap.newKeySet<String>()

    /** 待推送到 WebView 的事件流（由 MarkdownWebView 转成 th:<name> DOM CustomEvent） */
    private val _outboundEvents = MutableSharedFlow<Pair<String, JsonElement?>>(extraBufferCapacity = 64)
    val outboundEvents: SharedFlow<Pair<String, JsonElement?>> = _outboundEvents.asSharedFlow()

    /** 宿主事件收集 job：挂在外部传入的 scope 上，由 [cancelHostEventCollection] 随 controller 生命周期取消 */
    private var hostEventJob: Job? = null

    /** sendHook.register 注册的发送前钩子源码（发送管线经 [mutateOutgoing] 问询执行） */
    private val sendHookSource = AtomicReference<String?>()

    init {
        // 脚本 events.emit 产生的事件回投到同一 WebView（本地广播，无需订阅权限）
        eventBus.addListener { name, payload ->
            _outboundEvents.tryEmit(name to payload)
        }
        // 宿主生命周期事件只在脚本显式订阅后转发，且按 conversationId 过滤
        if (hostEventFlow != null && hostEventScope != null) {
            hostEventJob = hostEventScope.launch {
                hostEventFlow.collect { event ->
                    val matchesConversation = event.conversationId == null || event.conversationId == conversationId
                    if (matchesConversation && event.type.name in subscribedHostEvents) {
                        _outboundEvents.tryEmit(event.type.name to event.payload)
                    }
                }
            }
        }
    }

    /**
     * 取消宿主事件收集 job（幂等）。
     * controller 被重建（如 tavernConversationId 变化）或离开组合时调用，
     * 避免旧 controller 的收集 job 泄漏到外部 scope 结束才取消、期间继续空发。
     */
    fun cancelHostEventCollection() {
        hostEventJob?.cancel()
        hostEventJob = null
    }

    /**
     * Rebinds this persistent browser session to the active chat without replacing its WebView.
     */
    fun updateConversationId(conversationId: Uuid?) {
        this.conversationId = conversationId
    }

    /** 宿主注入当前消息 JSON（messages.getCurrent 的数据源） */
    fun setCurrentMessage(message: JsonElement) {
        currentMessage = message
    }

    /**
     * 宿主推送上下文快照（SillyTavern.getContext 数据源）。
     * 内容不变时跳过推送；变化时经 outbound 事件 th:context_updated 送达 WebView。
     * 受 allowScripts 总开关保护：脚本禁用时丢弃快照且不更新哈希（启用后下次快照变化自愈）。
     */
    fun setContext(context: JsonObject?) {
        if (!permissionStore.current().allowScripts) {
            return
        }
        contextSnapshot = context
        val hash = context?.hashCode()
        if (hash != lastContextHash) {
            lastContextHash = hash
            if (context != null) {
                _outboundEvents.tryEmit("context_updated" to context)
            }
        }
    }

    fun dispatch(request: TavernRuntimeRequest): TavernRuntimeResponse {
        return try {
            if (!permissionStore.current().allowScripts && request.method != "runtime.ping") {
                return permissionDenied(request, "Runtime scripts are disabled")
            }
            when (request.method) {
                "runtime.ping" -> TavernRuntimeResponse.success(request.id, JsonPrimitive("pong"))
                "variables.get" -> getVariable(request)
                "variables.set" -> setVariable(request)
                "variables.list" -> listVariables(request)
                "variables.delete" -> deleteVariable(request)
                "slash.run" -> runSlash(request)
                "events.emit" -> emitEvent(request)
                "events.subscribe" -> subscribeHostEvent(request)
                "events.unsubscribe" -> unsubscribeHostEvent(request)
                "world.getEntries" -> getWorldEntries(request)
                "world.upsertEntry" -> upsertWorldEntry(request)
                "world.deleteEntry" -> deleteWorldEntry(request)
                "messages.getCurrent" -> getCurrentMessage(request)
                "messages.updateCurrent" -> updateCurrentMessage(request)
                "macros.register" -> registerMacro(request)
                "macros.remove" -> removeMacro(request)
                "macros.list" -> listMacros(request)
                "slash.register" -> registerSlashCommand(request)
                "slash.unregister" -> unregisterSlashCommand(request)
                "requestHeaders.get" -> getRequestHeaders(request)
                "sendHook.register" -> registerSendHook(request)
                "extensions.install",
                "extensions.uninstall",
                "extensions.update",
                "server.getAdminStatus",
                "server.filesystem.read",
                "dom.jquery.queryTopLevel",
                "backend.st.request" -> unsupportedHostCapability(request)
                else -> unsupportedHostCapability(request)
            }
        } catch (e: Exception) {
            TavernRuntimeResponse.error(
                id = request.id,
                code = "INTERNAL_ERROR",
                message = e.message ?: "Unexpected runtime failure",
            )
        }
    }

    private fun permissionDenied(request: TavernRuntimeRequest, message: String): TavernRuntimeResponse {
        return TavernRuntimeResponse.error(request.id, "PERMISSION_DENIED", message)
    }

    private fun unsupportedHostCapability(request: TavernRuntimeRequest): TavernRuntimeResponse {
        return TavernRuntimeResponse.error(
            id = request.id,
            code = "UNSUPPORTED_HOST_CAPABILITY",
            message = "Request '${request.id}' cannot use unavailable host capability '${request.method}'",
        )
    }

    /**
     * 读取必填字符串参数；缺失时统一返回 "<method> requires params.<name>" 的 BAD_REQUEST。
     */
    private inline fun withRequiredStringParam(
        request: TavernRuntimeRequest,
        name: String,
        block: (String) -> TavernRuntimeResponse,
    ): TavernRuntimeResponse {
        val value = request.params.getString(name)
            ?: return TavernRuntimeResponse.error(
                request.id, "BAD_REQUEST", "${request.method} requires params.$name"
            )
        return block(value)
    }

    private fun getVariable(request: TavernRuntimeRequest): TavernRuntimeResponse {
        return withRequiredStringParam(request, "key") { key ->
            val scope = resolveScope(request) ?: return@withRequiredStringParam unsupportedScope(request)
            val value = variableGateway.get(conversationId, scope, key) ?: JsonNull
            TavernRuntimeResponse.success(request.id, value)
        }
    }

    private fun setVariable(request: TavernRuntimeRequest): TavernRuntimeResponse {
        if (!permissionStore.current().allowVariablesWrite) {
            return permissionDenied(request, "Variable write access is disabled for this script")
        }
        return withRequiredStringParam(request, "key") { key ->
            val scope = resolveScope(request) ?: return@withRequiredStringParam unsupportedScope(request)
            val value = request.params["value"] ?: JsonNull
            if (value.utf8ByteSize() > MAX_VARIABLE_VALUE_BYTES) {
                return@withRequiredStringParam TavernRuntimeResponse.error(
                    request.id, "VALUE_TOO_LARGE", "Variable value exceeds the 64KB limit"
                )
            }
            val merged = variableGateway.list(conversationId, scope).toMutableMap()
            merged[key] = value
            if (JsonObject(merged).utf8ByteSize() > MAX_VARIABLE_TOTAL_BYTES) {
                return@withRequiredStringParam TavernRuntimeResponse.error(
                    request.id, "QUOTA_EXCEEDED", "Variables exceed the 512KB total limit for scope '$scope'"
                )
            }
            variableGateway.set(conversationId, scope, key, value)
            TavernRuntimeResponse.success(request.id, JsonPrimitive(true))
        }
    }

    private fun listVariables(request: TavernRuntimeRequest): TavernRuntimeResponse {
        val scope = resolveScope(request) ?: return unsupportedScope(request)
        return TavernRuntimeResponse.success(request.id, variableGateway.list(conversationId, scope))
    }

    private fun deleteVariable(request: TavernRuntimeRequest): TavernRuntimeResponse {
        if (!permissionStore.current().allowVariablesWrite) {
            return permissionDenied(request, "Variable write access is disabled for this script")
        }
        return withRequiredStringParam(request, "key") { key ->
            val scope = resolveScope(request) ?: return@withRequiredStringParam unsupportedScope(request)
            TavernRuntimeResponse.success(
                request.id,
                JsonPrimitive(variableGateway.delete(conversationId, scope, key)),
            )
        }
    }

    private fun resolveScope(request: TavernRuntimeRequest): String? {
        return when (val scope = request.params.getString("scope") ?: TAVERN_VARIABLE_SCOPE_CHAT) {
            TAVERN_VARIABLE_SCOPE_CHAT, TAVERN_VARIABLE_SCOPE_GLOBAL -> scope
            else -> null
        }
    }

    private fun unsupportedScope(request: TavernRuntimeRequest): TavernRuntimeResponse {
        return TavernRuntimeResponse.error(
            request.id,
            "BAD_REQUEST",
            "Unsupported variables scope '${request.params.getString("scope")}'",
        )
    }

    private fun runSlash(request: TavernRuntimeRequest): TavernRuntimeResponse {
        val command = request.params.getString("command")?.trim().orEmpty()
        return when (command.removePrefix("/")) {
            "th help" -> TavernRuntimeResponse.success(
                request.id,
                JsonPrimitive("/th help\n/th vars\n/th ping"),
            )
            "th ping" -> TavernRuntimeResponse.success(
                request.id,
                JsonPrimitive("pong"),
            )
            "th vars" -> listVariables(request)
            else -> if (command.isBlank()) {
                TavernRuntimeResponse.error(
                    request.id,
                    "BAD_REQUEST",
                    "slash.run requires params.command",
                )
            } else {
                TavernRuntimeResponse.error(
                    request.id,
                    "UNSUPPORTED_SLASH_COMMAND",
                    "Slash command '$command' is not supported by Rikkahub Tavern compatibility runtime",
                )
            }
        }
    }

    private fun emitEvent(request: TavernRuntimeRequest): TavernRuntimeResponse {
        return withRequiredStringParam(request, "name") { name ->
            eventBus.emit(name, request.params["payload"])
            TavernRuntimeResponse.success(request.id, JsonPrimitive(name))
        }
    }

    private fun subscribeHostEvent(request: TavernRuntimeRequest): TavernRuntimeResponse {
        if (!permissionStore.current().allowEventSubscribe) {
            return permissionDenied(request, "Event subscription is disabled for this script")
        }
        return withRequiredStringParam(request, "name") { name ->
            subscribedHostEvents += name
            TavernRuntimeResponse.success(request.id, JsonPrimitive(true))
        }
    }

    private fun unsubscribeHostEvent(request: TavernRuntimeRequest): TavernRuntimeResponse {
        if (!permissionStore.current().allowEventSubscribe) {
            return permissionDenied(request, "Event subscription is disabled for this script")
        }
        return withRequiredStringParam(request, "name") { name ->
            subscribedHostEvents -= name
            TavernRuntimeResponse.success(request.id, JsonPrimitive(true))
        }
    }

    private fun getWorldEntries(request: TavernRuntimeRequest): TavernRuntimeResponse {
        return TavernRuntimeResponse.success(request.id, JsonArray(worldRepository.listEntries()))
    }

    private fun upsertWorldEntry(request: TavernRuntimeRequest): TavernRuntimeResponse {
        if (!permissionStore.current().allowWorldWrite) {
            return permissionDenied(request, "World write access is disabled for this script")
        }
        val entry = request.params["entry"] as? JsonObject
            ?: return TavernRuntimeResponse.error(
                request.id, "BAD_REQUEST", "world.upsertEntry requires params.entry object"
            )
        val id = worldRepository.upsertEntry(entry)
        return TavernRuntimeResponse.success(request.id, JsonPrimitive(id))
    }

    private fun deleteWorldEntry(request: TavernRuntimeRequest): TavernRuntimeResponse {
        if (!permissionStore.current().allowWorldWrite) {
            return permissionDenied(request, "World write access is disabled for this script")
        }
        return withRequiredStringParam(request, "id") { id ->
            TavernRuntimeResponse.success(request.id, JsonPrimitive(worldRepository.deleteEntry(id)))
        }
    }

    private fun getCurrentMessage(request: TavernRuntimeRequest): TavernRuntimeResponse {
        val fromContext = contextSnapshot?.get("chat")?.jsonArray
            ?.lastOrNull { it.jsonObject["isCurrent"]?.jsonPrimitive?.boolean == true }
            ?: contextSnapshot?.get("chat")?.jsonArray?.lastOrNull()
        return TavernRuntimeResponse.success(request.id, fromContext ?: currentMessage)
    }

    private fun updateCurrentMessage(request: TavernRuntimeRequest): TavernRuntimeResponse {
        if (!permissionStore.current().allowMessageWrite) {
            return permissionDenied(request, "Message write access is disabled for this script")
        }
        currentMessage = request.params["patch"] ?: JsonNull
        return TavernRuntimeResponse.success(request.id, JsonPrimitive(true))
    }

    private fun badRequest(request: TavernRuntimeRequest, message: String): TavernRuntimeResponse {
        return TavernRuntimeResponse.error(request.id, "BAD_REQUEST", message)
    }

    private fun registerMacro(request: TavernRuntimeRequest): TavernRuntimeResponse {
        if (!permissionStore.current().allowMacroRegister) {
            return permissionDenied(request, "Macro registration is disabled for this script")
        }
        val name = request.params.getString("name")
            ?: return badRequest(request, "macros.register requires params.name")
        val source = request.params.getString("source")
            ?: return badRequest(request, "macros.register requires params.source")
        val ok = scriptRegistry.registerMacro(name, source)
        return TavernRuntimeResponse.success(request.id, JsonPrimitive(ok))
    }

    private fun removeMacro(request: TavernRuntimeRequest): TavernRuntimeResponse {
        if (!permissionStore.current().allowMacroRegister) {
            return permissionDenied(request, "Macro registration is disabled for this script")
        }
        val name = request.params.getString("name")
            ?: return badRequest(request, "macros.remove requires params.name")
        scriptRegistry.removeMacro(name)
        return TavernRuntimeResponse.success(request.id, JsonPrimitive(true))
    }

    private fun listMacros(request: TavernRuntimeRequest): TavernRuntimeResponse {
        return TavernRuntimeResponse.success(
            request.id,
            JsonArray(scriptRegistry.listMacros().map { JsonPrimitive(it) }),
        )
    }

    private fun registerSlashCommand(request: TavernRuntimeRequest): TavernRuntimeResponse {
        if (!permissionStore.current().allowMacroRegister) {
            return permissionDenied(request, "Slash command registration is disabled for this script")
        }
        val name = request.params.getString("name")
            ?: return badRequest(request, "slash.register requires params.name")
        val source = request.params.getString("source")
            ?: return badRequest(request, "slash.register requires params.source")
        val aliases = (request.params["aliases"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?: emptyList()
        val helpString = request.params.getString("helpString") ?: ""
        val ok = scriptRegistry.registerSlashCommand(name, source, aliases, helpString)
        return TavernRuntimeResponse.success(request.id, JsonPrimitive(ok))
    }

    private fun unregisterSlashCommand(request: TavernRuntimeRequest): TavernRuntimeResponse {
        if (!permissionStore.current().allowMacroRegister) {
            return permissionDenied(request, "Slash command registration is disabled for this script")
        }
        val name = request.params.getString("name")
            ?: return badRequest(request, "slash.unregister requires params.name")
        scriptRegistry.removeSlashCommand(name)
        return TavernRuntimeResponse.success(request.id, JsonPrimitive(true))
    }

    private fun getRequestHeaders(request: TavernRuntimeRequest): TavernRuntimeResponse {
        if (!permissionStore.current().allowRequestHeaders) {
            return permissionDenied(request, "Request header access is disabled for this script")
        }
        val headers = headerSource?.invoke() ?: emptyList()
        return TavernRuntimeResponse.success(
            request.id,
            JsonArray(headers.map { (name, value) ->
                buildJsonObject {
                    put("name", name)
                    put("value", value)
                }
            }),
        )
    }

    private fun registerSendHook(request: TavernRuntimeRequest): TavernRuntimeResponse {
        // best-effort 发送前钩子：源码注册后由 ChatService 发送管线经 mutateOutgoing 问询执行
        if (!permissionStore.current().allowMacroRegister) {
            return permissionDenied(request, "Send hook registration is disabled for this script")
        }
        val source = request.params.getString("source")
            ?: return badRequest(request, "sendHook.register requires params.source")
        return registerSendHookInternal(request, source)
    }

    private fun registerSendHookInternal(request: TavernRuntimeRequest, source: String): TavernRuntimeResponse {
        if (!scriptRegistry.registerSendHook(source)) {
            return badRequest(request, "sendHook.register source exceeds the 64KB limit")
        }
        sendHookSource.set(source)
        return TavernRuntimeResponse.success(request.id, JsonPrimitive(true))
    }

    /**
     * 发送前 best-effort 文本变换：把注册的 sendHook 源码作为特殊宏单宏直调执行。
     * 直调不走 {{}} 全文正则语法——文本含 }} / {{ / 引号 / 换行均安全（无截断损坏）。
     * 无钩子/权限关闭/引擎不可用/展开失败/超时 → 原样返回（Task 7 发送管线消费）。
     */
    suspend fun mutateOutgoing(text: String, timeoutMs: Long = SEND_HOOK_TIMEOUT_MS): String {
        if (sendHookSource.get() == null) return text
        // 与宏展开门控一致：总开关关闭时 sendHook 同样不生效
        if (!permissionStore.current().allowScripts) return text
        if (!permissionStore.current().allowMacroRegister) return text
        val expanded = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                scriptRegistry.expandSendHookAsync(text)
            }
        }
        // 展开失败（引擎不可用/超时/异常）→ best-effort 原样
        return expanded ?: text
    }
}

/** 序列化后的 UTF-8 字节数（用于变量体积配额校验） */
private fun JsonElement.utf8ByteSize(): Int = toString().toByteArray(Charsets.UTF_8).size
