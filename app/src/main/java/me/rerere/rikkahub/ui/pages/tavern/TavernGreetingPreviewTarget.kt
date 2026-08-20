package me.rerere.rikkahub.ui.pages.tavern

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
        expectedConversationId: Uuid,
        patch: JsonElement,
        writer: (conversationId: Uuid, patch: JsonElement) -> Unit,
    ) {
        val target = requireReadyTarget(expectedConversationId)
        writer(target.conversationId, patch)
    }

    fun routeChatVariables(
        expectedConversationId: Uuid,
        variables: kotlinx.serialization.json.JsonObject,
        writer: (conversationId: Uuid, variables: kotlinx.serialization.json.JsonObject) -> Unit,
    ) {
        val target = requireReadyTarget(expectedConversationId)
        writer(target.conversationId, variables)
    }

    fun validateTarget(expectedConversationId: Uuid) {
        requireReadyTarget(expectedConversationId)
    }

    private fun requireReadyTarget(expectedConversationId: Uuid): TavernGreetingPreviewTarget {
        val target = checkNotNull(_selected.value) {
            "Select a real conversation before starting the full Tavern preview"
        }
        check(target.conversationId == expectedConversationId) {
            "Discarding a stale preview callback for a previously selected conversation"
        }
        check(_ready.value) { "The selected preview conversation is not loaded yet" }
        return target
    }
}

internal class TavernGreetingPreviewOwner {
    private val _active = MutableStateFlow<String?>(null)
    val active: StateFlow<String?> = _active.asStateFlow()

    fun show(fieldKey: String) {
        _active.value = fieldKey
    }

    fun showSource(fieldKey: String) {
        if (_active.value == fieldKey) _active.value = null
    }
}

internal class TavernPreviewConversationLease(
    private val acquire: (Uuid) -> Unit,
    private val release: (Uuid) -> Unit,
) {
    private var current: Uuid? = null

    fun switchTo(conversationId: Uuid) {
        if (current == conversationId) return
        current?.let(release)
        current = conversationId
        acquire(conversationId)
    }

    fun clear() {
        current?.let(release)
        current = null
    }
}

/** Serializes bridge callbacks so a later preview write cannot overtake an earlier suspended persistence call. */
internal class TavernPreviewSideEffectQueue(
    dispatcher: CoroutineDispatcher,
    private val acquire: (Uuid) -> Unit,
    private val release: (Uuid) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private data class Effect(
        val conversationId: Uuid,
        val run: suspend () -> Unit,
    )

    private val effects = Channel<Effect>(Channel.UNLIMITED)
    private val drained = CompletableDeferred<Unit>()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    init {
        scope.launch {
            try {
                for (effect in effects) {
                    try {
                        effect.run()
                    } catch (error: Throwable) {
                        runCatching { onFailure(error) }
                    } finally {
                        runCatching { release(effect.conversationId) }
                            .onFailure { releaseError -> runCatching { onFailure(releaseError) } }
                    }
                }
            } finally {
                drained.complete(Unit)
            }
        }
    }

    fun submit(conversationId: Uuid, effect: suspend () -> Unit) {
        acquire(conversationId)
        val result = effects.trySend(Effect(conversationId, effect))
        if (result.isFailure) {
            runCatching { release(conversationId) }
                .onFailure { releaseError -> runCatching { onFailure(releaseError) } }
            result.getOrThrow()
        }
    }

    fun close() {
        effects.close()
    }

    suspend fun awaitDrained() {
        drained.await()
    }
}
