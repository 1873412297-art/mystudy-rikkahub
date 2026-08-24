package me.rerere.rikkahub.data.ai.tavernhelper

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class TavernHelperCharacterBundle(
    val scripts: List<TavernHelperScriptNode>,
    val variables: JsonObject,
    val migratedLegacy: Boolean,
)

internal class TavernHelperCharacterCardCodec(
    private val scriptCodec: TavernHelperScriptCodec = TavernHelperScriptCodec(),
) {
    fun decode(rawCardJson: String, occupiedIds: Set<String> = emptySet()): TavernHelperCharacterBundle {
        val root = Json.parseToJsonElement(rawCardJson) as? JsonObject
            ?: throw TavernHelperSchemaException("$", "角色卡根节点必须是对象")
        val data = root["data"] as? JsonObject ?: root
        val extensions = data["extensions"] as? JsonObject ?: JsonObject(emptyMap())
        val hasCurrent = "tavern_helper" in extensions
        val settings = if (hasCurrent) normalizeSettings(extensions.getValue("tavern_helper")) else null
        val scriptElements = if (hasCurrent) {
            settings?.get("scripts") as? JsonArray ?: JsonArray(emptyList())
        } else {
            extensions[LEGACY_SCRIPTS] as? JsonArray ?: JsonArray(emptyList())
        }
        val variables = if (hasCurrent) {
            settings?.get("variables") as? JsonObject ?: JsonObject(emptyMap())
        } else {
            extensions[LEGACY_VARIABLES] as? JsonObject ?: JsonObject(emptyMap())
        }
        val occupied = occupiedIds.toMutableSet()
        val scripts = scriptElements.mapIndexed { index, element ->
            val nodeObject = element as? JsonObject
                ?: throw TavernHelperSchemaException("$.data.extensions.scripts[$index]", "必须是对象")
            scriptCodec.decodeImport(nodeObject.toString(), occupied).also { node ->
                occupied += node.allIds()
            }
        }
        return TavernHelperCharacterBundle(
            scripts = scripts,
            variables = variables,
            migratedLegacy = !hasCurrent && (LEGACY_SCRIPTS in extensions || LEGACY_VARIABLES in extensions),
        )
    }

    private fun normalizeSettings(value: JsonElement): JsonObject = when (value) {
        is JsonObject -> value
        is JsonArray -> JsonObject(value.mapIndexed { index, entry ->
            val pair = entry as? JsonArray
                ?: throw TavernHelperSchemaException("$.data.extensions.tavern_helper[$index]", "必须是键值对")
            val key = (pair.getOrNull(0) as? JsonPrimitive)?.contentOrNull
                ?: throw TavernHelperSchemaException("$.data.extensions.tavern_helper[$index][0]", "必须是字符串")
            val item = pair.getOrNull(1)
                ?: throw TavernHelperSchemaException("$.data.extensions.tavern_helper[$index][1]", "缺少值")
            key to item
        }.toMap())
        else -> throw TavernHelperSchemaException("$.data.extensions.tavern_helper", "必须是对象或键值对数组")
    }

    private fun TavernHelperScriptNode.allIds(): Set<String> = when (this) {
        is TavernHelperScript -> setOf(id)
        is TavernHelperScriptFolder -> buildSet {
            add(id)
            addAll(scripts.map { it.id })
        }
    }

    private companion object {
        const val LEGACY_SCRIPTS = "TavernHelper_scripts"
        const val LEGACY_VARIABLES = "TavernHelper_characterScriptVariables"
    }
}
