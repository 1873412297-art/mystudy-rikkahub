package me.rerere.rikkahub.data.ai.slash

import android.util.Log
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.TransformerContext

/**
 * Input message transformer that detects slash commands (/command args)
 * and dispatches them to registered JS-Slash-Runner compatible scripts.
 */
class SlashCommandInterceptor(
    private val scriptManager: ScriptManager,
) : InputMessageTransformer {

    companion object {
        private const val TAG = "SlashCmd"
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
        Log.i(TAG, "Detected /$command args='$args'")

        val scripts = scriptManager.listScripts()
        val script = scripts.firstOrNull { s ->
            scriptManager.engine.extractCommands(s.source).any { it.command == command }
        }

        if (script == null) {
            Log.w(TAG, "No handler for /$command")
            return messages // pass through to AI
        }

        val slashCtx = SlashContext(
            charName = ctx.assistant.name,
            userName = ctx.settings.displaySetting.userNickname.ifBlank { "User" },
            conversationId = ctx.conversationId?.toString(),
            chatMessageCount = messages.size,
            recentMessages = messages.takeLast(8),
            variables = ScriptVariableStoreAccessor(script.name, scriptManager.variableStore),
        )

        val result = scriptManager.engine.execute(script.source, args, slashCtx)
        return result.fold(
            onSuccess = { out ->
                val text = out["result"] ?: ""
                val html = out["html"]
                val err = out["error"]
                if (!err.isNullOrBlank()) {
                    Log.e(TAG, "Script error: $err")
                    messages.dropLast(1) + userMsg("[Error: /$command] $err")
                } else if (!text.isBlank() || !html.isNullOrBlank()) {
                    buildSlashOutput(messages, text, html)
                } else messages
            },
            onFailure = { e ->
                Log.e(TAG, "Failed: $command", e)
                messages.dropLast(1) + userMsg("[/$command failed: ${e.message}]")
            },
        )
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
