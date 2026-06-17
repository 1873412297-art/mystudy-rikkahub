package me.rerere.rikkahub.ui.components.ai.completion

import androidx.compose.ui.text.TextRange
import me.rerere.rikkahub.data.model.GroupMember

class GroupMentionCompletionProvider(
    private val members: List<GroupMember>,
) : ChatCompletionProvider {
    override val id: String = "group_mentions"

    override suspend fun complete(context: ChatCompletionContext): ChatCompletionList? {
        if (context.hasSelection) return null
        val mention = findMention(context.text, context.cursor) ?: return null
        val query = mention.query.trim()
        val items = members
            .asSequence()
            .filter { it.enabled && it.displayName.isNotBlank() }
            .mapNotNull { member ->
                val score = member.displayName.mentionScore(query) ?: return@mapNotNull null
                ChatCompletionItem(
                    label = member.displayName,
                    insertText = "@${member.displayName} ",
                    detail = "Group member",
                    sortScore = score,
                )
            }
            .sortedWith(
                compareByDescending<ChatCompletionItem> { it.sortScore }
                    .thenBy { it.label.length }
                    .thenBy { it.label.lowercase() }
            )
            .take(MAX_ITEMS)
            .toList()
        if (items.isEmpty()) return null
        return ChatCompletionList(
            providerId = id,
            replacementRange = mention.range,
            items = items,
        )
    }

    private fun findMention(text: String, cursor: Int): MentionCandidate? {
        if (cursor < 0 || cursor > text.length) return null
        val prefix = text.substring(0, cursor)
        val start = prefix.lastIndexOf('@')
        if (start < 0) return null
        if (start > 0 && !text[start - 1].isMentionBoundary()) return null
        val query = prefix.substring(start + 1)
        if (query.any { it == '\n' || it == '\r' }) return null
        if (query.contains("/workspace")) return null
        if (query.contains("/")) return null
        if (query.contains("\\")) return null
        return MentionCandidate(
            query = query,
            range = TextRange(start, cursor),
        )
    }

    private fun String.mentionScore(query: String): Int? {
        if (query.isBlank()) return 100
        if (equals(query, ignoreCase = true)) return 1_000
        if (startsWith(query, ignoreCase = true)) return 800 - length.coerceAtMost(200)
        val containsIndex = indexOf(query, ignoreCase = true)
        if (containsIndex >= 0) return 600 - containsIndex.coerceAtMost(200)
        return null
    }

    private fun Char.isMentionBoundary(): Boolean = isWhitespace() || this in "([{<\"'"

    private data class MentionCandidate(
        val query: String,
        val range: TextRange,
    )

    companion object {
        private const val MAX_ITEMS = 8
    }
}
