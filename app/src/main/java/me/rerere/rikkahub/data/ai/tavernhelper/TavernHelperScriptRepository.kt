package me.rerere.rikkahub.data.ai.tavernhelper

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.db.dao.TavernHelperScriptDAO

internal class TavernHelperScriptRepository(
    private val dao: TavernHelperScriptDAO,
    private val mapper: TavernHelperEntityMapper,
    private val codec: TavernHelperScriptCodec = TavernHelperScriptCodec(),
    private val characterCodec: TavernHelperCharacterCardCodec = TavernHelperCharacterCardCodec(codec),
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun observe(scope: TavernHelperScope): Flow<List<TavernHelperScriptNode>> =
        dao.observeScope(scope.type.name, scope.id).map(mapper::toTrees)

    suspend fun importJson(scope: TavernHelperScope, rawJson: String): TavernHelperScriptNode {
        val node = codec.decodeImport(rawJson, dao.getAllIds().toSet())
        val order = dao.nextTopLevelOrder(scope.type.name, scope.id)
        dao.upsertAll(mapper.toEntities(node, scope, order))
        return node
    }

    suspend fun importCharacterCard(scopeId: String, rawCardJson: String): TavernHelperCharacterBundle {
        val scope = TavernHelperScope(TavernHelperScopeType.CHARACTER, scopeId)
        val scopeEntities = dao.getScope(scope.type.name, scope.id)
        val scopeIds = scopeEntities.mapTo(mutableSetOf()) { it.id }
        val occupiedElsewhere = dao.getAllIds().filterNotTo(mutableSetOf()) { it in scopeIds }
        val bundle = characterCodec.decode(rawCardJson, occupiedElsewhere)
        val priorEnabled = scopeEntities.associate { it.id to it.enabled }
        val restored = bundle.scripts.map { it.withEnabledState(priorEnabled) }
        dao.markScopeDeleted(scope.type.name, scope.id, now())
        dao.upsertAll(restored.flatMapIndexed { index, node -> mapper.toEntities(node, scope, index) })
        return bundle.copy(scripts = restored)
    }

    suspend fun save(scope: TavernHelperScope, node: TavernHelperScriptNode, topLevelOrder: Int) {
        val entities = mapper.toEntities(node, scope, topLevelOrder)
        val incomingIds = entities.mapTo(mutableSetOf()) { it.id }
        dao.getChildren(node.id).filterNot { it.id in incomingIds }.forEach { dao.markDeleted(it.id, now()) }
        dao.upsertAll(entities)
    }

    fun encodeExport(node: TavernHelperScriptNode): String = codec.encodeExport(node)

    suspend fun replaceRuntimeData(scriptId: String, rawJson: String) {
        require(rawJson.toByteArray().size <= MAX_SCRIPT_DATA_BYTES) { "脚本数据不能超过 1MB" }
        val data = Json.parseToJsonElement(rawJson) as? JsonObject ?: error("脚本数据必须是 JSON 对象")
        updateScript(scriptId) { it.copy(data = data) }
    }

    suspend fun replaceRuntimeButtons(scriptId: String, rawJson: String) {
        val array = Json.parseToJsonElement(rawJson) as? JsonArray ?: error("按钮必须是 JSON 数组")
        require(array.size <= MAX_SCRIPT_BUTTONS) { "单个脚本最多 $MAX_SCRIPT_BUTTONS 个按钮" }
        val buttons = array.mapIndexed { index, element ->
            val item = element as? JsonObject ?: error("按钮[$index]必须是对象")
            val name = item["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            require(name.isNotBlank()) { "按钮[$index].name 不能为空" }
            TavernHelperButton(
                name = name,
                visible = item["visible"]?.jsonPrimitive?.booleanOrNull ?: true,
                compatExtras = JsonObject(item.filterKeys { it != "name" && it != "visible" }),
            )
        }
        require(buttons.map { it.name }.distinct().size == buttons.size) { "同一脚本内按钮名不能重复" }
        updateScript(scriptId) { script ->
            script.copy(button = script.button.copy(buttons = buttons))
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        dao.setEnabled(id, enabled, now())
    }

    suspend fun delete(id: String) {
        dao.markDeleted(id, now())
    }

    private fun TavernHelperScriptNode.withEnabledState(prior: Map<String, Boolean>): TavernHelperScriptNode = when (this) {
        is TavernHelperScript -> copy(enabled = prior[id] ?: false)
        is TavernHelperScriptFolder -> copy(
            enabled = prior[id] ?: false,
            scripts = scripts.map { it.copy(enabled = prior[it.id] ?: false) },
        )
    }

    private suspend fun updateScript(scriptId: String, transform: (TavernHelperScript) -> TavernHelperScript) {
        val entity = dao.getById(scriptId) ?: error("脚本不存在")
        val scope = TavernHelperScope(TavernHelperScopeType.valueOf(entity.scope), entity.scopeId)
        val trees = mapper.toTrees(dao.getScope(entity.scope, entity.scopeId))
        val rootIndex = trees.indexOfFirst { root ->
            root.id == scriptId || (root is TavernHelperScriptFolder && root.scripts.any { it.id == scriptId })
        }
        require(rootIndex >= 0) { "脚本不在当前作用域" }
        val updated = when (val root = trees[rootIndex]) {
            is TavernHelperScript -> transform(root)
            is TavernHelperScriptFolder -> root.copy(
                scripts = root.scripts.map { if (it.id == scriptId) transform(it) else it },
            )
        }
        save(scope, updated, rootIndex)
    }

    private companion object {
        const val MAX_SCRIPT_DATA_BYTES = 1024 * 1024
        const val MAX_SCRIPT_BUTTONS = 64
    }
}
