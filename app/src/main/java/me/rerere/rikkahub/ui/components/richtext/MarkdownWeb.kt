package me.rerere.rikkahub.ui.components.richtext

import android.content.Context
import androidx.compose.material3.ColorScheme
import me.rerere.rikkahub.utils.base64Encode
import me.rerere.rikkahub.utils.escapeHtml
import me.rerere.rikkahub.utils.toCssHex
import me.rerere.rikkahub.ui.components.richtext.runtime.buildTavernRuntimeScript
import me.rerere.rikkahub.ui.components.richtext.st.BundledVendorAssets

private data class MarkdownPreviewAssets(
    val template: String,
    val vendorScripts: String,
    val vendorStyles: String,
)

private val katexFontSourceRegex = Regex(
    """src:url\(fonts/([A-Za-z0-9_-]+)\.woff2\)[^}]+""",
)

@Volatile
private var cachedMarkdownPreviewAssets: MarkdownPreviewAssets? = null

@Volatile
private var cachedKatexFontData: Map<String, String>? = null

internal fun inlineKatexFontSources(
    css: String,
    fontData: (String) -> String?,
): String = katexFontSourceRegex.replace(css) { match ->
    val name = match.groupValues[1]
    val encoded = requireNotNull(fontData(name)) { "Missing bundled KaTeX font: $name" }
    "src:url(data:font/woff2;base64,$encoded) format(\"woff2\")"
}

internal fun loadBundledKatexFontData(context: Context): Map<String, String> {
    cachedKatexFontData?.let { return it }
    return synchronized(MarkdownPreviewAssets::class.java) {
        cachedKatexFontData ?: context.assets.open("html/vendor/katex-fonts.b64")
            .bufferedReader()
            .useLines { lines ->
                lines.filter { it.isNotBlank() }
                    .associate { it.substringBefore('=') to it.substringAfter('=') }
            }
            .also { cachedKatexFontData = it }
    }
}

private fun loadMarkdownPreviewAssets(context: Context): MarkdownPreviewAssets {
    cachedMarkdownPreviewAssets?.let { return it }
    return synchronized(MarkdownPreviewAssets::class.java) {
        cachedMarkdownPreviewAssets ?: MarkdownPreviewAssets(
            template = context.assets.open("html/mark.html").bufferedReader().use { it.readText() },
            vendorScripts = BundledVendorAssets.scripts(context),
            vendorStyles = BundledVendorAssets.styles(context),
        ).also { cachedMarkdownPreviewAssets = it }
    }
}

/**
 * Build HTML page for markdown preview with support for:
 * - Markdown rendering via marked.js
 * - LaTeX math via KaTeX
 * - Mermaid diagrams
 * - Syntax highlighting via highlight.js
 * - Auto-detection of HTML content for direct rendering
 */
fun buildMarkdownPreviewHtml(context: Context, markdown: String, colorScheme: ColorScheme): String {
    val assets = loadMarkdownPreviewAssets(context)
    val normalizedMarkdown = normalizeRichTextContent(markdown)

    return assets.template
        .replace("{{VENDOR_LIBS}}", assets.vendorScripts)
        .replace("{{VENDOR_STYLES}}", assets.vendorStyles)
        .replace("{{TAVERN_RUNTIME}}", buildTavernRuntimeScript())
        .replace("{{MARKDOWN_BASE64}}", normalizedMarkdown.base64Encode())
        .replace("{{BACKGROUND_COLOR}}", colorScheme.background.toCssHex())
        .replace("{{ON_BACKGROUND_COLOR}}", colorScheme.onBackground.toCssHex())
        .replace("{{SURFACE_COLOR}}", colorScheme.surface.toCssHex())
        .replace("{{ON_SURFACE_COLOR}}", colorScheme.onSurface.toCssHex())
        .replace("{{SURFACE_VARIANT_COLOR}}", colorScheme.surfaceVariant.toCssHex())
        .replace("{{ON_SURFACE_VARIANT_COLOR}}", colorScheme.onSurfaceVariant.toCssHex())
        .replace("{{PRIMARY_COLOR}}", colorScheme.primary.toCssHex())
        .replace("{{OUTLINE_COLOR}}", colorScheme.outline.toCssHex())
        .replace("{{OUTLINE_VARIANT_COLOR}}", colorScheme.outlineVariant.toCssHex())
}

/**
 * Build HTML page for pure HTML preview with sandboxed iframe rendering.
 * Used when the user explicitly wants to view content as rendered HTML.
 */
