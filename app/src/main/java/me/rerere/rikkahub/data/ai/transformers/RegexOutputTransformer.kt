package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import org.koin.core.component.KoinComponent

object RegexOutputTransformer : OutputMessageTransformer, KoinComponent {
    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val assistant = ctx.assistant
        if (assistant.regexes.isEmpty()) return messages // No regexes, return original messages
        return applyVisualRegexes(messages, assistant)
    }
}

/**
 * 对 ASSISTANT 消息应用助手的输出正则（非 visualOnly 规则）。
 *
 * 按消息倒序索引计算深度（最新消息 depth = 0），带 minDepth/maxDepth 的规则
 * 仅在深度命中时应用；无深度限制的规则行为与旧版一致。
 *
 * 流式输出时每个 chunk 都会经过这里：未发生变化的 part 与 message 直接复用原实例，
 * 避免对整段历史消息反复分配新对象。
 */
internal fun applyVisualRegexes(
    messages: List<UIMessage>,
    assistant: Assistant,
): List<UIMessage> {
    if (assistant.regexes.isEmpty()) return messages
    return messages.mapIndexed { index, message ->
        if (message.role != MessageRole.ASSISTANT) return@mapIndexed message // Skip non-assistant messages
        val depth = messages.size - 1 - index
        var changed = false
        val newParts = message.parts.map { part ->
            val newPart = part.replaceOutputRegexes(assistant, depth)
            if (newPart !== part) changed = true
            newPart
        }
        if (changed) message.copy(parts = newParts) else message
    }
}

private fun UIMessagePart.replaceOutputRegexes(
    assistant: Assistant,
    depth: Int,
): UIMessagePart = when (this) {
    is UIMessagePart.Text -> {
        val newText = text.replaceRegexes(assistant, AssistantAffectScope.ASSISTANT, visual = false, depth = depth)
        if (newText === text) this else copy(text = newText)
    }

    is UIMessagePart.Reasoning -> {
        val newReasoning = reasoning.replaceRegexes(
            assistant, AssistantAffectScope.ASSISTANT, visual = false, depth = depth
        )
        if (newReasoning === reasoning) this else copy(reasoning = newReasoning)
    }

    else -> this
}
