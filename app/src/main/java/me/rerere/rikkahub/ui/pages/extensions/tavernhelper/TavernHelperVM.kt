package me.rerere.rikkahub.ui.pages.extensions.tavernhelper

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperButtonConfig
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperExportWith
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperScope
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperScopeType
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperScript
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperScriptNode
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperScriptRepository
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.TavernHelperRenderSettings

internal class TavernHelperVM(
    private val repository: TavernHelperScriptRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    val scope = MutableStateFlow(TavernHelperScope(TavernHelperScopeType.GLOBAL))
    val scripts = scope
        .flatMapLatest(repository::observe)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings.dummy())
    val error = MutableStateFlow<String?>(null)

    fun selectScope(value: TavernHelperScope) {
        scope.value = value
    }

    fun importJson(rawJson: String) {
        viewModelScope.launch {
            runCatching { repository.importJson(scope.value, rawJson) }
                .onFailure { error.value = it.message ?: "导入失败" }
        }
    }

    fun addScript(name: String, content: String) {
        val script = TavernHelperScript(
            id = UUID.randomUUID().toString(),
            name = name,
            enabled = false,
            content = content,
            info = "",
            button = TavernHelperButtonConfig(true, emptyList(), JsonObject(emptyMap())),
            data = JsonObject(emptyMap()),
            exportWith = TavernHelperExportWith(true, true, JsonObject(emptyMap())),
            compatExtras = JsonObject(emptyMap()),
        )
        viewModelScope.launch {
            runCatching { repository.save(scope.value, script, scripts.value.size) }
                .onFailure { error.value = it.message ?: "保存失败" }
        }
    }

    fun setEnabled(node: TavernHelperScriptNode, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { repository.setEnabled(node.id, enabled) }
                .onFailure { error.value = it.message ?: "更新失败" }
        }
    }

    fun delete(node: TavernHelperScriptNode) {
        viewModelScope.launch {
            runCatching { repository.delete(node.id) }
                .onFailure { error.value = it.message ?: "删除失败" }
        }
    }

    fun updateRenderSettings(update: (TavernHelperRenderSettings) -> TavernHelperRenderSettings) {
        viewModelScope.launch {
            settingsStore.update { current ->
                current.copy(tavernHelperRenderSettings = update(current.tavernHelperRenderSettings))
            }
        }
    }

    fun clearError() {
        error.value = null
    }
}
