package me.rerere.rikkahub.service.tavern

import java.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.TavernCharacterCard
import me.rerere.rikkahub.data.model.TavernOpeningRef
import me.rerere.rikkahub.data.model.TavernOpeningRuntimeState
import me.rerere.rikkahub.data.model.TavernOpeningSlashRegistration
import me.rerere.rikkahub.data.model.openingMessage
import me.rerere.rikkahub.data.model.openingRef
import me.rerere.rikkahub.data.model.markTavernOpeningRuntimeExecuted
import me.rerere.rikkahub.data.model.withTavernOpeningRuntimeState
import me.rerere.rikkahub.data.model.tavernOpeningRef
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.ai.slash.TavernScriptRegistry
import me.rerere.rikkahub.data.ai.status.JsonPatchOp
import me.rerere.rikkahub.data.ai.status.StatusFallbackHtml
import me.rerere.rikkahub.data.ai.status.extractTavernCardStatusTemplate
import me.rerere.rikkahub.data.ai.status.applyPatch
import me.rerere.rikkahub.data.ai.status.toPlainValue
import me.rerere.rikkahub.ui.components.richtext.runtime.TAVERN_VARIABLE_SCOPE_CHAT
import me.rerere.rikkahub.ui.components.richtext.runtime.TAVERN_VARIABLE_SCOPE_GLOBAL
import me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeRegistrationObserver
import me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeVariableGateway
import me.rerere.rikkahub.ui.components.richtext.runtime.TavernWorldRepository
import kotlin.uuid.Uuid

enum class TavernGreetingVariableScope { CHAT, GLOBAL }

data class TavernGreetingSlashRegistration(
    val source: String,
    val aliases: List<String> = emptyList(),
    val helpString: String = "",
)

data class TavernGreetingRegistrations(
    val macros: Map<String, String> = emptyMap(),
    val slashCommands: Map<String, TavernGreetingSlashRegistration> = emptyMap(),
    val sendHookSource: String? = null,
)

data class TavernGreetingOverlay(
    val messages: List<UIMessage>,
    val chatVariables: JsonObject,
    val globalVariables: JsonObject,
    val worldEntries: List<JsonObject>,
    val registrations: TavernGreetingRegistrations,
)

data class TavernGreetingMutationJournal(
    val globalVariables: Map<String, JsonElement?> = emptyMap(),
    val worldUpserts: Map<String, JsonObject> = emptyMap(),
    val worldDeletes: Set<String> = emptySet(),
)

internal fun rebaseGreetingGlobalVariables(
    current: JsonObject,
    journal: TavernGreetingMutationJournal,
): JsonObject = JsonObject(current.toMutableMap().apply {
    journal.globalVariables.forEach { (key, value) ->
        if (value == null) remove(key) else put(key, value)
    }
})

