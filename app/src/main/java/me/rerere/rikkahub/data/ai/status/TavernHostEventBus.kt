package me.rerere.rikkahub.data.ai.status

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/**
 * 宿主 → 酒馆脚本 的生命周期事件类型（对齐 SillyTavern event_types 命名）。
 * 经 TavernRuntimeController 按订阅关系转发进 WebView 后，
 * 以 th:<name> 的 DOM CustomEvent 形式送达脚本。
 */
enum class TavernHostEventType {
    // SillyTavern event_types 对齐（B2a）
    GENERATION_STARTED,
    MESSAGE_SENT,
    MESSAGE_RECEIVED,
    MESSAGE_EDITED,
    MESSAGE_DELETED,
    MESSAGE_SWIPED,
    CHARACTER_MESSAGE_RENDERED,
    USER_MESSAGE_RENDERED,
    // 旧事件名（兼容保留）
    MESSAGE_SENDING,
    GENERATION_FINISHED,
    MESSAGE_RENDERED,
}

data class TavernHostEvent(
    val type: TavernHostEventType,
    val conversationId: Uuid? = null,
    val payload: JsonObject = JsonObject(emptyMap()),
)

/**
 * 宿主事件总线（Koin 单例）。
 * 发射方：ChatService（发送/生成完成）、MarkdownWebView（渲染完成）。
 * 消费方：各 WebView 的 TavernRuntimeController（按 conversationId + 订阅名过滤后推送）。
 *
 * extraBufferCapacity + tryEmit：无订阅者时事件直接丢弃，发射方永远不挂起。
 */
class TavernHostEventBus {
    private val _events = MutableSharedFlow<TavernHostEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<TavernHostEvent> = _events.asSharedFlow()

    fun emit(event: TavernHostEvent) {
        _events.tryEmit(event)
    }

    fun emit(
        type: TavernHostEventType,
        conversationId: Uuid?,
        payload: JsonObject = JsonObject(emptyMap()),
    ) {
        emit(TavernHostEvent(type = type, conversationId = conversationId, payload = payload))
    }
}
