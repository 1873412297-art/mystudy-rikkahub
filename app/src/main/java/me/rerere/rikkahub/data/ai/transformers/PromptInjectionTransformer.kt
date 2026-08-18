package me.rerere.rikkahub.data.ai.transformers

import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.trace.PromptInjectionMatch
import me.rerere.rikkahub.data.ai.trace.PromptInjectionMatchType
import me.rerere.rikkahub.data.ai.trace.PromptInjectionSourceType
import me.rerere.rikkahub.data.ai.trace.PromptInjectionTrace
import me.rerere.rikkahub.data.ai.trace.PromptTraceRecorder
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AuthorNote
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.utils.SimpleCache
import java.util.concurrent.TimeUnit
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
    val targetMessages: Map<Int, UIMessage>,
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
            conversationAuthorNote = ctx.conversationAuthorNote,
            promptTraceRecorder = ctx.promptTraceSession,
        )
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
    conversationAuthorNote: AuthorNote? = null,
    random: kotlin.random.Random = kotlin.random.Random.Default,
): List<UIMessage> = transformMessagesWithTrace(
    messages = messages,
    assistant = assistant,
    modeInjections = modeInjections,
    lorebooks = lorebooks,
    conversationModeInjectionIds = conversationModeInjectionIds,
    conversationLorebookIds = conversationLorebookIds,
    conversationAuthorNote = conversationAuthorNote,
    random = random,
).messages

internal fun transformMessagesWithTrace(
    messages: List<UIMessage>,
    assistant: Assistant,
    modeInjections: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
    conversationAuthorNote: AuthorNote? = null,
    promptTraceRecorder: PromptTraceRecorder? = null,
    random: kotlin.random.Random = kotlin.random.Random.Default,
): PromptInjectionTransformResult {
    val collected = collectInjectionMatches(
        messages = messages,
        assistant = assistant,
        modeInjections = modeInjections,
        lorebooks = lorebooks,
        conversationModeInjectionIds = conversationModeInjectionIds,
        conversationLorebookIds = conversationLorebookIds,
        conversationAuthorNote = conversationAuthorNote,
        random = random,
    )

    val result = if (collected.isEmpty()) {
        PromptInjectionTransformResult(messages, emptyList())
    } else {
        val ordered = collected
            .sortedByDescending { it.injection.priority }
            .mapIndexed { index, item -> OrderedPromptInjection(index, item) }
        val application = applyCollectedInjections(
            messages = messages,
            byPosition = ordered.groupBy { it.collected.injection.position },
        )
        val applied = ordered.mapNotNull { item ->
            val targetMessage = application.targetMessages[item.order] ?: return@mapNotNull null
            AppliedPromptInjection(
                collected = item.collected,
                targetMessageId = targetMessage.id,
                targetMessageIndex = application.messages.indexOfFirst { it === targetMessage },
            )
        }
        PromptInjectionTransformResult(application.messages, applied)
    }
    try {
        promptTraceRecorder?.recordInjectionHits(result.applied.map { it.toTrace() })
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Prompt tracing is best-effort and must not affect generation.
    }
    return result
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
    conversationAuthorNote: AuthorNote? = null,
    random: kotlin.random.Random = kotlin.random.Random.Default,
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
            collected += collectLorebookInjectionMatches(
                lorebook = lorebook,
                nonSystemMessages = nonSystemMessages,
                random = random,
            )
        }

    collectAuthorNoteInjection(
        assistant = assistant,
        conversationAuthorNote = conversationAuthorNote,
        messages = messages,
    )?.let { collected += it }

    return collected
}

internal fun collectInjections(
    messages: List<UIMessage>,
    assistant: Assistant,
    modeInjections: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
    conversationAuthorNote: AuthorNote? = null,
    random: kotlin.random.Random = kotlin.random.Random.Default,
): List<PromptInjection> = collectInjectionMatches(
    messages = messages,
    assistant = assistant,
    modeInjections = modeInjections,
    lorebooks = lorebooks,
    conversationModeInjectionIds = conversationModeInjectionIds,
    conversationLorebookIds = conversationLorebookIds,
    conversationAuthorNote = conversationAuthorNote,
    random = random,
).map { it.injection }

/**
 * 作者注释：合成为一条 ModeInjection 进入统一管线，
 * 自动获得安全插入、同位置同 role 合并、优先级排序与 PromptTrace 记录。
 * [AuthorNote.position] 决定注入位置：AT_DEPTH（默认，深度由 [AuthorNote.depth] 决定）/
 * TOP_OF_CHAT / BOTTOM_OF_CHAT。
 */
