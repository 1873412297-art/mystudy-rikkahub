package me.rerere.rikkahub.data.ai.status

/**
 * 状态块标签族的单一事实来源。
 *
 * 标签族（大小写不敏感）：`status_block` / `statusblock` / `statusbar` / `status` / `status!` / `状态栏`。
 * 各消费方（[StatusBlockExtractor]、Markdown 分段路由、RichTextRenderPolicy）统一从这里取正则，
 * 避免同一语义在多处重复定义、扩展标签族时漏改。
 */
object StatusTags {

    /** 可直接嵌入 Regex 的标签名 alternation。长名在前保证整体匹配，`status!?` 同时覆盖 `status` 与 `status!`。 */
    const val NAME_ALTERNATION: String = "status_block|statusblock|statusbar|status!?|状态栏"

    /** 开标签：`<status_block>` 等，标签名两侧允许空白。 */
    fun openTagRegex(): Regex =
        Regex("<\\s*(?:$NAME_ALTERNATION)\\s*>", RegexOption.IGNORE_CASE)

    /** 闭标签：`</status_block>` 等，标签名两侧允许空白。 */
    fun closeTagRegex(): Regex =
        Regex("</\\s*(?:$NAME_ALTERNATION)\\s*>", RegexOption.IGNORE_CASE)

    /** 段落级：从开标签开始匹配，缺失闭标签时延伸到文本末尾（用于分段/路由）。 */
    fun segmentRegex(): Regex =
        Regex("<(?:$NAME_ALTERNATION)>[\\s\\S]*?(?:</(?:$NAME_ALTERNATION)>|$)", RegexOption.IGNORE_CASE)

    /** 整块包裹：整段内容恰好由一个状态块包裹（用于提取展示文本）。 */
    fun wrapperRegex(): Regex =
        Regex(
            "^\\s*<(?:$NAME_ALTERNATION)>\\s*([\\s\\S]*?)(?:</(?:$NAME_ALTERNATION)>\\s*)?$",
            RegexOption.IGNORE_CASE,
        )
}
