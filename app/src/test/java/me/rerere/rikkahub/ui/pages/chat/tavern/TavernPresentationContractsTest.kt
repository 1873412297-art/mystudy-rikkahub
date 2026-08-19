package me.rerere.rikkahub.ui.pages.chat.tavern

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class TavernPresentationContractsTest {

    @Test
    fun `uses ST web for a solo card conversation containing markdown text`() {
        val decision = resolveTavernPresentation(
            assistant = tavernAssistant(),
            conversation = conversation(UIMessagePart.Text("Hello")),
        )

        assertEquals(TavernPresentationMode.ST_WEB, decision.mode)
        assertNull(decision.fallbackReason)
    }

    @Test
    fun `uses ST web for a solo card conversation containing HTML text`() {
        val decision = resolveTavernPresentation(
            assistant = tavernAssistant(),
            conversation = conversation(
                UIMessagePart.Text("<main>Hello</main>", UIMessagePart.RenderMode.HTML),
            ),
        )

        assertEquals(TavernPresentationMode.ST_WEB, decision.mode)
        assertNull(decision.fallbackReason)
    }

    @Test
    fun `falls back when the assistant has no Tavern card`() {
        val decision = resolveTavernPresentation(
            assistant = Assistant(),
            conversation = conversation(UIMessagePart.Text("Hello")),
        )

        assertEquals(TavernPresentationMode.COMPOSE, decision.mode)
        assertNotNull(decision.fallbackReason)
    }

    @Test
    fun `falls back for group assistants`() {
        val decision = resolveTavernPresentation(
            assistant = tavernAssistant().copy(assistantType = AssistantType.GROUP),
            conversation = conversation(UIMessagePart.Text("Hello")),
        )

        assertEquals(TavernPresentationMode.COMPOSE, decision.mode)
        assertNotNull(decision.fallbackReason)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `falls back for every non-text message part family`() {
        val unsupportedParts = listOf(
            UIMessagePart.Image("https://example.com/image.png"),
            UIMessagePart.Video("https://example.com/video.mp4"),
            UIMessagePart.Audio("https://example.com/audio.mp3"),
            UIMessagePart.Document("content://example.com/file", "file.txt"),
            UIMessagePart.Reasoning("reasoning"),
            UIMessagePart.StatusPlaceholder("<p>status</p>"),
            UIMessagePart.Search,
            UIMessagePart.ToolCall("call", "tool", "{}"),
            UIMessagePart.ToolResult("call", "tool", kotlinx.serialization.json.JsonNull, kotlinx.serialization.json.JsonNull),
            UIMessagePart.Tool("call", "tool", "{}"),
        )

        unsupportedParts.forEach { unsupportedPart ->
            val decision = resolveTavernPresentation(
                assistant = tavernAssistant(),
                conversation = conversation(UIMessagePart.Text("Hello"), unsupportedPart),
            )

            assertEquals("${unsupportedPart::class.simpleName} must fall back", TavernPresentationMode.COMPOSE, decision.mode)
            assertNotNull("${unsupportedPart::class.simpleName} must explain its fallback", decision.fallbackReason)
        }
    }

    private fun tavernAssistant() = Assistant(tavernCardJson = "{\"name\":\"Card\"}")

    private fun conversation(vararg parts: UIMessagePart) = Conversation(
        assistantId = Uuid.random(),
        messageNodes = listOf(
            MessageNode(
                id = Uuid.random(),
                messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())),
            ),
        ),
    )
}