/** Candidate-local journal. Every mutating operation stays here until the candidate is selected. */
class TavernGreetingCandidateRuntime internal constructor(initial: TavernGreetingOverlay) :
    TavernRuntimeVariableGateway,
    TavernWorldRepository,
    TavernRuntimeRegistrationObserver {
    private val lock = Any()
    private var messages = initial.messages
    private val chatVariables = initial.chatVariables.toMutableMap()
    private val globalVariables = initial.globalVariables.toMutableMap()
    private val worldEntries = initial.worldEntries.associateByTo(linkedMapOf()) { entryId(it) }
    private val macros = initial.registrations.macros.toMutableMap()
    private val slashCommands = initial.registrations.slashCommands.toMutableMap()
    private var sendHookSource = initial.registrations.sendHookSource
    private val globalVariableMutations = linkedMapOf<String, JsonElement?>()
    private val worldUpserts = linkedMapOf<String, JsonObject>()
    private val worldDeletes = linkedSetOf<String>()
    private var frozen = false
    @Volatile private var ready = false
    private val candidateScriptRegistry = TavernScriptRegistry()
    private val _overlayFlow = MutableStateFlow(initial)
    val overlayFlow: StateFlow<TavernGreetingOverlay> = _overlayFlow.asStateFlow()

    fun setVariable(scope: TavernGreetingVariableScope, key: String, value: JsonElement) = synchronized(lock) {
        if (frozen) return@synchronized
        variables(scope)[key] = value
        if (scope == TavernGreetingVariableScope.GLOBAL) globalVariableMutations[key] = value
        publishLocked()
    }

    fun deleteVariable(scope: TavernGreetingVariableScope, key: String): Boolean = synchronized(lock) {
        if (frozen) return@synchronized false
        val removed = variables(scope).remove(key) != null
        if (removed && scope == TavernGreetingVariableScope.GLOBAL) globalVariableMutations[key] = null
        publishLocked()
        removed
    }

    fun listVariables(scope: TavernGreetingVariableScope): JsonObject = synchronized(lock) {
        JsonObject(variables(scope).toMap())
    }

    fun upsertWorldEntry(entry: JsonObject): String = synchronized(lock) {
        if (frozen) return@synchronized ""
        val id = entry["id"]?.let { (it as? JsonPrimitive)?.content }?.takeIf { it.isNotBlank() }
            ?: Uuid.random().toString()
        val normalized = buildJsonObject {
            entry.forEach { (key, value) -> put(key, value) }
            put("id", id)
        }
        worldEntries[id] = normalized
        worldUpserts[id] = normalized
        worldDeletes.remove(id)
        publishLocked()
        id
    }

    fun deleteWorldEntry(id: String): Boolean = synchronized(lock) {
        if (frozen) return@synchronized false
        val removed = worldEntries.remove(id) != null
        if (removed) {
            worldUpserts.remove(id)
            worldDeletes += id
        }
        publishLocked()
        removed
    }

    fun updateOpening(message: UIMessage) = synchronized(lock) {
        if (frozen) return@synchronized
        messages = listOf(message)
        publishLocked()
    }

    fun registerMacro(name: String, source: String) = synchronized(lock) {
        if (frozen) return@synchronized
        macros[name] = source
        publishLocked()
    }

    fun removeMacro(name: String) = synchronized(lock) {
        if (frozen) return@synchronized
        macros.remove(name)
        publishLocked()
    }

    fun registerSlashCommand(
        name: String,
        source: String,
        aliases: List<String> = emptyList(),
        helpString: String = "",
    ) = synchronized(lock) {
        if (frozen) return@synchronized
        slashCommands[name] = TavernGreetingSlashRegistration(source, aliases, helpString)
        publishLocked()
    }

    fun removeSlashCommand(name: String) = synchronized(lock) {
        if (frozen) return@synchronized
        slashCommands.remove(name)
        publishLocked()
    }

    fun registerSendHook(source: String) = synchronized(lock) {
        if (frozen) return@synchronized
        sendHookSource = source
        publishLocked()
    }

    fun snapshot(): TavernGreetingOverlay = _overlayFlow.value

    fun freezeAndSnapshot(): Pair<TavernGreetingOverlay, TavernGreetingMutationJournal> = synchronized(lock) {
        frozen = true
        snapshotLocked() to TavernGreetingMutationJournal(
            globalVariables = globalVariableMutations.toMap(),
            worldUpserts = worldUpserts.toMap(),
            worldDeletes = worldDeletes.toSet(),
        )
    }

    fun unfreeze() = synchronized(lock) { frozen = false }

    fun markReady() { ready = true }
    fun isReady(): Boolean = ready

    private fun snapshotLocked(): TavernGreetingOverlay = TavernGreetingOverlay(
        messages = messages.toList(),
        chatVariables = JsonObject(chatVariables.toMap()),
        globalVariables = JsonObject(globalVariables.toMap()),
        worldEntries = worldEntries.values.toList(),
        registrations = TavernGreetingRegistrations(macros.toMap(), slashCommands.toMap(), sendHookSource),
    )

    private fun publishLocked() {
        _overlayFlow.value = snapshotLocked()
    }

    internal fun runtimeBindings(): TavernGreetingRuntimeBindings = TavernGreetingRuntimeBindings(
        worldRepository = this,
        variableGateway = this,
        scriptRegistry = candidateScriptRegistry,
        registrationObserver = this,
        currentMessageWriter = ::writeCurrentMessage,
    )

    override fun get(conversationId: Uuid?, scope: String, key: String, ownerId: String?): JsonElement? =
        synchronized(lock) {
            variables(scope.toGreetingScope())[key]
        }

    override fun list(conversationId: Uuid?, scope: String, ownerId: String?): JsonObject =
        listVariables(scope.toGreetingScope())

    override fun set(conversationId: Uuid?, scope: String, key: String, value: JsonElement, ownerId: String?) {
        setVariable(scope.toGreetingScope(), key, value)
    }

    override fun delete(conversationId: Uuid?, scope: String, key: String, ownerId: String?): Boolean =
        deleteVariable(scope.toGreetingScope(), key)

    override fun replace(conversationId: Uuid?, scope: String, variables: JsonObject, ownerId: String?) {
        synchronized(lock) {
            if (frozen) return@synchronized
            val greetingScope = scope.toGreetingScope()
            val store = if (greetingScope == TavernGreetingVariableScope.CHAT) chatVariables else globalVariables
            val removedKeys = store.keys - variables.keys
            store.clear()
            variables.forEach { (key, value) -> store[key] = value }
            if (greetingScope == TavernGreetingVariableScope.GLOBAL) {
                removedKeys.forEach { globalVariableMutations[it] = null }
                variables.forEach { (key, value) -> globalVariableMutations[key] = value }
            }
            publishLocked()
        }
    }

    override fun listEntries(): List<JsonObject> = synchronized(lock) { worldEntries.values.toList() }

    override fun upsertEntry(entry: JsonObject): String = upsertWorldEntry(entry)

    override fun deleteEntry(id: String): Boolean = deleteWorldEntry(id)

    // 候选运行时的世界模型是平铺条目集（无独立 book 实体）；
    // book 级 API 以单一合成 book 暴露，book 级变更不支持（返回 null/false）。
    override fun listBooks(): List<JsonObject> = synchronized(lock) {
        if (worldEntries.isEmpty()) return@synchronized emptyList()
        listOf(greetingBookJson(includeEntries = false))
    }

    override fun getBook(nameOrId: String): JsonObject? = synchronized(lock) {
        if (worldEntries.isEmpty()) return@synchronized null
        if (nameOrId != GREETING_BOOK_ID && nameOrId != GREETING_BOOK_NAME) return@synchronized null
        greetingBookJson(includeEntries = true)
    }

    override fun createBook(name: String, entries: List<JsonObject>): JsonObject? = null

    override fun updateBook(nameOrId: String, patch: JsonObject): JsonObject? = null

    override fun deleteBook(nameOrId: String): Boolean = false

    private fun greetingBookJson(includeEntries: Boolean): JsonObject = buildJsonObject {
        put("id", GREETING_BOOK_ID)
        put("name", GREETING_BOOK_NAME)
        put("entryCount", worldEntries.size)
        if (includeEntries) put("entries", kotlinx.serialization.json.JsonArray(worldEntries.values.toList()))
    }

    override fun onMacroRegistered(name: String, source: String) = registerMacro(name, source)

    override fun onMacroRemoved(name: String) {
        removeMacro(name)
    }

    override fun onSlashCommandRegistered(
        name: String,
        source: String,
        aliases: List<String>,
        helpString: String,
    ) = registerSlashCommand(name, source, aliases, helpString)

    override fun onSlashCommandRemoved(name: String) {
        removeSlashCommand(name)
    }

    override fun onSendHookRegistered(source: String) = registerSendHook(source)

    private fun writeCurrentMessage(patch: JsonElement) = synchronized(lock) {
        if (frozen) return@synchronized
        val replacementText = when (patch) {
            is JsonPrimitive -> patch.content
            is JsonObject -> (patch["text"] as? JsonPrimitive)?.content
            else -> null
        } ?: return@synchronized
        val current = messages.firstOrNull() ?: return@synchronized
        messages = listOf(
            current.copy(
                parts = current.parts.mapIndexed { index, part ->
                    if (index == 0 && part is me.rerere.ai.ui.UIMessagePart.Text) {
                        part.copy(text = replacementText)
                    } else {
                        part
                    }
                },
            )
        )
        publishLocked()
    }

    private fun variables(scope: TavernGreetingVariableScope) =
        if (scope == TavernGreetingVariableScope.CHAT) chatVariables else globalVariables

    private companion object {
        const val GREETING_BOOK_ID = "greeting"
        const val GREETING_BOOK_NAME = "greeting"

        fun entryId(entry: JsonObject): String =
            (entry["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: Uuid.random().toString()
    }
}

internal data class TavernGreetingRuntimeBindings(
    val worldRepository: TavernWorldRepository,
    val variableGateway: TavernRuntimeVariableGateway,
    val scriptRegistry: TavernScriptRegistry,
    val registrationObserver: TavernRuntimeRegistrationObserver,
    val currentMessageWriter: (JsonElement) -> Unit,
)

private fun String.toGreetingScope(): TavernGreetingVariableScope = when (this) {
    TAVERN_VARIABLE_SCOPE_GLOBAL -> TavernGreetingVariableScope.GLOBAL
    TAVERN_VARIABLE_SCOPE_CHAT -> TavernGreetingVariableScope.CHAT
    else -> TavernGreetingVariableScope.CHAT
}

data class TavernGreetingCandidate(
    val id: Uuid = Uuid.random(),
    val greetingIndex: Int,
    val openingRef: TavernOpeningRef,
    val renderedOpening: String,
    val runtime: TavernGreetingCandidateRuntime,
) {
    fun overlay(): TavernGreetingOverlay = runtime.snapshot()

    fun snapshot(): TavernGreetingCandidateSnapshot {
        val (overlay, journal) = runtime.freezeAndSnapshot()
        return TavernGreetingCandidateSnapshot(
            id, greetingIndex, openingRef, renderedOpening, overlay, journal, runtime.isReady(),
        )
    }
}

data class TavernGreetingCandidateSnapshot(
    val id: Uuid,
    val greetingIndex: Int,
    val openingRef: TavernOpeningRef,
    val renderedOpening: String,
    val overlay: TavernGreetingOverlay,
    val journal: TavernGreetingMutationJournal = TavernGreetingMutationJournal(),
    val runtimeExecuted: Boolean = true,
)

fun interface TavernGreetingCommitTarget {
    suspend fun commit(candidate: TavernGreetingCandidateSnapshot)
}

class TavernGreetingLockedException(message: String) : IllegalStateException(message)

internal fun TavernGreetingSession?.requestCommitForSend(willSendNewMessage: Boolean): Boolean {
    if (!willSendNewMessage) return true
    return this?.requestSelectedCommit() ?: true
}

/** Owns one pre-user-message selection transaction for a conversation. */
class TavernGreetingSession private constructor(
    val conversationId: Uuid,
    initialCandidates: List<TavernGreetingCandidate>,
    initiallyLocked: Boolean,
    private val commitTarget: TavernGreetingCommitTarget,
) {
    private val commitMutex = Mutex()
    private var activeCandidates = initialCandidates
    @Volatile
    private var selectedCandidateId: Uuid? = initialCandidates.firstOrNull()?.id
    @Volatile
    private var commitRequested = false

    val candidates: List<TavernGreetingCandidate> get() = activeCandidates
    var committedCandidateId: Uuid? = null
        private set
    var isLocked: Boolean = initiallyLocked
        private set

    fun selectCandidate(candidateId: Uuid) {
        if (isLocked) throw TavernGreetingLockedException("Opening is locked after the first user message")
        require(activeCandidates.any { it.id == candidateId }) { "Greeting candidate is no longer active" }
        selectedCandidateId = candidateId
    }

    suspend fun commitSelected(): TavernGreetingCandidateSnapshot {
        val candidateId = selectedCandidateId ?: throw IllegalStateException("No greeting candidate is selected")
        return commit(candidateId)
    }

    suspend fun commit(
        candidateId: Uuid,
        requireReady: Boolean = true,
    ): TavernGreetingCandidateSnapshot = commitMutex.withLock {
        if (isLocked) throw TavernGreetingLockedException("Opening is locked after the first user message")
        val candidate = activeCandidates.firstOrNull { it.id == candidateId }
            ?: throw IllegalStateException("Greeting candidate is no longer active")
        check(!requireReady || candidate.runtime.isReady()) { "Greeting runtime is not ready" }
        val snapshot = candidate.snapshot()
        try {
            commitTarget.commit(snapshot)
        } catch (error: Throwable) {
            candidate.runtime.unfreeze()
            commitRequested = false
            throw error
        }
        committedCandidateId = candidate.id
        selectedCandidateId = null
        activeCandidates = emptyList()
        isLocked = true
        snapshot
    }

    fun lock() {
        isLocked = true
        selectedCandidateId = null
        activeCandidates = emptyList()
    }

    fun markCandidateReady(candidateId: Uuid) {
        activeCandidates.firstOrNull { it.id == candidateId }?.runtime?.markReady()
    }

    @Synchronized
    fun requestCommit(candidateId: Uuid): Boolean {
        if (candidateId != selectedCandidateId || commitRequested) return false
        commitRequested = true
        return true
    }

    fun requestSelectedCommit(): Boolean = selectedCandidateId?.let(::requestCommit) == true

    fun isSelectedCandidateReady(): Boolean = activeCandidates
        .firstOrNull { it.id == selectedCandidateId }
        ?.runtime
        ?.isReady() == true && !commitRequested

    companion object {
        fun create(
            conversation: Conversation,
            card: TavernCharacterCard,
            initialChatVariables: JsonObject,
            initialGlobalVariables: JsonObject,
            initialWorldEntries: List<JsonObject>,
            commitTarget: TavernGreetingCommitTarget,
        ): TavernGreetingSession {
            val statusTemplate = extractTavernCardStatusTemplate(card.extensions)
            val candidates = card.allGreetings().mapIndexed { index, opening ->
                val prepared = prepareTavernOpening(
                    card.openingMessage(index),
                    initialChatVariables,
                    statusTemplate,
                )
                TavernGreetingCandidate(
                    greetingIndex = index,
                    openingRef = card.openingRef(index),
                    renderedOpening = opening,
                    runtime = TavernGreetingCandidateRuntime(
                        TavernGreetingOverlay(
                            messages = listOf(prepared.message),
                            chatVariables = prepared.variables,
                            globalVariables = initialGlobalVariables,
                            worldEntries = initialWorldEntries,
                            registrations = TavernGreetingRegistrations(),
                        )
                    ),
                )
            }
            return TavernGreetingSession(
                conversationId = conversation.id,
                initialCandidates = candidates,
                initiallyLocked = requiresNewConversationForGreetingChange(conversation),
                commitTarget = commitTarget,
            )
        }
    }
}

private data class PreparedTavernOpening(
    val message: UIMessage,
    val variables: JsonObject,
)

private val OPENING_UPDATE_VARIABLE = Regex(
    """<UpdateVariable>\s*(?:<Analysis>.*?</Analysis>\s*)?<JSONPatch>(.*?)</JSONPatch>\s*</UpdateVariable>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val OPENING_STATUS_PLACEHOLDER = Regex(
    """<StatusPlaceHolderImpl\s*/>""",
    RegexOption.IGNORE_CASE,
)
private val OPENING_REQUIRES_ISOLATED_HTML = Regex(
    """<!doctype\b|<(?:html|head|body|script|style|iframe)\b""",
    RegexOption.IGNORE_CASE,
)
@OptIn(ExperimentalSerializationApi::class)
private val openingJson = Json {
    ignoreUnknownKeys = true
    allowTrailingComma = true
}

/**
 * ST cards store first_mes as HTML for compatibility even when it is ordinary Markdown plus
 * operational status tags. Resolve those tags before the opening enters the conversation so
 * they cannot leak into an iframe as visible JSON.
 */
private fun prepareTavernOpening(
    message: UIMessage,
    initialVariables: JsonObject,
    statusTemplate: String? = null,
): PreparedTavernOpening {
    var variables: JsonElement = initialVariables
    val preparedParts = message.parts.flatMap { part ->
        if (part !is me.rerere.ai.ui.UIMessagePart.Text) return@flatMap listOf(part)

        var text = part.text
        text = OPENING_UPDATE_VARIABLE.replace(text) { match ->
            runCatching {
                val operations = openingJson.decodeFromString<List<JsonPatchOp>>(match.groupValues[1].trim())
                variables = variables.applyPatch(operations)
                ""
            }.getOrElse { "" }
        }

        val containsStatus = OPENING_STATUS_PLACEHOLDER.containsMatchIn(text)
        text = OPENING_STATUS_PLACEHOLDER.replace(text, "").trim()
        val visibleMode = if (OPENING_REQUIRES_ISOLATED_HTML.containsMatchIn(text)) {
            me.rerere.ai.ui.UIMessagePart.RenderMode.HTML
        } else {
            me.rerere.ai.ui.UIMessagePart.RenderMode.MARKDOWN
        }
        buildList {
            if (text.isNotBlank()) add(part.copy(text = text, renderMode = visibleMode))
            if (containsStatus) {
                add(buildOpeningStatusPart(variables as? JsonObject ?: JsonObject(emptyMap()), statusTemplate))
            }
        }
    }
    return PreparedTavernOpening(
        message = message.copy(
            parts = preparedParts,
            finishedAt = message.finishedAt ?: message.createdAt,
        ),
        variables = variables as? JsonObject ?: initialVariables,
    )
}

private fun buildOpeningStatusPart(
    variables: JsonObject,
    statusTemplate: String? = null,
): me.rerere.ai.ui.UIMessagePart.StatusPlaceholder {
    @Suppress("UNCHECKED_CAST")
    val plain = variables.toPlainValue() as? Map<String, Any?> ?: emptyMap()
    val worldKeys = setOf("世界", "world", "_expression")
    val characters = plain.entries
        .filter { (name, value) -> name !in worldKeys && value is Map<*, *> && value.size >= 2 }
        .sortedBy { it.key }
    val pages = if (statusTemplate != null || characters.size < 2) {
        emptyList()
    } else {
        characters.map { (name, value) ->
            val html = StringBuilder()
                .append("<div style=\"font-family:sans-serif;font-size:13px;line-height:1.6;\">")
                .append("<div style=\"font-size:15px;font-weight:700;margin-bottom:6px;\">")
                .append(StatusFallbackHtml.escapeHtml(name))
                .append("</div>")
            @Suppress("UNCHECKED_CAST")
            StatusFallbackHtml.appendRows(html, value as Map<String, Any?>)
            html.append("</div>")
            me.rerere.ai.ui.UIMessagePart.CharacterStatusPage(name, html.toString())
        }
    }
    val visibleVariables = if (pages.isEmpty()) plain else plain.filterKeys { it in worldKeys }
    return me.rerere.ai.ui.UIMessagePart.StatusPlaceholder(
        htmlContent = statusTemplate ?: StatusFallbackHtml.build(visibleVariables, emptyMap()),
        characterPages = pages,
    )
}

fun requiresNewConversationForGreetingChange(conversation: Conversation): Boolean =
    conversation.currentMessages.any { it.role == MessageRole.USER }

internal fun mergeCommittedGreeting(
    conversation: Conversation,
    candidate: TavernGreetingCandidateSnapshot,
): Conversation {
    if (requiresNewConversationForGreetingChange(conversation)) {
        throw TavernGreetingLockedException("Opening changes require a new conversation after the first user message")
    }
    val retained = conversation.messageNodes.filterNot { node ->
        node.currentMessage.parts.any { part ->
            part is me.rerere.ai.ui.UIMessagePart.Text && part.tavernOpeningRef() != null
        }
    }
    return conversation.copy(
        messageNodes = retained + candidate.overlay.messages.map { message ->
            message.copy(
                parts = message.parts.map { part ->
                    if (part is me.rerere.ai.ui.UIMessagePart.Text && part.tavernOpeningRef() != null) {
                        if (!candidate.runtimeExecuted) return@map part
                        part.withTavernOpeningRuntimeState(
                            TavernOpeningRuntimeState(
                                macros = candidate.overlay.registrations.macros,
                                slashCommands = candidate.overlay.registrations.slashCommands.mapValues { (_, value) ->
                                    TavernOpeningSlashRegistration(value.source, value.aliases, value.helpString)
                                },
                                sendHookSource = candidate.overlay.registrations.sendHookSource,
                            ),
                        ).markTavernOpeningRuntimeExecuted()
                    } else {
                        part
                    }
                },
            ).toMessageNode()
        },
        statusVariables = candidate.overlay.chatVariables,
    )
}

data class TavernGreetingNavigation(
    val greetingIndex: Int?,
    val legacyGreeting: String? = null,
)

/** New typed navigation wins. Legacy Base64 is decoded only as a compatibility fallback. */
fun resolveGreetingNavigation(
    greetingIndex: Int?,
    legacyGreetingBase64: String?,
    greetings: List<String>,
): TavernGreetingNavigation? {
    if (greetingIndex != null) {
        return greetingIndex.takeIf { it in greetings.indices }?.let(::TavernGreetingNavigation)
    }
    val decoded = legacyGreetingBase64?.takeIf { it.isNotBlank() }?.let { encoded ->
        runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8) }.getOrNull()
    }?.takeIf { it.isNotBlank() } ?: return null
    return TavernGreetingNavigation(greetings.indexOf(decoded).takeIf { it >= 0 }, decoded)
}

data class TavernGreetingConversationRequest(
    val assistantId: Uuid,
    val greetingIndex: Int,
    val conversationId: Uuid = Uuid.random(),
) {
    init {
        require(greetingIndex >= 0)
    }
}