private fun collectAuthorNoteInjection(
    assistant: Assistant,
    conversationAuthorNote: AuthorNote?,
    messages: List<UIMessage>,
): CollectedPromptInjection? {
    val note = resolveEffectiveAuthorNote(
        assistant = assistant,
        conversationAuthorNote = conversationAuthorNote,
    ) ?: return null
    if (note.content.isBlank()) return null
    val userTurns = messages.count { it.role == MessageRole.USER }
    if (!shouldInjectAuthorNoteAtUserTurn(userTurns, note.interval)) return null
    return CollectedPromptInjection(
        injection = PromptInjection.ModeInjection(
            // 会话级生效时与助手级区分命名，便于 trace 排查
            name = if (note === conversationAuthorNote) "会话作者注释" else "作者注释",
            position = note.position,
            content = note.content,
            injectDepth = note.depth,
            role = note.role,
        ),
        sourceType = PromptInjectionSourceType.AUTHOR_NOTE,
    )
}

/**
 * 解析生效的作者注释：
 * 会话级注释仅在助手开启 [Assistant.allowConversationAuthorNote] 且注释自身 enabled 时优先，
 * 否则回退到助手级注释（需 enabled）；两者都不可用时不注入。
 */
internal fun resolveEffectiveAuthorNote(
    assistant: Assistant,
    conversationAuthorNote: AuthorNote?,
): AuthorNote? {
    if (assistant.allowConversationAuthorNote && conversationAuthorNote?.enabled == true) {
        return conversationAuthorNote
    }
    return assistant.authorNote.takeIf { it.enabled }
}

/**
 * 作者注释间隔规则（确定性）：
 * 以上下文中 USER 消息的数量 N 作为当前用户轮次（含当前输入，第 1 条用户消息即第 1 轮），
 * 从第 1 轮起每隔 [interval] 轮注入一次，即当 `(N - 1) % interval == 0` 时注入；
 * interval <= 1 时每轮都注入；interval > 1 且没有用户消息时不注入。
 */
internal fun shouldInjectAuthorNoteAtUserTurn(userMessageCount: Int, interval: Int): Boolean {
    if (interval <= 1) return true
    if (userMessageCount <= 0) return false
    return (userMessageCount - 1) % interval == 0
}

/**
 * 递归扫描的最大轮数，防止条目内容互相触发导致死循环
 */
private const val MAX_RECURSIVE_SCAN_ROUNDS = 5

/**
 * 收集单个 Lorebook 的命中条目，实现 SillyTavern 风格的世界书匹配语义：
 * - selective：主关键词与次关键词都在扫描窗口命中才触发
 * - probability：按百分比随机决定是否注入（常驻条目不受概率影响）
 * - tokenBudget：按字符数近似预算，从最低优先级开始裁剪命中条目（至少保留一条）
 * - recursiveScanning：已命中条目的内容纳入后续轮次的扫描文本，最多 [MAX_RECURSIVE_SCAN_ROUNDS] 轮
 * - sticky / cooldown / delay：触发装饰器，状态从完整消息历史确定性推导（见 [resolveEntryDecorator]）
 */
