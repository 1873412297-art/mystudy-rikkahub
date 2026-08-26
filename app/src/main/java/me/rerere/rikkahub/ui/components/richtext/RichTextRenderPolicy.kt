package me.rerere.rikkahub.ui.components.richtext

import me.rerere.rikkahub.data.ai.transformers.findBareJsonPatch
import me.rerere.rikkahub.data.ai.status.StatusTags

internal data class RichTextRenderIntent(
    val normalizedContent: String,
    val hasStatusBlock: Boolean,
    val hasJsonPatch: Boolean,
    val isRawHtmlDocument: Boolean,
) {
    val useMarkdownWebView: Boolean
        get() = hasStatusBlock || hasJsonPatch || isRawHtmlDocument
}

internal enum class RichTextRendererMode {
    NATIVE_MARKDOWN,
    WEBVIEW_SEGMENTS,
    STABLE_DOM,
}

private val MAIN_TEXT_WRAPPER_REGEX = Regex(
    """^\s*<maintext\b[^>]*>([\s\S]*?)</maintext>\s*$""",
    RegexOption.IGNORE_CASE
)

private val MAIN_TEXT_BLOCK_REGEX = Regex(
    """<maintext\b[^>]*>([\s\S]*?)</maintext>""",
    RegexOption.IGNORE_CASE
)

private val STATUS_BLOCK_SEGMENT_REGEX = StatusTags.segmentRegex()
private val STATUS_BLOCK_WRAPPER_REGEX = StatusTags.wrapperRegex()

private val DETAILS_SUMMARY_REGEX = Regex(
    """<details\b[^>]*>\s*<summary\b[^>]*>([\s\S]*?)</summary>""",
    RegexOption.IGNORE_CASE
)

private val HTML_DETAILS_TAG_REGEX = Regex(
    """</?details\b[^>]*>|</?summary\b[^>]*>""",
    RegexOption.IGNORE_CASE
)

private val FENCE_LINE_REGEX = Regex("""(?m)^\s*`{3,}[\w-]*\s*$""")

internal data class RichTextSegment(
    val kind: Kind,
    val raw: String,
) {
    enum class Kind {
        MARKDOWN,
        STATUS_BLOCK,
        JSON_PATCH,
        JSON_PATCH_DIAGNOSTIC,
        HTML_DOCUMENT,
        FRONTEND_HTML,
    }
}

internal fun normalizeRichTextContent(content: String): String {
    val trimmed = content.trim()
    val match = MAIN_TEXT_WRAPPER_REGEX.matchEntire(trimmed) ?: return content
    return match.groupValues[1].trim()
}

internal fun analyzeRichTextContent(content: String): RichTextRenderIntent {
    val normalized = normalizeRichTextContent(content)
    val segments = parseRichTextSegments(normalized)
    return RichTextRenderIntent(
        normalizedContent = normalized,
        hasStatusBlock = segments.any { it.kind == RichTextSegment.Kind.STATUS_BLOCK },
        hasJsonPatch = segments.any {
            it.kind == RichTextSegment.Kind.JSON_PATCH || it.kind == RichTextSegment.Kind.JSON_PATCH_DIAGNOSTIC
        },
        isRawHtmlDocument = segments.any { it.kind == RichTextSegment.Kind.HTML_DOCUMENT },
    )
}

internal fun shouldUseWebViewRendering(content: String): Boolean {
    return analyzeRichTextContent(content).useMarkdownWebView
}

internal fun chooseRendererMode(content: String): RichTextRendererMode {
    val segments = parseRichTextSegments(content)
    return when {
        segments.any {
            it.kind == RichTextSegment.Kind.HTML_DOCUMENT || it.kind == RichTextSegment.Kind.FRONTEND_HTML
        } -> RichTextRendererMode.WEBVIEW_SEGMENTS
        segments.any { it.kind != RichTextSegment.Kind.MARKDOWN } -> RichTextRendererMode.STABLE_DOM
        else -> RichTextRendererMode.NATIVE_MARKDOWN
    }
}

internal fun looksLikeHtmlDocument(content: String): Boolean {
    val t = content.trimStart()
    if (t.startsWith("<!DOCTYPE", ignoreCase = true)) return true
    if (t.startsWith("<html", ignoreCase = true)) return true
    if (t.startsWith("<body", ignoreCase = true)) return true
    if (t.startsWith("@media")) return true

    if (looksLikeFencedHtmlDocument(t)) return true

    val stripped = stripMarkdownCodeRegions(t)
    if (Regex("<!doctype\\s+html", RegexOption.IGNORE_CASE).containsMatchIn(stripped)) return true
    if (Regex("<html[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(stripped)) return true
    if (Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE).containsMatchIn(stripped)) return true
    if (Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE).containsMatchIn(stripped)) return true
    if (Regex("<svg[\\s>][\\s\\S]*?</svg>", RegexOption.IGNORE_CASE).containsMatchIn(stripped)) return true
    return false
}

