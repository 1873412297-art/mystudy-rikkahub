package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.TavernCharacterCard

/** 上下文快照中 chat 列表的最大消息数 */
private const val MAX_CHAT_ENTRIES = 50

/** 单条消息纯文本的截断长度 */
private const val MAX_MESSAGE_TEXT_LENGTH = 2000

/**
 * 上下文快照输入（宿主 ChatList 层组装）。
 *
 * @property characterCard 角色卡数据（description/personality/scenario 来源；Assistant 模型不含这些字段）
 * @property worldEntries 世界书条目（名称 → 内容纯文本），按对话绑定顺序
 */
internal data class TavernContextSnapshotInput(
    val conversation: Conversation,
    val assistant: Assistant?,
    val characterCard: TavernCharacterCard? = null,
    val userName: String,
    val isGenerating: Boolean,
    val variables: JsonObject,
    val worldEntries: List<Pair<String, String>>,
)

/**
 * 构建 SillyTavern.getContext() 风格上下文快照（实用子集）。
 * 纯函数，可 JVM 测试。
 */
internal fun buildTavernContextSnapshot(input: TavernContextSnapshotInput): JsonObject {
    val chat = input.conversation.currentMessages
        .takeLast(MAX_CHAT_ENTRIES)
        .map { message -> message.toChatEntry(isCurrent = message.id == input.conversation.currentMessages.lastOrNull()?.id) }
    val snapshot = buildJsonObject {
        put("chat", JsonArray(chat))
        if (input.assistant != null) {
            put("character", buildJsonObject {
                put("name", input.assistant.name)
                input.characterCard?.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
                input.characterCard?.personality?.takeIf { it.isNotBlank() }?.let { put("personality", it) }
                input.characterCard?.scenario?.takeIf { it.isNotBlank() }?.let { put("scenario", it) }
            })
        }
        put("user", buildJsonObject { put("name", input.userName) })
        if (input.worldEntries.isNotEmpty()) {
            put("worldInfo", buildJsonArray {
                input.worldEntries.forEach { (name, content) ->
                    add(buildJsonObject { put("name", name); put("content", content) })
                }
            })
        }
        put("conversationId", input.conversation.id.toString())
        put("onlineStatus", input.isGenerating)
        put("variables", input.variables)
    }
    return snapshot
}

private fun UIMessage.toChatEntry(isCurrent: Boolean): JsonObject = buildJsonObject {
    put("role", role.name.lowercase())
    put("text", toText().take(MAX_MESSAGE_TEXT_LENGTH))
    put("messageId", id.toString())
    put("isCurrent", isCurrent)
}
