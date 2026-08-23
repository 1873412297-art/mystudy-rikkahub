package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.dokar.sonner.ToastType
import com.dokar.sonner.ToasterState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.withImportedTavernCardImage
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.normalizeTavernCardText
import me.rerere.rikkahub.data.model.TavernCharacterCard
import me.rerere.rikkahub.data.model.openingRef
import me.rerere.rikkahub.data.model.withTavernOpening
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun AssistantImporter(
    modifier: Modifier = Modifier,
    onUpdate: (Assistant) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        SillyTavernImporter(onImport = onUpdate)
    }
}

@Composable
private fun SillyTavernImporter(
    onImport: (Assistant) -> Unit
) {
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var isLoading by remember { mutableStateOf(false) }

    val pngPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            isLoading = true
            scope.launch {
                try {
                    runCatching {
                        importAssistantFromUri(
                            context = context,
                            uri = uri,
                            onImport = onImport,
                            toaster = toaster,
                            filesManager = filesManager,
                            settingsStore = settingsStore,
                        )
                    }.onFailure { exception ->
                        exception.printStackTrace()
                        toaster.show(exception.message ?: context.getString(R.string.assistant_importer_import_failed))
                    }
                } finally {
                    isLoading = false
                }
            }
        }
    }

    val jsonPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            isLoading = true
            scope.launch {
                try {
                    runCatching {
                        importAssistantFromUri(
                            context = context,
                            uri = uri,
                            onImport = onImport,
                            toaster = toaster,
                            filesManager = filesManager,
                            settingsStore = settingsStore,
                        )
                    }.onFailure { exception ->
                        exception.printStackTrace()
                        toaster.show(exception.message ?: context.getString(R.string.assistant_importer_import_failed))
                    }
                } finally {
                    isLoading = false
                }
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = {
                pngPickerLauncher.launch(arrayOf("image/png"))
            },
            enabled = !isLoading
        ) {
            AutoAIIcon(name = "tavern", modifier = Modifier.padding(end = 8.dp))
            Text(text = if (isLoading) stringResource(R.string.assistant_importer_importing) else stringResource(R.string.assistant_importer_import_tavern_png))
        }

        OutlinedButton(
            onClick = {
                jsonPickerLauncher.launch(arrayOf("application/json"))
            },
            enabled = !isLoading
        ) {
            AutoAIIcon(name = "tavern", modifier = Modifier.padding(end = 8.dp))
            Text(text = if (isLoading) stringResource(R.string.assistant_importer_importing) else stringResource(R.string.assistant_importer_import_tavern_json))
        }
    }
}

// region Card Data Extraction

/**
 * Extracted card data from V2/V3 JSON, used for building lorebooks and regexes.
 */
private data class CardExtras(
    val characterBook: CharacterBookData? = null,
    val regexes: List<AssistantRegex> = emptyList(),
    val statusRenderJs: String? = null,
    val statusCss: String? = null,
)

private data class CharacterBookData(
    val name: String?,
    val description: String?,
    val scanDepth: Int?,
    val entries: List<CharacterBookEntryData>,
)

private data class CharacterBookEntryData(
    val keys: List<String>,
    val content: String,
    val enabled: Boolean,
    val insertionOrder: Int,
    val caseSensitive: Boolean?,
    val name: String?,
    val priority: Int?,
    val constant: Boolean?,
    val position: String?, // "before_char" or "after_char"
    val selective: Boolean?,
    val secondaryKeys: List<String>,
    val probability: Int?, // ST: entry.extensions.probability，0-100 整数
    val depth: Int?, // ST: entry.depth（@Depth 语义，从最新消息往前数）
    val extensionsPosition: Int?, // ST: entry.extensions.position 数值枚举
    val extensionsRole: Int?, // ST: entry.extensions.role（0=system, 1=user, 2=assistant）
    val sticky: Int?, // ST: entry.extensions.sticky
    val cooldown: Int?, // ST: entry.extensions.cooldown
    val delay: Int?, // ST: entry.extensions.delay
)

