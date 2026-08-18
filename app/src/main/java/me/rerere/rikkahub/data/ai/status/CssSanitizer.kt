package me.rerere.rikkahub.data.ai.status

/**
 * 清洗用户提供的 CSS，阻断逃出 <style> 块的注入。
 * 浏览器 HTML 解析器在 <style> 内只识别 "</style"（不区分大小写）作为结束标记，
 * 核心是破坏这个序列。CSS 中正常情况下不会出现 "</"，把所有 "</" 替换为
 * "/* */ " 是无害的（CSS 注释，几乎不影响任何合法选择器或值）。
 */
object CssSanitizer {
    fun sanitize(css: String): String =
        Regex("</", RegexOption.IGNORE_CASE).replace(css, "/* */ ")
}
