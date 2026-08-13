package me.rerere.rikkahub.ui.components.richtext.st

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.ai.status.CssSanitizer

private val json = Json {
    encodeDefaults = true
}

private const val MESSAGE_JSON_PLACEHOLDER = "{{MESSAGE_JSON}}"
private const val VENDOR_LIBS_PLACEHOLDER = "{{VENDOR_LIBS}}"
private const val VENDOR_STYLES_PLACEHOLDER = "{{VENDOR_STYLES}}"
private const val EXTRA_CSS_PLACEHOLDER = "{{EXTRA_CSS}}"

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
            "<style>$css</style>"
        }
    return buildStableMessageHtml(message, template, vendorScripts, vendorStyles, cssVariables, extraCss)
}

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
    val variablesInjected = (DEFAULT_CSS_VARIABLES + cssVariables).entries.fold(template) { acc, (key, value) ->
        acc.replace("{{$key}}", value)
    }
    // 顺序要求：MESSAGE_JSON 必须先于 EXTRA_CSS 替换——EXTRA_CSS 来自角色卡 CSS，若其中
    // 含 "{{MESSAGE_JSON}}" 字样，后替换的 MESSAGE_JSON 会污染已注入的 CSS。
    return variablesInjected
        .replace(VENDOR_LIBS_PLACEHOLDER, vendorScripts)
        .replace(VENDOR_STYLES_PLACEHOLDER, vendorStyles)
        .replace(MESSAGE_JSON_PLACEHOLDER, messageJson)
        .replace(EXTRA_CSS_PLACEHOLDER, extraCss?.let { CssSanitizer.sanitize(it) } ?: "")
}
