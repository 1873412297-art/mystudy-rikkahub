package me.rerere.rikkahub.data.ai.slash

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.status.StatusVariableStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 斜杠命令分发路由测试（spec §9 缺失项）。
 *
 * 环境降级说明：磁盘脚本路径依赖 QuickJS 原生库（仅 Android .so），JVM 单测不可用；
 * 因此经 [SlashScriptSource] 假实现只测分发路由逻辑，不测 QuickJS 执行。
 * WebView 注册路径经真实 [TavernScriptRegistry]（JVM 无引擎降级为 error 兜底），
 * 断言第三档「路由正确性」——error 结果走既有脚本错误路径即可。
 */
class SlashCommandInterceptorTest {

    private class FakeScriptSource(
        private val scripts: List<SlashScript> = emptyList(),
        private val commands: List<SlashCommand> = emptyList(),
    ) : SlashScriptSource {
        val executedArgs = mutableListOf<String>()
        private val variables = mutableMapOf<String, String>()

        override fun listScripts(): List<SlashScript> = scripts

        override fun extractCommands(source: String): List<SlashCommand> = commands

        override suspend fun execute(source: String, args: String, context: SlashContext): Result<Map<String, String>> {
            executedArgs += args
            return Result.success(mapOf("result" to "disk: $args"))
        }

        override fun variableAccessor(scriptName: String): ScriptVariableAccessor =
            object : ScriptVariableAccessor {
                override fun get(key: String) = variables[key]
                override fun set(key: String, value: String) {
                    variables[key] = value
                }

                override fun delete(key: String) {
                    variables.remove(key)
                }

                override fun all(): Map<String, String> = variables.toMap()
            }
    }

    private fun userMessage(text: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun textOf(message: UIMessage): String =
        message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    private fun interceptor(
        source: SlashScriptSource = FakeScriptSource(),
        registry: TavernScriptRegistry = TavernScriptRegistry(),
    ) = SlashCommandInterceptor(source, StatusVariableStore(), registry)

    @Test
    fun `builtin command wins over disk script registering the same command`() = runBlocking {
        val source = FakeScriptSource(
            scripts = listOf(SlashScript(name = "disk", source = "src")),
            commands = listOf(SlashCommand(command = "echo", scriptName = "disk")),
        )
        val interceptor = interceptor(source = source)
        val messages = listOf(userMessage("/echo hello"))

        val result = interceptor.dispatch(
            messages = messages,
            command = "echo",
            rawArgs = "hello",
            args = "echo hello",
            charName = "Char",
            userName = "User",
            conversationId = null,
        )

        assertEquals(1, result.size)
        assertEquals(MessageRole.USER, result[0].role)
        assertEquals("hello", textOf(result[0]))
        assertTrue("disk script must not be executed when builtin wins", source.executedArgs.isEmpty())
    }

    @Test
    fun `unhandled slash command passes through to AI unchanged`() = runBlocking {
        val interceptor = interceptor()
        val messages = listOf(userMessage("/nobody here"))

        val result = interceptor.dispatch(
            messages = messages,
            command = "nobody",
            rawArgs = "here",
            args = "nobody here",
            charName = "Char",
            userName = "User",
            conversationId = null,
        )

        assertSame(messages, result)
    }

    @Test
    fun `registry registered command is dispatched when no disk script matches`() = runBlocking {
        val registry = TavernScriptRegistry()
        registry.registerSlashCommand("flip", "function callback(args){ return 'flipped'; }", emptyList(), "flip")
        val interceptor = interceptor(registry = registry)
        val messages = listOf(userMessage("/flip x"))

        val result = interceptor.dispatch(
            messages = messages,
            command = "flip",
            rawArgs = "x",
            args = "flip x",
            charName = "Char",
            userName = "User",
            conversationId = null,
        )

        // JVM 无 QuickJS 引擎：executeSlashCommand 返回 error 兜底 → 走既有脚本错误路径
        assertEquals(1, result.size)
        assertEquals("[Error: /flip] callback evaluation failed", textOf(result[0]))
    }

    @Test
    fun `disk script result is routed through slash output builder`() = runBlocking {
        val source = FakeScriptSource(
            scripts = listOf(SlashScript(name = "disk", source = "src")),
            commands = listOf(SlashCommand(command = "dice", scriptName = "disk")),
        )
        val interceptor = interceptor(source = source)
        val messages = listOf(userMessage("/dice 1d6"))

        val result = interceptor.dispatch(
            messages = messages,
            command = "dice",
            rawArgs = "1d6",
            args = "dice 1d6",
            charName = "Char",
            userName = "User",
            conversationId = null,
        )

        assertEquals(1, result.size)
        assertEquals("disk: dice 1d6", textOf(result[0]))
        assertEquals(listOf("dice 1d6"), source.executedArgs)
    }
}
