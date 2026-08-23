package me.rerere.rikkahub.data.ai.status

import me.rerere.ai.ui.UIMessagePart

/** Returns an immutable message-body display copy while keeping status data exclusively in the HUD source. */
fun UIMessagePart.withoutInlineStatus(): UIMessagePart? = when (this) {
    is UIMessagePart.Text -> StatusBlockExtractor.extract(text).cleanedText
        .takeIf(String::isNotBlank)
        ?.let { copy(text = it) }
    is UIMessagePart.StatusPlaceholder -> null
    else -> this
}

/**
 * 状态区域中的一个分节（对应 `<details><summary>T</summary>body</details>`，
 * 或未被 details 包裹的剩余成段文字——此时 [title] 为空串）。
 */
data class StatusSection(val title: String, val content: String, val isHtml: Boolean)

/** 状态区域末尾的编号选项（如 `1. [最佳] 冒险潜入……`）。 */
data class StatusOption(val label: String, val text: String)

/**
 * 一次状态块提取的结果。
 *
 * @property cleanedText 移除所有状态区域、剥掉 maintext 标签后的正文
 * @property headerLine 状态区域内首个 `『…』` 行（保留书名号），没有则为 null
 * @property sections 状态区域内的结构化分节，按文档顺序
 * @property options 状态区域内 details 之外的编号选项，按文档顺序
 * @property rawStatusText 所有状态区域原文（含标签）的拼接；无状态区域时为 null
 */
data class StatusBlockExtraction(
    val cleanedText: String,
    val headerLine: String?,
    val sections: List<StatusSection>,
    val options: List<StatusOption>,
    val rawStatusText: String?,
)

/**
 * 通用状态块解析器，供聊天页"动态状态栏（HUD）"使用。
 *
 * 纯启发式、无 Android 依赖、无具体业务词硬编码。规则要点：
 * 1. 状态区域识别（大小写不敏感）：`<status_block>` / `<statusblock>` / `<statusbar>` /
 *    `<status>` / `<status!>` / `<状态栏>`；只有开标签时区域延伸到文本末尾；多个区域全部处理。
 *    无已知标签时兜底：连续 2+ 个（或末尾单个）仅由空白分隔的裸 `<details>` 块视作状态区域。
 * 2. `<maintext>…</maintext>`（大小写不敏感）只剥标签、内容保留在 cleanedText。
 * 3. 区域内 `<details><summary>T</summary>body</details>` 成为一个 [StatusSection]，
 *    body 中的 ``` 围栏行会被移除。
 * 4. details 之外的区域文本：先抽取编号选项（连续的 `数字 + .、）)` 开头的行），
 *    选项行块前紧邻的 `『…』` 标题行（如『剧情发展』）随选项块一并消费、不作为 headerLine；
 *    之后剩余文本中首个 `『…』` 行成为 headerLine（全程只取第一个）。
 * 5. 取舍：未被 details 包裹、且不属于 header/选项的剩余文字，统一合并为一个
 *    title 为空串的 section（策略简单一致；相邻 details 不被污染）。
 * 6. [StatusSection.isHtml]：content 含除 details/summary/br 之外的 HTML 标签时为 true。
 */
object StatusBlockExtractor {

    // 开/闭标签族由 StatusTags 单一提供（status_block / statusblock / statusbar / status!? / 状态栏）。
    private val openTagRegex = StatusTags.openTagRegex()
    private val closeTagRegex = StatusTags.closeTagRegex()

    // maintext 开/闭标签统一剥离（不要求配对）。
    private val maintextTagRegex = Regex("</?\\s*maintext\\s*>", RegexOption.IGNORE_CASE)

