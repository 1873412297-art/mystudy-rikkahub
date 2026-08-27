package me.rerere.rikkahub.ui.pages.tavern

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.TavernCardV2
import me.rerere.rikkahub.data.model.TavernCharacterCard
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

class TavernCardEditorVM(
    private val assistantId: String,
    private val settingsStore: SettingsStore,
    private val conversationRepository: ConversationRepository,
    private val chatService: ChatService,
) : ViewModel() {
    private val parsedAssistantId = Uuid.parse(assistantId)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    val assistant: StateFlow<Assistant?> = settingsStore.settingsFlowRaw
        .map { settings -> settings.assistants.find { it.id.toString() == assistantId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val settings = settingsStore.settingsFlow

    val previewTargets: StateFlow<List<Conversation>> = conversationRepository
        .getConversationsOfAssistant(parsedAssistantId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val previewTargetSelection = TavernGreetingPreviewTargetSelection(parsedAssistantId)
    private val previewConversationLease = TavernPreviewConversationLease(
        acquire = chatService::addConversationReference,
        release = chatService::removeConversationReference,
    )
    private val previewSideEffectQueue = TavernPreviewSideEffectQueue(
        dispatcher = Dispatchers.IO,
        acquire = chatService::addConversationReference,
        release = chatService::removeConversationReference,
        onFailure = { error -> chatService.addError(error) },
    )
    internal val selectedPreviewTarget = previewTargetSelection.selected
    internal val selectedPreviewTargetReady = previewTargetSelection.ready
    val selectedPreviewConversation: StateFlow<Conversation?> = selectedPreviewTarget
        .flatMapLatest { target ->
            target?.let { chatService.getConversationFlow(it.conversationId).map { conversation -> conversation } }
                ?: flowOf(null)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _card = MutableStateFlow(TavernCharacterCard.empty())
    val card: StateFlow<TavernCharacterCard> = _card.asStateFlow()

    private var loaded = false

    init {
        viewModelScope.launch {
            val current = assistant.value ?: return@launch
            loadCard(current)
        }
    }

    private fun loadCard(source: Assistant) {
        if (loaded) return
        loaded = true
        val existing = source.tavernCardJson
        if (existing != null) {
            try {
                val root = json.parseToJsonElement(existing).jsonObject
                val spec = root["spec"]?.let { p ->
                    if (p is JsonPrimitive && p.isString) p.content else ""
                } ?: ""
                when {
                    spec == "chara_card_v2" -> {
                        val v2 = json.decodeFromString<TavernCardV2>(existing)
                        _card.value = TavernCharacterCard.fromWrapper(v2)
                    }
                    spec == "chara_card_v3" -> {
                        val v3 = json.decodeFromString<me.rerere.rikkahub.data.model.TavernCardV3>(existing)
                        _card.value = TavernCharacterCard.fromWrapper(v3)
                    }
                    else -> {
                        val v1 = json.decodeFromString<me.rerere.rikkahub.data.model.TavernCardV1>(existing)
                        _card.value = TavernCharacterCard.fromV1(v1)
                    }
                }
            } catch (_: Exception) {
                _card.value = reverseEngineerFromAssistant(source)
            }
        } else {
            _card.value = reverseEngineerFromAssistant(source)
        }
    }

    private fun reverseEngineerFromAssistant(source: Assistant): TavernCharacterCard {
        return extractFieldsFromPrompt(source.systemPrompt).let { fields ->
            TavernCharacterCard(
                spec = "chara_card_v2",
                specVersion = "2.0",
                name = source.name,
                description = fields.description,
                personality = fields.personality,
                scenario = fields.scenario,
                firstMes = source.presetMessages
                    .filter { it.role == me.rerere.ai.core.MessageRole.ASSISTANT }
                    .firstOrNull()
                    ?.parts?.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
                    ?.firstOrNull()?.text ?: "",
                mesExample = fields.mesExample,
                systemPrompt = source.systemPrompt,
                postHistoryInstructions = fields.postHistoryInstructions,
                creatorNotes = "",
                alternateGreetings = emptyList(),
                characterBook = null,
                tags = emptyList(),
                creator = "",
                characterVersion = "",
                extensions = null,
            )
        }
    }

    fun update(transform: (TavernCharacterCard) -> TavernCharacterCard) {
        _card.value = transform(_card.value)
    }

    fun save() {
        val currentCard = _card.value
        val currentAssistant = assistant.value ?: return

        val cardJson = buildJsonObject {
            put("spec", JsonPrimitive("chara_card_v2"))
            put("spec_version", JsonPrimitive("2.0"))
            put("data", buildJsonObject {
                put("name", JsonPrimitive(currentCard.name))
                put("description", JsonPrimitive(currentCard.description))
                put("personality", JsonPrimitive(currentCard.personality))
                put("scenario", JsonPrimitive(currentCard.scenario))
                put("first_mes", JsonPrimitive(currentCard.firstMes))
                put("mes_example", JsonPrimitive(currentCard.mesExample))
                put("creator_notes", JsonPrimitive(currentCard.creatorNotes))
                put("system_prompt", JsonPrimitive(currentCard.systemPrompt))
                put("post_history_instructions", JsonPrimitive(currentCard.postHistoryInstructions))
                putJsonArray("alternate_greetings") {
                    currentCard.alternateGreetings.forEach { add(JsonPrimitive(it)) }
                }
                putJsonArray("tags") {
                    currentCard.tags.forEach { add(JsonPrimitive(it)) }
                }
                put("creator", JsonPrimitive(currentCard.creator))
                put("character_version", JsonPrimitive(currentCard.characterVersion))
                // Character book
                currentCard.characterBook?.let { book ->
                    put("character_book", buildJsonObject {
                        book.name?.let { put("name", JsonPrimitive(it)) }
                        book.description?.let { put("description", JsonPrimitive(it)) }
                        book.scanDepth?.let { put("scan_depth", JsonPrimitive(it)) }
                        book.tokenBudget?.let { put("token_budget", JsonPrimitive(it)) }
                        book.recursiveScanning?.let { put("recursive_scanning", JsonPrimitive(it)) }
                        book.extensions?.let { put("extensions", it) }
                        putJsonArray("entries") {
                            book.entries.forEach { entry ->
                                add(buildJsonObject {
                                    putJsonArray("keys") { entry.keys.forEach { add(JsonPrimitive(it)) } }
                                    put("content", JsonPrimitive(entry.content))
                                    put("enabled", JsonPrimitive(entry.enabled))
                                    put("insertion_order", JsonPrimitive(entry.insertionOrder))
                                    entry.caseSensitive?.let { put("case_sensitive", JsonPrimitive(it)) }
                                    entry.name?.let { put("name", JsonPrimitive(it)) }
                                    entry.priority?.let { put("priority", JsonPrimitive(it)) }
                                    entry.constant?.let { put("constant", JsonPrimitive(it)) }
                                    entry.position?.let { put("position", JsonPrimitive(it)) }
                                    entry.id?.let { put("id", JsonPrimitive(it)) }
                                    entry.comment?.let { put("comment", JsonPrimitive(it)) }
                                    entry.selective?.let { put("selective", JsonPrimitive(it)) }
                                    entry.secondaryKeys?.let { keys ->
                                        putJsonArray("secondary_keys") { keys.forEach { add(JsonPrimitive(it)) } }
                                    }
                                    entry.depth?.let { put("depth", JsonPrimitive(it)) }
                                    entry.extensions?.let { put("extensions", it) }
                                })
                            }
                        }
                    })
                }
                // Extensions
                currentCard.extensions?.let { put("extensions", it) }
            })
        }.toString()

        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id.toString() == assistantId)
                            it.copy(tavernCardJson = cardJson)
                        else it
                    }
                )
            }
        }
    }

    fun selectPreviewTarget(conversation: Conversation) {
        require(conversation.assistantId == parsedAssistantId) {
            "Preview target must belong to the edited Tavern assistant"
        }
        val current = selectedPreviewTarget.value
        if (current?.conversationId == conversation.id) return
        previewTargetSelection.select(
            TavernGreetingPreviewTarget(
                conversationId = conversation.id,
                assistantId = conversation.assistantId,
                title = conversation.title.ifBlank { "未命名对话" },
            ),
        )
        previewConversationLease.switchTo(conversation.id)
        viewModelScope.launch {
            runCatching { chatService.initializeConversation(conversation.id) }
                .onSuccess {
                    if (selectedPreviewTarget.value?.conversationId == conversation.id) {
                        previewTargetSelection.markReady(conversation.id)
                    }
                }
                .onFailure {
                    if (selectedPreviewTarget.value?.conversationId == conversation.id) {
                        previewConversationLease.clear()
                        previewTargetSelection.clear()
                    }
                }
        }
    }

    fun writePreviewCurrentMessage(expectedConversationId: Uuid, patch: JsonElement) {
        previewTargetSelection.routeMessageWrite(expectedConversationId, patch) { conversationId, routedPatch ->
            previewSideEffectQueue.submit(conversationId) {
                chatService.applyTavernPreviewCurrentMessagePatch(conversationId, routedPatch)
            }
        }
    }

    fun writePreviewChatVariables(expectedConversationId: Uuid, variables: JsonObject) {
        previewTargetSelection.routeChatVariables(expectedConversationId, variables) { conversationId, routedVariables ->
            previewSideEffectQueue.submit(conversationId) {
                chatService.persistTavernPreviewChatVariables(conversationId, routedVariables)
            }
        }
    }

    fun validatePreviewTarget(expectedConversationId: Uuid) {
        previewTargetSelection.validateTarget(expectedConversationId)
    }

    override fun onCleared() {
        previewSideEffectQueue.close()
        previewConversationLease.clear()
        previewTargetSelection.clear()
    }

    companion object {
        private fun extractFieldsFromPrompt(prompt: String): ExtractedFields {
            val remaining = prompt
            var description = ""
            var personality = ""
            var scenario = ""
            var mesExample = ""
            var postHistory = ""

            val descMatch = Regex("""##\s*Description of the character\s*\n(.*?)(?=##\s*|\z)""", RegexOption.DOT_MATCHES_ALL).find(remaining)
            descMatch?.let { description = it.groupValues[1].trim() }

            val persMatch = Regex("""##\s*Personality of the character\s*\n(.*?)(?=##\s*|\z)""", RegexOption.DOT_MATCHES_ALL).find(remaining)
            persMatch?.let { personality = it.groupValues[1].trim() }

            val scenMatch = Regex("""##\s*Scenario\s*\n(.*?)(?=##\s*|\z)""", RegexOption.DOT_MATCHES_ALL).find(remaining)
            scenMatch?.let { scenario = it.groupValues[1].trim() }

            val exMatch = Regex("""##\s*Example Dialogue\s*\n(.*?)(?=##\s*|\z)""", RegexOption.DOT_MATCHES_ALL).find(remaining)
            exMatch?.let { mesExample = it.groupValues[1].trim() }

            val postMatch = Regex("""##\s*Post-history Instructions\s*\n(.*?)\z""", RegexOption.DOT_MATCHES_ALL).find(remaining)
            postMatch?.let { postHistory = it.groupValues[1].trim() }

            return ExtractedFields(description, personality, scenario, mesExample, postHistory)
        }

        data class ExtractedFields(
            val description: String = "",
            val personality: String = "",
            val scenario: String = "",
            val mesExample: String = "",
            val postHistoryInstructions: String = "",
        )
    }
}

fun TavernCharacterCard.Companion.empty(): TavernCharacterCard = TavernCharacterCard(
    spec = "chara_card_v2",
    specVersion = "2.0",
    name = "",
    description = "",
    personality = "",
    scenario = "",
    firstMes = "",
    mesExample = "",
    systemPrompt = "",
    postHistoryInstructions = "",
    creatorNotes = "",
    alternateGreetings = emptyList(),
    characterBook = null,
    tags = emptyList(),
    creator = "",
    characterVersion = "",
    extensions = null,
)