private fun collectLorebookInjectionMatches(
    lorebook: Lorebook,
    nonSystemMessages: List<UIMessage>,
    random: kotlin.random.Random,
): List<CollectedPromptInjection> {
    val enabledEntries = lorebook.entries.filter { it.enabled }
    if (enabledEntries.isEmpty()) return emptyList()

    // 同一 scanDepth 的条目共享扫描窗口与拼接后的基础上下文，避免逐条目逐轮重复计算
    val scanContexts = mutableMapOf<Int, Pair<List<UIMessage>, String>>()
    fun scanContextOf(scanDepth: Int): Pair<List<UIMessage>, String> =
        scanContexts.getOrPut(scanDepth) {
            val scannedMessages = nonSystemMessages.takeLast(scanDepth)
            scannedMessages to scannedMessages.joinToString("\n") { it.toText() }
        }

    // 装饰器状态与消息历史无关轮次，整个收集过程只解析一次
    val decoratorStates = mutableMapOf<Uuid, LorebookEntryDecorator>()
    fun decoratorOf(entry: PromptInjection.RegexInjection): LorebookEntryDecorator =
        decoratorStates.getOrPut(entry.id) { resolveEntryDecorator(entry, nonSystemMessages) }

    val matches = mutableListOf<LorebookEntryMatch>()
    val matchedEntryIds = mutableSetOf<Uuid>()
    val probabilityFailedIds = mutableSetOf<Uuid>()
    val recursiveContents = mutableListOf<String>()
    for (round in 0 until MAX_RECURSIVE_SCAN_ROUNDS) {
        val roundMatches = mutableListOf<LorebookEntryMatch>()
        enabledEntries.forEach { entry ->
            if (entry.id in matchedEntryIds || entry.id in probabilityFailedIds) return@forEach
            val decorator = decoratorOf(entry)
            // delay 未到 / cooldown 中：本次生成所有轮次都不触发
            if (decorator.suppressed) return@forEach
            val (scannedMessages, baseContext) = scanContextOf(entry.scanDepth)
            val scannedContext = if (recursiveContents.isEmpty()) {
                baseContext
            } else {
                baseContext + "\n" + recursiveContents.joinToString("\n")
            }
            when (val result = scanLorebookEntry(entry, scannedMessages, scannedContext, round, random, decorator)) {
                is LorebookEntryScanResult.Matched -> roundMatches += result.match
                LorebookEntryScanResult.NotMatched -> Unit
                LorebookEntryScanResult.ProbabilityFailed -> probabilityFailedIds += entry.id
            }
        }
        if (roundMatches.isEmpty()) break
        matches += roundMatches
        matchedEntryIds += roundMatches.map { it.entry.id }
        if (!lorebook.recursiveScanning) break
        // 递归扫描：本轮命中条目的内容纳入后续轮次的扫描文本
        recursiveContents += roundMatches.map { it.entry.content }
    }

    trimMatchesToTokenBudget(matches, lorebook.tokenBudget)
    return matches.map { it.toCollectedInjection(lorebook) }
}

/**
 * 条目触发装饰器（sticky / cooldown / delay）的解析结果。
 *
 * 状态完全从消息历史推导，不持久化：
 * - 以完整非系统消息历史（不受条目 scanDepth 限制）定位主关键词最近一次命中的消息；
 * - "用户轮次" = USER 消息计数；turnsAgo = 命中消息之后的 USER 消息数（含当前输入），
 *   因此命中发生在上一个用户轮次时 turnsAgo = 1；
 * - 当前输入（最后一条 USER 消息）本身不计入历史命中，否则 cooldown 会阻止本次正常触发；
 * - 记账基于关键词命中而非实际注入：概率失败 / 被 tokenBudget 裁剪的触发同样计入历史。
 */
private data class LorebookEntryDecorator(
    val suppressed: Boolean,                 // delay 未到 / cooldown 中：本次生成完全不触发
    val stickyEligible: Boolean,             // 历史命中在 sticky 范围内：常规未命中时可粘性注入
    val stickyMatchedTerms: List<String> = emptyList(),
    val stickyTurnsAgo: Int? = null,
) {
    companion object {
        val NONE = LorebookEntryDecorator(suppressed = false, stickyEligible = false)
    }
}

private fun PromptInjection.RegexInjection.usesDecorators(): Boolean =
    !constantActive && keywords.isNotEmpty() && (sticky > 0 || cooldown > 0 || delay > 0)

private fun resolveEntryDecorator(
    entry: PromptInjection.RegexInjection,
    nonSystemMessages: List<UIMessage>,
): LorebookEntryDecorator {
    if (!entry.usesDecorators()) return LorebookEntryDecorator.NONE
    val userTurnCount = nonSystemMessages.count { it.role == MessageRole.USER }
    // delay：对话前 delay 个用户轮次不触发（delay=2 时从第 2 轮起激活）
    if (entry.delay > 0 && userTurnCount < entry.delay) {
        return LorebookEntryDecorator(suppressed = true, stickyEligible = false)
    }
    // 历史命中排除当前输入（最后一条 USER 消息）
    val currentUserIndex = nonSystemMessages.indexOfLast { it.role == MessageRole.USER }
    val historyEndExclusive = if (currentUserIndex >= 0) currentUserIndex else nonSystemMessages.size
    var lastHitIndex = -1
    var lastHitTerms: List<String> = emptyList()
    for (i in historyEndExclusive - 1 downTo 0) {
        val terms = matchInjectionKeywords(
            entry.keywords, nonSystemMessages[i].toText(), entry.useRegex, entry.caseSensitive
        )
        if (terms.isNotEmpty()) {
            lastHitIndex = i
            lastHitTerms = terms
            break
        }
    }
    if (lastHitIndex < 0) return LorebookEntryDecorator.NONE
    val turnsAgo = nonSystemMessages.subList(lastHitIndex + 1, nonSystemMessages.size)
        .count { it.role == MessageRole.USER }
    // cooldown 优先于 sticky：冷却期内粘性也不生效
    if (entry.cooldown > 0 && turnsAgo <= entry.cooldown) {
        return LorebookEntryDecorator(suppressed = true, stickyEligible = false)
    }
    return LorebookEntryDecorator(
        suppressed = false,
        stickyEligible = entry.sticky > 0 && turnsAgo <= entry.sticky,
        stickyMatchedTerms = lastHitTerms,
        stickyTurnsAgo = turnsAgo,
    )
}