fun buildHtmlPreviewHtml(context: Context, html: String, colorScheme: ColorScheme): String {
    val htmlTemplate = context.assets.open("html/html_viewer.html").bufferedReader().use { it.readText() }

    return htmlTemplate
        .replace("{{HTML_BASE64}}", html.base64Encode())
        .replace("{{BACKGROUND_COLOR}}", colorScheme.background.toCssHex())
        .replace("{{ON_BACKGROUND_COLOR}}", colorScheme.onBackground.toCssHex())
        .replace("{{SURFACE_COLOR}}", colorScheme.surface.toCssHex())
        .replace("{{ON_SURFACE_COLOR}}", colorScheme.onSurface.toCssHex())
        .replace("{{SURFACE_VARIANT_COLOR}}", colorScheme.surfaceVariant.toCssHex())
        .replace("{{ON_SURFACE_VARIANT_COLOR}}", colorScheme.onSurfaceVariant.toCssHex())
        .replace("{{PRIMARY_COLOR}}", colorScheme.primary.toCssHex())
        .replace("{{OUTLINE_COLOR}}", colorScheme.outline.toCssHex())
        .replace("{{OUTLINE_VARIANT_COLOR}}", colorScheme.outlineVariant.toCssHex())
}

/**
 * Build HTML page for SillyTavern character card content preview.
 * Optimized for rendering SillyTavern character card fields (description, personality, scenario, etc.)
 * with support for:
 * - HTML tags (font, span, div, etc.)
 * - Markdown formatting
 * - SillyTavern macros ({{user}}, {{char}})
 * - Action markers (*action*)
 * - KaTeX math
 * - Mermaid diagrams
 */
fun buildTavernCardPreviewHtml(context: Context, content: String, colorScheme: ColorScheme): String {
    val htmlTemplate = runCatching {
        context.assets.open("html/tavern_card.html").bufferedReader().use { it.readText() }
    }.getOrElse {
        // Fallback: minimal HTML template for preview mode or when asset is missing
        return buildMinimalPreviewHtml(content, colorScheme)
    }

    return htmlTemplate
        .replace("{{CONTENT_BASE64}}", content.base64Encode())
        .replace("{{BACKGROUND_COLOR}}", colorScheme.background.toCssHex())
        .replace("{{ON_BACKGROUND_COLOR}}", colorScheme.onBackground.toCssHex())
        .replace("{{SURFACE_COLOR}}", colorScheme.surface.toCssHex())
        .replace("{{ON_SURFACE_COLOR}}", colorScheme.onSurface.toCssHex())
        .replace("{{SURFACE_VARIANT_COLOR}}", colorScheme.surfaceVariant.toCssHex())
        .replace("{{ON_SURFACE_VARIANT_COLOR}}", colorScheme.onSurfaceVariant.toCssHex())
        .replace("{{PRIMARY_COLOR}}", colorScheme.primary.toCssHex())
        .replace("{{OUTLINE_COLOR}}", colorScheme.outline.toCssHex())
        .replace("{{OUTLINE_VARIANT_COLOR}}", colorScheme.outlineVariant.toCssHex())
}

/**
 * Build a minimal HTML page for preview without requiring assets.
 * Used as fallback when tavern_card.html asset is not accessible (e.g., Compose Preview).
 */
private fun buildMinimalPreviewHtml(content: String, colorScheme: ColorScheme): String {
    val bg = colorScheme.background.toCssHex()
    val text = colorScheme.onBackground.toCssHex()
    val primary = colorScheme.primary.toCssHex()
    // Simple fallback HTML for preview mode - wraps content in basic styled page
    val escaped = content
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\n\n", "</p><p>").replace("\n", "<br>")
    return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><style>" +
        "*{box-sizing:border-box}body{font-family:-apple-system,sans-serif;line-height:1.7;color:$text;margin:0;padding:16px;background:$bg;font-size:15px}" +
        "p{margin-bottom:14px}h1,h2,h3{color:$text}a{color:$primary}" +
        "code{background:${text}18;padding:.15em .4em;border-radius:4px;color:$primary;font-size:.88em}" +
        ".tavern-macro{background:${primary}22;color:$primary;padding:1px 6px;border-radius:4px;font-family:monospace}" +
        "</style></head><body><p>$escaped</p></body></html>"
}

/**
 * Build a complete character card viewer HTML page.
 * Renders the full character card with header, sections, and formatted content.
 */
