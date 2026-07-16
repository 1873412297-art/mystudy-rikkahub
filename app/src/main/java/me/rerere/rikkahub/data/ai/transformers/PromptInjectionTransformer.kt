package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.trace.PromptInjectionMatch
import me.rerere.rikkahub.data.ai.trace.PromptInjectionMatchType
import me.rerere.rikkahub.data.ai.trace.PromptInjectionSourceType
import me.rerere.rikkahub.data.ai.trace.PromptInjectionTrace
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import kotlin.uuid.Uuid

internal data class CollectedPromptInjection(
    val injection: PromptInjection,
    val sourceType: PromptInjectionSourceType,
    val lorebookId: Uuid? = null,
    val lorebookName: String? = null,
    val match: PromptInjectionMatch? = null,
)

internal data class AppliedPromptInjection(
    val collected: CollectedPromptInjection,
    val targetMessageId: Uuid?,
    val targetMessageIndex: Int?,
)

internal data class PromptInjectionTransformResult(
    val messages: List<UIMessage>,
    val applied: List<AppliedPromptInjection>,
)

private data class OrderedPromptInjection(
    val order: Int,
    val collected: CollectedPromptInjection,
)

private data class PromptInjectionApplicationResult(
    val messages: List<UIMessage>,
    val targetMessageIds: Map<Int, Uuid>,
)

/**
 * 提示词注入转换器
 *
 * 根据 Assistant 关联的 ModeInjection 和 Lorebook 进行提示词注入
 */
object PromptInjectionTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val result = transformMessagesWithTrace(
            messages = messages,
            assistant = ctx.assistant,
            modeInjections = ctx.settings.modeInjections,
            lorebooks = ctx.settings.lorebooks,
            conversationModeInjectionIds = ctx.conversationModeInjectionIds,
            conversationLorebookIds = ctx.conversationLorebookIds,
        )
        ctx.promptTraceSession?.recordInjectionHits(result.applied.map { it.toTrace() })
        return result.messages
    }
}

internal fun AppliedPromptInjection.toTrace(): PromptInjectionTrace {
    val item = collected
    return PromptInjectionTrace(
        injectionId = item.injection.id,
        injectionName = item.injection.name,
        sourceType = item.sourceType,
        lorebookId = item.lorebookId,
        lorebookName = item.lorebookName,
        match = item.match,
        position = item.injection.position.name,
        role = item.injection.role,
        priority = item.injection.priority,
        injectDepth = item.injection.injectDepth,
        content = item.injection.content,
        targetMessageId = targetMessageId,
        targetMessageIndex = targetMessageIndex,
    )
}

/**
 * 核心注入逻辑（可测试的纯函数）
 */
internal fun transformMessages(
    messages: List<UIMessage>,
    assistant: Assistant,
    modeInjections: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
): List<UIMessage> = transformMessagesWithTrace(
    messages = messages,
    assistant = assistant,
    modeInjections = modeInjections,
    lorebooks = lorebooks,
    conversationModeInjectionIds = conversationModeInjectionIds,
    conversationLorebookIds = conversationLorebookIds,
).messages

internal fun transformMessagesWithTrace(
    messages: List<UIMessage>,
    assistant: Assistant,
    modeInjections: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
): PromptInjectionTransformResult {
    val collected = collectInjectionMatches(
        messages = messages,
        assistant = assistant,
        modeInjections = modeInjections,
        lorebooks = lorebooks,
        conversationModeInjectionIds = conversationModeInjectionIds,
        conversationLorebookIds = conversationLorebookIds,
    )

    if (collected.isEmpty()) {
        return PromptInjectionTransformResult(messages, emptyList())
    }

    val ordered = collected
        .sortedByDescending { it.injection.priority }
        .mapIndexed { index, item -> OrderedPromptInjection(index, item) }
    val application = applyCollectedInjections(
        messages = messages,
        byPosition = ordered.groupBy { it.collected.injection.position },
    )
    val finalIndexes = application.messages
        .withIndex()
        .associate { (index, message) -> message.id to index }
    val applied = ordered.map { item ->
        val targetMessageId = application.targetMessageIds[item.order]
        AppliedPromptInjection(
            collected = item.collected,
            targetMessageId = targetMessageId,
            targetMessageIndex = targetMessageId?.let(finalIndexes::get),
        )
    }
    return PromptInjectionTransformResult(application.messages, applied)
}

/**
 * 收集需要注入的内容及其精确匹配来源。
 */
