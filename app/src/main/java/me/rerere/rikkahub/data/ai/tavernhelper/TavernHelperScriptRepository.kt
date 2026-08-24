package me.rerere.rikkahub.data.ai.tavernhelper

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.TavernHelperScriptDAO

internal class TavernHelperScriptRepository(
    private val dao: TavernHelperScriptDAO,
    private val mapper: TavernHelperEntityMapper,
    private val codec: TavernHelperScriptCodec = TavernHelperScriptCodec(),
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

    suspend fun save(scope: TavernHelperScope, node: TavernHelperScriptNode, topLevelOrder: Int) {
        dao.upsertAll(mapper.toEntities(node, scope, topLevelOrder))
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        dao.setEnabled(id, enabled, now())
    }

    suspend fun delete(id: String) {
        dao.markDeleted(id, now())
    }
}