/**
 * Extract character book entries and regex extensions from raw card data JSON.
 */
private fun extractCardExtras(data: JsonObject): CardExtras {
    var characterBook = data["character_book"]?.jsonObject?.let { bookJson ->
        CharacterBookData(
            name = bookJson["name"]?.jsonPrimitiveOrNull?.contentOrNull,
            description = bookJson["description"]?.jsonPrimitiveOrNull?.contentOrNull,
            scanDepth = bookJson["scan_depth"]?.jsonPrimitive?.intOrNull,
            entries = bookJson["entries"]?.jsonArray?.mapNotNull { entryJson ->
                val entryObj = entryJson.jsonObject
                val extensionsObj = entryObj["extensions"] as? JsonObject
                CharacterBookEntryData(
                    keys = entryObj["keys"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                    content = entryObj["content"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
                    enabled = entryObj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                    insertionOrder = entryObj["insertion_order"]?.jsonPrimitive?.intOrNull ?: 0,
                    caseSensitive = entryObj["case_sensitive"]?.jsonPrimitive?.booleanOrNull,
                    name = entryObj["name"]?.jsonPrimitiveOrNull?.contentOrNull,
                    priority = entryObj["priority"]?.jsonPrimitive?.intOrNull,
                    constant = entryObj["constant"]?.jsonPrimitive?.booleanOrNull,
                    position = entryObj["position"]?.jsonPrimitiveOrNull?.contentOrNull,
                    selective = entryObj["selective"]?.jsonPrimitiveOrNull?.booleanOrNull,
                    secondaryKeys = (entryObj["secondary_keys"] as? JsonArray)?.mapNotNull {
                        it.jsonPrimitiveOrNull?.contentOrNull
                    } ?: emptyList(),
                    // ST 把触发概率放在 entry.extensions.probability（0-100），部分导出也写到顶层
                    probability = extensionsObj?.get("probability")?.jsonPrimitiveOrNull?.intOrNull
                        ?: entryObj["probability"]?.jsonPrimitiveOrNull?.intOrNull,
                    // ST @Depth 语义：顶层 entry.depth，部分导出放在 extensions.depth
                    depth = entryObj["depth"]?.jsonPrimitiveOrNull?.intOrNull
                        ?: extensionsObj?.get("depth")?.jsonPrimitiveOrNull?.intOrNull,
                    extensionsPosition = extensionsObj?.get("position")?.jsonPrimitiveOrNull?.intOrNull,
                    extensionsRole = extensionsObj?.get("role")?.jsonPrimitiveOrNull?.intOrNull,
                    // ST 触发装饰器放在 entry.extensions.sticky/cooldown/delay，部分导出写到顶层
                    sticky = extensionsObj?.get("sticky")?.jsonPrimitiveOrNull?.intOrNull
                        ?: entryObj["sticky"]?.jsonPrimitiveOrNull?.intOrNull,
                    cooldown = extensionsObj?.get("cooldown")?.jsonPrimitiveOrNull?.intOrNull
                        ?: entryObj["cooldown"]?.jsonPrimitiveOrNull?.intOrNull,
                    delay = extensionsObj?.get("delay")?.jsonPrimitiveOrNull?.intOrNull
                        ?: entryObj["delay"]?.jsonPrimitiveOrNull?.intOrNull,
                )
            } ?: emptyList()
        )
    }

    // Extract regex scripts from extensions
    val regexes = mutableListOf<AssistantRegex>()
    val extensions = data["extensions"]?.jsonObject
    if (extensions != null) {
        // SillyTavern format: extensions.regex is an array of regex scripts
        val regexArray = extensions["regex"]?.jsonArray
        if (regexArray != null) {
            for (regexJson in regexArray) {
                parseStRegexScript(regexJson.jsonObject)?.let { regexes.add(it) }
            }
        }
    }

    // Extract world info entries from extensions (ST stores world info inline or as file reference)
    if (characterBook == null && extensions != null) {
        val worldJson = extensions["world"]
        if (worldJson != null) {
            // World info can be an object with entries or a file reference string
            val worldEntries = when (worldJson) {
                is kotlinx.serialization.json.JsonObject -> {
                    worldJson["entries"]?.jsonObject?.values?.mapNotNull { it.jsonObject } ?: emptyList()
                }
                else -> emptyList()
            }
            if (worldEntries.isNotEmpty()) {
                characterBook = CharacterBookData(
                    name = worldJson.jsonObject["name"]?.jsonPrimitiveOrNull?.contentOrNull,
                    description = worldJson.jsonObject["description"]?.jsonPrimitiveOrNull?.contentOrNull,
                    scanDepth = worldJson.jsonObject["scan_depth"]?.jsonPrimitive?.intOrNull,
                    entries = worldEntries.map { entry ->
                        val entryExtensions = entry["extensions"] as? JsonObject
                        CharacterBookEntryData(
                            keys = entry["key"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                                ?: emptyList(),
                            content = entry["content"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
                            enabled = entry["disable"]?.jsonPrimitive?.booleanOrNull?.not() ?: true,
                            insertionOrder = entry["order"]?.jsonPrimitive?.intOrNull ?: 0,
                            caseSensitive = entry["caseSensitive"]?.jsonPrimitive?.booleanOrNull,
                            name = entry["comment"]?.jsonPrimitiveOrNull?.contentOrNull,
                            priority = entry["order"]?.jsonPrimitive?.intOrNull,
                            constant = entry["constant"]?.jsonPrimitive?.booleanOrNull,
                            position = when (entry["position"]?.jsonPrimitiveOrNull?.contentOrNull) {
                                "before_char" -> "before_char"
                                "after_char" -> "after_char"
                                else -> null
                            },
                            selective = entry["selective"]?.jsonPrimitiveOrNull?.booleanOrNull,
                            // ST 世界书次关键词：keysecondary / secondary_keys 两种命名都兼容
                            secondaryKeys = (entry["keysecondary"] as? JsonArray)?.mapNotNull {
                                it.jsonPrimitiveOrNull?.contentOrNull
                            } ?: (entry["secondary_keys"] as? JsonArray)?.mapNotNull {
                                it.jsonPrimitiveOrNull?.contentOrNull
                            } ?: emptyList(),
                            // 该路径条目为 ST 世界书风格：probability/depth 位于顶层，position 为数值枚举
                            probability = entry["probability"]?.jsonPrimitiveOrNull?.intOrNull,
                            depth = entry["depth"]?.jsonPrimitiveOrNull?.intOrNull,
                            extensionsPosition = entry["position"]?.jsonPrimitiveOrNull?.intOrNull,
                            extensionsRole = entryExtensions?.get("role")?.jsonPrimitiveOrNull?.intOrNull,
                            // 触发装饰器：extensions 优先，顶层兜底
                            sticky = entryExtensions?.get("sticky")?.jsonPrimitiveOrNull?.intOrNull
                                ?: entry["sticky"]?.jsonPrimitiveOrNull?.intOrNull,
                            cooldown = entryExtensions?.get("cooldown")?.jsonPrimitiveOrNull?.intOrNull
                                ?: entry["cooldown"]?.jsonPrimitiveOrNull?.intOrNull,
                            delay = entryExtensions?.get("delay")?.jsonPrimitiveOrNull?.intOrNull
                                ?: entry["delay"]?.jsonPrimitiveOrNull?.intOrNull,
                        )
                    }
                )
            }
        }
    }

    // Extract status render script and CSS from extensions
    var statusRenderJs: String? = null
    var statusCss: String? = null
    if (extensions != null) {
        // Try various known field names for status render JS
        statusRenderJs = extensions["status_script"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: extensions["status_js"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: extensions["js"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: extensions["script"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: extensions["status"]?.jsonObject?.get("script")?.jsonPrimitiveOrNull?.contentOrNull
            ?: extensions["status"]?.jsonObject?.get("status_script")?.jsonPrimitiveOrNull?.contentOrNull

        // Try various known field names for status CSS
        statusCss = extensions["status_css"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: extensions["css"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: extensions["status"]?.jsonObject?.get("css")?.jsonPrimitiveOrNull?.contentOrNull
            ?: extensions["status"]?.jsonObject?.get("status_css")?.jsonPrimitiveOrNull?.contentOrNull
    }

    return CardExtras(
        characterBook = characterBook,
        regexes = regexes,
        statusRenderJs = statusRenderJs,
        statusCss = statusCss,
    )
}

/**
 * 解析单条 ST 正则脚本（extensions.regex 数组元素）。
 * 容忍字段缺失；flags 与 depth 字段见 [parseStRegexFlags]。
 */
internal fun parseStRegexScript(obj: JsonObject): AssistantRegex? {
    val findRegex = obj["regex"]?.jsonPrimitiveOrNull?.contentOrNull ?: return null
    val replaceString = obj["replacement"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
    val scope = obj["scope"]?.jsonPrimitiveOrNull?.contentOrNull
    val enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
    val name = obj["name"]?.jsonPrimitiveOrNull?.contentOrNull ?: findRegex.take(30)
    val visualOnly = obj["visual_only"]?.jsonPrimitive?.booleanOrNull ?: false

    val affectingScope = when (scope) {
        "global" -> setOf(AssistantAffectScope.USER, AssistantAffectScope.ASSISTANT)
        "user" -> setOf(AssistantAffectScope.USER)
        "assistant" -> setOf(AssistantAffectScope.ASSISTANT)
        else -> setOf(AssistantAffectScope.ASSISTANT) // default: affect assistant output
    }

    return AssistantRegex(
        id = Uuid.random(),
        name = name,
        enabled = enabled,
        findRegex = findRegex,
        replaceString = replaceString,
        affectingScope = affectingScope,
        visualOnly = visualOnly,
        options = parseStRegexFlags(obj),
        minDepth = obj["minDepth"]?.jsonPrimitiveOrNull?.intOrNull
            ?: obj["min_depth"]?.jsonPrimitiveOrNull?.intOrNull,
        maxDepth = obj["maxDepth"]?.jsonPrimitiveOrNull?.intOrNull
            ?: obj["max_depth"]?.jsonPrimitiveOrNull?.intOrNull,
    )
}

/**
 * 解析 ST 正则脚本的修饰标志字段 flags，容忍多种格式：
 * - JS 风格字符串，如 "i"、"ms"、"ims"（i=忽略大小写，m=多行，s=点匹配换行）
 * - 字符串数组，如 ["IGNORE_CASE", "MULTILINE"]（枚举名，大小写不敏感）
 * - 缺失或无法识别时返回空集合
 */
internal fun parseStRegexFlags(obj: JsonObject): Set<RegexOption> {
    val element = obj["flags"] ?: return emptySet()
    val tokens: List<String> = when (element) {
        is JsonArray -> element.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
        is JsonPrimitive -> {
            // JsonNull 也是 JsonPrimitive，contentOrNull 可能为 null，统一按空串处理
            val content = element.contentOrNull.orEmpty()
            if (content.length > 1 && content.all { it.lowercaseChar() in "ims" }) {
                // JS 风格连续字母，如 "ims"
                content.map { it.toString() }
            } else {
                listOf(content)
            }
        }

        else -> emptyList()
    }
    return tokens.mapNotNull { token ->
        when (token.trim().lowercase()) {
            "i", "ignore_case", "ignorecase" -> RegexOption.IGNORE_CASE
            "m", "multiline" -> RegexOption.MULTILINE
            "s", "dot_matches_all", "dotmatchesall", "dotall" -> RegexOption.DOT_MATCHES_ALL
            else -> null
        }
    }.toSet()
}

// endregion

// region Parsing Strategy

private interface TavernCardParser {
    val specName: String
    fun parse(context: Context, json: JsonObject, background: String?): Pair<Assistant, CardExtras>
}

private class CharaCardV2Parser : TavernCardParser {
    override val specName: String = "chara_card_v2"

    override fun parse(context: Context, json: JsonObject, background: String?): Pair<Assistant, CardExtras> {
        val data = json["data"]?.jsonObject ?: error(context.getString(R.string.assistant_importer_missing_data_field))
        val name = data["name"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: error(context.getString(R.string.assistant_importer_missing_name_field))
        val firstMessage = data["first_mes"]?.jsonPrimitiveOrNull?.contentOrNull
        val system = data["system_prompt"]?.jsonPrimitiveOrNull?.contentOrNull
        val description = data["description"]?.jsonPrimitiveOrNull?.contentOrNull
        val personality = data["personality"]?.jsonPrimitiveOrNull?.contentOrNull
        val scenario = data["scenario"]?.jsonPrimitiveOrNull?.contentOrNull
        val postHistoryInstructions = data["post_history_instructions"]?.jsonPrimitiveOrNull?.contentOrNull

        val alternateGreetings = data["alternate_greetings"]?.jsonArray?.mapNotNull {
            it.jsonPrimitiveOrNull?.contentOrNull
        } ?: emptyList()
        val mesExample = data["mes_example"]?.jsonPrimitiveOrNull?.contentOrNull
        val extras = extractCardExtras(data)
        val prompt = buildAssistantPrompt(
            name = name,
            system = system,
            description = description,
            personality = personality,
            scenario = scenario,
            postHistoryInstructions = postHistoryInstructions,
            mesExample = mesExample
        )

        // Only use first_mes as the default preset message.
        // Alternate greetings are preserved in tavernCardJson and can be
        // selected via the greeting picker in the chat UI.
        // 角色卡 first_mes 经常是完整 HTML 文档（带 CSS 动画/JS 交互），
        // 用 assistantHtml 让渲染端走 sandbox iframe 路径而不是 markdown。
        return Assistant(
            name = name,
            presetMessages = if (firstMessage != null) listOf(UIMessage.assistantHtml(firstMessage)) else emptyList(),
            systemPrompt = prompt,
            background = background
        ) to extras
    }
}

private class CharaCardV3Parser : TavernCardParser {
    override val specName: String = "chara_card_v3"

    override fun parse(context: Context, json: JsonObject, background: String?): Pair<Assistant, CardExtras> {
        val data = json["data"]?.jsonObject ?: error(context.getString(R.string.assistant_importer_missing_data_field))
        val name = data["name"]?.jsonPrimitiveOrNull?.contentOrNull ?: error(context.getString(R.string.assistant_importer_missing_name_field))
        val description = data["description"]?.jsonPrimitiveOrNull?.contentOrNull
        val firstMessage = data["first_mes"]?.jsonPrimitiveOrNull?.contentOrNull
        val system = data["system_prompt"]?.jsonPrimitiveOrNull?.contentOrNull
        val personality = data["personality"]?.jsonPrimitiveOrNull?.contentOrNull
        val scenario = data["scenario"]?.jsonPrimitiveOrNull?.contentOrNull
        val postHistoryInstructions = data["post_history_instructions"]?.jsonPrimitiveOrNull?.contentOrNull
        val alternateGreetings = data["alternate_greetings"]?.jsonArray?.mapNotNull {
            it.jsonPrimitiveOrNull?.contentOrNull
        } ?: emptyList()
        val mesExample = data["mes_example"]?.jsonPrimitiveOrNull?.contentOrNull

        val extras = extractCardExtras(data)
        val prompt = buildAssistantPrompt(
            name = name,
            system = system,
            description = description,
            personality = personality,
            scenario = scenario,
            postHistoryInstructions = postHistoryInstructions,
            mesExample = mesExample
        )

        // Only use first_mes as the default preset message.
        // Alternate greetings are preserved in tavernCardJson and can be
        // selected via the greeting picker in the chat UI.
        // 角色卡 first_mes → assistantHtml（同 V2 路径）
        return Assistant(
            name = name,
            presetMessages = if (firstMessage != null) listOf(UIMessage.assistantHtml(firstMessage)) else emptyList(),
            systemPrompt = prompt,
            background = background
        ) to extras
    }
}

private fun buildAssistantPrompt(
    name: String,
    system: String?,
    description: String?,
    personality: String?,
    scenario: String?,
    postHistoryInstructions: String?,
    mesExample: String? = null,
): String {
    return buildString {
        appendLine("You are roleplaying as $name.")
        appendLine()
        if (!system.isNullOrBlank()) {
            appendLine(system.normalizeTavernCardText(charName = name))
            appendLine()
        }
        if (!description.isNullOrBlank()) {
            appendLine("## Description of the character")
            appendLine(description.normalizeTavernCardText(charName = name))
            appendLine()
        }
        if (!personality.isNullOrBlank()) {
            appendLine("## Personality of the character")
            appendLine(personality.normalizeTavernCardText(charName = name))
            appendLine()
        }
        if (!scenario.isNullOrBlank()) {
            appendLine("## Scenario")
            appendLine(scenario.normalizeTavernCardText(charName = name))
            appendLine()
        }
        if (!mesExample.isNullOrBlank()) {
            appendLine("## Example Dialogue")
            appendLine("The following examples demonstrate how $name speaks and behaves. Use them as a reference for tone, style, and formatting:")
            appendLine()
            appendLine(mesExample.normalizeTavernCardText(charName = name))
            appendLine()
        }
        if (!postHistoryInstructions.isNullOrBlank()) {
            appendLine("## Post-history Instructions")
            appendLine(postHistoryInstructions.normalizeTavernCardText(charName = name))
        }
    }.trim()
}

private val TAVERN_PARSERS: Map<String, TavernCardParser> = listOf(
    CharaCardV2Parser(),
    CharaCardV3Parser(),
    CharaCardV1Parser(),
).associateBy { it.specName }

/**
 * Parse character card JSON. V1 cards (no `spec` field) are detected and converted
 * to V2-equivalent format before parsing, matching ST's getCharaCardV2 behavior.
 */
private fun parseAssistantFromJson(
    context: Context,
    json: JsonObject,
    background: String?,
): Pair<Assistant, CardExtras> {
    val spec = json["spec"]?.jsonPrimitive?.contentOrNull

    if (spec == null) {
        // V1 card: convert to V2-like structure for parsing
        // ST behavior: getCharaCardV2 → convertToV2 when spec is undefined
        val v2Json = convertV1ToV2Json(json)
        return CharaCardV2Parser().parse(
            context = context, json = v2Json, background = background
        )
    }

    val parser = TAVERN_PARSERS[spec]
        ?: error(context.getString(R.string.assistant_importer_unsupported_spec, spec))
    return parser.parse(context = context, json = json, background = background)
}

/**
 * Convert a V1 (flat) character card JSON to V2 wrapped format.
 * Mimics ST's convertToV2 → charaFormatData behavior.
 */
private fun convertV1ToV2Json(v1: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject {
    return kotlinx.serialization.json.buildJsonObject {
        put("spec", "chara_card_v2")
        put("spec_version", "2.0")
        put("data", kotlinx.serialization.json.buildJsonObject {
            v1["name"]?.let { put("name", it) }
            v1["description"]?.let { put("description", it) }
            v1["personality"]?.let { put("personality", it) }
            v1["scenario"]?.let { put("scenario", it) }
            v1["first_mes"]?.let { put("first_mes", it) }
            v1["mes_example"]?.let { put("mes_example", it) }
            v1["creator_notes"]?.let { put("creator_notes", it) }
                ?: v1["creatorcomment"]?.let { put("creator_notes", it) }
            v1["tags"]?.let { put("tags", it) }
            v1["creator"]?.let { put("creator", it) }
            v1["character_version"]?.let { put("character_version", it) }
                ?: v1["version"]?.let { put("character_version", it) }
        })
    }
}

/**
 * V1 card parser — handles legacy TavernAI flat format.
 * Internally converts to V2 structure then delegates to the V2 parser.
 */
private class CharaCardV1Parser : TavernCardParser {
    override val specName: String = "v1"

    override fun parse(context: Context, json: kotlinx.serialization.json.JsonObject, background: String?): Pair<Assistant, CardExtras> {
        // V1 cards are already converted via convertV1ToV2Json in parseAssistantFromJson
        // This parser exists for completeness when spec is explicitly "v1"
        val v2Json = convertV1ToV2Json(json)
        return CharaCardV2Parser().parse(context, v2Json, background)
    }
}

// endregion

/**
 * Convert character book entries to RikkaHub RegexInjection entries for a Lorebook.
 */
private fun convertCharacterBookToLorebook(
    cardName: String,
    book: CharacterBookData,
): Lorebook {
    val entries = book.entries.map { entry ->
        PromptInjection.RegexInjection(
            id = Uuid.random(),
            name = entry.name ?: entry.keys.firstOrNull() ?: "Entry",
            enabled = entry.enabled,
            priority = entry.priority ?: entry.insertionOrder,
            position = when (entry.position) {
                "before_char" -> InjectionPosition.BEFORE_SYSTEM_PROMPT
                "after_char" -> InjectionPosition.AFTER_SYSTEM_PROMPT
                // 字符串 position 缺席时回退到 extensions.position（ST 数值枚举，
                // 映射规则与 ExportSerializer.mapSillyTavernPosition 保持一致）
                else -> when (entry.extensionsPosition) {
                    0 -> InjectionPosition.BEFORE_SYSTEM_PROMPT
                    2, 3 -> InjectionPosition.TOP_OF_CHAT
                    4 -> InjectionPosition.AT_DEPTH
                    else -> InjectionPosition.AFTER_SYSTEM_PROMPT // 1 及未知值
                }
            },
            content = entry.content,
            // ST depth（@Depth，从最新消息往前数）与本项目 injectDepth 语义一致；缺席时用 ST 默认 4
            // （不用 book.scanDepth 兜底：scanDepth 是匹配扫描范围，与注入深度语义不同）
            injectDepth = entry.depth ?: 4,
            role = when (entry.extensionsRole) {
                0 -> MessageRole.SYSTEM
                2 -> MessageRole.ASSISTANT
                else -> MessageRole.USER // 1 及未知值
            },
            keywords = entry.keys,
            useRegex = false,
            caseSensitive = entry.caseSensitive ?: false,
            scanDepth = book.scanDepth ?: 4,
            constantActive = entry.constant == true,
            secondaryKeywords = entry.secondaryKeys,
            selective = entry.selective ?: false,
            probability = entry.probability?.coerceIn(0, 100) ?: 100,
            sticky = entry.sticky?.coerceAtLeast(0) ?: 0,
            cooldown = entry.cooldown?.coerceAtLeast(0) ?: 0,
            delay = entry.delay?.coerceAtLeast(0) ?: 0,
        )
    }

    return Lorebook(
        id = Uuid.random(),
        name = book.name?.ifBlank { null } ?: "$cardName - Character Book",
        description = book.description ?: "",
        enabled = true,
        entries = entries,
    )
}

private suspend fun importAssistantFromUri(
    context: Context,
    uri: Uri,
    onImport: (Assistant) -> Unit,
    toaster: ToasterState,
    filesManager: FilesManager,
    settingsStore: SettingsStore,
) {
    try {
        val mime = withContext(Dispatchers.IO) { filesManager.getFileMimeType(uri) }
        val (jsonString, sourceImageUri) = withContext(Dispatchers.IO) {
            when (mime) {
                "image/png" -> {
                    val result = ImageUtils.getTavernCharacterMeta(context, uri)
                    result.map { base64Data ->
                        val json = decodeBase64Lenient(base64Data)
                        val bg = filesManager.createChatFilesByContents(listOf(uri)).first().toString()
                        json to bg
                    }.getOrElse { throw it }
                }

                "application/json" -> {
                    val json = context.contentResolver.openInputStream(uri)?.bufferedReader()
                        .use { it?.readText() }
                        ?: error(context.getString(R.string.assistant_importer_read_json_failed))
                    json to null
                }

                else -> error(context.getString(R.string.assistant_importer_unsupported_file_type, mime ?: "unknown"))
            }
        }

        val json = Json.parseToJsonElement(jsonString).jsonObject
        val (assistant, extras) = parseAssistantFromJson(context = context, json = json, background = null)

        // Build the final assistant with all imported data
        var finalAssistant = assistant.copy(
            tavernCardJson = jsonString,
            statusRenderJs = extras.statusRenderJs,
        ).withImportedTavernCardImage(sourceImageUri)
        TavernCharacterCard.fromJson(jsonString)?.takeIf { it.allGreetings().isNotEmpty() }?.let { card ->
            val ref = card.openingRef(0)
            finalAssistant = finalAssistant.copy(
                presetMessages = finalAssistant.presetMessages.mapIndexed { index, message ->
                    if (index != 0) message else message.copy(
                        parts = message.parts.map { part ->
                            if (part is UIMessagePart.Text) part.withTavernOpening(ref) else part
                        },
                    )
                },
            )
        }

        // Import character book as lorebook
        if (extras.characterBook != null && extras.characterBook.entries.isNotEmpty()) {
            val lorebook = convertCharacterBookToLorebook(
                cardName = finalAssistant.name,
                book = extras.characterBook,
            )
            // Add lorebook to settings and associate with assistant
            withContext(Dispatchers.IO) {
                settingsStore.update { settings ->
                    settings.copy(
                        // Add or replace lorebook (if a lorebook with same name already exists for this character, replace it)
                        lorebooks = settings.lorebooks.filter { it.id != lorebook.id } + lorebook,
                    )
                }
            }
            finalAssistant = finalAssistant.copy(
                lorebookIds = finalAssistant.lorebookIds + lorebook.id
            )
            toaster.show(
                context.getString(
                    R.string.assistant_importer_imported_lorebook,
                    lorebook.entries.size
                )
            )
        }

        // Import regexes from card extensions
        if (extras.regexes.isNotEmpty()) {
            finalAssistant = finalAssistant.copy(
                regexes = finalAssistant.regexes + extras.regexes
            )
            toaster.show(
                context.getString(
                    R.string.assistant_importer_imported_regexes,
                    extras.regexes.size
                )
            )
        }

        onImport(finalAssistant)
    } catch (exception: Exception) {
        exception.printStackTrace()
        toaster.show(
            message = exception.message ?: context.getString(R.string.assistant_importer_import_failed),
            type = ToastType.Error
        )
    }
}

/**
 * Lenient base64 decoding compatible with different character card tools.
 * - Removes all whitespace
 * - Pads if necessary
 * - Falls back to URL-safe base64
 */
private fun decodeBase64Lenient(encoded: String): String {
    val cleaned = encoded.replace(Regex("\\s+"), "")
    if (cleaned.isEmpty()) error("Empty base64 data in character card")

    val padded = when (val remainder = cleaned.length % 4) {
        0 -> cleaned
        else -> cleaned + "=".repeat(4 - remainder)
    }

    return try {
        String(Base64.decode(padded, Base64.DEFAULT))
    } catch (e: IllegalArgumentException) {
        try {
            String(Base64.decode(padded, Base64.URL_SAFE))
        } catch (e2: IllegalArgumentException) {
            error("Invalid base64 data in character card. First 80 chars: ${cleaned.take(80)}")
        }
    }
}