internal fun parseRichTextSegments(content: String): List<RichTextSegment> {
    val normalized = normalizeRichTextContent(content)
    if (looksLikeHtmlDocument(normalized)) {
        return listOf(RichTextSegment(RichTextSegment.Kind.HTML_DOCUMENT, normalized))
    }

    val segments = mutableListOf<RichTextSegment>()
    var cursor = 0

    fun addPlainMarkdown(raw: String) {
        if (raw.isNotBlank()) {
            segments += RichTextSegment(RichTextSegment.Kind.MARKDOWN, raw)
        }
    }

    fun addMarkdown(raw: String) {
        val unwrapped = unwrapMainTextBlocks(raw)
        val frontendSegments = TavernFrontendBlockExtractor.extract(unwrapped)
        if (frontendSegments.none { it is TavernFrontendSegment.Frontend }) {
            addPlainMarkdown(unwrapped)
            return
        }
        frontendSegments.forEach { segment ->
            when (segment) {
                is TavernFrontendSegment.Text -> addPlainMarkdown(segment.content)
                is TavernFrontendSegment.Code -> addPlainMarkdown(segment.toMarkdownFence())
                is TavernFrontendSegment.Frontend -> segments += RichTextSegment(
                    kind = RichTextSegment.Kind.FRONTEND_HTML,
                    raw = segment.html,
                )
            }
        }
    }

    while (cursor < normalized.length) {
        val nextStatus = STATUS_BLOCK_SEGMENT_REGEX.find(normalized, cursor)
        val nextPatch = findBareJsonPatch(normalized.substring(cursor))?.let { range ->
            (cursor + range.first)..(cursor + range.last)
        }
        val nextMalformedPatch = findMalformedJsonPatchRange(normalized, cursor)

        val candidates = listOfNotNull(
            nextStatus?.let { SegmentCandidate(RichTextSegment.Kind.STATUS_BLOCK, it.range.first, it.range.last + 1) },
            nextPatch?.let { SegmentCandidate(RichTextSegment.Kind.JSON_PATCH, it.first, it.last + 1) },
            nextMalformedPatch?.let {
                SegmentCandidate(RichTextSegment.Kind.JSON_PATCH_DIAGNOSTIC, it.first, it.last + 1)
            },
        )
        val next = candidates.minWithOrNull(compareBy<SegmentCandidate> { it.start }.thenBy { it.end })
        if (next == null) {
            addMarkdown(normalized.substring(cursor))
            break
        }

        if (next.start > cursor) {
            addMarkdown(normalized.substring(cursor, next.start))
        }

        val raw = normalized.substring(next.start, next.end)
        val segmentRaw = if (next.kind == RichTextSegment.Kind.JSON_PATCH_DIAGNOSTIC) {
            buildJsonPatchDiagnostic(raw)
        } else if (next.kind == RichTextSegment.Kind.STATUS_BLOCK) {
            statusBlockDisplayText(raw)
        } else {
            raw
        }
        segments += RichTextSegment(next.kind, segmentRaw)
        cursor = next.end
    }

    return segments.ifEmpty {
        listOf(RichTextSegment(RichTextSegment.Kind.MARKDOWN, unwrapMainTextBlocks(normalized)))
    }
}

private fun TavernFrontendSegment.Code.toMarkdownFence(): String {
    val longestRun = Regex("`+").findAll(code).maxOfOrNull { it.value.length } ?: 0
    val fence = "`".repeat(maxOf(3, longestRun + 1))
    val info = language.takeIf { it.isNotEmpty() }.orEmpty()
    return "$fence$info\n$code$fence"
}

private data class SegmentCandidate(
    val kind: RichTextSegment.Kind,
    val start: Int,
    val end: Int,
)

private fun unwrapMainTextBlocks(content: String): String {
    return MAIN_TEXT_BLOCK_REGEX.replace(content) { match ->
        match.groupValues[1].trim()
    }
}

private fun statusBlockDisplayText(raw: String): String {
    val unwrapped = STATUS_BLOCK_WRAPPER_REGEX.matchEntire(raw.trim())
        ?.groupValues
        ?.get(1)
        ?: raw

    return unwrapped
        .replace(DETAILS_SUMMARY_REGEX) { match ->
            "\n${match.groupValues[1].trim()}\n"
        }
        .replace(HTML_DETAILS_TAG_REGEX, "")
        .replace(FENCE_LINE_REGEX, "")
        .lines()
        .joinToString("\n") { it.trimEnd() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

private fun findMalformedJsonPatchRange(content: String, startIndex: Int): IntRange? {
    val start = content.indexOf('[', startIndex)
    if (start < 0) return null
    val tail = content.substring(start)
    val compact = tail.trimStart()
    if (!compact.startsWith("[{") && !compact.startsWith("[ {")) return null
    if (!tail.contains("\"op\"") || !tail.contains("\"path\"")) return null
    if (findBareJsonPatch(tail) != null) return null

    val end = content.indexOf('\n', start).takeIf { it >= 0 } ?: content.length
    return start until end
}

private fun buildJsonPatchDiagnostic(raw: String): String {
    return """
        > JSON Patch 渲染失败：检测到疑似变量更新数组，但 JSON 结构不完整或括号未闭合。
        >
        > `${raw.trim().take(160).replace("`", "\\`")}`
    """.trimIndent()
}
