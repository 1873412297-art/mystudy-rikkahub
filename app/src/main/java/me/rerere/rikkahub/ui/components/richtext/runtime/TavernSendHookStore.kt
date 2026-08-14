package me.rerere.rikkahub.ui.components.richtext.runtime

import me.rerere.ai.ui.UIMessagePart

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

    /**
     * 最近组合的消息 WebView controller。
     * MarkdownWebView 组合时写入（多 WebView 并发时最后组合者生效），
     * 离开组合/被重建时按身份清空（`===` 判定避免清掉更新的 controller）。
     */
    @Volatile
    internal var activeController: TavernRuntimeController? = null

    suspend fun mutateOutgoing(parts: List<UIMessagePart>, timeoutMs: Long = 500): List<UIMessagePart> {
        val controller = activeController ?: return parts
        return parts.map { part ->
            if (part is UIMessagePart.Text) {
                part.copy(text = controller.mutateOutgoing(part.text, timeoutMs))
            } else {
                part
            }
        }
    }
}
