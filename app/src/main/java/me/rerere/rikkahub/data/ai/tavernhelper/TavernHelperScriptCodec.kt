package me.rerere.rikkahub.data.ai.tavernhelper

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal sealed interface TavernHelperScriptNode {
    val id: String
    val name: String
    val enabled: Boolean
    val compatExtras: JsonObject
}

internal data class TavernHelperScript(
    override val id: String,
    override val name: String,
    override val enabled: Boolean,
    val content: String,
    val info: String,
    val button: TavernHelperButtonConfig,
    val data: JsonObject,
    val exportWith: TavernHelperExportWith,
    override val compatExtras: JsonObject,
) : TavernHelperScriptNode

internal data class TavernHelperScriptFolder(
    override val id: String,
    override val name: String,
    override val enabled: Boolean,
    val icon: String,
    val color: String,
    val scripts: List<TavernHelperScript>,
    override val compatExtras: JsonObject,
) : TavernHelperScriptNode

internal data class TavernHelperButton(
    val name: String,
    val visible: Boolean,
    val compatExtras: JsonObject = JsonObject(emptyMap()),
)

internal data class TavernHelperButtonConfig(
    val enabled: Boolean,
    val buttons: List<TavernHelperButton>,
    val compatExtras: JsonObject,
)

internal data class TavernHelperExportWith(
    val data: Boolean,
    val button: Boolean,
    val compatExtras: JsonObject,
)

internal class TavernHelperSchemaException(
    val path: String,
    detail: String,
) : IllegalArgumentException("$path: $detail")

internal class TavernHelperScriptCodec(
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    companion object {
        const val MAX_SOURCE_BYTES = 2 * 1024 * 1024
    }

    fun decodeImport(rawJson: String, occupiedIds: Set<String> = emptySet()): TavernHelperScriptNode {
        val root = parseRoot(rawJson)
        return decodeRoot(root, occupiedIds, preserveEnabled = false)
    }

    fun decodeStored(rawJson: String): TavernHelperScriptNode =
        decodeRoot(parseRoot(rawJson), emptySet(), preserveEnabled = true)

    fun encodeStored(node: TavernHelperScriptNode): String = when (node) {
        is TavernHelperScript -> node.toJson(honorExportFlags = false).toString()
        is TavernHelperScriptFolder -> node.toJson(honorExportFlags = false).toString()
    }

    private fun parseRoot(rawJson: String): JsonObject = try {
        Json.parseToJsonElement(rawJson) as? JsonObject
            ?: throw TavernHelperSchemaException("$", "根节点必须是对象")
    } catch (error: TavernHelperSchemaException) {
        throw error
    } catch (error: Exception) {
        throw TavernHelperSchemaException("$", error.message ?: "JSON 无效")
    }

    private fun decodeRoot(
        root: JsonObject,
        occupiedIds: Set<String>,
        preserveEnabled: Boolean,
    ): TavernHelperScriptNode {
        val type = root.string("type", "$", "script")
        return when (type) {
            "script" -> {
                val scriptRoot = root["value"] as? JsonObject ?: root
                decodeScript(
                    scriptRoot,
                    if (scriptRoot === root) "$" else "$.value",
                    occupiedIds,
                    preserveEnabled,
                )
            }
            "folder" -> decodeFolder(root, "$", occupiedIds, preserveEnabled)
            else -> throw TavernHelperSchemaException("$.type", "暂不支持的类型 $type")
        }
    }

    fun encodeExport(node: TavernHelperScriptNode): String = when (node) {
        is TavernHelperScript -> node.toJson(honorExportFlags = true).toString()
        is TavernHelperScriptFolder -> node.toJson(honorExportFlags = true).toString()
    }

    private fun decodeFolder(
        root: JsonObject,
        path: String,
        occupiedIds: Set<String>,
        preserveEnabled: Boolean,
    ): TavernHelperScriptFolder {
        val storedEnabled = root.boolean("enabled", path, false)
        val rawId = root.string("id", path, "")
        val id = rawId.takeIf { it.isNotBlank() && it !in occupiedIds } ?: idFactory()
        val usedIds = occupiedIds.toMutableSet().apply { add(id) }
        val scriptsField = if ("scripts" in root) "scripts" else "value"
        val scripts = root.arrayObjects(scriptsField, path).mapIndexed { index, scriptObject ->
            val scriptPath = "$path.$scriptsField[$index]"
            val script = decodeScript(scriptObject, scriptPath, usedIds, preserveEnabled)
            usedIds += script.id
            script
        }
        return TavernHelperScriptFolder(
            id = id,
            name = root.string("name", path, ""),
            enabled = storedEnabled && preserveEnabled,
            icon = root.string("icon", path, "fa-solid fa-folder"),
            color = root.string("color", path, ""),
            scripts = scripts,
            compatExtras = root.extras(setOf("type", "enabled", "name", "id", "icon", "color", "scripts", "value")),
        )
    }

    private fun decodeScript(
        root: JsonObject,
        path: String,
        occupiedIds: Set<String>,
        preserveEnabled: Boolean = false,
    ): TavernHelperScript {
        val storedEnabled = root.boolean("enabled", path, false)
        val content = root.string("content", path, "")
        if (content.toByteArray(Charsets.UTF_8).size > MAX_SOURCE_BYTES) {
            throw TavernHelperSchemaException("$path.content", "脚本源码不能超过 2MB")
        }
        val rawId = root.string("id", path, "")
        val id = rawId.takeIf { it.isNotBlank() && it !in occupiedIds } ?: idFactory()
        val buttonObject = root.objectValue("button", path, JsonObject(emptyMap()))
        val legacyButtons = if ("button" !in root) root.arrayObjects("buttons", path) else emptyList()
        val buttonObjects = if (legacyButtons.isNotEmpty()) legacyButtons else buttonObject.arrayObjects("buttons", "$path.button")
        val buttonList = buttonObjects.mapIndexed { index, item ->
            val buttonPath = if (legacyButtons.isNotEmpty()) "$path.buttons[$index]" else "$path.button.buttons[$index]"
            TavernHelperButton(
                name = item.string("name", buttonPath, ""),
                visible = item.boolean("visible", buttonPath, true),
                compatExtras = item.extras(setOf("name", "visible")),
            )
        }
        val exportObject = root.objectValue("export_with", path, JsonObject(emptyMap()))
        return TavernHelperScript(
            id = id,
            name = root.string("name", path, ""),
            enabled = storedEnabled && preserveEnabled,
            content = content,
            info = root.string("info", path, ""),
            button = TavernHelperButtonConfig(
                enabled = buttonObject.boolean("enabled", "$path.button", true),
                buttons = buttonList,
                compatExtras = buttonObject.extras(setOf("enabled", "buttons")),
            ),
            data = root.objectValue("data", path, JsonObject(emptyMap())),
            exportWith = TavernHelperExportWith(
                data = exportObject.boolean("data", "$path.export_with", true),
                button = exportObject.boolean("button", "$path.export_with", true),
                compatExtras = exportObject.extras(setOf("data", "button")),
            ),
            compatExtras = root.extras(
                setOf("type", "enabled", "name", "id", "content", "info", "button", "buttons", "data", "export_with"),
            ),
        )
    }
}

