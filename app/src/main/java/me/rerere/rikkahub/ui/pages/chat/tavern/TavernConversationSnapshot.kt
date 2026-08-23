package me.rerere.rikkahub.ui.pages.chat.tavern

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.ai.status.CssSanitizer
import me.rerere.rikkahub.data.ai.status.TavernCardStyleResolver
import me.rerere.rikkahub.data.ai.status.withoutInlineStatus
import me.rerere.rikkahub.data.model.isTavernOpeningRuntimeExecuted
import me.rerere.rikkahub.data.model.tavernOpeningRuntimeState

const val TAVERN_CHAT_PROTOCOL_VERSION = 2

@Serializable
data class TavernOpeningSwipe(
    val index: Int,
    val count: Int,
    val ready: Boolean,
    val failed: Boolean = false,
    val swipes: List<String> = emptyList(),
) {
    init {
        require(count > 0)
        require(index in 0 until count)
        require(swipes.isEmpty() || swipes.size == count)
    }
}

@Serializable
data class TavernConversationSnapshot(
    val conversationId: String,
    val nodes: List<TavernConversationNode>,
    val userName: String,
    val characterName: String,
    val themeCssVariables: Map<String, String>,
    val cardCss: String,
    val streaming: Boolean,
    val members: List<TavernConversationMember> = emptyList(),
    val protocolVersion: Int = TAVERN_CHAT_PROTOCOL_VERSION,
    val sessionId: String = "",
    val revision: Long = 0,
    val characterAvatarUrl: String? = null,
    val characterAvatarEmoji: String? = null,
    val userAvatarUrl: String? = null,
    val userAvatarEmoji: String? = null,
    val openingSwipe: TavernOpeningSwipe? = null,
)

@Serializable
data class TavernConversationMember(
    val id: String,
    val name: String,
    val avatarUrl: String? = null,
    val avatarEmoji: String? = null,
    val scopedCss: String = "",
)

@Serializable
data class TavernConversationNode(
    val id: String,
    val selectedIndex: Int,
    val branchCount: Int,
    val selectedMessage: TavernConversationMessage,
) {
    init {
        require(branchCount > 0) { "Tavern conversation nodes must contain at least one branch" }
        require(selectedIndex in 0 until branchCount) {
            "Selected branch $selectedIndex is outside 0..${branchCount - 1} for node $id"
        }
    }
}