fun buildCharacterCardViewerHtml(
    context: Context,
    name: String,
    description: String,
    personality: String,
    scenario: String,
    firstMes: String,
    mesExample: String,
    creatorNotes: String,
    systemPrompt: String,
    postHistoryInstructions: String,
    alternateGreetings: List<String>,
    tags: List<String>,
    creator: String,
    characterVersion: String,
    colorScheme: ColorScheme
): String {
    val htmlTemplate = runCatching {
        context.assets.open("html/tavern_card.html").bufferedReader().use { it.readText() }
    }.getOrElse {
        return buildMinimalPreviewHtml(
            listOfNotNull(
                description, personality, scenario, firstMes, mesExample
            ).joinToString("\n\n"),
            colorScheme
        )
    }

    // Build the full card content — ST-style sections with proper HTML wrapping
    // 安全：所有用户字段（来自第三方角色卡 JSON/PNG）拼接到 HTML 模板前都需 escapeHtml，
    // 防止恶意角色卡通过 description/name/tags 等字段注入 <script>。
    // 字段内的 markdown 语法仍由 markdown-it 渲染（html:false 模式下安全）。
    val cardContent = buildString {
        // ═══ Header ═══
        appendLine("<div class=\"card-header\">")
        appendLine("<h1>${name.escapeHtml()}</h1>")
        if (creator.isNotBlank()) {
            appendLine("<div class=\"meta\"><span>by <strong>${creator.escapeHtml()}</strong></span></div>")
        }
        if (characterVersion.isNotBlank()) {
            appendLine("<div class=\"meta\"><span style=\"font-size:0.85em;opacity:0.7\">v${characterVersion.escapeHtml()}</span></div>")
        }
        if (tags.isNotEmpty()) {
            appendLine("<div class=\"tags\">")
            tags.forEach { tag -> appendLine("<span class=\"tag-badge\">${tag.escapeHtml()}</span>") }
            appendLine("</div>")
        }
        appendLine("</div>")

        // ═══ Creator Notes ═══
        if (creatorNotes.isNotBlank()) {
            appendLine("<div class=\"creator-notes-box\">")
            appendLine("<div class=\"section-title\">Creator Notes</div>")
            appendLine("<div class=\"section-body\">")
            appendLine(creatorNotes.escapeHtml())
            appendLine("</div></div>")
        }

        // ═══ Description ═══
        if (description.isNotBlank()) {
            appendLine("<div class=\"card-section\">")
            appendLine("<div class=\"section-title\">Description</div>")
            appendLine("<div class=\"section-body\">")
            appendLine(description.escapeHtml())
            appendLine("</div></div>")
        }

        // ═══ Personality ═══
        if (personality.isNotBlank()) {
            appendLine("<div class=\"card-section\">")
            appendLine("<div class=\"section-title\">Personality</div>")
            appendLine("<div class=\"section-body\">")
            appendLine(personality.escapeHtml())
            appendLine("</div></div>")
        }

        // ═══ Scenario ═══
        if (scenario.isNotBlank()) {
            appendLine("<div class=\"card-section\">")
            appendLine("<div class=\"section-title\">Scenario</div>")
            appendLine("<div class=\"section-body\">")
            appendLine(scenario.escapeHtml())
            appendLine("</div></div>")
        }

        // ═══ System Prompt ═══
        if (systemPrompt.isNotBlank()) {
            appendLine("<div class=\"card-section\">")
            appendLine("<div class=\"section-title\">System Prompt</div>")
            appendLine("<div class=\"section-body\">")
            appendLine("```")
            appendLine(systemPrompt.escapeHtml())
            appendLine("```")
            appendLine("</div></div>")
        }

        // ═══ Post-history Instructions ═══
        if (postHistoryInstructions.isNotBlank()) {
            appendLine("<div class=\"card-section\">")
            appendLine("<div class=\"section-title\">Post-History Instructions</div>")
            appendLine("<div class=\"section-body\">")
            appendLine(postHistoryInstructions.escapeHtml())
            appendLine("</div></div>")
        }

        // ═══ First Message ═══
        if (firstMes.isNotBlank()) {
            appendLine("<div class=\"card-section\">")
            appendLine("<div class=\"section-title\">First Message</div>")
            appendLine("<div class=\"section-body\">")
            appendLine(firstMes.escapeHtml())
            appendLine("</div></div>")
        }

        // ═══ Alternate Greetings ═══
        if (alternateGreetings.isNotEmpty()) {
            appendLine("<div class=\"card-section\">")
            appendLine("<div class=\"section-title\">Alternate Greetings</div>")
            appendLine("<div class=\"section-body\">")
            alternateGreetings.forEach { greeting ->
                appendLine("<div class=\"dialogue-block\">")
                appendLine(greeting.escapeHtml())
                appendLine("</div>")
            }
            appendLine("</div></div>")
        }

        // ═══ Example Messages ═══
        if (mesExample.isNotBlank()) {
            appendLine("<div class=\"card-section\">")
            appendLine("<div class=\"section-title\">Example Messages</div>")
            appendLine("<div class=\"section-body\">")
            appendLine(mesExample.escapeHtml())
            appendLine("</div></div>")
        }
    }

    return htmlTemplate
        .replace("{{CONTENT_BASE64}}", cardContent.base64Encode())
        .replace("{{BACKGROUND_COLOR}}", colorScheme.background.toCssHex())
        .replace("{{ON_BACKGROUND_COLOR}}", colorScheme.onBackground.toCssHex())
        .replace("{{SURFACE_COLOR}}", colorScheme.surface.toCssHex())
        .replace("{{ON_SURFACE_COLOR}}", colorScheme.onSurface.toCssHex())
        .replace("{{SURFACE_VARIANT_COLOR}}", colorScheme.surfaceVariant.toCssHex())
        .replace("{{ON_SURFACE_VARIANT_COLOR}}", colorScheme.onSurfaceVariant.toCssHex())
        .replace("{{PRIMARY_COLOR}}", colorScheme.primary.toCssHex())
        .replace("{{OUTLINE_COLOR}}", colorScheme.outline.toCssHex())
        .replace("{{OUTLINE_VARIANT_COLOR}}", colorScheme.outlineVariant.toCssHex())
}
