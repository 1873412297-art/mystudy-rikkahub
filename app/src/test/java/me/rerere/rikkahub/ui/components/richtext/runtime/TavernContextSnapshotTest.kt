package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.boolean
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.TavernCharacterCard
import me.rerere.rikkahub.ui.pages.tavern.empty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class TavernContextSnapshotTest {

    private fun textMessage(id: Uuid, role: MessageRole, text: String) = UIMessage(
        id = id,
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun node(id: Uuid, vararg messages: UIMessage) = MessageNode(
        id = id,
        messages = messages.toList(),
        selectIndex = 0,
    )

    private fun conversation(vararg nodes: MessageNode) = Conversation(
        id = Uuid.random(),
        assistantId = Uuid.random(),
        title = "t",
        messageNodes = nodes.toList(),
        chatSuggestions = emptyList(),
    )

    private fun characterCard(name: String, description: String = "") = TavernCharacterCard.empty().copy(
        name = name,
        description = description,
    )

    @Test
    fun `builds chat entries with current flag on last message`() {
        val m1 = textMessage(Uuid.random(), MessageRole.USER, "hello")
        val m2 = textMessage(Uuid.random(), MessageRole.ASSISTANT, "hi there")
        val input = TavernContextSnapshotInput(
            conversation = conversation(node(Uuid.random(), m1), node(Uuid.random(), m2)),
            assistant = Assistant(name = "Char"),
            userName = "User",
            isGenerating = true,
            variables = JsonObject(emptyMap()),
            worldEntries = emptyList(),
        )
        val snapshot = buildTavernContextSnapshot(input)
        val chat = snapshot["chat"]!!.jsonArray
        assertEquals(2, chat.size)
        assertEquals("hello", chat[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(false, chat[0].jsonObject["isCurrent"]!!.jsonPrimitive.boolean)
        assertTrue(chat[1].jsonObject["isCurrent"]!!.jsonPrimitive.boolean)
        assertEquals("Char", snapshot["character"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("User", snapshot["user"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(true, snapshot["onlineStatus"]!!.jsonPrimitive.boolean)
        assertEquals(input.conversation.id.toString(), snapshot["conversationId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `truncates chat to 50 most recent messages`() {
        val nodes = (0 until 60).map { index ->
            node(Uuid.random(), textMessage(Uuid.random(), MessageRole.USER, "msg-$index"))
        }
        val input = TavernContextSnapshotInput(
            conversation = conversation(*nodes.toTypedArray()),
            assistant = Assistant(name = "C"),
            userName = "U",
            isGenerating = false,
            variables = JsonObject(emptyMap()),
            worldEntries = emptyList(),
        )
        val snapshot = buildTavernContextSnapshot(input)
        val chat = snapshot["chat"]!!.jsonArray
        assertEquals(50, chat.size)
        assertEquals("msg-10", chat[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("msg-59", chat[49].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `truncates single message text to 2000 chars`() {
        val long = "x".repeat(2500)
        val input = TavernContextSnapshotInput(
            conversation = conversation(node(Uuid.random(), textMessage(Uuid.random(), MessageRole.USER, long))),
            assistant = Assistant(name = "C"),
            userName = "U",
            isGenerating = false,
            variables = JsonObject(emptyMap()),
            worldEntries = emptyList(),
        )
        val text = buildTavernContextSnapshot(input)["chat"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertEquals(2000, text.length)
        assertTrue(text.startsWith("x"))
    }

    @Test
    fun `includes variables and world info`() {
        val input = TavernContextSnapshotInput(
            conversation = conversation(),
            assistant = Assistant(name = "C"),
            characterCard = characterCard(name = "C", description = "A card"),
            userName = "U",
            isGenerating = false,
            variables = buildJsonObject { put("hp", 42) },
            worldEntries = listOf("World" to "lore content"),
        )
        val snapshot = buildTavernContextSnapshot(input)
        assertEquals(42, snapshot["variables"]!!.jsonObject["hp"]!!.jsonPrimitive.int)
        assertEquals("lore content", snapshot["worldInfo"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("A card", snapshot["character"]!!.jsonObject["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun `emits no world info when empty`() {
        val input = TavernContextSnapshotInput(
            conversation = conversation(),
            assistant = Assistant(name = "C"),
            userName = "U",
            isGenerating = false,
            variables = JsonObject(emptyMap()),
            worldEntries = emptyList(),
        )
        val snapshot = buildTavernContextSnapshot(input)
        assertFalse(snapshot.containsKey("worldInfo"))
    }
}
