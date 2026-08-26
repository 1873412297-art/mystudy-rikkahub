package me.rerere.rikkahub.ui.components.richtext

internal sealed interface TavernFrontendSegment {
    data class Text(val content: String) : TavernFrontendSegment

    data class Code(
        val language: String,
        val code: String,
    ) : TavernFrontendSegment

    data class Frontend(
        val language: String,
        val html: String,
    ) : TavernFrontendSegment
}

internal object TavernFrontendBlockExtractor {
    private val openingFence = Regex("(?m)^(`{3,}|~{3,})([^\\r\\n]*)\\r?\\n")
    private val frontendLanguages = setOf("html", "htm", "frontend", "web", "iframe")
    private val htmlElement = Regex("<[A-Za-z][A-Za-z0-9:-]*(?:\\s[^<>]*)?/?>")

    fun extract(message: String): List<TavernFrontendSegment> {
        if (message.isEmpty()) return emptyList()

        val segments = mutableListOf<TavernFrontendSegment>()
        var cursor = 0
        while (cursor < message.length) {
            val opening = openingFence.find(message, cursor) ?: break
            val marker = opening.groupValues[1]
            val closingFence = Regex(
                pattern = "(?m)^${Regex.escape(marker.first().toString())}{${marker.length},}[ \\t]*(?:\\r?\\n|$)",
            ).find(message, opening.range.last + 1) ?: break

            if (opening.range.first > cursor) {
                segments += TavernFrontendSegment.Text(message.substring(cursor, opening.range.first))
            }

            val language = opening.groupValues[2].trim().substringBefore(' ').lowercase()
            val content = message.substring(opening.range.last + 1, closingFence.range.first)
            segments += if (isFrontend(language, content)) {
                TavernFrontendSegment.Frontend(language = language, html = content)
            } else {
                TavernFrontendSegment.Code(language = language, code = content)
            }
            cursor = closingFence.range.last + 1
        }

        if (cursor < message.length) {
            segments += TavernFrontendSegment.Text(message.substring(cursor))
        }
        return segments
    }

    private fun isFrontend(language: String, content: String): Boolean {
        if (language in frontendLanguages) return htmlElement.containsMatchIn(content)
        if (language.isNotEmpty()) return false
        return Regex("<!doctype\\s+html", RegexOption.IGNORE_CASE).containsMatchIn(content) ||
            Regex("<html[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(content)
    }
}
