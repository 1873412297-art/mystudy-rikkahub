package me.rerere.rikkahub.data.ai.slash

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.status.StatusVariableStore
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import kotlin.uuid.Uuid

/**
 * 磁盘脚本源抽象（JS-Slash-Runner 磁盘脚本的查找与执行）。
 * [ScriptManager] 是唯一生产实现；JVM 测试提供假实现以只测分发逻辑。
 */
interface SlashScriptSource {
    fun listScripts(): List<SlashScript>
    fun extractCommands(source: String): List<SlashCommand>
    suspend fun execute(source: String, args: String, context: SlashContext): Result<Map<String, String>>
    fun variableAccessor(scriptName: String): ScriptVariableAccessor
}

/**
 * Input message transformer that detects slash commands (/command args)
 * and dispatches them with three-tier priority:
 * host built-in commands → disk JS-Slash-Runner scripts → WebView-registered
 * commands ([TavernScriptRegistry]). Unhandled commands pass through to the AI.
 */
class SlashCommandInterceptor(
    private val scriptSource: SlashScriptSource,
    private val statusVariableStore: StatusVariableStore,
    private val tavernScriptRegistry: TavernScriptRegistry,
) : InputMessageTransformer {

    companion object {
        private val SLASH_REGEX = Regex("""^/(\w+)\s*(.*)$""")
    }

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val last = messages.lastOrNull() ?: return messages
        if (last.role != MessageRole.USER) return messages

        val text = last.parts.filterIsInstance<UIMessagePart.Text>()
            .joinToString("") { it.text }.trim()
        val match = SLASH_REGEX.find(text) ?: return messages

        val command = match.groupValues[1]
        val rawArgs = match.groupValues[2].trim()
        // Always include command name as first arg, matching JS-Slash-Runner convention
        val args = if (rawArgs.isEmpty()) command else "$command $rawArgs"

        return dispatch(
            messages = messages,
            command = command,
            rawArgs = rawArgs,
            args = args,
            charName = ctx.assistant.name,
            userName = ctx.settings.displaySetting.userNickname.ifBlank { "User" },
            conversationId = ctx.conversationId,
        )
    }

    internal suspend fun dispatch(
        messages: List<UIMessage>,
        command: String,
        rawArgs: String,
        args: String,
        charName: String,
        userName: String,
        conversationId: Uuid?,
    ): List<UIMessage> {
        // 宿主内建命令优先（变量后端为 chat 作用域 StatusVariableStore）
        val hostResult = HostSlashCommands.execute(command, rawArgs, conversationId, statusVariableStore)
        if (hostResult != null) {
            val hostError = hostResult.error
            if (!hostError.isNullOrBlank()) {
                return messages.dropLast(1) + userMsg("[Error: /$command] $hostError")
            }
            return buildSlashOutput(messages, hostResult.text ?: "", hostResult.html)
        }

        val scripts = scriptSource.listScripts()
        val script = scripts.firstOrNull { s ->
            scriptSource.extractCommands(s.source).any { it.command == command }
        }

        if (script != null) {
            val slashCtx = SlashContext(
                charName = charName,
                userName = userName,
                conversationId = conversationId?.toString(),
                chatMessageCount = messages.size,
                recentMessages = messages.takeLast(8),
                variables = scriptSource.variableAccessor(script.name),
            )

            val result = scriptSource.execute(script.source, args, slashCtx)
            return result.fold(
                onSuccess = { out ->
                    val text = out["result"] ?: ""
                    val html = out["html"]
                    val err = out["error"]
                    if (!err.isNullOrBlank()) {
                        messages.dropLast(1) + userMsg("[Error: /$command] $err")
                    } else if (!text.isBlank() || !html.isNullOrBlank()) {
                        buildSlashOutput(messages, text, html)
                    } else messages
                },
                onFailure = { e ->
                    messages.dropLast(1) + userMsg("[/$command failed: ${e.message}]")
                },
            )
        }

        // WebView 注册命令（SlashCommandParser.add）第三档；未注册返回 null
        val registered = tavernScriptRegistry.executeSlashCommand(
            command,
            rawArgs,
            MacroExpandContext(
                userName = userName,
                charName = charName,
                conversationId = conversationId?.toString(),
            ),
        )
        if (registered != null) {
            val err = registered.error
            if (!err.isNullOrBlank()) {
                return messages.dropLast(1) + userMsg("[Error: /$command] $err")
            }
            return buildSlashOutput(messages, registered.text ?: "", registered.html)
        }

        return messages // pass through to AI
    }

    /**
     * Build output messages from slash command result.
     * - Plain text → sent as USER message to AI
     * - HTML → rendered inline via StatusPlaceholder in an ASSISTANT message
     * - Both → text sent to AI, HTML rendered separately
     */
    private fun buildSlashOutput(
        messages: List<UIMessage>,
        text: String,
        html: String?,
    ): List<UIMessage> {
        val result = mutableListOf<UIMessage>()
        // Keep previous messages, replacing the slash command
        if (text.isNotBlank()) {
            result.addAll(messages.dropLast(1))
            result.add(userMsg(text))
        }
        // Render HTML inline if present
        if (!html.isNullOrBlank()) {
            result.add(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.StatusPlaceholder(htmlContent = html)),
                )
            )
        }
        return result.ifEmpty { messages }
    }

    private fun userMsg(text: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )

}
