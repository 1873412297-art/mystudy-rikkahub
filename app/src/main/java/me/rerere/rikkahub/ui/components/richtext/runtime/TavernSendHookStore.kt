package me.rerere.rikkahub.ui.components.richtext.runtime

import java.util.concurrent.ConcurrentHashMap
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/**
 * 发送前钩子桥（应用级 Koin 单例）：保存最近组合的消息 WebView 所建的
 * [TavernRuntimeController]，供 ChatService 发送管线 best-effort 问询
 * [mutateOutgoing]。
 *
 * 语义：
 * - 无活跃 controller（无消息 WebView / 已滚出组合）→ 原样返回 parts；
 * - 活跃 controller 未注册 sendHook / 权限关闭 / 引擎不可用 / 超时 →
 *   controller 内部兜底原样（不影响发送）。
 */
class TavernSendHookStore {

    private val activeControllers = ConcurrentHashMap<Uuid, TavernRuntimeController>()
    private val committedControllers = ConcurrentHashMap<Uuid, TavernRuntimeController>()

    /**
     * 最近组合的消息 WebView controller。
     * MarkdownWebView 组合时写入（多 WebView 并发时最后组合者生效），
     * 离开组合/被重建时按身份清空（`===` 判定避免清掉更新的 controller）。
     */
    @Volatile
    internal var activeController: TavernRuntimeController? = null

    internal fun attach(conversationId: Uuid, controller: TavernRuntimeController) {
        activeControllers[conversationId] = controller
    }

    internal fun detach(conversationId: Uuid, controller: TavernRuntimeController) {
        activeControllers.remove(conversationId, controller)
    }

    internal fun installCommitted(conversationId: Uuid, controller: TavernRuntimeController?) {
        if (controller == null) committedControllers.remove(conversationId) else committedControllers[conversationId] = controller
    }

    internal fun committedController(conversationId: Uuid): TavernRuntimeController? =
        committedControllers[conversationId]

    internal fun controllerFor(conversationId: Uuid): TavernRuntimeController? {
        val active = activeControllers[conversationId]
        return active?.takeIf { it.hasSendHook() } ?: committedControllers[conversationId] ?: active
    }

    suspend fun mutateOutgoing(
        conversationId: Uuid,
        parts: List<UIMessagePart>,
        timeoutMs: Long = 500,
    ): List<UIMessagePart> = mutateWithController(controllerFor(conversationId), parts, timeoutMs)

    suspend fun mutateOutgoing(parts: List<UIMessagePart>, timeoutMs: Long = 500): List<UIMessagePart> {
        return mutateWithController(activeController, parts, timeoutMs)
    }

    private suspend fun mutateWithController(
        controller: TavernRuntimeController?,
        parts: List<UIMessagePart>,
        timeoutMs: Long,
    ): List<UIMessagePart> {
        controller ?: return parts
        return parts.map { part ->
            if (part is UIMessagePart.Text) {
                part.copy(text = controller.mutateOutgoing(part.text, timeoutMs))
            } else {
                part
            }
        }
    }
}
