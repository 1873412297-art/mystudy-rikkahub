package me.rerere.rikkahub.data.ai.tavernhelper

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
        dao.upsertAll(mapper.toEntities(node, scope, topLevelOrder))
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
}
