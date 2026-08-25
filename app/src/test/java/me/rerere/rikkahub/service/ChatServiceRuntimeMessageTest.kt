package me.rerere.rikkahub.service

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ChatServiceRuntimeMessageTest {
    @Test
    fun `exact runtime text update preserves message identity attachments annotations and metadata`() {
        val textMetadata = buildJsonObject { put("format", "markdown") }
        val image = UIMessagePart.Image("file:///image.png", buildJsonObject { put("alt", "map") })
        val original = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("old", metadata = textMetadata), image),
            annotations = listOf(UIMessageAnnotation.UrlCitation("source", "https://example.com")),
        )

        val updated = original.replaceRuntimeMessageText("new")

        assertEquals(original.id, updated.id)
        assertEquals(original.role, updated.role)
        assertEquals(original.annotations, updated.annotations)
        assertEquals("new", (updated.parts.first() as UIMessagePart.Text).text)
        assertEquals(textMetadata, (updated.parts.first() as UIMessagePart.Text).metadata)
        assertSame(image, updated.parts[1])
    }
}
