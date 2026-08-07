package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * SillyTavern 角色卡完整数据模型
 * 支持 V1 (TavernAI), V2 (chara_card_v2) 和 V3 (chara_card_v3) 格式
 *
 * 官方规范参考:
 * - V2: https://github.com/SillyTavern/SillyTavern/blob/626b324f/src/types/spec-v2.d.ts
 */

/**
 * 角色卡包装结构（V2/V3）
 */
@Serializable
sealed class TavernCardWrapper {
    abstract val spec: String
    abstract val specVersion: String
    abstract val data: TavernCardData
}

@Serializable
@SerialName("chara_card_v2")
data class TavernCardV2(
    override val spec: String = "chara_card_v2",
    @SerialName("spec_version")
    override val specVersion: String = "2.0",
    override val data: TavernCardData
) : TavernCardWrapper()

@Serializable
@SerialName("chara_card_v3")
data class TavernCardV3(
    override val spec: String = "chara_card_v3",
    @SerialName("spec_version")
    override val specVersion: String = "3.0",
    override val data: TavernCardData
) : TavernCardWrapper()

/**
 * 角色卡核心数据（V2/V3 data 字段）
 */
@Serializable
data class TavernCardData(
    val name: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    @SerialName("first_mes")
    val firstMes: String = "",
    @SerialName("mes_example")
    val mesExample: String = "",
    @SerialName("creator_notes")
    val creatorNotes: String = "",
    @SerialName("system_prompt")
    val systemPrompt: String = "",
    @SerialName("post_history_instructions")
    val postHistoryInstructions: String = "",
    @SerialName("alternate_greetings")
    val alternateGreetings: List<String> = emptyList(),
    @SerialName("character_book")
    val characterBook: CharacterBook? = null,
    val tags: List<String> = emptyList(),
    val creator: String = "",
    @SerialName("character_version")
    val characterVersion: String = "",
    val extensions: JsonObject? = null,
)

/**
 * V1 格式（TavernAI 原始格式，扁平结构）
 */
@Serializable
data class TavernCardV1(
    val name: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    @SerialName("first_mes")
    val firstMes: String = "",
    @SerialName("mes_example")
    val mesExample: String = "",
    @SerialName("creatorcomment")
    val creatorComment: String = "",
    @SerialName("creator_notes")
    val creatorNotes: String = "",
    val tags: List<String> = emptyList(),
    val creator: String = "",
    val version: String = "",
    @SerialName("character_version")
    val characterVersion: String = "",
)

/**
 * 角色书 / World Info (Character Book)
 */
@Serializable
data class CharacterBook(
    val name: String? = null,
    val description: String? = null,
    @SerialName("scan_depth")
    val scanDepth: Int? = null,
    @SerialName("token_budget")
    val tokenBudget: Int? = null,
    @SerialName("recursive_scanning")
    val recursiveScanning: Boolean? = null,
    val extensions: JsonObject? = null,
    val entries: List<CharacterBookEntry> = emptyList()
)

/**
 * 角色书条目
 */
@Serializable
data class CharacterBookEntry(
    val keys: List<String> = emptyList(),
    val content: String = "",
    val extensions: JsonObject? = null,
    val enabled: Boolean = true,
    @SerialName("insertion_order")
    val insertionOrder: Int = 0,
    @SerialName("case_sensitive")
    val caseSensitive: Boolean? = null,
    val name: String? = null,
    val priority: Int? = null,
    val id: Int? = null,
    val comment: String? = null,
    val selective: Boolean? = null,
    @SerialName("secondary_keys")
    val secondaryKeys: List<String>? = null,
    val constant: Boolean? = null,
    val position: String? = null, // "before_char" | "after_char"
)

/**
 * 统一的角色卡模型（用于应用内部使用）
 * 将 V1/V2/V3 统一为一致的内部表示
 */
