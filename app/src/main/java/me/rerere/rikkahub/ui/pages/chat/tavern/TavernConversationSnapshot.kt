package me.rerere.rikkahub.ui.pages.chat.tavern

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.isTavernOpeningRuntimeExecuted
import me.rerere.rikkahub.data.model.tavernOpeningRuntimeState

@Serializable
data class TavernConversationSnapshot(
    val conversationId: String,
    val nodes: List<TavernConversationNode>,
    val userName: String,
    val characterName: String,
    val themeCssVariables: Map<String, String>,
    val cardCss: String,
    val streaming: Boolean,
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
    val parts: List<TavernConversationTextPart>,
)

@Serializable
data class TavernConversationTextPart(
    val text: String,
    val renderMode: UIMessagePart.RenderMode = UIMessagePart.RenderMode.MARKDOWN,
    val executeScripts: Boolean = true,
)

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
 * Only Text parts are accepted; presentation eligibility is decided by [resolveTavernPresentation] first.
 */
fun buildTavernConversationSnapshot(
    conversation: Conversation,
    userName: String,
    characterName: String,
    themeCssVariables: Map<String, String>,
    cardCss: String?,
    streaming: Boolean,
): TavernConversationSnapshot = TavernConversationSnapshot(
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
                name = message.name ?: when (message.role) {
                    MessageRole.USER -> userName
                    MessageRole.ASSISTANT -> characterName
                    MessageRole.SYSTEM -> "System"
                    MessageRole.TOOL -> "Tool"
                },
                parts = message.parts.map { part ->
                    require(part is UIMessagePart.Text) {
                        "Tavern conversation snapshots only support selected Text parts, got ${part::class.simpleName}"
                    }
                    TavernConversationTextPart(
                        text = part.text,
                        renderMode = part.renderMode,
                    executeScripts = !part.isTavernOpeningRuntimeExecuted() || part.tavernOpeningRuntimeState() == null,
                    )
                },
            ),
        )
    },
    userName = userName,
    characterName = characterName,
    themeCssVariables = themeCssVariables.toSortedMap(),
    cardCss = cardCss.orEmpty(),
    streaming = streaming,
)

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
        previous.cardCss != current.cardCss
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
