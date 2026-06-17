package me.rerere.rikkahub.data.ai.transformers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.status.JsonPatchOp
import me.rerere.rikkahub.data.ai.status.StatusRenderer
import me.rerere.rikkahub.data.ai.status.StatusVariableStore
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid

// region Regex patterns

/** Match `<UpdateVariable>` blocks. Supports two formats:
 *  1. `<UpdateVariable><JSONPatch>[...]</JSONPatch></UpdateVariable>`
 *  2. `<UpdateVariable><Analysis>...</Analysis><JSONPatch>[...]</JSONPatch></UpdateVariable>`
 *  The JSONPatch content may be empty (no-op). */
private val UPDATE_VARIABLE_REGEX = Regex(
    """<UpdateVariable>\s*(?:<Analysis>.*?</Analysis>\s*)?<JSONPatch>(.*?)</JSONPatch>\s*</UpdateVariable>""",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
)

/**
 * Find bare JSON Patch arrays in text using bracket-balance scanning.
 * Handles `{` `}` inside JSON string values (e.g. `{{user}}`).
 * Returns the range of the first valid JSON Patch array found, or null.
 */
internal fun findBareJsonPatch(text: String): IntRange? {
    var i = 0
    while (i < text.length) {
        // Look for "[{" sequence
        if (text[i] == '[') {
            val start = i
            val end = findMatchingBracket(text, start)
            if (end > start) {
                // Try to validate this is a real JSON Patch array
                val candidate = text.substring(start, end)
                if (candidate.contains("\"op\"")) {
                    return start until end
                }
            }
            i = end.coerceAtLeast(i + 1)
        } else {
            i++
        }
    }
    return null
}

/** Find the matching close bracket for an open bracket, tracking strings and escapes. */
private fun findMatchingBracket(text: String, openIndex: Int): Int {
    val openChar = text[openIndex]
    val closeChar = when (openChar) {
        '[' -> ']'
        '{' -> '}'
        else -> return openIndex
    }
    var depth = 0
    var inString = false
    var escaped = false
    var j = openIndex
    while (j < text.length) {
        val c = text[j]
        if (escaped) {
            escaped = false
        } else if (c == '\\') {
            escaped = true
        } else if (c == '"') {
            inString = !inString
        } else if (!inString) {
            if (c == openChar) depth++
            else if (c == closeChar) {
                depth--
                if (depth == 0) return j + 1
            }
        }
        j++
    }
    return openIndex // no match found
}

/** Match `<StatusPlaceHolderImpl/>` self-closing tag. */
private val STATUS_PLACEHOLDER_REGEX = Regex(
    """<StatusPlaceHolderImpl\s*/>""",
    RegexOption.IGNORE_CASE
)

/** Match `<Expression name="..."/>` or `<Expression>...</Expression>` tags. */
private val EXPRESSION_REGEX = Regex(
    """<Expression\s+name\s*=\s*"([^"]*)"\s*/>|<Expression>(.*?)</Expression>""",
    setOf(RegexOption.IGNORE_CASE)
)

// endregion

/**
 * Output message transformer that handles SillyTavern-style status/expression rendering.
 *
 * Tag support:
 * - `<UpdateVariable><JSONPatch>[...]</JSONPatch></UpdateVariable>` — apply JSONPatch to variable store
 * - `<StatusPlaceHolderImpl/>` — replace with JS-rendered HTML inline
 * - `<Expression name="happy"/>` or `<Expression>happy</Expression>` — set expression in variables
 *
 * All tags are stripped from visible text during processing.
 */
object StatusPlaceholderTransformer : OutputMessageTransformer, KoinComponent {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val store: StatusVariableStore by lazy { get<StatusVariableStore>() }
    private val renderer: StatusRenderer by lazy { get<StatusRenderer>() }

    private var loadedScriptKey: Int? = null

    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
        android.util.Log.i("StatusPlhd", "▶ transform() ENTER convId=${ctx.conversationId} messages=${messages.size}")
        // Pre-check: count tags in input
        val beforeCount = messages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Text>()
            .sumOf { txt -> UPDATE_VARIABLE_REGEX.findAll(txt.text).count() + STATUS_PLACEHOLDER_REGEX.findAll(txt.text).count() }
        if (beforeCount > 0) android.util.Log.i("StatusPlhd", "▶ transform() found $beforeCount tags in input text")

