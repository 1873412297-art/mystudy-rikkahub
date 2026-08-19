package me.rerere.rikkahub.ui.pages.tavern

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.Uuid

internal data class TavernGreetingPreviewTarget(
    val conversationId: Uuid,
    val assistantId: Uuid,
    val title: String,
)

/** Explicit target gate for the side-effecting editor preview. It never selects a conversation by itself. */
internal class TavernGreetingPreviewTargetSelection(
    private val expectedAssistantId: Uuid,
) {
    private val _selected = MutableStateFlow<TavernGreetingPreviewTarget?>(null)
    val selected: StateFlow<TavernGreetingPreviewTarget?> = _selected.asStateFlow()
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    fun select(target: TavernGreetingPreviewTarget) {
        require(target.assistantId == expectedAssistantId) {
            "Preview target must belong to the edited Tavern assistant"
        }
        _ready.value = false
        _selected.value = target
    }

    fun markReady(conversationId: Uuid) {
        check(_selected.value?.conversationId == conversationId) {
            "Only the selected preview target can become ready"
        }
        _ready.value = true
    }

    fun clear() {
        _ready.value = false
        _selected.value = null
    }

    fun routeMessageWrite(
        patch: JsonElement,
        writer: (conversationId: Uuid, patch: JsonElement) -> Unit,
    ) {
        val target = checkNotNull(_selected.value) {
            "Select a real conversation before starting the full Tavern preview"
        }
        check(_ready.value) { "The selected preview conversation is not loaded yet" }
        writer(target.conversationId, patch)
    }
}
