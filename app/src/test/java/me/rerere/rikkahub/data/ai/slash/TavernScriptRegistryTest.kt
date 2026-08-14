package me.rerere.rikkahub.data.ai.slash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主侧酒馆脚本注册表测试。
 *
 * 环境降级说明：QuickJS 原生库仅随 wrapper-android AAR 提供（Android .so），JVM 单测
 * 无法加载（QuickJSContext.create() 抛 QuickJSException）——已在探针用例中实证。
 * 因此执行路径用例（expandMacros 展开 / executeSlashCommand 执行）降级为断言
 * 「无可用引擎时返回原文 / error 兜底」；注册/配额/列表/重名覆盖等非执行路径用例
 * 保留全量断言。真实展开路径待模拟器冒烟验证（Task 8）。
 */
class TavernScriptRegistryTest {

    private fun registry() = TavernScriptRegistry()

    @Test
    fun `registers and lists macros`() {
        val registry = registry()
        assertTrue(registry.registerMacro("greet", "function macro(args){ return 'Hello ' + args; }"))
        assertEquals(listOf("greet"), registry.listMacros())
    }

    @Test
    fun `reregistering replaces existing macro entry`() {
        val registry = registry()
        assertTrue(registry.registerMacro("m", "function macro(args){ return 'a'; }"))
        assertTrue(registry.registerMacro("m", "function macro(args){ return 'b'; }"))
        assertEquals(listOf("m"), registry.listMacros())
    }

    @Test
    fun `macro expansion returns original text when no JS engine is available`() {
        // 降级断言：注册成功，但无引擎时展开安全兜底为原文（不抛异常）
        val registry = registry()
        registry.registerMacro("greet", "function macro(args){ return 'Hello ' + args; }")
        assertEquals(
            "{{greet::world}} and {{greet::there}}",
            registry.expandMacros(
                "{{greet::world}} and {{greet::there}}",
                MacroExpandContext(userName = "U", charName = "C"),
            ),
        )
    }

    @Test
    fun `unregistered macro syntax stays untouched`() {
        val registry = registry()
        assertEquals("{{nope::x}}", registry.expandMacros("{{nope::x}}", MacroExpandContext("U", "C")))
    }

    @Test
    fun `expansion is a no-op when nothing is registered`() {
        val registry = registry()
        assertEquals("plain text", registry.expandMacros("plain text", MacroExpandContext()))
    }

    @Test
    fun `removeMacro forgets the registration`() {
        val registry = registry()
        registry.registerMacro("m", "function macro(args){ return 'a'; }")
        registry.removeMacro("m")
        assertTrue(registry.listMacros().isEmpty())
        assertEquals("{{m::}}", registry.expandMacros("{{m::}}", MacroExpandContext("U", "C")))
    }

    @Test
    fun `registers slash command with aliases and help`() {
        val registry = registry()
        assertTrue(
            registry.registerSlashCommand(
                "flip",
                "function callback(args){ return 'flipped'; }",
                listOf("f"),
                "flip text",
            ),
        )
        val info = registry.listSlashCommands().single()
        assertEquals("flip", info.name)
        assertEquals(listOf("f"), info.aliases)
        assertEquals("flip text", info.helpString)
    }

    @Test
    fun `slash command execution returns error fallback when no JS engine is available`() {
        // 降级断言：注册成功，但无引擎时执行返回 error 兜底、未注册返回 null
        val registry = registry()
        registry.registerSlashCommand("flip", "function callback(args){ return 'flipped'; }", emptyList(), "flip text")
        val result = registry.executeSlashCommand("flip", "x", MacroExpandContext("U", "C"))
        assertEquals("callback evaluation failed", result?.error)
        assertNull(registry.executeSlashCommand("unknown", "x", MacroExpandContext("U", "C")))
    }

    @Test
    fun `rejects oversized macro source`() {
        val registry = registry()
        val big = "function macro(args){ return '" + "x".repeat(70 * 1024) + "'; }"
        assertFalse(registry.registerMacro("big", big))
    }

    @Test
    fun `rejects oversized slash command source`() {
        val registry = registry()
        val big = "function callback(args){ return '" + "x".repeat(70 * 1024) + "'; }"
        assertFalse(registry.registerSlashCommand("big", big, emptyList(), "big"))
    }

    @Test
    fun `enforces registration count limit`() {
        val registry = registry()
        repeat(64) { index -> registry.registerMacro("m$index", "function macro(args){ return ''; }") }
        assertFalse(registry.registerMacro("overflow", "function macro(args){ return ''; }"))
    }

    @Test
    fun `reregistering existing name is allowed at the limit`() {
        val registry = registry()
        repeat(64) { index -> registry.registerMacro("m$index", "function macro(args){ return ''; }") }
        assertTrue(registry.registerMacro("m0", "function macro(args){ return 'x'; }"))
        assertEquals(64, registry.listMacros().size)
    }
}
