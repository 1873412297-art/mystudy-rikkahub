package me.rerere.rikkahub.data.ai.status

import me.rerere.rikkahub.data.model.Assistant

/**
 * 角色卡渲染样式（卡 CSS + 版本键）。
 *
 * @property css 角色卡 CSS（可为 null，表示卡未提供样式）
 * @property versionKey 样式版本键：卡 JSON / renderStatus JS 变化时改变，用于 WebView renderKey 失效
 */
data class TavernCardStyle(
    val css: String?,
    val versionKey: String,
)

/**
 * 解析角色卡样式：CSS 复用 TavernCardCssExtractor；版本键由卡 JSON 与 renderStatus JS 的 hash 组合。
 * 无卡（tavernCardJson/statusRenderJs 均 null）时返回 null（消息渲染无需注入）。
 */
object TavernCardStyleResolver {

    fun resolve(assistant: Assistant?): TavernCardStyle? {
        if (assistant == null) return null
        val cardJson = assistant.tavernCardJson
        val renderJs = assistant.statusRenderJs
        if (cardJson == null && renderJs == null) return null
        val css = cardJson?.let { TavernCardCssExtractor.extract(it) }
        val versionKey = "${cardJson?.hashCode() ?: 0}|${renderJs?.hashCode() ?: 0}"
        return TavernCardStyle(css = css, versionKey = versionKey)
    }
}