    private val detailsRegex = Regex(
        "<\\s*details\\s*>\\s*<\\s*summary\\s*>(.*?)</\\s*summary\\s*>(.*?)</\\s*details\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    // 编号选项行：`1. [标签] 文本` / `2、文本` / `3) 文本` 等。
    private val optionLineRegex = Regex("^\\s*(\\d+)\\s*[.、）)]\\s*(?:\\[([^]]+)])?\\s*(.+)$")

    // 整行都是 『…』 的标题行。
    private val cornerTitleLineRegex = Regex("^\\s*『.*』\\s*$")

    // ``` 或 ```lang 形式的围栏行。
    private val fenceLineRegex = Regex("^\\s*```[A-Za-z0-9_-]*\\s*$")

    // isHtml 判定时的白名单标签（先从 content 中剔除再检测其余标签）。
    private val allowedHtmlRegex = Regex("</?\\s*(details|summary|br)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val htmlTagRegex = Regex("<\\s*/?\\s*[A-Za-z][^>]*>")

    // 连续空行（含空白字符行）压缩为一个空行。
    private val multiBlankLinesRegex = Regex("\\n[ \\t]*(?:\\n[ \\t]*)+")

    fun extract(text: String): StatusBlockExtraction {
        if (text.isEmpty()) {
            return StatusBlockExtraction("", null, emptyList(), emptyList(), null)
        }
        val input = text.replace("\r\n", "\n").replace("\r", "\n")

        // 1. 定位所有状态区域（未闭合的延伸到文末）。
        val spans = mutableListOf<IntRange>()
        val contents = mutableListOf<String>()
        var searchFrom = 0
        while (searchFrom < input.length) {
            val open = openTagRegex.find(input, searchFrom) ?: break
            val close = closeTagRegex.find(input, open.range.last + 1)
            val contentEnd = close?.range?.first ?: input.length
            val spanEnd = close?.range?.last?.plus(1) ?: input.length
            spans.add(open.range.first until spanEnd)
            contents.add(input.substring(open.range.last + 1, contentEnd))
            searchFrom = spanEnd
        }

        // 无标签状态区域：尝试裸 <details> 连续块兜底；仍无结果则返回原文（仅剥 maintext）。
        if (spans.isEmpty()) {
            return extractBareDetailsFallback(input) ?: StatusBlockExtraction(
                cleanedText = maintextTagRegex.replace(input, ""),
                headerLine = null,
                sections = emptyList(),
                options = emptyList(),
                rawStatusText = null,
            )
        }

        // 2. cleanedText：移除状态区域 + 剥 maintext 标签 + trim + 压缩空行。
        val narrative = buildString {
            var cursor = 0
            for (span in spans) {
                append(input, cursor, span.first)
                cursor = span.last + 1
            }
            append(input, cursor, input.length)
        }
        val cleanedText = multiBlankLinesRegex
            .replace(maintextTagRegex.replace(narrative, ""), "\n\n")
            .trim()

        // 3. rawStatusText：所有状态区域原文（含标签）拼接。
        val rawStatusText = spans.joinToString("\n") { input.substring(it.first, it.last + 1) }

        // 4. 逐区域解析 header / sections / options（跨区域保持文档顺序）。
        val sections = mutableListOf<StatusSection>()
        val options = mutableListOf<StatusOption>()
        var headerLine: String? = null
        for (content in contents) {
            headerLine = parseRegion(content, sections, options, headerLine)
        }

        return StatusBlockExtraction(cleanedText, headerLine, sections, options, rawStatusText)
    }

    /**
     * 裸 `<details>` 连续块兜底：文本中没有任何已知状态标签时调用。
     *
     * 把所有 details 块按"块间只有空白"分组成极大连续 run，识别最后一个满足条件的 run：
     * - run 内含至少 2 个 details 块；或
     * - run 是单个 details 块且位于文本末尾（其后只有空白）。
     *
     * 识别成功时：各块转成 [StatusSection]（与标签路径同一套提取逻辑），
     * 整段 run 从 cleanedText 移除并记入 rawStatusText。
     * 保守起见，兜底路径不做 headerLine / options 抽取。
     * 单个非末尾 details 块（普通 HTML 内容）不会被捕获。
     */
    private fun extractBareDetailsFallback(input: String): StatusBlockExtraction? {
        val matches = detailsRegex.findAll(input).toList()
        if (matches.isEmpty()) return null

        // 按"相邻块之间只有空白"分组成极大连续 run。
        val runs = mutableListOf<MutableList<MatchResult>>()
        var previousEnd = -1
        for (match in matches) {
            if (runs.isEmpty() || input.substring(previousEnd, match.range.first).isNotBlank()) {
                runs.add(mutableListOf(match))
            } else {
                runs.last().add(match)
            }
            previousEnd = match.range.last + 1
        }

        // 取最后一个满足条件的 run（2+ 块，或单个末尾块）。
        val run = runs.lastOrNull { candidates ->
            candidates.size >= 2 || input.substring(candidates.last().range.last + 1).isBlank()
        } ?: return null

        val runStart = run.first().range.first
        val runEnd = run.last().range.last + 1

        val narrative = input.removeRange(runStart, runEnd)
        val cleanedText = multiBlankLinesRegex
            .replace(maintextTagRegex.replace(narrative, ""), "\n\n")
            .trim()

        val sections = run.map { match ->
            val title = match.groupValues[1].trim()
            val body = stripFenceLines(match.groupValues[2])
            StatusSection(title = title, content = body, isHtml = containsHtml(body))
        }

        return StatusBlockExtraction(
            cleanedText = cleanedText,
            headerLine = null,
            sections = sections,
            options = emptyList(),
            rawStatusText = input.substring(runStart, runEnd),
        )
    }

    /** 解析单个状态区域内部，按文档顺序产出 sections 与 options。 */
    private fun parseRegion(
        content: String,
        sections: MutableList<StatusSection>,
        options: MutableList<StatusOption>,
        headerLine: String?,
    ): String? {
        var header = headerLine
        var cursor = 0
        for (match in detailsRegex.findAll(content)) {
            // details 之前的裸文本段。
            header = processPlainSegment(content.substring(cursor, match.range.first), sections, options, header)
            val title = match.groupValues[1].trim()
            val body = stripFenceLines(match.groupValues[2])
            sections.add(StatusSection(title = title, content = body, isHtml = containsHtml(body)))
            cursor = match.range.last + 1
        }
        // 末尾裸文本段（选项通常在这里）。
        header = processPlainSegment(content.substring(cursor), sections, options, header)
        return header
    }

    /**
     * 处理 details 之外的裸文本段：全文首个 『…』 行优先作 headerLine；
     * 再抽编号选项（连同其前的 『…』 选项标题行）；剩余文字合并为一个无标题 section。
     */
    private fun processPlainSegment(
        segment: String,
        sections: MutableList<StatusSection>,
        options: MutableList<StatusOption>,
        headerLine: String?,
    ): String? {
        var header = headerLine
        val lines = segment.lines()
        val consumed = BooleanArray(lines.size)

        // headerLine 优先：全文首个整行 『…』 行一律作为 header（即使它紧邻选项块）；
        // 只有 header 已确定后，选项块前的 『…』 行才会被当作选项标题消费。
        if (header == null) {
            val idx = lines.indexOfFirst { cornerTitleLineRegex.matches(it) }
            if (idx >= 0) {
                header = lines[idx].trim()
                consumed[idx] = true
            }
        }

        // 编号选项：连续匹配行构成一个选项块；块前紧邻的 『…』 行是选项标题，一并消费。
        var i = 0
        while (i < lines.size) {
            if (optionLineRegex.matchEntire(lines[i]) == null) {
                i++
                continue
            }
            if (i > 0 && !consumed[i - 1] && cornerTitleLineRegex.matches(lines[i - 1])) {
                consumed[i - 1] = true
            }
            while (i < lines.size) {
                val m = optionLineRegex.matchEntire(lines[i]) ?: break
                consumed[i] = true
                options.add(StatusOption(label = m.groupValues[2].trim(), text = m.groupValues[3].trim()))
                i++
            }
        }

        val remaining = lines.filterIndexed { index, _ -> !consumed[index] }

        // 取舍：剩余成段文字（含【】标题段）统一合并为一个 title 为空的 section。
        val rest = remaining.joinToString("\n").trim()
        if (rest.isNotEmpty()) {
            sections.add(StatusSection(title = "", content = rest, isHtml = containsHtml(rest)))
        }
        return header
    }

    /** 移除 ``` 围栏行并 trim。 */
    private fun stripFenceLines(body: String): String =
        body.lines().filterNot { fenceLineRegex.matches(it) }.joinToString("\n").trim()

    /** content 是否含除 details/summary/br 之外的 HTML 标签。 */
    private fun containsHtml(content: String): Boolean =
        htmlTagRegex.containsMatchIn(allowedHtmlRegex.replace(content, ""))
}