/**
 * 单次扫描的判定结果：
 * - [Matched]：条目命中，进入本轮命中列表
 * - [NotMatched]：关键词未命中，本轮跳过（后续轮次仍可命中）
 * - [ProbabilityFailed]：概率判定失败，整个收集过程不再重掷
 */
private sealed interface LorebookEntryScanResult {
    data class Matched(val match: LorebookEntryMatch) : LorebookEntryScanResult
    data object NotMatched : LorebookEntryScanResult
    data object ProbabilityFailed : LorebookEntryScanResult
}

private data class LorebookEntryMatch(
    val entry: PromptInjection.RegexInjection,
    val matchedTerms: List<String>,
    val secondaryMatchedTerms: List<String>,
    val scannedMessages: List<UIMessage>,
    val recursiveRound: Int,
    val stickyTrigger: Boolean = false,
)

/**
 * 单条目单轮扫描：依次判定主关键词、selective 次关键词与 probability。
 * 概率判定只在关键词命中后进行，且 probability <= 0 时不消耗随机数（短路）。
 * [decorator] 提供 sticky/cooldown/delay 预解析结果：常规匹配未命中且 sticky 有效时，
 * 以粘性触发注入（不检查 selective、不消耗概率掷骰）。
 */
private fun scanLorebookEntry(
    entry: PromptInjection.RegexInjection,
    scannedMessages: List<UIMessage>,
    scannedContext: String,
    recursiveRound: Int,
    random: kotlin.random.Random,
    decorator: LorebookEntryDecorator = LorebookEntryDecorator.NONE,
): LorebookEntryScanResult {
    val matchedTerms = if (entry.constantActive) {
        emptyList()
    } else {
        matchInjectionKeywords(entry.keywords, scannedContext, entry.useRegex, entry.caseSensitive, entry.matchWholeWords)
    }
    if (!entry.constantActive && matchedTerms.isEmpty()) {
        // sticky：历史命中仍在粘着范围内时，无需再次命中关键词即可注入
        if (decorator.stickyEligible) {
            return LorebookEntryScanResult.Matched(
                LorebookEntryMatch(
                    entry = entry,
                    matchedTerms = decorator.stickyMatchedTerms,
                    secondaryMatchedTerms = emptyList(),
                    scannedMessages = scannedMessages,
                    recursiveRound = recursiveRound,
                    stickyTrigger = true,
                )
            )
        }
        return LorebookEntryScanResult.NotMatched
    }
    // selective：主关键词命中后，次关键词也必须在扫描窗口命中（次关键词为空时退化为仅主关键词匹配）
    val checkSecondary = entry.selective && entry.secondaryKeywords.isNotEmpty() && !entry.constantActive
    val secondaryMatchedTerms = if (checkSecondary) {
        matchInjectionKeywords(entry.secondaryKeywords, scannedContext, entry.useRegex, entry.caseSensitive, entry.matchWholeWords)
    } else {
        emptyList()
    }
    if (checkSecondary && secondaryMatchedTerms.isEmpty()) return LorebookEntryScanResult.NotMatched
    // probability：按百分比随机决定是否注入；概率判定失败的条目后续轮次不再重掷
    if (!entry.constantActive && entry.probability < 100 &&
        (entry.probability <= 0 || random.nextInt(100) >= entry.probability)
    ) {
        return LorebookEntryScanResult.ProbabilityFailed
    }
    return LorebookEntryScanResult.Matched(
        LorebookEntryMatch(
            entry = entry,
            matchedTerms = matchedTerms,
            secondaryMatchedTerms = secondaryMatchedTerms,
            scannedMessages = scannedMessages,
            recursiveRound = recursiveRound,
        )
    )
}

/**
 * tokenBudget：按字符数近似，从最低优先级开始裁剪，直到进入预算（至少保留一条）
 */
private fun trimMatchesToTokenBudget(matches: MutableList<LorebookEntryMatch>, tokenBudget: Int) {
    if (tokenBudget <= 0) return
    var totalLength = matches.sumOf { it.entry.content.length }
    while (totalLength > tokenBudget && matches.size > 1) {
        val lowestIndex = matches.indices.minBy { matches[it].entry.priority }
        totalLength -= matches[lowestIndex].entry.content.length
        matches.removeAt(lowestIndex)
    }
}

