package me.rerere.rikkahub.data.ai.transformers

/**
 * 显示层残留 user 名替换。
 *
 * 生成侧 `{{user}}` 宏在未设置昵称时兜底为 "user"，模型会把 "user" 当作用户名
 * 写进中文叙事正文并持久化（如 `user没有丝毫犹豫`）。此函数只在显示路径使用，
 * 把正文中残留的独立 "user"/"User" 替换为展示名（昵称或兜底「你」），不改动消息数据。
 *
 * 规则保守，防误伤：
 * - 仅匹配独立单词 user/User（两侧不能紧跟 ASCII 字母/数字/下划线）。
 * - 仅当至少一侧相邻字符是 CJK 统一表意文字或中文标点时才替换；
 *   纯 ASCII 上下文（如英文句子 "the user clicks"）一律保留。
 * - 串首/串尾算单词边界，但不构成 CJK 上下文。
 */
private val USER_WORD_REGEX = Regex("""(?<![A-Za-z0-9_])[uU]ser(?![A-Za-z0-9_])""")

private const val CJK_PUNCTUATION = "，。、；：？！“”‘’（）《》…—"

private fun isCjkContextChar(c: Char): Boolean =
    c in '一'..'鿿' ||           // CJK Unified Ideographs
        c in '㐀'..'䶿' ||       // CJK Unified Ideographs Extension A
        c in CJK_PUNCTUATION

fun replaceResidualUserName(text: String, displayName: String): String {
    if (displayName.isEmpty()) return text
    if (!text.contains("user") && !text.contains("User")) return text
    return USER_WORD_REGEX.replace(text) { match ->
        val start = match.range.first
        val end = match.range.last
        val beforeCjk = start > 0 && isCjkContextChar(text[start - 1])
        val afterCjk = end < text.length - 1 && isCjkContextChar(text[end + 1])
        if (beforeCjk || afterCjk) displayName else match.value
    }
}
