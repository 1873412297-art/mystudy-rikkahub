package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * 清理「裸状态尾块」的输出 transformer。
 *
 * 部分角色卡自带状态追踪提示词，会让模型在回复末尾输出无标签的元信息块，例如：
 * `- Time: ... - Dramatic Updates: ... - Variable Analysis: ... []`
 * 现有 [StatusPlaceholderTransformer] 只识别 `<UpdateVariable>` 等带标签格式，
 * 匹配不到这种裸格式，导致元信息直接污染气泡显示。
 *
 * 清理规则刻意保守，防止误伤正文（详见 [stripTrailingStatusBlock]）：
 * - 状态块必须出现在文本末尾；
 * - 至少命中两个不同的已知 key（Time / Dramatic Updates / Variable Analysis）；
 * - 条目之间、最后一个 value 内都不允许出现换行段落；
 * - 末尾残留的裸 JSON 数组片段（`[]`、未闭合的 `[` 等）随块一起清除；
 * - 流式期间只在块「完整」时剥离，不完整则保留原文等完整后再剥，避免闪烁误删。
 *
 * 独立于 StatusPlaceholderTransformer 的原因：那边职责是 ST 标签渲染与变量读写
 * （依赖 Koin / StatusVariableStore），这里是纯文本清理、无任何依赖，便于单测与日后加开关。
 */
object StatusTrailingBlockTransformer : OutputMessageTransformer {

    /**
     * 是否启用。本期默认开启、规则保守；
     * 日后做助手级 / 全局开关时，在这里读取 ctx.assistant 或 ctx.settings 的配置即可。
     */
    @Suppress("UNUSED_PARAMETER")
    private fun isEnabled(ctx: TransformerContext): Boolean = true

    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = clean(ctx, messages, streaming = true)

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = clean(ctx, messages, streaming = false)

    private fun clean(
        ctx: TransformerContext,
        messages: List<UIMessage>,
        streaming: Boolean,
    ): List<UIMessage> {
        if (!isEnabled(ctx)) return messages
        return messages.map { message ->
            if (message.role != MessageRole.ASSISTANT) return@map message
            message.copy(
                parts = message.parts.map { part ->
                    if (part is UIMessagePart.Text) {
                        val stripped = stripTrailingStatusBlock(part.text, streaming)
                        if (stripped != part.text) part.copy(text = stripped) else part
                    } else {
                        part
                    }
                }
            )
        }
    }
}

// region 裸状态块识别（纯函数，便于 JVM 单测）

/** 已知状态 key；含空格的 key 用 \s+ 允许任意空白。 */
private const val STATUS_KEY_ALTERNATIVES = """Dramatic\s+Updates|Variable\s+Analysis|Time"""

/**
 * 条目起始：行首（可带 `- ` 或 `* ` 项目符号），或行内的 ` - ` / ` * ` 项目符号。
 * 行内无项目符号的 `Key:` 不匹配（大概率是正文），这是保守性的一部分。
 */
private val STATUS_ENTRY_REGEX = Regex(
    """^[ \t]*[-*]?[ \t]*(?:$STATUS_KEY_ALTERNATIVES)[ \t]*:|[ \t]+[-*][ \t]+(?:$STATUS_KEY_ALTERNATIVES)[ \t]*:""",
    setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)
)

private val STATUS_KEY_EXTRACT_REGEX = Regex(STATUS_KEY_ALTERNATIVES, RegexOption.IGNORE_CASE)

/** 末尾已闭合的裸 JSON 数组片段（单行），如 ` []`、` [{"op":...}]`。 */
private val CLOSED_JSON_FRAGMENT_REGEX = Regex("""[ \t]*\[[^\[\]\r\n]*\][ \t]*$""")

/** 末尾未闭合的裸 JSON 数组片段（单行），如 ` [`、` [{`。 */
private val OPEN_JSON_FRAGMENT_REGEX = Regex("""[ \t]*\[[^\[\]\r\n]*$""")

private val BLANK_LINE_REGEX = Regex("""\r?\n[ \t]*\r?\n""")

private val WHITESPACE_RUN_REGEX = Regex("""\s+""")

private val SENTENCE_TERMINATORS =
    charArrayOf('.', '。', '!', '！', '?', '？', '…', '"', '”', '\'', '’', ')', '）')

/**
 * 剥离文本末尾的裸状态块，返回清理后的文本；不满足剥离条件时原样返回。
 *
 * @param streaming true 表示流式显示（visualTransform）：块「完整」才剥离——
 *   以已闭合 JSON 片段结尾，或最后一个 value 以句末标点结束且没有未闭合的 `[`；
 *   false 表示生成结束（onGenerationFinish）：结构命中即剥离，
 *   末尾残留的未闭合 JSON 片段随块一起清除。
 */
internal fun stripTrailingStatusBlock(text: String, streaming: Boolean): String {
    if (text.isBlank()) return text
    val matches = STATUS_ENTRY_REGEX.findAll(text).toList()
    if (matches.size < 2) return text

    // 末尾连续条目串：从最后一个条目向前扩展，条目之间出现空行则视为正文边界
    var runStart = matches.size - 1
    while (runStart > 0) {
        val gap = text.substring(matches[runStart - 1].range.last + 1, matches[runStart].range.first)
        if (BLANK_LINE_REGEX.containsMatchIn(gap)) break
        runStart--
    }
    val run = matches.subList(runStart, matches.size)

    // 至少命中两个不同的已知 key，防止误伤正文
    val distinctKeys = run.mapNotNull { match ->
        STATUS_KEY_EXTRACT_REGEX.find(match.value)
            ?.value
            ?.lowercase()
            ?.replace(WHITESPACE_RUN_REGEX, " ")
    }.toSet()
    if (distinctKeys.size < 2) return text

    // 最后一个条目的 value（含可能残留的 JSON 片段）必须是单行，否则视为正文延续
    val tail = text.substring(run.last().range.last + 1)
    val closedFrag = CLOSED_JSON_FRAGMENT_REGEX.find(tail)
    val openFrag = if (closedFrag == null) OPEN_JSON_FRAGMENT_REGEX.find(tail) else null
    val valueEnd = (closedFrag ?: openFrag)?.range?.first ?: tail.length
    val lastValue = tail.substring(0, valueEnd)
    if (lastValue.contains('\n') || lastValue.contains('\r')) return text

    if (streaming) {
        val lastChar = lastValue.trimEnd().lastOrNull()
        val terminated = lastChar != null && lastChar in SENTENCE_TERMINATORS
        val complete = closedFrag != null || (openFrag == null && terminated)
        if (!complete) return text
    }

    val kept = text.substring(0, run.first().range.first).trimEnd()
    // 保守：整条消息都是状态块时不剥，避免产生空气泡
    return kept.ifBlank { text }
}

// endregion
