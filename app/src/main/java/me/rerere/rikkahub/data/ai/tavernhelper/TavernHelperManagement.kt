package me.rerere.rikkahub.data.ai.tavernhelper

import java.util.UUID

internal data class TavernHelperSearchResult(
    val nodes: List<TavernHelperScriptNode>,
    val error: String? = null,
)

internal data class TavernHelperDetachResult(
    val remaining: List<TavernHelperScriptNode>,
    val node: TavernHelperScriptNode?,
)

internal fun reorderTavernHelperNodes(
    nodes: List<TavernHelperScriptNode>,
    nodeId: String,
    offset: Int,
): List<TavernHelperScriptNode> {
    fun <T> move(items: List<T>, index: Int): List<T> {
        if (index !in items.indices) return items
        val destination = (index + offset).coerceIn(items.indices)
        if (destination == index) return items
        return items.toMutableList().apply { add(destination, removeAt(index)) }
    }

    val rootIndex = nodes.indexOfFirst { it.id == nodeId }
    if (rootIndex >= 0) return move(nodes, rootIndex)
    return nodes.map { node ->
        if (node !is TavernHelperScriptFolder) return@map node
        val childIndex = node.scripts.indexOfFirst { it.id == nodeId }
        if (childIndex < 0) node else node.copy(scripts = move(node.scripts, childIndex))
    }
}

internal fun detachTavernHelperNode(
    nodes: List<TavernHelperScriptNode>,
    nodeId: String,
): TavernHelperDetachResult {
    nodes.firstOrNull { it.id == nodeId }?.let { found ->
        return TavernHelperDetachResult(nodes.filterNot { it.id == nodeId }, found)
    }
    var detached: TavernHelperScript? = null
    val remaining = nodes.map { node ->
        if (node !is TavernHelperScriptFolder || detached != null) return@map node
        val child = node.scripts.firstOrNull { it.id == nodeId } ?: return@map node
        detached = child
        node.copy(scripts = node.scripts.filterNot { it.id == nodeId })
    }
    return TavernHelperDetachResult(remaining, detached)
}

internal fun TavernHelperScriptNode.copyForTavernHelperTransfer(
    idFactory: () -> String = { UUID.randomUUID().toString() },
): TavernHelperScriptNode = when (this) {
    is TavernHelperScript -> copy(id = idFactory(), enabled = false)
    is TavernHelperScriptFolder -> copy(
        id = idFactory(),
        enabled = false,
        scripts = scripts.map { it.copy(id = idFactory(), enabled = false) },
    )
}

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
