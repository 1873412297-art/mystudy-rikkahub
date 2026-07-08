package me.rerere.rikkahub.ui.pages.tavern

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.TavernCardV2
import me.rerere.rikkahub.data.model.TavernCharacterCard

class TavernCardEditorVM(
    private val assistantId: String,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    val assistant: StateFlow<Assistant?> = settingsStore.settingsFlowRaw
        .map { settings -> settings.assistants.find { it.id.toString() == assistantId } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _card = MutableStateFlow(TavernCharacterCard.empty())
    val card: StateFlow<TavernCharacterCard> = _card.asStateFlow()

    private var loaded = false

    init {
        scope.launch {
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
                                })
                            }
                        }
                    })
                }
                // Extensions
                currentCard.extensions?.let { put("extensions", it) }
            })
        }.toString()

        scope.launch {
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
