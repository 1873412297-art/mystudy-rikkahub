package me.rerere.rikkahub.data.ai.tavernhelper

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.db.entity.TavernHelperScriptEntity

internal enum class TavernHelperScopeType {
    GLOBAL,
    CHARACTER,
    ASSISTANT,
}

internal data class TavernHelperScope(
    val type: TavernHelperScopeType,
    val id: String = "",
) {
    init {
        require(type != TavernHelperScopeType.GLOBAL || id.isEmpty())
        require(type == TavernHelperScopeType.GLOBAL || id.isNotBlank())
    }
}

internal class TavernHelperEntityMapper(
    private val fileStore: TavernHelperFileStore,
    private val codec: TavernHelperScriptCodec = TavernHelperScriptCodec(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun toEntities(
        node: TavernHelperScriptNode,
        scope: TavernHelperScope,
        topLevelOrder: Int,
    ): List<TavernHelperScriptEntity> = when (node) {
        is TavernHelperScript -> listOf(scriptEntity(node, scope, null, topLevelOrder))
        is TavernHelperScriptFolder -> buildList {
            add(folderEntity(node, scope, topLevelOrder))
            node.scripts.forEachIndexed { index, script ->
                add(scriptEntity(script, scope, node.id, index))
            }
        }
    }

    fun toTrees(entities: List<TavernHelperScriptEntity>): List<TavernHelperScriptNode> {
        val scripts = entities.filter { it.kind == KIND_SCRIPT }.associate { entity ->
            entity.id to entity.toScript()
        }
        return entities
            .filter { it.parentId == null }
            .sortedBy { it.sortOrder }
            .map { entity ->
                when (entity.kind) {
                    KIND_SCRIPT -> scripts.getValue(entity.id)
                    KIND_FOLDER -> TavernHelperScriptFolder(
                        id = entity.id,
                        name = entity.name,
                        enabled = entity.enabled,
                        icon = entity.icon.orEmpty(),
                        color = entity.color.orEmpty(),
                        scripts = entities
                            .filter { it.parentId == entity.id && it.kind == KIND_SCRIPT }
                            .sortedBy { it.sortOrder }
                            .map { scripts.getValue(it.id) },
                        compatExtras = parseObject(entity.compatJson),
                    )
                    else -> throw TavernHelperContentCorruptException("未知脚本节点类型: ${entity.kind}")
                }
            }
    }

    private fun scriptEntity(
        script: TavernHelperScript,
        scope: TavernHelperScope,
        parentId: String?,
        order: Int,
    ): TavernHelperScriptEntity {
        val storedJson = Json.parseToJsonElement(codec.encodeStored(script)).jsonObject
        val source = fileStore.store(TavernHelperFileKind.SOURCE, script.id, script.content)
        val data = fileStore.store(TavernHelperFileKind.DATA, script.id, script.data.toString())
        return TavernHelperScriptEntity(
            id = script.id,
            kind = KIND_SCRIPT,
            scope = scope.type.name,
            scopeId = scope.id,
            parentId = parentId,
            sortOrder = order,
            enabled = script.enabled,
            name = script.name,
            info = script.info,
            sourceInline = source.inline,
            sourcePath = source.relativePath,
            sourceSha256 = source.sha256,
            sourceBytes = source.bytes,
            buttonJson = storedJson.getValue("button").toString(),
            dataInline = data.inline,
            dataPath = data.relativePath,
            dataSha256 = data.sha256,
            dataBytes = data.bytes,
            exportJson = storedJson.getValue("export_with").toString(),
            compatJson = script.compatExtras.toString(),
            icon = null,
            color = null,
            tombstone = false,
            updatedAt = now(),
        )
    }

    private fun folderEntity(
        folder: TavernHelperScriptFolder,
        scope: TavernHelperScope,
        order: Int,
    ) = TavernHelperScriptEntity(
        id = folder.id,
        kind = KIND_FOLDER,
        scope = scope.type.name,
        scopeId = scope.id,
        parentId = null,
        sortOrder = order,
        enabled = folder.enabled,
        name = folder.name,
        info = "",
        sourceInline = null,
        sourcePath = null,
        sourceSha256 = null,
        sourceBytes = 0,
        buttonJson = "{}",
        dataInline = null,
        dataPath = null,
        dataSha256 = null,
        dataBytes = 0,
        exportJson = "{}",
        compatJson = folder.compatExtras.toString(),
        icon = folder.icon,
        color = folder.color,
        tombstone = false,
        updatedAt = now(),
    )

    private fun TavernHelperScriptEntity.toScript(): TavernHelperScript {
        val source = fileStore.read(
            TavernHelperStoredContent(sourceInline, sourcePath, sourceSha256.orEmpty(), sourceBytes),
        )
        val dataText = fileStore.read(
            TavernHelperStoredContent(dataInline, dataPath, dataSha256.orEmpty(), dataBytes),
        )
        val root = parseObject(compatJson).toMutableMap().apply {
            put("type", JsonPrimitive("script"))
            put("enabled", JsonPrimitive(enabled))
            put("name", JsonPrimitive(name))
            put("id", JsonPrimitive(id))
            put("content", JsonPrimitive(source))
            put("info", JsonPrimitive(info))
            put("button", parseObject(buttonJson))
            put("data", parseObject(dataText))
            put("export_with", parseObject(exportJson))
        }
        return codec.decodeStored(JsonObject(root).toString()) as TavernHelperScript
    }

    private fun parseObject(text: String): JsonObject = try {
        Json.parseToJsonElement(text) as JsonObject
    } catch (error: Exception) {
        throw TavernHelperContentCorruptException("脚本 JSON 损坏: ${error.message}")
    }

    private companion object {
        const val KIND_SCRIPT = "SCRIPT"
        const val KIND_FOLDER = "FOLDER"
    }
}
