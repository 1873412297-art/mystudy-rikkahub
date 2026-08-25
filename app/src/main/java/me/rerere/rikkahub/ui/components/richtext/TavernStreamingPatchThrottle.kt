package me.rerere.rikkahub.ui.components.richtext

/**
 * 流式渲染的段 patch 节流器（spec §5.5：开启流式后以 120ms 节流提取最后生成消息）。
 *
 * 语义：内容到达时 —
 * - 距上次实际派发 ≥ intervalMs：立即派发（返回 0）。
 * - 不足 intervalMs：安排一次尾随派发，返回应延迟的毫秒数；尾随只保留最新内容。
 * - 尾随已挂起：返回 -1，调用方只更新待派发内容，不再重复安排。
 *
 * 线程安全；时间源由调用方注入（UI 侧 SystemClock.uptimeMillis，测试侧假时钟）。
 */
internal class TavernStreamingPatchThrottle(
    private val intervalMs: Long = 120L,
) {
    private var lastDispatchAt: Long? = null
    private var trailingPending = false

    /**
     * 内容到达。
     * @return 0 = 立即派发；>0 = 在该毫秒数后安排尾随派发；-1 = 尾随已挂起（仅更新内容）
     */
    @Synchronized
    fun onContent(nowMs: Long): Long {
        if (trailingPending) return TRAILING_ALREADY_PENDING
        val last = lastDispatchAt
        val elapsed = if (last == null) intervalMs else nowMs - last
        return if (elapsed >= intervalMs) {
            lastDispatchAt = nowMs
            DISPATCH_NOW
        } else {
            trailingPending = true
            intervalMs - elapsed
        }
    }

    /** 尾随派发实际执行时调用（随后应用最新内容）。 */
    @Synchronized
    fun onTrailingDispatch(nowMs: Long) {
        trailingPending = false
        lastDispatchAt = nowMs
    }

    companion object {
        const val DISPATCH_NOW = 0L
        const val TRAILING_ALREADY_PENDING = -1L
    }
}