@Serializable
data class TavernConversationMessage(
    val id: String,
    val role: MessageRole,
    val name: String,
    val memberId: String? = null,
    val parts: List<TavernConversationPart>,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("partType")
sealed interface TavernConversationPart {
    val text: String get() = ""
    val renderMode: UIMessagePart.RenderMode get() = UIMessagePart.RenderMode.MARKDOWN
    val executeScripts: Boolean get() = false
}

@Serializable
@SerialName("text")
data class TavernConversationTextPart(
    override val text: String,
    override val renderMode: UIMessagePart.RenderMode = UIMessagePart.RenderMode.MARKDOWN,
    override val executeScripts: Boolean = true,
) : TavernConversationPart

@Serializable
data class TavernConversationStatusPage(val name: String, val html: String)

@Serializable
@SerialName("status")
data class TavernConversationStatusPart(
    val htmlContent: String,
    val characterPages: List<TavernConversationStatusPage> = emptyList(),
) : TavernConversationPart

@Serializable
@SerialName("image")
data class TavernConversationImagePart(val url: String) : TavernConversationPart

@Serializable
@SerialName("video")
data class TavernConversationVideoPart(val url: String) : TavernConversationPart

@Serializable
@SerialName("audio")
data class TavernConversationAudioPart(val url: String) : TavernConversationPart

@Serializable
@SerialName("document")
data class TavernConversationDocumentPart(
    val url: String,
    val fileName: String,
    val mime: String,
) : TavernConversationPart

@Serializable
@SerialName("reasoning")
data class TavernConversationReasoningPart(
    val reasoning: String,
    val finished: Boolean,
) : TavernConversationPart

@Serializable
@SerialName("tool")
data class TavernConversationToolPart(
    val toolCallId: String,
    val toolName: String,
    val input: String,
    val output: List<TavernConversationPart>,
    val approvalState: ToolApprovalState,
) : TavernConversationPart

@Suppress("DEPRECATION")
@Serializable
@SerialName("tool_call")
data class TavernConversationToolCallPart(
    val toolCallId: String,
    val toolName: String,
    val arguments: String,
    val approvalState: ToolApprovalState,
) : TavernConversationPart

@Suppress("DEPRECATION")
@Serializable
@SerialName("tool_result")
data class TavernConversationToolResultPart(
    val toolCallId: String,
    val toolName: String,
    val content: JsonElement,
    val arguments: JsonElement,
) : TavernConversationPart

@Serializable
@SerialName("search")
data object TavernConversationSearchPart : TavernConversationPart

@Serializable
sealed interface TavernConversationPatch {

    @Serializable
    @SerialName("replace_all")
    data class ReplaceAll(val snapshot: TavernConversationSnapshot) : TavernConversationPatch

    @Serializable
    @SerialName("upsert_message")
    data class UpsertMessage(
        val nodeId: String,
        val nodeIndex: Int,
        val message: TavernConversationMessage,
    ) : TavernConversationPatch

    @Serializable
    @SerialName("remove_message")
    data class RemoveMessage(
        val nodeId: String,
        val messageId: String,
    ) : TavernConversationPatch

    @Serializable
    @SerialName("select_branch")
    data class SelectBranch(
        val nodeId: String,
        val selectedIndex: Int,
        val messageId: String,
        val branchCount: Int = selectedIndex + 1,
    ) : TavernConversationPatch

    @Serializable
    @SerialName("set_streaming")
    data class SetStreaming(val streaming: Boolean) : TavernConversationPatch
}

/**
 * Converts current application state to the serializable, UI-independent protocol used by the ST document.
 * Every UI part is preserved in its original order for the ST document.
 */
fun buildTavernConversationSnapshot(
    conversation: Conversation,
    userName: String,
    characterName: String,
    themeCssVariables: Map<String, String>,
    cardCss: String?,
    streaming: Boolean,
    members: List<TavernConversationMember> = emptyList(),
    characterAvatarUrl: String? = null,
    characterAvatarEmoji: String? = null,
    userAvatarUrl: String? = null,
    userAvatarEmoji: String? = null,
    openingSwipe: TavernOpeningSwipe? = null,
    revision: Long = 0,
    allowCardScripts: Boolean = true,
    resourceUrlMapper: (String, String?) -> String = { url, _ -> url },
): TavernConversationSnapshot {
    val resolvedUserName = userName.ifBlank { "你" }
    return TavernConversationSnapshot(
        conversationId = conversation.id.toString(),
        nodes = conversation.messageNodes.map { node ->
        val message = node.currentMessage
        TavernConversationNode(
            id = node.id.toString(),
            selectedIndex = node.selectIndex,
            branchCount = node.messages.size,
            selectedMessage = TavernConversationMessage(
                id = message.id.toString(),
                role = message.role,
                name = members.firstOrNull { it.id == message.memberId?.toString() }?.name ?: message.name ?: when (message.role) {
                    MessageRole.USER -> resolvedUserName
                    MessageRole.ASSISTANT -> characterName
                    MessageRole.SYSTEM -> "System"
                    MessageRole.TOOL -> "Tool"
                },
                memberId = message.memberId?.toString(),
                parts = message.parts.flatMap {
                    toTavernConversationParts(
                        part = it,
                        resourceUrlMapper = resourceUrlMapper,
                        allowCardScripts = allowCardScripts,
                        userName = resolvedUserName,
                        characterName = characterName,
                    )
                },
            ),
        )
        },
        userName = resolvedUserName,
        characterName = characterName,
        themeCssVariables = themeCssVariables.toSortedMap(),
        cardCss = cardCss.orEmpty(),
        streaming = streaming,
        members = members,
        sessionId = conversation.id.toString(),
        revision = revision,
        characterAvatarUrl = characterAvatarUrl,
        characterAvatarEmoji = characterAvatarEmoji,
        userAvatarUrl = userAvatarUrl,
        userAvatarEmoji = userAvatarEmoji,
        openingSwipe = openingSwipe,
    )
}

fun buildTavernConversationMembers(
    assistant: Assistant,
    assistantsById: Map<kotlin.uuid.Uuid, Assistant>,
): List<TavernConversationMember> {
    if (assistant.assistantType != AssistantType.GROUP) return emptyList()
    return assistant.groupMembers.filter { it.enabled }.map { member ->
        val referenced = assistantsById[member.assistantId]
        val avatar = when (val value = member.avatar.takeUnless { it == Avatar.Dummy } ?: referenced?.avatar) {
            is Avatar.Image -> value.url to null
            is Avatar.Emoji -> null to value.content
            else -> null to null
        }
        val css = TavernCardStyleResolver.resolve(referenced)?.css.orEmpty()
        TavernConversationMember(
            id = member.id.toString(),
            name = member.displayName.ifBlank { referenced?.name.orEmpty().ifBlank { "Assistant" } },
            avatarUrl = avatar.first,
            avatarEmoji = avatar.second,
            scopedCss = scopeTavernMemberCss(member.id.toString(), CssSanitizer.sanitize(css)),
        )
    }
}

internal fun scopeTavernMemberCss(memberId: String, css: String): String {
    if (css.isBlank()) return ""
    val escapedId = memberId.replace("\\", "\\\\").replace("\"", "\\\"")
    val scope = "[data-member-id=\"$escapedId\"]"
    return CSS_RULE_HEADER.replace(css) { match ->
        val header = match.groupValues[1].trim()
        if (header.startsWith("@")) return@replace match.value
        header.split(',').joinToString(", ") { selector ->
            val trimmed = selector.trim()
            when (trimmed.lowercase()) {
                ":root", "html", "body" -> scope
                else -> when {
                    trimmed.startsWith(".mes") || trimmed.startsWith("[data-member-id") -> "$scope$trimmed"
                    trimmed.startsWith("body ") -> "$scope ${trimmed.substringAfter(' ')}"
                    trimmed.startsWith("html ") -> "$scope ${trimmed.substringAfter(' ')}"
                    else -> "$scope $trimmed"
                }
            }
        } + " {"
    }
}

private val CSS_RULE_HEADER = Regex("([^{}]+)\\{")

@Suppress("DEPRECATION")
private fun toTavernConversationParts(
    part: UIMessagePart,
    resourceUrlMapper: (String, String?) -> String,
    allowCardScripts: Boolean = true,
    userName: String,
    characterName: String,
): List<TavernConversationPart> {
    return when (val displayPart = part.withoutInlineStatus()) {
        null -> emptyList()
        is UIMessagePart.Text -> listOf(
            TavernConversationTextPart(
                text = resolveTavernDisplayText(displayPart.text, userName, characterName),
                renderMode = displayPart.renderMode,
                executeScripts = allowCardScripts &&
                    (!displayPart.isTavernOpeningRuntimeExecuted() || displayPart.tavernOpeningRuntimeState() == null),
            ),
        )
        is UIMessagePart.Image -> listOf(TavernConversationImagePart(resourceUrlMapper(displayPart.url, "image/*")))
        is UIMessagePart.Video -> listOf(TavernConversationVideoPart(resourceUrlMapper(displayPart.url, "video/*")))
        is UIMessagePart.Audio -> listOf(TavernConversationAudioPart(resourceUrlMapper(displayPart.url, "audio/*")))
        is UIMessagePart.Document -> listOf(TavernConversationDocumentPart(
            resourceUrlMapper(displayPart.url, displayPart.mime), displayPart.fileName, displayPart.mime,
        ))
        is UIMessagePart.Reasoning -> listOf(
            TavernConversationReasoningPart(
                resolveTavernDisplayText(displayPart.reasoning, userName, characterName),
                displayPart.finishedAt != null,
            ),
        )
        is UIMessagePart.Tool -> listOf(TavernConversationToolPart(
            toolCallId = displayPart.toolCallId,
            toolName = displayPart.toolName,
            input = displayPart.input,
            output = displayPart.output.flatMap {
                toTavernConversationParts(
                    part = it,
                    resourceUrlMapper = resourceUrlMapper,
                    allowCardScripts = allowCardScripts,
                    userName = userName,
                    characterName = characterName,
                )
            },
            approvalState = displayPart.approvalState,
        ))
        is UIMessagePart.ToolCall -> listOf(TavernConversationToolCallPart(
            displayPart.toolCallId, displayPart.toolName, displayPart.arguments, displayPart.approvalState,
        ))
        is UIMessagePart.ToolResult -> listOf(TavernConversationToolResultPart(
            displayPart.toolCallId, displayPart.toolName, displayPart.content, displayPart.arguments,
        ))
        UIMessagePart.Search -> listOf(TavernConversationSearchPart)
        is UIMessagePart.StatusPlaceholder -> emptyList()
    }
}

/**
 * Produces patches in protocol order: removals, upserts, branch selections, then streaming state.
 * Changes that the patch vocabulary cannot express safely use one deterministic full replacement.
 */
fun diffTavernSnapshots(
    previous: TavernConversationSnapshot?,
    current: TavernConversationSnapshot,
): List<TavernConversationPatch> {
    if (previous == null || requiresFullReplacement(previous, current)) {
        return listOf(TavernConversationPatch.ReplaceAll(current))
    }

    val patches = mutableListOf<TavernConversationPatch>()
    val currentNodes = current.nodes.associateBy { it.id }
    previous.nodes.forEach { oldNode ->
        if (oldNode.id !in currentNodes) {
            patches += TavernConversationPatch.RemoveMessage(oldNode.id, oldNode.selectedMessage.id)
        }
    }

    val previousNodes = previous.nodes.associateBy { it.id }
    current.nodes.forEachIndexed { nodeIndex, node ->
        if (previousNodes[node.id]?.selectedMessage != node.selectedMessage) {
            patches += TavernConversationPatch.UpsertMessage(
                nodeId = node.id,
                nodeIndex = nodeIndex,
                message = node.selectedMessage,
            )
        }
    }

    current.nodes.forEach { node ->
        val oldNode = previousNodes[node.id]
        if (oldNode == null || oldNode.selectedIndex != node.selectedIndex ||
            oldNode.selectedMessage.id != node.selectedMessage.id || oldNode.branchCount != node.branchCount
        ) {
            patches += TavernConversationPatch.SelectBranch(
                nodeId = node.id,
                selectedIndex = node.selectedIndex,
                messageId = node.selectedMessage.id,
                branchCount = node.branchCount,
            )
        }
    }

    if (previous.streaming != current.streaming) {
        patches += TavernConversationPatch.SetStreaming(current.streaming)
    }
    return patches
}

private fun requiresFullReplacement(
    previous: TavernConversationSnapshot,
    current: TavernConversationSnapshot,
): Boolean {
    if (previous.conversationId != current.conversationId ||
        previous.userName != current.userName ||
        previous.characterName != current.characterName ||
        previous.themeCssVariables != current.themeCssVariables ||
        previous.cardCss != current.cardCss ||
        previous.members != current.members ||
        previous.protocolVersion != current.protocolVersion ||
        previous.sessionId != current.sessionId ||
        previous.characterAvatarUrl != current.characterAvatarUrl ||
        previous.characterAvatarEmoji != current.characterAvatarEmoji ||
        previous.userAvatarUrl != current.userAvatarUrl ||
        previous.userAvatarEmoji != current.userAvatarEmoji ||
        previous.openingSwipe != current.openingSwipe
    ) {
        return true
    }
    if (!keepsCommonOrder(previous.nodes.map { it.id }, current.nodes.map { it.id })) return true
    return false
}

private fun keepsCommonOrder(previous: List<String>, current: List<String>): Boolean {
    val currentIds = current.toSet()
    val previousIds = previous.toSet()
    return previous.filter { it in currentIds } == current.filter { it in previousIds }
}

internal fun TavernConversationSnapshot.withCardScriptsDisabled(): TavernConversationSnapshot = copy(
    nodes = nodes.map { node ->
        node.copy(
            selectedMessage = node.selectedMessage.copy(
                parts = node.selectedMessage.parts.map(TavernConversationPart::withoutScripts),
            ),
        )
    },
)

private fun TavernConversationPart.withoutScripts(): TavernConversationPart = when (this) {
    is TavernConversationTextPart -> copy(executeScripts = false)
    is TavernConversationToolPart -> copy(output = output.map(TavernConversationPart::withoutScripts))
    else -> this
}
