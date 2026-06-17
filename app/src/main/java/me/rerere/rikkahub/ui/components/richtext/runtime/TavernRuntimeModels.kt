package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
internal data class TavernRuntimeRequest(
    val id: String,
    val method: String,
    val params: JsonObject = buildJsonObject { },
)

@Serializable
internal data class TavernRuntimeError(
    val code: String,
    val message: String,
)

@Serializable
internal data class TavernRuntimeResponse(
    val id: String,
    val ok: Boolean,
    val result: JsonElement? = null,
    val error: TavernRuntimeError? = null,
) {
    companion object {
        fun success(id: String, result: JsonElement): TavernRuntimeResponse {
            return TavernRuntimeResponse(id = id, ok = true, result = result)
        }

        fun error(id: String, code: String, message: String): TavernRuntimeResponse {
            return TavernRuntimeResponse(
                id = id,
                ok = false,
                error = TavernRuntimeError(code = code, message = message),
            )
        }
    }
}

internal fun JsonObject.getString(name: String): String? {
    return (this[name] as? JsonPrimitive)?.content
}
