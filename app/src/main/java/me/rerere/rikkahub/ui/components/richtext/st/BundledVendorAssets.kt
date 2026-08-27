package me.rerere.rikkahub.ui.components.richtext.st

import android.content.Context
import me.rerere.rikkahub.ui.components.richtext.inlineKatexFontSources
import me.rerere.rikkahub.ui.components.richtext.loadBundledKatexFontData

/**
 * assets/html/vendor/ 内联产物的进程级缓存。
 * vendor 文件打包进 APK，运行期不变，缓存永不失效。
 * katex.min.css 的字体 url 在首次加载时替换为 b64 内联 data（无 CDN/font:// 依赖）。
 */
internal object BundledVendorAssets {
    @Volatile
    private var cachedScripts: String? = null

    @Volatile
    private var cachedStyles: String? = null

    fun scripts(context: Context): String {
        cachedScripts?.let { return it }
        return synchronized(this) {
            cachedScripts ?: context.assets.list("html/vendor")
                .orEmpty()
                .filter { it.endsWith(".js") }
                .sorted()
                .joinToString("\n") { name ->
                    val code = context.assets.open("html/vendor/$name").bufferedReader().use { it.readText() }
                    "<script>$code</script>"
                }
                .also { cachedScripts = it }
        }
    }

    fun styles(context: Context): String {
        cachedStyles?.let { return it }
        return synchronized(this) {
            cachedStyles ?: context.assets.list("html/vendor")
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
                .also { cachedStyles = it }
        }
    }
}
