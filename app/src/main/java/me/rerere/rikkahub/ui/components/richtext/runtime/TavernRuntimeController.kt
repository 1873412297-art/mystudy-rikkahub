package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.status.TavernHostEvent
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/** 单个变量值序列化后的体积上限（UTF-8 字节） */
private const val MAX_VARIABLE_VALUE_BYTES = 64 * 1024

/** 同一作用域下变量总量序列化后的体积上限（UTF-8 字节） */
private const val MAX_VARIABLE_TOTAL_BYTES = 512 * 1024

internal class TavernRuntimeController(
    private val conversationId: Uuid? = null,
    private val eventBus: TavernRuntimeEventBus = TavernRuntimeEventBus(),
    private val worldRepository: TavernWorldRepository = TavernRuntimeWorldStore(),
    private val permissionStore: TavernRuntimePermissionStore = TavernRuntimePermissionStore(),
    private val variableGateway: TavernRuntimeVariableGateway = InMemoryTavernRuntimeVariableGateway(),
    hostEventFlow: SharedFlow<TavernHostEvent>? = null,
    hostEventScope: CoroutineScope? = null,
) {
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
                else -> TavernRuntimeResponse.error(
                    id = request.id,
                    code = "UNSUPPORTED",
                    message = "Runtime method '${request.method}' is not available in this compatibility layer",
                )
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
}

/** 序列化后的 UTF-8 字节数（用于变量体积配额校验） */
private fun JsonElement.utf8ByteSize(): Int = toString().toByteArray(Charsets.UTF_8).size