private fun LorebookEntryMatch.toCollectedInjection(lorebook: Lorebook): CollectedPromptInjection =
    CollectedPromptInjection(
        injection = entry,
        sourceType = PromptInjectionSourceType.LOREBOOK,
        lorebookId = lorebook.id,
        lorebookName = lorebook.name,
        match = PromptInjectionMatch(
            type = when {
                stickyTrigger -> PromptInjectionMatchType.STICKY
                entry.constantActive -> PromptInjectionMatchType.CONSTANT
                entry.useRegex -> PromptInjectionMatchType.REGEX
                else -> PromptInjectionMatchType.KEYWORD
            },
            matchedTerms = matchedTerms,
            scanDepth = entry.scanDepth,
            scannedMessageIds = scannedMessages.map { it.id },
            caseSensitive = entry.caseSensitive,
            regexEnabled = entry.useRegex,
            matchWholeWords = entry.matchWholeWords,
            selective = entry.selective,
            secondaryMatchedTerms = secondaryMatchedTerms,
            probability = entry.probability,
            recursiveRound = recursiveRound,
        ),
    )

// 世界书关键词正则在每次生成（递归扫描时甚至每轮）都会逐条编译，
// 与输出正则一样缓存编译结果；编译失败同样缓存，避免反复构造异常
private val lorebookKeywordRegexCache = SimpleCache.builder<String, Result<Regex>>()
    .expireAfterWrite(10, TimeUnit.MINUTES)
    .build()

private fun compileKeywordRegex(pattern: String, caseSensitive: Boolean): Regex? {
    // 缓存键同时区分大小写标志与 pattern，避免同 pattern 不同标志互相污染
    val key = "$caseSensitive|${pattern.length}|$pattern"
    lorebookKeywordRegexCache.getIfPresent(key)?.let { return it.getOrNull() }
    val result = runCatching {
        if (caseSensitive) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)
    }
    lorebookKeywordRegexCache.put(key, result)
    return result.getOrNull()
}

/**
 * 返回在给定上下文中命中的关键词列表。
 *
 * [matchWholeWords] 为 true 且非正则模式时，关键词必须作为独立词出现：
 * 前后不得紧邻 ASCII 字母/数字/下划线（SillyTavern \b 语义，CJK 保持子串匹配），
 * 避免 "apple" 误命中 "pineapple"。正则模式是显式匹配，不叠加整词限制。
 */
private fun matchInjectionKeywords(
    keywords: List<String>,
    context: String,
    useRegex: Boolean,
    caseSensitive: Boolean,
    matchWholeWords: Boolean = false,
): List<String> = keywords.filter { keyword ->
    if (useRegex) {
        val regex = compileKeywordRegex(keyword, caseSensitive) ?: return@filter false
        try {
            regex.containsMatchIn(context)
        } catch (_: Exception) {
            false
        }
    } else if (matchWholeWords) {
        containsWholeWord(context, keyword, caseSensitive)
    } else {
        context.contains(keyword, ignoreCase = !caseSensitive)
    }
}

/** 词边界字符：ASCII 字母 / 数字 / 下划线（与 SillyTavern 的 \b 语义一致，CJK 按子串匹配）。 */
private const val WHOLE_WORD_BOUNDARY = "[A-Za-z0-9_]"

/** 整词匹配：关键词两侧不得紧邻 ASCII 词边界字符。 */
private fun containsWholeWord(context: String, keyword: String, caseSensitive: Boolean): Boolean {
    if (keyword.isEmpty()) return false
    val escaped = Regex.escape(keyword)
    val pattern = "(?<!$WHOLE_WORD_BOUNDARY)$escaped(?!$WHOLE_WORD_BOUNDARY)"
    val regex = if (caseSensitive) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)
    return regex.containsMatchIn(context)
}

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
    val targetMessages = mutableMapOf<Int, UIMessage>()

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
            (beforeItems + afterItems)
                .filter { it.collected.injection.content.isNotEmpty() }
                .forEach { item ->
                    targetMessages[item.order] = result[systemIndex]
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
            (beforeItems + afterItems)
                .filter { it.collected.injection.content.isNotEmpty() }
                .forEach { item -> targetMessages[item.order] = message }
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
            merged.items.forEach { item -> targetMessages[item.order] = merged.message }
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
            merged.items.forEach { item -> targetMessages[item.order] = merged.message }
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
                merged.items.forEach { item -> targetMessages[item.order] = merged.message }
                insertIndex++
            }
        }
    }

    return PromptInjectionApplicationResult(result, targetMessages)
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