data class TavernCharacterCard(
    val name: String,
    val description: String,
    val personality: String,
    val scenario: String,
    val firstMes: String,
    val mesExample: String,
    val creatorNotes: String,
    val systemPrompt: String,
    val postHistoryInstructions: String,
    val alternateGreetings: List<String>,
    val characterBook: CharacterBook?,
    val tags: List<String>,
    val creator: String,
    val characterVersion: String,
    val extensions: JsonObject?,
    val spec: String, // "v1", "chara_card_v2", "chara_card_v3"
    val specVersion: String,
    // 附加元数据
    val sourceImageUri: String? = null, // PNG 角色卡图片 URI
) {
    companion object {
        /**
         * 从 V1 数据创建统一模型
         */
        fun fromV1(v1: TavernCardV1): TavernCharacterCard {
            return TavernCharacterCard(
                name = v1.name,
                description = v1.description,
                personality = v1.personality,
                scenario = v1.scenario,
                firstMes = v1.firstMes,
                mesExample = v1.mesExample,
                creatorNotes = v1.creatorNotes.ifBlank { v1.creatorComment },
                systemPrompt = "",
                postHistoryInstructions = "",
                alternateGreetings = emptyList(),
                characterBook = null,
                tags = v1.tags,
                creator = v1.creator,
                characterVersion = v1.characterVersion.ifBlank { v1.version },
                extensions = null,
                spec = "v1",
                specVersion = "1.0"
            )
        }

        /**
         * 从 V2/V3 包装结构创建统一模型
         */
        fun fromWrapper(wrapper: TavernCardWrapper, sourceImageUri: String? = null): TavernCharacterCard {
            return TavernCharacterCard(
                name = wrapper.data.name,
                description = wrapper.data.description,
                personality = wrapper.data.personality,
                scenario = wrapper.data.scenario,
                firstMes = wrapper.data.firstMes,
                mesExample = wrapper.data.mesExample,
                creatorNotes = wrapper.data.creatorNotes,
                systemPrompt = wrapper.data.systemPrompt,
                postHistoryInstructions = wrapper.data.postHistoryInstructions,
                alternateGreetings = wrapper.data.alternateGreetings,
                characterBook = wrapper.data.characterBook,
                tags = wrapper.data.tags,
                creator = wrapper.data.creator,
                characterVersion = wrapper.data.characterVersion,
                extensions = wrapper.data.extensions,
                spec = wrapper.spec,
                specVersion = wrapper.specVersion,
                sourceImageUri = sourceImageUri
            )
        }
    }

    /**
     * 获取所有问候语（包括 first_mes 和 alternate_greetings）
     */
    fun allGreetings(): List<String> {
        val greetings = mutableListOf<String>()
        if (firstMes.isNotBlank()) {
            greetings.add(firstMes)
        }
        greetings.addAll(alternateGreetings.filter { it.isNotBlank() })
        return greetings
    }

    /**
     * 获取用于 AI 提示的系统提示词（综合 system_prompt + description + personality + scenario）
     */
    fun buildSystemPrompt(userName: String = "User", charName: String = name): String {
        return buildString {
            if (systemPrompt.isNotBlank()) {
                appendLine(systemPrompt.normalizeTavernCardText(userName, charName))
                appendLine()
            }
            if (description.isNotBlank()) {
                appendLine("## Description")
                appendLine(description.normalizeTavernCardText(userName, charName))
                appendLine()
            }
            if (personality.isNotBlank()) {
                appendLine("## Personality")
                appendLine(personality.normalizeTavernCardText(userName, charName))
                appendLine()
            }
            if (scenario.isNotBlank()) {
                appendLine("## Scenario")
                appendLine(scenario.normalizeTavernCardText(userName, charName))
                appendLine()
            }
            if (mesExample.isNotBlank()) {
                appendLine("## Example Dialogue")
                appendLine(mesExample.normalizeTavernCardText(userName, charName))
                appendLine()
            }
            if (postHistoryInstructions.isNotBlank()) {
                appendLine("## Post-history Instructions")
                appendLine(postHistoryInstructions.normalizeTavernCardText(userName, charName))
            }
        }.trim()
    }

    /**
     * 将 {{user}} 和 {{char}} 宏替换为实际值
     */
    fun replaceMacros(userName: String = "User", charName: String = name): String {
        return description.normalizeTavernCardText(userName, charName)
    }
}

fun String.normalizeTavernCardText(userName: String = "User", charName: String): String {
    if (isBlank()) return this

    var result = this
    result = Regex("\\{\\{//.*?\\}\\}", RegexOption.DOT_MATCHES_ALL).replace(result, "")
    result = result
        .replace("{{user}}", userName, ignoreCase = true)
        .replace("{{char}}", charName, ignoreCase = true)
        .replace("{user}", userName, ignoreCase = true)
        .replace("{char}", charName, ignoreCase = true)
        .replace("{{newline}}", "\n", ignoreCase = true)
        .replace("{{noop}}", "", ignoreCase = true)
        .replace("{{trim}}", "", ignoreCase = true)

    return result
        .lines()
        .joinToString("\n") { it.trimEnd() }
        .trim()
}

/**
 * 从 [Assistant.tavernCardJson] 解析所有可用开场白（first_mes + alternate_greetings）。
 * 没有角色卡或解析失败时回退到 presetMessages 中的 ASSISTANT 文本。
 */
fun Assistant.normalizedSystemPromptForGeneration(userName: String = "User"): String {
    val charName = name.ifBlank { "assistant" }
    return if (tavernCardJson != null) {
        systemPrompt.normalizeTavernCardText(userName = userName, charName = charName)
    } else {
        systemPrompt
    }
}

fun Assistant.parseTavernGreetings(): List<String> {
    val json = tavernCardJson ?: return fallbackGreetings()
    return try {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
        val data = if (root.containsKey("data")) root["data"]?.jsonObject else root
        val greetings = mutableListOf<String>()
        data?.get("first_mes")?.let { p ->
            if (p is kotlinx.serialization.json.JsonPrimitive && p.isString && p.content.isNotBlank())
                greetings.add(p.content)
        }
        data?.get("alternate_greetings")?.jsonArray?.forEach { g ->
            if (g is kotlinx.serialization.json.JsonPrimitive && g.isString && g.content.isNotBlank())
                greetings.add(g.content)
        }
        greetings.ifEmpty { fallbackGreetings() }
    } catch (_: Exception) {
        fallbackGreetings()
    }
}

private fun Assistant.fallbackGreetings(): List<String> =
    presetMessages
        .filter { it.role == me.rerere.ai.core.MessageRole.ASSISTANT }
        .mapNotNull { it.parts.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>().firstOrNull()?.text }