internal fun collectInjectionMatches(
    messages: List<UIMessage>,
    assistant: Assistant,
    modeInjections: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
): List<CollectedPromptInjection> {
    val effectiveModeIds = if (assistant.allowConversationPromptInjection) {
        conversationModeInjectionIds
    } else {
        assistant.modeInjectionIds
    }
    val effectiveLorebookIds = if (assistant.allowConversationPromptInjection) {
        conversationLorebookIds
    } else {
        assistant.lorebookIds
    }
    val collected = mutableListOf<CollectedPromptInjection>()

    modeInjections
        .filter { it.enabled && it.id in effectiveModeIds }
        .forEach { injection ->
            collected += CollectedPromptInjection(
                injection = injection,
                sourceType = PromptInjectionSourceType.MODE,
            )
        }

    val nonSystemMessages = messages.filter { it.role != MessageRole.SYSTEM }
    lorebooks
        .filter { it.enabled && it.id in effectiveLorebookIds }
        .forEach { lorebook ->
            lorebook.entries
                .filter { it.enabled }
                .forEach { entry ->
                    val scannedMessages = nonSystemMessages.takeLast(entry.scanDepth)
                    val scannedContext = scannedMessages.joinToString("\n") { it.toText() }
                    val matchedTerms = when {
                        entry.constantActive -> emptyList()
                        entry.useRegex -> entry.keywords.filter { keyword ->
                            try {
                                val options = if (entry.caseSensitive) {
                                    emptySet()
                                } else {
                                    setOf(RegexOption.IGNORE_CASE)
                                }
                                Regex(keyword, options).containsMatchIn(scannedContext)
                            } catch (_: Exception) {
                                false
                            }
                        }

                        else -> entry.keywords.filter { keyword ->
                            scannedContext.contains(keyword, ignoreCase = !entry.caseSensitive)
                        }
                    }
                    if (entry.constantActive || matchedTerms.isNotEmpty()) {
                        collected += CollectedPromptInjection(
                            injection = entry,
                            sourceType = PromptInjectionSourceType.LOREBOOK,
                            lorebookId = lorebook.id,
                            lorebookName = lorebook.name,
                            match = PromptInjectionMatch(
                                type = when {
                                    entry.constantActive -> PromptInjectionMatchType.CONSTANT
                                    entry.useRegex -> PromptInjectionMatchType.REGEX
                                    else -> PromptInjectionMatchType.KEYWORD
                                },
                                matchedTerms = matchedTerms,
                                scanDepth = entry.scanDepth,
                                scannedMessageIds = scannedMessages.map { it.id },
                                caseSensitive = entry.caseSensitive,
                                regexEnabled = entry.useRegex,
                            ),
                        )
                    }
                }
        }

    return collected
}

internal fun collectInjections(
    messages: List<UIMessage>,
    assistant: Assistant,
    modeInjections: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
): List<PromptInjection> = collectInjectionMatches(
    messages = messages,
    assistant = assistant,
    modeInjections = modeInjections,
    lorebooks = lorebooks,
    conversationModeInjectionIds = conversationModeInjectionIds,
    conversationLorebookIds = conversationLorebookIds,
).map { it.injection }

/**
 * 应用注入到消息列表
 */
internal fun applyInjections(
    messages: List<UIMessage>,
    byPosition: Map<InjectionPosition, List<PromptInjection>>,
): List<UIMessage> {
    var order = 0
    val orderedByPosition = byPosition.mapValues { (_, injections) ->
        injections.map { injection ->
            OrderedPromptInjection(
                order = order++,
                collected = CollectedPromptInjection(
                    injection = injection,
                    sourceType = PromptInjectionSourceType.MODE,
                ),
            )
        }
    }
    return applyCollectedInjections(
        messages = messages,
        byPosition = orderedByPosition,
    ).messages
}

