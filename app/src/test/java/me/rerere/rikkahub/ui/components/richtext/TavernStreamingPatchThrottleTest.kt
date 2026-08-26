package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Test

class TavernStreamingPatchThrottleTest {

    @Test
    fun `first content dispatches immediately`() {
        val throttle = TavernStreamingPatchThrottle(intervalMs = 120)
        assertEquals(0L, throttle.onContent(1_000L))
    }

    @Test
    fun `content within interval schedules one trailing dispatch with remaining delay`() {
        val throttle = TavernStreamingPatchThrottle(intervalMs = 120)
        assertEquals(0L, throttle.onContent(1_000L))

        // 50ms 后到达：应延迟 70ms 尾随
        assertEquals(70L, throttle.onContent(1_050L))
        // 尾随挂起期间继续到达：只更新内容，不再安排
        assertEquals(-1L, throttle.onContent(1_060L))
        assertEquals(-1L, throttle.onContent(1_100L))

        // 尾随执行
        throttle.onTrailingDispatch(1_120L)
        // 尾随后立即到达：距上次派发 0ms，再次节流
        assertEquals(120L, throttle.onContent(1_120L))
    }

    @Test
    fun `content after full interval dispatches immediately again`() {
        val throttle = TavernStreamingPatchThrottle(intervalMs = 120)
        assertEquals(0L, throttle.onContent(1_000L))
        assertEquals(0L, throttle.onContent(1_120L))
        assertEquals(0L, throttle.onContent(1_240L))
    }

    @Test
    fun `trailing dispatch releases the pending flag so later content can schedule again`() {
        val throttle = TavernStreamingPatchThrottle(intervalMs = 120)
        throttle.onContent(0L)
        assertEquals(100L, throttle.onContent(20L))
        throttle.onTrailingDispatch(120L)

        assertEquals(0L, throttle.onContent(240L))
        assertEquals(60L, throttle.onContent(300L))
        assertEquals(-1L, throttle.onContent(310L))
        throttle.onTrailingDispatch(360L)
        assertEquals(0L, throttle.onContent(480L))
    }

    @Test
    fun `per-token burst collapses to at most one dispatch per interval`() {
        val throttle = TavernStreamingPatchThrottle(intervalMs = 120)
        var dispatches = 0
        var trailingAt: Long? = null
        // 模拟 10 个 token，每 10ms 一个
        for (t in 0L..90L step 10L) {
            trailingAt?.takeIf { t >= it }?.let {
                throttle.onTrailingDispatch(it)
                dispatches++
                trailingAt = null
            }
            when (val decision = throttle.onContent(t)) {
                0L -> dispatches++
                -1L -> Unit
                else -> trailingAt = t + decision
            }
        }
        trailingAt?.let {
            throttle.onTrailingDispatch(it)
            dispatches++
        }
        // 100ms 的 token 突发收敛为 2 次派发（首次立即 + 一次尾随）
        assertEquals(2, dispatches)
    }
}
