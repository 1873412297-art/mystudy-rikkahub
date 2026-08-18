package me.rerere.rikkahub.data.ai.status

/**
 * 状态变量 fallback HTML 的统一构建器。
 *
 * 同时被 [StatusRenderer]（QuickJS 不可用/渲染失败时）与
 * StatusPlaceholderTransformer（流式阶段同步构建）使用，避免两份近似实现漂移。
 *
 * 安全约定：所有来自状态变量的 key/value 一律做 HTML 转义（& < >），
 * 防止角色卡/模型输出中的 HTML 注入到 WebView。
 */
object StatusFallbackHtml {

    /** 根容器内联样式。 */
    private const val ROOT_STYLE = "font-family:sans-serif;font-size:13px;line-height:1.5;"

    fun build(variables: Map<String, Any?>, metadata: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append("<div style=\"$ROOT_STYLE\">")
        metadata["expression"]?.takeIf { it.isNotBlank() }?.let { expr ->
            sb.append("<div style=\"font-size:16px;font-weight:600;margin-bottom:4px;\">")
            sb.append(escapeHtml(expr))
            sb.append("</div>")
        }
        if (variables.isNotEmpty()) {
            appendRows(sb, variables)
        }
        sb.append("</div>")
        return sb.toString()
    }

    /** 供角色分页等场景复用：把 map 渲染为多行 HTML 追加到 [sb]。 */
    internal fun appendRows(sb: StringBuilder, map: Map<String, Any?>, indent: Int = 0) {
        for ((key, value) in map) {
            when (value) {
                is Map<*, *> -> {
                    sb.append("<div style=\"font-weight:600;margin-top:4px;\">").append(escapeHtml(key)).append("</div>")
                    sb.append("<div style=\"margin-left:${8 + indent * 8}px;\">")
                    @Suppress("UNCHECKED_CAST")
                    appendRows(sb, value as Map<String, Any?>, indent + 1)
                    sb.append("</div>")
                }

                is List<*> -> {
                    val joined = value.joinToString(", ") { it?.toString() ?: "—" }
                    sb.append("<div><b>").append(escapeHtml(key)).append(":</b> ").append(escapeHtml(joined)).append("</div>")
                }

                else -> {
                    val displayValue = value?.toString() ?: "—"
                    sb.append("<div><b>").append(escapeHtml(key)).append(":</b> ").append(escapeHtml(displayValue)).append("</div>")
                }
            }
        }
    }

    /** 转义 & < >，顺序必须保持 & 最先，避免二次转义。 */
    fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
