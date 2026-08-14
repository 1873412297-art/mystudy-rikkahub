package me.rerere.rikkahub.data.ai.slash

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 宏展开权限门控测试。
 *
 * 说明：ChatService.preprocessUserInputParts 依赖完整服务基建（Application/AppScope 等），
 * JVM 单测难以构造；门控逻辑抽取为纯函数 [expandMacrosIfAllowed]，此处注入假
 * [MacroExpander] 观察调用次数，覆盖「allowScripts 关闭时不调用展开器」的核心契约。
 */
class MacroExpansionGateTest {

    @Test
    fun `expansion is skipped when allowScripts is disabled`() {
        var calls = 0
        val expander = MacroExpander { text, _ ->
            calls++
            "expanded: $text"
        }

        val result = expandMacrosIfAllowed(
            expander = expander,
            text = "{{hp::1}} and more",
            context = MacroExpandContext(),
            allowScripts = false,
        )

        assertEquals("{{hp::1}} and more", result)
        assertEquals(0, calls)
    }

    @Test
    fun `expansion runs when allowScripts is enabled`() {
        val expander = MacroExpander { text, _ -> "expanded: $text" }

        val result = expandMacrosIfAllowed(
            expander = expander,
            text = "hello",
            context = MacroExpandContext(),
            allowScripts = true,
        )

        assertEquals("expanded: hello", result)
    }
}
