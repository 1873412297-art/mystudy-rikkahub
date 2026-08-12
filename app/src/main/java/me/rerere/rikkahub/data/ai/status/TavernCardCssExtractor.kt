package me.rerere.rikkahub.data.ai.status

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.utils.JsonInstant

/**
 * 从 SillyTavern 角色卡原始 JSON（V2/V3）提取状态渲染 CSS。
 * SillyTavern 卡片的 CSS 可能位于 extensions.css / extensions.status_css / extensions.status.css 等位置。
 * 供 web tavern-render 端点与 StatusPlaceholderTransformer 共用。
 */
object TavernCardCssExtractor {

    fun extract(cardJson: String): String? {
        return try {
            val root = JsonInstant.parseToJsonElement(cardJson)
            val extensions = root.jsonObject["data"]?.jsonObject?.get("extensions")?.jsonObject
            val topExtensions = root.jsonObject["extensions"]?.jsonObject
            val ext = extensions ?: topExtensions ?: return null

            ext["css"]?.let { p -> if (p is JsonPrimitive && p.isString) return p.content }
            ext["status_css"]?.let { p -> if (p is JsonPrimitive && p.isString) return p.content }
            ext["status"]?.jsonObject?.get("css")?.let { p -> if (p is JsonPrimitive && p.isString) return p.content }
            ext["status"]?.jsonObject?.get("status_css")?.let { p -> if (p is JsonPrimitive && p.isString) return p.content }
            null
        } catch (e: Exception) {
            null
        }
    }
}
