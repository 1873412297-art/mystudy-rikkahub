package me.rerere.rikkahub.ui.components.richtext.st

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.ai.status.CssSanitizer
import me.rerere.rikkahub.ui.components.richtext.inlineKatexFontSources
import me.rerere.rikkahub.ui.components.richtext.loadBundledKatexFontData

private val json = Json {
    encodeDefaults = true
}

/** CSS 变量默认值：调用方（MarkdownBlock 传 Material 色值）未提供时回退到中性值。 */
private val DEFAULT_CSS_VARIABLES = mapOf(
    "CSS_VAR_BG" to "transparent",
    "CSS_VAR_SURFACE" to "rgba(127,127,127,.08)",
    "CSS_VAR_SURFACE_VARIANT" to "rgba(80,120,255,.10)",
    "CSS_VAR_TEXT" to "inherit",
    "CSS_VAR_TEXT_SECONDARY" to "inherit",
    "CSS_VAR_BORDER" to "rgba(127,127,127,.25)",
    "CSS_VAR_ACCENT" to "#4a90d9",
)

/**
 * 从 assets 读取 st-message.html 模板，把本地 vendor 库（assets/html/vendor/）内联为
 * <script>/<style> 块后注入消息 JSON。运行时无 CDN/file:// 依赖。
 */
internal fun buildStableMessageHtml(
    context: Context,
    message: StableDomMessage,
    cssVariables: Map<String, String> = emptyMap(),
    extraCss: String? = null,
): String {
    val template = context.assets
        .open("html/st-message.html")
        .bufferedReader()
        .use { it.readText() }
    val vendorScripts = context.assets.list("html/vendor")
        .orEmpty()
        .filter { it.endsWith(".js") }
        .sorted()
        .joinToString("\n") { name ->
            val code = context.assets.open("html/vendor/$name").bufferedReader().use { it.readText() }
            "<script>$code</script>"
        }
    val vendorStyles = context.assets.list("html/vendor")
        .orEmpty()
        .filter { it.endsWith(".css") }
        .sorted()
        .joinToString("\n") { name ->
            val css = context.assets.open("html/vendor/$name").bufferedReader().use { it.readText() }
            val localizedCss = if (name == "katex.min.css") {
                val fonts = loadBundledKatexFontData(context)
                inlineKatexFontSources(css, fonts::get)
            } else {
                css
            }
            "<style>$localizedCss</style>"
        }
    return buildStableMessageHtml(message, template, vendorScripts, vendorStyles, cssVariables, extraCss)
}

/** 单遍占位符正则：一次遍历替换所有 {{XXX}}，替换值不会再次被扫描（双向防碰撞）。 */
private val PLACEHOLDER_REGEX = Regex("\\{\\{([A-Z_0-9]+)\\}\\}")

/** 纯函数版本：给定模板与内联产物注入（JVM 测试用）。 */
internal fun buildStableMessageHtml(
    message: StableDomMessage,
    template: String,
    vendorScripts: String = "",
    vendorStyles: String = "",
    cssVariables: Map<String, String> = emptyMap(),
    extraCss: String? = null,
): String {
    // 安全嵌入 JSON：HTML 对 script 闭合不区分大小写，把 < 转义为 \u003c（JSON 字符串内的
    // \u003c 会被解析回 <），彻底阻断 </script / </SCRIPT / </ScRiPt 等变体逃逸。
    val messageJson = json.encodeToString(message).replace("<", "\\u003c")
    // 单遍替换：消息文本/卡 CSS 中出现的 "{{XXX}}" 字样不会被后序替换污染，
    // 替换值（含 CSS 中的 {{MESSAGE_JSON}} 等）也不会被再次扫描。
    val values = (DEFAULT_CSS_VARIABLES + cssVariables) + mapOf(
        "VENDOR_LIBS" to vendorScripts,
        "VENDOR_STYLES" to vendorStyles,
        "MESSAGE_JSON" to messageJson,
        "EXTRA_CSS" to (extraCss?.let { CssSanitizer.sanitize(it) } ?: ""),
    )
    return PLACEHOLDER_REGEX.replace(template) { match ->
        values[match.groupValues[1]] ?: match.value
    }
}