private fun applyCollectedInjections(
    messages: List<UIMessage>,
    byPosition: Map<InjectionPosition, List<OrderedPromptInjection>>,
): PromptInjectionApplicationResult {
    val result = messages.toMutableList()
    val targetMessageIds = mutableMapOf<Int, Uuid>()

    // 找到系统消息的索引（通常是第一条）
    val systemIndex = result.indexOfFirst { it.role == MessageRole.SYSTEM }

    // 处理 BEFORE_SYSTEM_PROMPT 和 AFTER_SYSTEM_PROMPT
    if (systemIndex >= 0) {
        val beforeItems = byPosition[InjectionPosition.BEFORE_SYSTEM_PROMPT].orEmpty()
        val afterItems = byPosition[InjectionPosition.AFTER_SYSTEM_PROMPT].orEmpty()
        val beforeContent = beforeItems.joinToString("\n") { it.collected.injection.content }
        val afterContent = afterItems.joinToString("\n") { it.collected.injection.content }

        if (beforeContent.isNotEmpty() || afterContent.isNotEmpty()) {
            val systemMessage = result[systemIndex]
            val originalText = systemMessage.parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("") { it.text }

            val newText = buildString {
                if (beforeContent.isNotEmpty()) {
                    append(beforeContent)
                    appendLine()
                }
                append(originalText)
                if (afterContent.isNotEmpty()) {
                    appendLine()
                    append(afterContent)
                }
            }

            result[systemIndex] = systemMessage.copy(
                parts = listOf(UIMessagePart.Text(newText))
            )
            (beforeItems + afterItems).forEach { item ->
                targetMessageIds[item.order] = result[systemIndex].id
            }
        }
    } else {
        // 没有系统消息时，创建一个新的系统消息
        val beforeItems = byPosition[InjectionPosition.BEFORE_SYSTEM_PROMPT].orEmpty()
        val afterItems = byPosition[InjectionPosition.AFTER_SYSTEM_PROMPT].orEmpty()
        val beforeContent = beforeItems.joinToString("\n") { it.collected.injection.content }
        val afterContent = afterItems.joinToString("\n") { it.collected.injection.content }

        val combinedContent = buildString {
            if (beforeContent.isNotEmpty()) {
                append(beforeContent)
            }
            if (afterContent.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                append(afterContent)
            }
        }

        if (combinedContent.isNotEmpty()) {
            val message = UIMessage.system(combinedContent)
            result.add(0, message)
            (beforeItems + afterItems).forEach { item ->
                targetMessageIds[item.order] = message.id
            }
        }
    }

    // 处理 TOP_OF_CHAT：在第一条用户消息之前插入
    val topInjections = byPosition[InjectionPosition.TOP_OF_CHAT]
    if (!topInjections.isNullOrEmpty()) {
        // 重新计算索引（因为可能插入了系统消息）
        var insertIndex = result.indexOfFirst { it.role == MessageRole.USER }
            .takeIf { it >= 0 } ?: result.size
        insertIndex = findSafeInsertIndex(result, insertIndex)
        createMergedInjectionMessagesWithTargets(topInjections).forEach { merged ->
            result.add(insertIndex, merged.message)
            merged.items.forEach { item -> targetMessageIds[item.order] = merged.message.id }
            insertIndex++
        }
    }

    // 处理 BOTTOM_OF_CHAT：在最后一条消息之前插入
    val bottomInjections = byPosition[InjectionPosition.BOTTOM_OF_CHAT]
    if (!bottomInjections.isNullOrEmpty()) {
        var insertIndex = (result.size - 1).coerceAtLeast(0)
        insertIndex = findSafeInsertIndex(result, insertIndex)
        createMergedInjectionMessagesWithTargets(bottomInjections).forEach { merged ->
            result.add(insertIndex, merged.message)
            merged.items.forEach { item -> targetMessageIds[item.order] = merged.message.id }
            insertIndex++
        }
    }

    // 处理 AT_DEPTH：在指定深度位置插入（从最新消息往前数）
    // 按 injectDepth 分组，相同深度的合并，按深度从大到小处理（避免索引变化问题）
    val atDepthInjections = byPosition[InjectionPosition.AT_DEPTH]
    if (!atDepthInjections.isNullOrEmpty()) {
        val byDepth = atDepthInjections.groupBy { it.collected.injection.injectDepth }
        byDepth.keys.sortedDescending().forEach { depth ->
            val injections = byDepth[depth] ?: return@forEach
            // 计算插入位置：result.size - depth，但要确保在有效范围内
            // depth=1 表示在最后一条消息之前，depth=2 表示在倒数第二条之前...
            var insertIndex = (result.size - depth.coerceAtLeast(1)).coerceIn(0, result.size)
            insertIndex = findSafeInsertIndex(result, insertIndex)
            createMergedInjectionMessagesWithTargets(injections).forEach { merged ->
                result.add(insertIndex, merged.message)
                merged.items.forEach { item -> targetMessageIds[item.order] = merged.message.id }
                insertIndex++
            }
        }
    }

    return PromptInjectionApplicationResult(result, targetMessageIds)
}

/**
 * 将同一 role 的注入合并成消息列表
 * 按 role 分组后合并内容，返回合并后的消息列表
 */
private data class MergedPromptInjectionMessage(
    val message: UIMessage,
    val items: List<OrderedPromptInjection>,
)

private fun createMergedInjectionMessagesWithTargets(
    injections: List<OrderedPromptInjection>,
): List<MergedPromptInjectionMessage> {
    return injections
        .groupBy { it.collected.injection.role }
        .map { (role, grouped) ->
            val mergedContent = grouped.joinToString("\n") { it.collected.injection.content }
            val message = when (role) {
                MessageRole.ASSISTANT -> UIMessage.assistant(mergedContent)
                else -> UIMessage.user(mergedContent)
            }
            MergedPromptInjectionMessage(message, grouped)
        }
}

/**
 * 查找安全的插入位置，避免注入到 USER → ASSISTANT(含Tool) 之间
 *
 * 某些提供商（如 deepseek）要求 USER 之后紧跟带工具的 ASSISTANT，
 * 在两者之间插入消息会导致报错或破坏推理连续性。
 */
internal fun findSafeInsertIndex(messages: List<UIMessage>, targetIndex: Int): Int {
    var index = targetIndex.coerceIn(0, messages.size)

    // 向前查找，直到找到一个安全的位置
    while (index > 0) {
        val prevMessage = messages.getOrNull(index - 1)
        val currentMessage = messages.getOrNull(index)

        // 不能插入到 USER → ASSISTANT(含Tool) 之间
        val isPrevUser = prevMessage?.role == MessageRole.USER
        val isCurrentAssistantWithTools = currentMessage?.role == MessageRole.ASSISTANT
            && currentMessage.getTools().isNotEmpty()

        if (isPrevUser && isCurrentAssistantWithTools) {
            index--
        } else {
            break
        }
    }

    return index
}
