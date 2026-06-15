package me.rerere.rikkahub.data.ai.status

import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * QuickJS-based JavaScript execution for rendering status/expression HTML.
 *
 * Script convention: must define `function renderStatus(variables, metadata) { return htmlString; }`
 * where:
 *   - `variables` is a plain JavaScript object mirroring the JSON variable tree
 *   - `metadata` is a plain JavaScript object with keys: char_name, user_name, expression, avatar_uri
 */
class StatusRenderer {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "StatusRenderer").apply { isDaemon = true }
    }

    // Current state
    private val currentScriptRef = AtomicReference<String?>(null)
    private val currentCssRef = AtomicReference<String?>(null)
    @Volatile private var jsContext: QuickJSContext? = null
    @Volatile private var renderFn: JSFunction? = null

    /**
     * Load (or reload) the rendering JavaScript and optional CSS.
     * The script must define: function renderStatus(variables, metadata) { ... return htmlString; }
     * If the script hasn't changed, this is a no-op.
     */
    fun loadScript(script: String, css: String? = null) {
        val scriptChanged = currentScriptRef.getAndSet(script) != script
        val cssChanged = currentCssRef.getAndSet(css) != css
        if (!scriptChanged && !cssChanged) return

        cleanup()
        try {
            executor.submit {
                try {
                    val ctx = QuickJSContext.create()
                    ctx.evaluate(script)
                    // Wrapper: parses JSON arguments, calls user's renderStatus, returns HTML
                    ctx.evaluate("""
                        function __parseAndRender(varsJson, metaJson) {
                            var vars = JSON.parse(varsJson);
                            var meta = JSON.parse(metaJson);
                            if (typeof renderStatus !== 'function') {
                                return '<div style="color:red;padding:8px;">Error: renderStatus(variables, metadata) function not found in script</div>';
                            }
                            try {
                                var html = renderStatus(vars, meta);
                                return typeof html === 'string' ? html : '';
                            } catch(e) {
                                return '<div style="color:red;padding:8px;">Render error: ' + e.message + '</div>';
                            }
                        }
                    """.trimIndent())
                    val fn = ctx.globalObject.getJSFunction("__parseAndRender")
                        ?: error("Failed to register __parseAndRender wrapper")
                    jsContext = ctx
                    renderFn = fn
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.get()
        } catch (e: Exception) {
            e.printStackTrace()
            cleanup()
        }
    }

    /**
     * Get the current CSS (if any).
     */
    fun getCss(): String? = currentCssRef.get()

    /**
     * Render status variables + metadata to HTML string.
     *
     * @param variables The variable tree from StatusVariableStore
     * @param metadata Extra context: char_name, user_name, expression, avatar_uri
     */
    suspend fun render(variables: Map<String, Any?>, metadata: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val ctx = jsContext
            val fn = renderFn

            if (ctx == null || fn == null) {
                return@withContext buildFallbackHtml(variables, metadata)
            }

            try {
                val result = executor.submit<String> {
                    try {
                        val varsJson = mapToJson(variables)
                        val metaJson = mapToJson(metadata)
                        val jsResult = fn.call(varsJson, metaJson) as? String
                        val html = jsResult?.toString() ?: ""
                        // Inject CSS if available
                        val css = currentCssRef.get()
                        if (!css.isNullOrBlank() && html.isNotBlank()) {
                            // 安全：清洗 CSS 中可能逃出 <style> 块的序列。
                            // 第三方角色卡的 extensions.css 中若含 "</style><script>..."
                            // 会逃出样式块直接执行 JS。这里把所有 "</" 替换成无害形式。
                            val safeCss = sanitizeCss(css)
                            "<style>$safeCss</style>\n$html"
                        } else {
                            html
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        buildFallbackHtml(variables, metadata)
                    }
                }
                result.get()
            } catch (e: Exception) {
                e.printStackTrace()
                buildFallbackHtml(variables, metadata)
            }
        }

    /**
     * 清洗用户提供的 CSS，阻断逃出 <style> 块的注入。
     * 浏览器 HTML 解析器在 <style> 内只识别 "</style" (不区分大小写) 作为结束标记，
     * 所以核心是破坏这个序列。CSS 中正常情况下不会出现 "</"，把所有 "</" 替换为
     * "/* */ " 是无害的（CSS 注释，几乎不影响任何合法选择器或值）。
     */
    private fun sanitizeCss(css: String): String =
        Regex("</", RegexOption.IGNORE_CASE).replace(css, "/* */ ")

    fun destroy() {
        cleanup()
        executor.shutdown()
    }

    private fun cleanup() {
        try { jsContext?.destroy() } catch (_: Exception) {}
        jsContext = null
        renderFn = null
    }

    // region Fallback rendering

    private fun buildFallbackHtml(variables: Map<String, Any?>, metadata: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append("<div style=\"font-family:sans-serif;font-size:13px;line-height:1.5;\">")
        // Expression if present
        metadata["expression"]?.let { expr ->
            if (expr.isNotBlank()) {
                sb.append("<div style=\"font-size:16px;font-weight:600;margin-bottom:4px;\">")
                sb.append(escapeHtml(expr))
                sb.append("</div>")
            }
        }
        if (variables.isNotEmpty()) {
            appendFallbackRows(sb, variables)
        }
        sb.append("</div>")
        return sb.toString()
    }

    private fun appendFallbackRows(sb: StringBuilder, map: Map<String, Any?>, indent: Int = 0) {
        for ((key, value) in map) {
            when (value) {
                is Map<*, *> -> {
                    sb.append("<div style=\"font-weight:600;margin-top:4px;\">${escapeHtml(key)}</div>")
                    sb.append("<div style=\"margin-left:${8 + indent * 8}px;\">")
                    @Suppress("UNCHECKED_CAST")
                    appendFallbackRows(sb, value as Map<String, Any?>, indent + 1)
                    sb.append("</div>")
                }
                is List<*> -> {
                    sb.append("<div><b>${escapeHtml(key)}:</b> ${escapeHtml(value.joinToString(", "))}</div>")
                }
                else -> {
                    val displayValue = value?.toString() ?: "—"
                    sb.append("<div><b>${escapeHtml(key)}:</b> ${escapeHtml(displayValue)}</div>")
                }
            }
        }
    }

    private fun escapeHtml(s: String): String {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    // endregion

    // region JSON serialization (safe, no external dependencies)

    /**
     * Convert any value to a valid JSON string.
     * Properly escapes all JSON control characters.
     */
    private fun mapToJson(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> stringToJson(value)
            is Number -> value.toString()
            is Boolean -> value.toString()
            is Map<*, *> -> {
                val entries = value.entries.joinToString(",") { (k, v) ->
                    "${stringToJson(k.toString())}:${mapToJson(v)}"
                }
                "{$entries}"
            }
            is List<*> -> {
                val items = value.joinToString(",") { mapToJson(it) }
                "[$items]"
            }
            else -> stringToJson(value.toString())
        }
    }

    /**
     * Properly escape a string for JSON: handles ", \, newlines, tabs, and other control chars.
     */
    private fun stringToJson(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) {
                        sb.append("\\u${c.code.toString(16).padStart(4, '0')}")
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    // endregion
}
