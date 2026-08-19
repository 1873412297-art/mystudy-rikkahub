package me.rerere.rikkahub.ui.pages.chat.tavern

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class TavernConversationSnapshotTest {

    @Test
    fun `snapshot uses selected branches and preserves the current raw html message`() {
        val markdown = uiMessage("00000000-0000-0000-0000-000000000011", MessageRole.ASSISTANT, "old")
        val html = uiMessage(
            "00000000-0000-0000-0000-000000000012",
            MessageRole.ASSISTANT,
            "<html><body>{{char}} says hi</body></html>",
            UIMessagePart.RenderMode.HTML,
        )
        val conversation = conversation(
            MessageNode(
                id = uuid("00000000-0000-0000-0000-000000000101"),
                messages = listOf(markdown, html),
                selectIndex = 1,
            ),
        )

        val snapshot = buildTavernConversationSnapshot(
            conversation = conversation,
            userName = "User",
            characterName = "Alice",
            themeCssVariables = linkedMapOf("--z" to "last", "--a" to "first"),
            cardCss = ".mes { color: red; }",
            streaming = true,
        )

        assertEquals(conversation.id.toString(), snapshot.conversationId)
        assertEquals(1, snapshot.nodes.single().selectedIndex)
        assertEquals(2, snapshot.nodes.single().branchCount)
        assertEquals(html.id.toString(), snapshot.nodes.single().selectedMessage.id)
        assertEquals(UIMessagePart.RenderMode.HTML, snapshot.nodes.single().selectedMessage.parts.single().renderMode)
        assertEquals("<html><body>{{char}} says hi</body></html>", snapshot.nodes.single().selectedMessage.parts.single().text)
        assertEquals("Alice", snapshot.nodes.single().selectedMessage.name)
        assertEquals(listOf("--a", "--z"), snapshot.themeCssVariables.keys.toList())
        assertTrue(snapshot.streaming)
    }

    @Test
    fun `snapshot ignores unsupported parts in an unselected branch`() {
        val supported = uiMessage("00000000-0000-0000-0000-000000000011", MessageRole.ASSISTANT, "selected")
        val unsupported = UIMessage(
            id = uuid("00000000-0000-0000-0000-000000000012"),
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Image("https://example.invalid/image.png")),
        )
        val snapshot = buildTavernConversationSnapshot(
            conversation = conversation(
                MessageNode(
                    id = uuid("00000000-0000-0000-0000-000000000101"),
                    messages = listOf(supported, unsupported),
                    selectIndex = 0,
                ),
            ),
            userName = "User",
            characterName = "Alice",
            themeCssVariables = emptyMap(),
            cardCss = null,
            streaming = false,
        )

        assertEquals(supported.id.toString(), snapshot.nodes.single().selectedMessage.id)
        assertEquals(2, snapshot.nodes.single().branchCount)
    }

    @Test
    fun `null previous snapshot emits one full replacement`() {
        val current = snapshot(node("n1", selectedIndex = 0, message("m1", MessageRole.USER, "hello")))

        assertEquals(listOf(TavernConversationPatch.ReplaceAll(current)), diffTavernSnapshots(null, current))
    }

    @Test
    fun `message removals and upserts have deterministic snapshot order`() {
        val previous = snapshot(
            node("n1", 0, 2, message("m1", MessageRole.USER, "one")),
            node("n2", 0, message("m3", MessageRole.ASSISTANT, "old")),
        )
        val updatedM3 = previous.nodes[1].selectedMessage.copy(parts = listOf(TavernConversationTextPart("new")))
        val addedM4 = TavernConversationMessage(
            id = "m4",
            role = MessageRole.ASSISTANT,
            name = "Alice",
            parts = listOf(TavernConversationTextPart("four")),
        )
        val current = previous.copy(
            nodes = listOf(
                previous.nodes[1].copy(selectedMessage = updatedM3),
                TavernConversationNode(id = "n3", selectedIndex = 0, branchCount = 1, selectedMessage = addedM4),
            ),
        )

        assertEquals(
            listOf(
                TavernConversationPatch.RemoveMessage(nodeId = "n1", messageId = "m1"),
                TavernConversationPatch.UpsertMessage(nodeId = "n2", nodeIndex = 0, message = updatedM3),
                TavernConversationPatch.UpsertMessage(nodeId = "n3", nodeIndex = 1, message = addedM4),
                TavernConversationPatch.SelectBranch(nodeId = "n3", selectedIndex = 0, messageId = "m4"),
            ),
            diffTavernSnapshots(previous, current),
        )
    }

    @Test
    fun `branch and streaming changes emit only their focused patches`() {
        val previous = snapshot(
            node("n1", 0, 2, message("m1", MessageRole.ASSISTANT, "one")),
        )
        val selectedM2 = message("m2", MessageRole.ASSISTANT, "two")
        val current = previous.copy(
            nodes = listOf(previous.nodes.single().copy(selectedIndex = 1, selectedMessage = selectedM2)),
            streaming = true,
        )

        assertEquals(
            listOf(
                TavernConversationPatch.UpsertMessage(nodeId = "n1", nodeIndex = 0, message = selectedM2),
                TavernConversationPatch.SelectBranch(nodeId = "n1", selectedIndex = 1, messageId = "m2"),
                TavernConversationPatch.SetStreaming(streaming = true),
            ),
            diffTavernSnapshots(previous, current),
        )
    }

    @Test
    fun `theme card css or unsupported reordering requires full replacement`() {
        val previous = snapshot(
            node("n1", 0, message("m1", MessageRole.USER, "one")),
            node("n2", 0, message("m2", MessageRole.ASSISTANT, "two")),
        )
        val reordered = previous.copy(nodes = previous.nodes.reversed())
        val themed = previous.copy(themeCssVariables = sortedMapOf("--bg" to "black"))
        val styled = previous.copy(cardCss = ".mes { opacity: .9; }")

        assertEquals(listOf(TavernConversationPatch.ReplaceAll(reordered)), diffTavernSnapshots(previous, reordered))
        assertEquals(listOf(TavernConversationPatch.ReplaceAll(themed)), diffTavernSnapshots(previous, themed))
        assertEquals(listOf(TavernConversationPatch.ReplaceAll(styled)), diffTavernSnapshots(previous, styled))
    }

    @Test
    fun `snapshot and every patch are serializable with stable protocol names`() {
        val current = snapshot(node("n1", 0, message("m1", MessageRole.ASSISTANT, "hello")))
        val message = current.nodes.single().selectedMessage
        val patches: List<TavernConversationPatch> = listOf(
            TavernConversationPatch.ReplaceAll(current),
            TavernConversationPatch.UpsertMessage("n1", 0, message),
            TavernConversationPatch.RemoveMessage("n1", "m1"),
            TavernConversationPatch.SelectBranch("n1", 0, "m1", 1),
            TavernConversationPatch.SetStreaming(true),
        )
        val json = Json { encodeDefaults = true; classDiscriminator = "type" }

        val encodedSnapshot = json.encodeToString(current)
        val encodedPatches = json.encodeToString(patches)

        assertEquals(encodedSnapshot, json.encodeToString(current))
        listOf("replace_all", "upsert_message", "remove_message", "select_branch", "set_streaming").forEach {
            assertTrue("missing serialized patch $it", encodedPatches.contains("\"type\":\"$it\""))
        }
        assertEquals(patches, json.decodeFromString<List<TavernConversationPatch>>(encodedPatches))
        assertTrue(encodedSnapshot.contains("\"renderMode\":\"markdown\""))
    }

    private fun conversation(vararg nodes: MessageNode) = Conversation(
        id = uuid("00000000-0000-0000-0000-000000000001"),
        assistantId = uuid("00000000-0000-0000-0000-000000000002"),
        messageNodes = nodes.toList(),
    )

    private fun snapshot(vararg nodes: TavernConversationNode) = TavernConversationSnapshot(
        conversationId = "conversation",
        nodes = nodes.toList(),
        userName = "User",
        characterName = "Alice",
        themeCssVariables = sortedMapOf("--bg" to "white"),
        cardCss = "",
        streaming = false,
    )

    private fun node(id: String, selectedIndex: Int, message: TavernConversationMessage) =
        node(id, selectedIndex, 1, message)

    private fun node(id: String, selectedIndex: Int, branchCount: Int, message: TavernConversationMessage) =
        TavernConversationNode(
            id = id,
            selectedIndex = selectedIndex,
            branchCount = branchCount,
            selectedMessage = message,
        )

    private fun message(
        id: String,
        role: MessageRole,
        text: String,
        renderMode: UIMessagePart.RenderMode = UIMessagePart.RenderMode.MARKDOWN,
    ) = TavernConversationMessage(
        id = id,
        role = role,
        name = if (role == MessageRole.USER) "User" else "Alice",
        parts = listOf(TavernConversationTextPart(text = text, renderMode = renderMode)),
    )

    private fun uiMessage(
        id: String,
        role: MessageRole,
        text: String,
        renderMode: UIMessagePart.RenderMode = UIMessagePart.RenderMode.MARKDOWN,
    ): UIMessage {
        return UIMessage(
            id = uuid(id),
            role = role,
            parts = listOf(UIMessagePart.Text(text, renderMode)),
        )
    }

    private fun uuid(value: String): Uuid = Uuid.parse(value)
}