private fun TavernHelperScript.toJson(honorExportFlags: Boolean): JsonObject {
    val includeButtons = !honorExportFlags || exportWith.button
    val includeData = !honorExportFlags || exportWith.data
    val buttonJson = JsonObject(button.compatExtras.toMutableMap().apply {
        put("enabled", JsonPrimitive(button.enabled))
        put("buttons", JsonArray(if (includeButtons) button.buttons.map { it.toJson() } else emptyList()))
    })
    val exportJson = JsonObject(exportWith.compatExtras.toMutableMap().apply {
        put("data", JsonPrimitive(exportWith.data))
        put("button", JsonPrimitive(exportWith.button))
    })
    return JsonObject(compatExtras.toMutableMap().apply {
        put("type", JsonPrimitive("script"))
        put("enabled", JsonPrimitive(enabled))
        put("name", JsonPrimitive(name))
        put("id", JsonPrimitive(id))
        put("content", JsonPrimitive(content))
        put("info", JsonPrimitive(info))
        put("button", buttonJson)
        put("data", if (includeData) data else JsonObject(emptyMap()))
        put("export_with", exportJson)
    })
}

private fun TavernHelperButton.toJson(): JsonObject = JsonObject(compatExtras.toMutableMap().apply {
    put("name", JsonPrimitive(name))
    put("visible", JsonPrimitive(visible))
})

private fun TavernHelperScriptFolder.toJson(honorExportFlags: Boolean): JsonObject = JsonObject(compatExtras.toMutableMap().apply {
    put("type", JsonPrimitive("folder"))
    put("enabled", JsonPrimitive(enabled))
    put("name", JsonPrimitive(name))
    put("id", JsonPrimitive(id))
    put("icon", JsonPrimitive(icon))
    put("color", JsonPrimitive(color))
    put("scripts", JsonArray(scripts.map { it.toJson(honorExportFlags) }))
})

private fun JsonObject.string(name: String, path: String, default: String): String {
    val value = this[name] ?: return default
    return (value as? JsonPrimitive)?.contentOrNull
        ?: throw TavernHelperSchemaException("$path.$name", "必须是字符串或可转成字符串的原始值")
}

private fun JsonObject.boolean(name: String, path: String, default: Boolean): Boolean {
    val value = this[name] ?: return default
    return (value as? JsonPrimitive)?.booleanOrNull
        ?: throw TavernHelperSchemaException("$path.$name", "必须是布尔值")
}

private fun JsonObject.objectValue(name: String, path: String, default: JsonObject): JsonObject {
    val value = this[name] ?: return default
    return value as? JsonObject ?: throw TavernHelperSchemaException("$path.$name", "必须是对象")
}

private fun JsonObject.arrayObjects(name: String, path: String): List<JsonObject> {
    val value = this[name] ?: return emptyList()
    val array = value as? JsonArray
        ?: throw TavernHelperSchemaException("$path.$name", "必须是数组")
    return array.mapIndexed { index, element ->
        element as? JsonObject ?: throw TavernHelperSchemaException("$path.$name[$index]", "必须是对象")
    }
}

private fun JsonObject.extras(known: Set<String>): JsonObject = JsonObject(filterKeys { it !in known })
