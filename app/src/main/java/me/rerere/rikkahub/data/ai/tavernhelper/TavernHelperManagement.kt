package me.rerere.rikkahub.data.ai.tavernhelper

internal data class TavernHelperSearchResult(
    val nodes: List<TavernHelperScriptNode>,
    val error: String? = null,
)

internal fun stripOuterScriptFence(source: String): String {
    val match = OUTER_SCRIPT_FENCE.matchEntire(source.trim()) ?: return source
    return match.groupValues.drop(1).first { it.isNotEmpty() }.trim()
}

internal fun searchTavernHelperNodes(
    nodes: List<TavernHelperScriptNode>,
    query: String,
): TavernHelperSearchResult {
    val value = query.trim()
    if (value.isEmpty()) return TavernHelperSearchResult(nodes)
    val matcher: (String) -> Boolean = if (value.startsWith('/') && value.lastIndexOf('/') > 0) {
        val end = value.lastIndexOf('/')
        val pattern = value.substring(1, end)
        val flags = value.substring(end + 1)
        if (flags.any { it != 'i' }) return TavernHelperSearchResult(emptyList(), "不支持的正则标志：$flags")
        val regex = runCatching {
            Regex(pattern, if ('i' in flags) setOf(RegexOption.IGNORE_CASE) else emptySet())
        }.getOrElse { return TavernHelperSearchResult(emptyList(), it.message ?: "正则表达式无效") }
        ({ text: String -> regex.containsMatchIn(text) })
    } else {
        { text -> text.contains(value, ignoreCase = true) }
    }
    return TavernHelperSearchResult(nodes.mapNotNull { node ->
        when (node) {
            is TavernHelperScript -> node.takeIf { matcher(it.name) || matcher(it.info) || matcher(it.content) }
            is TavernHelperScriptFolder -> {
                if (matcher(node.name)) node else node.copy(
                    scripts = node.scripts.filter { matcher(it.name) || matcher(it.info) || matcher(it.content) },
                ).takeIf { it.scripts.isNotEmpty() }
            }
        }
    })
}

private val OUTER_SCRIPT_FENCE = Regex(
    """(?s)^```(?:java)?script\s*\R(.*?)\R```$|^```(?:ts|typescript)\s*\R(.*?)\R```$""",
    RegexOption.IGNORE_CASE,
)
