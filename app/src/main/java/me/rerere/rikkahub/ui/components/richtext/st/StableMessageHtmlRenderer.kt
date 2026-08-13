package me.rerere.rikkahub.ui.components.richtext.st

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json {
    encodeDefaults = true
}

private const val MESSAGE_JSON_PLACEHOLDER = "{{MESSAGE_JSON}}"
private const val VENDOR_LIBS_PLACEHOLDER = "{{VENDOR_LIBS}}"
private const val VENDOR_STYLES_PLACEHOLDER = "{{VENDOR_STYLES}}"

/**
 * 从 assets 读取 st-message.html 模板，把本地 vendor 库（assets/html/vendor/）内联为
 * <script>/<style> 块后注入消息 JSON。运行时无 CDN/file:// 依赖。
 */
internal fun buildStableMessageHtml(context: Context, message: StableDomMessage): String {
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
    return buildStableMessageHtml(message, template, vendorScripts, vendorStyles)
}

/** 纯函数版本：给定模板与内联产物注入（JVM 测试用）。 */
internal fun buildStableMessageHtml(
    message: StableDomMessage,
    template: String,
    vendorScripts: String = "",
    vendorStyles: String = "",
): String {
    val messageJson = json.encodeToString(message).replace("</script>", "<\\/script>")
    return template
        .replace(VENDOR_LIBS_PLACEHOLDER, vendorScripts)
        .replace(VENDOR_STYLES_PLACEHOLDER, vendorStyles)
        .replace("{{CSS_VAR_BG}}", "transparent")
        .replace("{{CSS_VAR_SURFACE}}", "rgba(127,127,127,.08)")
        .replace("{{CSS_VAR_SURFACE_VARIANT}}", "rgba(80,120,255,.10)")
        .replace("{{CSS_VAR_TEXT}}", "inherit")
        .replace("{{CSS_VAR_TEXT_SECONDARY}}", "inherit")
        .replace("{{CSS_VAR_BORDER}}", "rgba(127,127,127,.25)")
        .replace("{{CSS_VAR_ACCENT}}", "#4a90d9")
        .replace("{{EXTRA_CSS}}", "")
        .replace(MESSAGE_JSON_PLACEHOLDER, messageJson)
}
