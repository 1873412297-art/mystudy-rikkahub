package me.rerere.rikkahub.data.ai.status

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Minimal RFC 6902 JSONPatch implementation for status variable updates.
 * Supports: add, remove, replace (the most common operations from AI model output).
 * JSON Pointer (RFC 6901) is used for path navigation.
 */

@Serializable
data class JsonPatchOp(
    val op: String,           // "add", "remove", "replace"
    val path: String,         // JSON Pointer path, e.g. "/世界/当前时间"
    val value: JsonElement? = null,  // value for add/replace
)

/**
 * Apply a list of JSONPatch operations to a JsonObject and return the modified value.
 */
fun JsonElement.applyPatch(ops: List<JsonPatchOp>): JsonElement {
    var result = this
    for (op in ops) {
        result = result.applyOp(op)
    }
    return result
}

private fun JsonElement.applyOp(op: JsonPatchOp): JsonElement {
    val segments = parseJsonPointer(op.path)
    return when (op.op) {
        "add" -> addAt(this, segments, op.value ?: JsonNull)
        "remove" -> removeAt(this, segments)
        "replace" -> replaceAt(this, segments, op.value ?: JsonNull)
        else -> this
    }
}

/**
 * Parse a JSON Pointer (RFC 6901) path like "/世界/当前时间" into segments ["世界", "当前时间"].
 * Handles escaped ~0 -> ~ and ~1 -> /.
 */
private fun parseJsonPointer(path: String): List<String> {
    if (path.isEmpty() || path == "/") return emptyList()
    return path.removePrefix("/").split("/").map { segment ->
        segment.replace("~1", "/").replace("~0", "~")
    }
}

private fun addAt(root: JsonElement, segments: List<String>, value: JsonElement): JsonElement {
    if (segments.isEmpty()) return value
    if (root !is JsonObject) return root

    val mutable = root.toMutableMap()
    if (segments.size == 1) {
        mutable[segments.first()] = value
        return JsonObject(mutable)
    }

    val parentKey = segments.first()
    val rest = segments.drop(1)
    val child = mutable[parentKey] ?: JsonObject(emptyMap())
    mutable[parentKey] = addAt(child, rest, value)
    return JsonObject(mutable)
}

private fun removeAt(root: JsonElement, segments: List<String>): JsonElement {
    if (segments.isEmpty()) return JsonObject(emptyMap())
    if (root !is JsonObject) return root

    val mutable = root.toMutableMap()
    if (segments.size == 1) {
        mutable.remove(segments.first())
        return JsonObject(mutable)
    }

    val parentKey = segments.first()
    val rest = segments.drop(1)
    val child = mutable[parentKey] ?: return root
    mutable[parentKey] = removeAt(child, rest)
    return JsonObject(mutable)
}

private fun replaceAt(root: JsonElement, segments: List<String>, value: JsonElement): JsonElement {
    if (segments.isEmpty()) return value
    if (root !is JsonObject) return root

    val mutable = root.toMutableMap()
    if (segments.size == 1) {
        mutable[segments.first()] = value
        return JsonObject(mutable)
    }

    val parentKey = segments.first()
    val rest = segments.drop(1)
    val child = mutable[parentKey] ?: JsonObject(emptyMap())
    mutable[parentKey] = replaceAt(child, rest, value)
    return JsonObject(mutable)
}

/**
 * Helper to convert a JsonObject recursively to a plain Map for JS interop.
 */
fun JsonElement.toPlainValue(): Any? {
    return when (this) {
        is JsonObject -> {
            val map = LinkedHashMap<String, Any?>()
            for ((key, value) in this) {
                map[key] = value.toPlainValue()
            }
            map
        }
        is JsonArray -> this.map { it.toPlainValue() }
        JsonNull -> null
        is JsonPrimitive -> {
            if (this.isString) this.content
            else if (this.content == "true") true
            else if (this.content == "false") false
            else this.content.toLongOrNull() ?: this.content.toDoubleOrNull() ?: this.content
        }
    }
}
