package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.JsonElement
import java.util.concurrent.CopyOnWriteArrayList

/** recent() 保留的最大历史事件数 */
private const val MAX_HISTORY_SIZE = 100

internal class TavernRuntimeEventBus {
    // emit 在 WebView JavaBridge 线程上调用，recent 可能在 UI 线程读取，需加锁保护
    private val history = ArrayDeque<Pair<String, JsonElement?>>()
    private val historyLock = Any()
    private val listeners = CopyOnWriteArrayList<(String, JsonElement?) -> Unit>()

    fun emit(name: String, payload: JsonElement?) {
        synchronized(historyLock) {
            history.addLast(name to payload)
            while (history.size > MAX_HISTORY_SIZE) {
                history.removeFirst()
            }
        }
        listeners.forEach { listener ->
            runCatching { listener(name, payload) }
        }
    }

    fun recent(): List<Pair<String, JsonElement?>> = synchronized(historyLock) { history.toList() }

    /** 订阅脚本事件（宿主用来把事件转发成 WebView 内的 th:<name> DOM 事件） */
    fun addListener(listener: (String, JsonElement?) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (String, JsonElement?) -> Unit) {
        listeners -= listener
    }
}