        val result = try {
            visualTransform(ctx, messages)
        } catch (e: Exception) {
            android.util.Log.wtf("StatusPlhd", "transform() CRASHED", e)
            messages
        }
        // Verification: log if any text part still has unprocessed tags
        val remaining = result.flatMap { it.parts }.filterIsInstance<UIMessagePart.Text>()
            .count { it.text.contains("StatusPlaceHolder") || it.text.contains("UpdateVariable") }
        val placeholderCount = result.flatMap { it.parts }.count { it is UIMessagePart.StatusPlaceholder }
        android.util.Log.i("StatusPlhd", "◀ transform() EXIT unprocessedTags=$remaining statusPlaceholders=$placeholderCount")
        if (remaining > 0) android.util.Log.w("StatusPlhd", "UNPROCESSED tags remaining: $remaining")
        return result
    }

    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val convId = ctx.conversationId
        if (convId == null) {
            android.util.Log.w("StatusPlhd", "visualTransform: conversationId is NULL, skipping")
            return messages
        }

        ensureScriptLoaded(ctx)

        return messages.map { message ->
            if (message.role != MessageRole.ASSISTANT) {
                return@map message
            }

            var variablesChanged = false

            // Step 1: Extract <UpdateVariable> blocks and <Expression> tags
            val partsAfterVars = message.parts.flatMap { part ->
                if (part !is UIMessagePart.Text) return@flatMap listOf(part)

                var text = part.text
                val textLen = text.length
                val resultParts = mutableListOf<UIMessagePart>()
                var tagsFound = 0

                // Process all tags in sequence
                while (true) {
                    val updateMatch = UPDATE_VARIABLE_REGEX.find(text)
                    val exprMatch = EXPRESSION_REGEX.find(text)
                    val barePatchRange = findBareJsonPatch(text)

                    // Collect matches with their start positions
                    data class TagMatch(val type: String, val start: Int, val end: Int)
                    val candidates = listOfNotNull(
                        updateMatch?.let { TagMatch("update", it.range.first, it.range.last + 1) },
                        exprMatch?.let { TagMatch("expr", it.range.first, it.range.last + 1) },
                        barePatchRange?.let { TagMatch("barePatch", it.first, it.last) },
                    )
                    val first = candidates.minByOrNull { it.start }

                    if (first == null) {
                        if (text.isNotBlank()) {
                            resultParts.add(UIMessagePart.Text(text))
                        }
                        break
                    }

                    // Text before this tag
                    val before = text.substring(0, first.start)
                    if (before.isNotBlank()) {
                        resultParts.add(UIMessagePart.Text(before))
                    }

                    val rawContent = text.substring(first.start, first.end)
                    when (first.type) {
                        "update" -> {
                            try {
                                val patchContent = rawContent
                                    .removePrefix("<UpdateVariable>").removeSuffix("</UpdateVariable>")
                                    .replace(Regex("""<JSONPatch>\s*"""), "")
                                    .replace(Regex("""\s*</JSONPatch>"""), "")
                                    .replace(Regex("""<Analysis>.*?</Analysis>""", setOf(RegexOption.DOT_MATCHES_ALL)), "")
                                    .trim()
                                if (patchContent.isNotBlank()) {
                                    val ops = json.decodeFromString<List<JsonPatchOp>>(patchContent)
                                    if (ops.isNotEmpty()) {
                                        store.applyPatch(convId, ops)
                                        variablesChanged = true
                                        android.util.Log.i("StatusPlhd", "  ✓ UpdateVariable: applied ${ops.size} patch ops")
                                    }
                                }
                                tagsFound++
                            } catch (e: Exception) {
                                android.util.Log.e("StatusPlhd", "  ✗ UpdateVariable parse error: ${e.message}")
                                resultParts.add(UIMessagePart.Text(rawContent))
                            }
                        }
                        "barePatch" -> {
                            try {
                                android.util.Log.d("StatusPlhd", "  ✓ Bare patch matched: ${rawContent.take(120)}")
                                val ops = json.decodeFromString<List<JsonPatchOp>>(rawContent)
                                store.applyPatch(convId, ops)
                                variablesChanged = true
                                tagsFound++
                            } catch (e: Exception) {
                                android.util.Log.e("StatusPlhd", "  ✗ Bare patch parse error", e)
                                resultParts.add(UIMessagePart.Text(rawContent))
                            }
                        }
                        "expr" -> {
                            // Extract name from: <Expression name="X"/> or <Expression>X</Expression>
                            val exprName = rawContent
                                .replace(Regex("""<Expression\s+name\s*=\s*"([^"]*)"\s*/>""", RegexOption.IGNORE_CASE)) { it.groupValues[1] }
                                .replace(Regex("""<Expression>(.*?)</Expression>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))) { it.groupValues[1] }
                                .trim()
                            if (exprName.isNotBlank()) {
                                try {
                                    val exprOp = JsonPatchOp(
                                        op = "replace",
                                        path = "/_expression",
                                        value = kotlinx.serialization.json.JsonPrimitive(exprName)
                                    )
                                    store.applyPatch(convId, listOf(exprOp))
                                    variablesChanged = true
                                    tagsFound++
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }
                        else -> { /* unknown tag type, skip */ }
                    }
                    // Advance past this tag
                    text = text.substring(first.end)
                }

                // Diagnostic: summarize what this text part processing did
                if (tagsFound > 0 || textLen > 100) {
                    android.util.Log.d("StatusPlhd", "  Step1 textPart len=$textLen tagsFound=$tagsFound varsChanged=$variablesChanged")
                }

                resultParts
            }

            // Step 2: Replace <StatusPlaceHolderImpl/> with rendered HTML
            var hasPlaceholders = false
            val partsWithPlaceholders = partsAfterVars.flatMap { part ->
                if (part !is UIMessagePart.Text) return@flatMap listOf(part)

                val matches = STATUS_PLACEHOLDER_REGEX.findAll(part.text).toList()
                if (matches.isEmpty()) return@flatMap listOf(part)

                android.util.Log.i("StatusPlhd", "  ✓ StatusPlaceHolderImpl: found ${matches.size} occurrences, rendering HTML")
                hasPlaceholders = true
                var text = part.text
                val newParts = mutableListOf<UIMessagePart>()

                for (match in matches) {
                    val before = text.substring(0, match.range.first)
                    if (before.isNotBlank()) {
                        newParts.add(UIMessagePart.Text(before))
                    }
                    try {
                        val variables = store.toJsObject(convId)
                        val metadata = buildMetadata(ctx, convId)
                        val charPages = buildCharacterPages(variables)
                        val worldHeader = if (charPages.isNotEmpty()) buildWorldHtml(variables) else ""
                        val bodyHtml = if (charPages.isNotEmpty()) {
                            // When multi-character: show world header + hint
                            buildFallbackHtmlDirect(variables.filterKeys { it in WORLD_INFO_KEYS }, metadata)
                        } else {
                            buildFallbackHtmlDirect(variables, metadata)
                        }
                        val fullHtml = if (worldHeader.isNotBlank()) "$worldHeader\n$bodyHtml" else bodyHtml
                        if (fullHtml.isNotBlank()) {
                            newParts.add(
                                UIMessagePart.StatusPlaceholder(
                                    htmlContent = fullHtml,
                                    characterPages = charPages,
                                )
                            )
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        newParts.add(UIMessagePart.Text(match.value))
                    }
                    text = text.substring(match.range.last + 1)
                }

                if (text.isNotBlank()) {
                    newParts.add(UIMessagePart.Text(text))
                }

                newParts.ifEmpty { listOf(part) }
            }

            // Step 3: Re-render existing StatusPlaceholder parts if variables changed
            // If no placeholder exists yet, auto-insert one so state is always visible
            val finalParts = if (variablesChanged) {
                val rendered = partsWithPlaceholders.map { part ->
                    if (part is UIMessagePart.StatusPlaceholder) {
                        try {
                            val variables = store.toJsObject(convId)
                            val metadata = buildMetadata(ctx, convId)
                            val charPages = buildCharacterPages(variables)
                            val worldHeader = if (charPages.isNotEmpty()) buildWorldHtml(variables) else ""
                            val bodyHtml = if (charPages.isNotEmpty()) {
                                buildFallbackHtmlDirect(variables.filterKeys { it in WORLD_INFO_KEYS }, metadata)
                            } else {
                                buildFallbackHtmlDirect(variables, metadata)
                            }
                            val fullHtml = if (worldHeader.isNotBlank()) "$worldHeader\n$bodyHtml" else bodyHtml
                            if (fullHtml.isNotBlank()) {
                                part.copy(htmlContent = fullHtml, characterPages = charPages)
                            } else part
                        } catch (e: Exception) { part }
                    } else part
                }
                // Auto-insert StatusPlaceholder if variables were changed but no placeholder exists
                if (!hasPlaceholders) {
                    try {
                        val variables = store.toJsObject(convId)
                        val metadata = buildMetadata(ctx, convId)
                        val charPages = buildCharacterPages(variables)
                        val worldHeader = if (charPages.isNotEmpty()) buildWorldHtml(variables) else ""
                        val bodyHtml = if (charPages.isNotEmpty()) {
                            buildFallbackHtmlDirect(variables.filterKeys { it in WORLD_INFO_KEYS }, metadata)
                        } else {
                            buildFallbackHtmlDirect(variables, metadata)
                        }
                        val fullHtml = if (worldHeader.isNotBlank()) "$worldHeader\n$bodyHtml" else bodyHtml
                        if (fullHtml.isNotBlank()) {
                            val sp = UIMessagePart.StatusPlaceholder(htmlContent = fullHtml, characterPages = charPages)
                            rendered + sp
                        } else rendered
                    } catch (e: Exception) { rendered }
                } else rendered
            } else {
                partsWithPlaceholders
            }

            message.copy(parts = finalParts).also { resultMsg ->
                val spCount = resultMsg.parts.count { it is UIMessagePart.StatusPlaceholder }
                if (variablesChanged || spCount > 0) {
                    android.util.Log.i("StatusPlhd", "  → message result: varsChanged=$variablesChanged hasPlaceholders=$hasPlaceholders statusParts=$spCount")
                }
            }
        }
    }

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val convId = ctx.conversationId ?: return messages

        // Only re-render the LAST message's StatusPlaceholder with the final state.
        // Older messages keep their snapshot-of-that-moment HTML — otherwise
        // every historical status panel shows the latest values (visual duplication).
        val lastIndex = messages.lastIndex
        return messages.mapIndexed { idx, message ->
            if (idx != lastIndex) return@mapIndexed message
            message.copy(
                parts = message.parts.map { part ->
                    if (part is UIMessagePart.StatusPlaceholder) {
                        try {
                            val variables = store.toJsObject(convId)
                            val metadata = buildMetadata(ctx, convId)
                            val html = renderer.render(variables, metadata)
                            part.copy(htmlContent = html.ifBlank { part.htmlContent })
                        } catch (e: Exception) {
                            part
                        }
                    } else part
                }
            )
        }
    }

    /**
     * Fast synchronous HTML builder — no QuickJS. Used during streaming to avoid
     * blocking message display. The full JS render happens in onGenerationFinish.
     */
    private fun buildFallbackHtmlDirect(variables: Map<String, Any?>, metadata: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append("<div style=\"font-family:sans-serif;font-size:13px;line-height:1.5;\">")
        metadata["expression"]?.takeIf { it.isNotBlank() }?.let { expr ->
            sb.append("<div style=\"font-size:16px;font-weight:600;margin-bottom:4px;\">")
            sb.append(expr.replace("&","&amp;").replace("<","&lt;"))
            sb.append("</div>")
        }
        if (variables.isNotEmpty()) {
            appendRows(sb, variables)
        }
        sb.append("</div>")
        return sb.toString()
    }

    private fun appendRows(sb: StringBuilder, map: Map<String, Any?>, indent: Int = 0) {
        for ((key, value) in map) {
            when (value) {
                is Map<*, *> -> {
                    sb.append("<div style=\"font-weight:600;margin-top:4px;\">${esc(key)}</div>")
                    sb.append("<div style=\"margin-left:${8 + indent * 8}px;\">")
                    @Suppress("UNCHECKED_CAST")
                    appendRows(sb, value as Map<String, Any?>, indent + 1)
                    sb.append("</div>")
                }
                is List<*> -> {
                    sb.append("<div><b>${esc(key)}:</b> ${esc(value.joinToString(", "))}</div>")
                }
                else -> {
                    val displayValue = value?.toString() ?: "—"
                    sb.append("<div><b>${esc(key)}:</b> ${esc(displayValue)}</div>")
                }
            }
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // region Character Paging

    /** Keys that represent world/scene info (not characters). */
    private val WORLD_INFO_KEYS = setOf("世界", "_expression")

    /**
     * Detect character groups from variables and generate per-character HTML pages.
     * A "character" is a top-level key whose value is a Map with 2+ entries,
     * and whose name is NOT in [WORLD_INFO_KEYS].
     */
    private fun buildCharacterPages(variables: Map<String, Any?>): List<UIMessagePart.CharacterStatusPage> {
        val characters = variables.entries
            .filter { (key, value) ->
                key !in WORLD_INFO_KEYS && value is Map<*, *> && value.size >= 2
            }
            .sortedBy { (key, _) -> key } // stable ordering

        if (characters.size < 2) return emptyList() // fall back to legacy full-HTML

        @Suppress("UNCHECKED_CAST")
        return characters.map { (name, value) ->
            val sb = StringBuilder()
            sb.append("<div style=\"font-family:sans-serif;font-size:13px;line-height:1.6;\">")
            sb.append("<div style=\"font-size:15px;font-weight:700;margin-bottom:6px;color:#inherit;\">${esc(name)}</div>")
            appendRows(sb, value as Map<String, Any?>, indent = 0)
            sb.append("</div>")
            UIMessagePart.CharacterStatusPage(name = name, html = sb.toString())
        }
    }

    /** Build compact world-info header HTML (time + location). */
    private fun buildWorldHtml(variables: Map<String, Any?>): String {
        @Suppress("UNCHECKED_CAST")
        val world = variables["世界"] as? Map<String, Any?> ?: return ""
        val time = (world["当前时间"] as? String) ?: (world["time"] as? String) ?: ""
        val place = (world["当前地点"] as? String) ?: (world["place"] as? String) ?: (world["location"] as? String) ?: ""
        if (time.isBlank() && place.isBlank()) return ""
        val sb = StringBuilder()
        sb.append("<div style=\"font-family:sans-serif;font-size:12px;line-height:1.4;display:flex;flex-wrap:wrap;gap:8px;\">")
        if (time.isNotBlank()) sb.append("<span>🕐 ${esc(time)}</span>")
        if (place.isNotBlank()) sb.append("<span>📍 ${esc(place)}</span>")
        sb.append("</div>")
        return sb.toString()
    }

    // endregion

    // region Helpers

    private fun ensureScriptLoaded(ctx: TransformerContext) {
        val key = ctx.assistant.statusRenderJs?.hashCode() ?: return
        if (loadedScriptKey != key) {
            // Attempt to load CSS from the card's tavern JSON extensions
            val css = extractCssFromCard(ctx)
            ctx.assistant.statusRenderJs?.let { renderer.loadScript(it, css) }
            loadedScriptKey = key
        }
    }

    /**
     * Build metadata map for JS renderer.
     */
    private fun buildMetadata(ctx: TransformerContext, convId: Uuid): Map<String, String> {
        val metadata = mutableMapOf<String, String>()
        metadata["char_name"] = ctx.assistant.name
        metadata["user_name"] = ctx.settings.displaySetting.userNickname.ifBlank { "User" }
        // Get current expression from variable store
        val vars = store.getValue(convId)
        vars["_expression"]?.let { expr ->
            if (expr is kotlinx.serialization.json.JsonPrimitive && expr.isString) {
                metadata["expression"] = expr.content
            }
        }
        return metadata
    }

    /**
     * Extract CSS from the character card's extensions data.
     * SillyTavern cards may have CSS in extensions.css or extensions.status_css.
     */
    private fun extractCssFromCard(ctx: TransformerContext): String? {
        val cardJson = ctx.assistant.tavernCardJson ?: return null
        return try {
            val root = json.parseToJsonElement(cardJson)
            // V2/V3: data.extensions
            val extensions = root.jsonObject["data"]?.jsonObject?.get("extensions")?.jsonObject
            // Also check V1: top-level extensions (less common)
            val topExtensions = root.jsonObject["extensions"]?.jsonObject
            val ext = extensions ?: topExtensions ?: return null

            // Try multiple possible CSS keys (SillyTavern stores them in various places)
            ext["css"]?.let { p -> if (p is kotlinx.serialization.json.JsonPrimitive && p.isString) return p.content }
            ext["status_css"]?.let { p -> if (p is kotlinx.serialization.json.JsonPrimitive && p.isString) return p.content }
            ext["status"]?.jsonObject?.get("css")?.let { p -> if (p is kotlinx.serialization.json.JsonPrimitive && p.isString) return p.content }
            ext["status"]?.jsonObject?.get("status_css")?.let { p -> if (p is kotlinx.serialization.json.JsonPrimitive && p.isString) return p.content }
            null
        } catch (e: Exception) {
            null
        }
    }

    // endregion
}
