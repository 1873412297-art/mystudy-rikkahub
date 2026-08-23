package me.rerere.rikkahub.ui.pages.chat.tavern

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.tavern.TavernGreetingOverlay
import me.rerere.rikkahub.service.tavern.TavernGreetingRegistrations
import me.rerere.rikkahub.ui.pages.chat.buildStatusHudPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.uuid.Uuid

class TavernOpeningSelectionMotionTest {

    @Test
    fun `direction follows previous next and same selection`() {
        assertEquals(1, resolveTavernOpeningSelectionDirection(previousIndex = 0, nextIndex = 1, count = 5))
        assertEquals(-1, resolveTavernOpeningSelectionDirection(previousIndex = 3, nextIndex = 1, count = 5))
        assertEquals(0, resolveTavernOpeningSelectionDirection(previousIndex = 2, nextIndex = 2, count = 5))
    }

    @Test
    fun `direction rejects out of range selections`() {
        assertEquals(0, resolveTavernOpeningSelectionDirection(previousIndex = -1, nextIndex = 0, count = 5))
        assertEquals(0, resolveTavernOpeningSelectionDirection(previousIndex = 0, nextIndex = 5, count = 5))
        assertEquals(0, resolveTavernOpeningSelectionDirection(previousIndex = 0, nextIndex = 0, count = 0))
    }

    @Test
    fun `opening preview exposes selected candidate status to floating hud`() {
        val conversation = Conversation.ofId(
            id = Uuid.parse("00000000-0000-0000-0000-000000000001"),
            assistantId = Uuid.parse("00000000-0000-0000-0000-000000000002"),
            newConversation = true,
        )
        val overlay = TavernGreetingOverlay(
            messages = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text("正文\n<status_block>『开场状态』</status_block>")),
                ),
            ),
            chatVariables = JsonObject(emptyMap()),
            globalVariables = JsonObject(emptyMap()),
            worldEntries = emptyList(),
            registrations = TavernGreetingRegistrations(),
        )

        val preview = buildTavernOpeningPreviewConversation(conversation, overlay)

        assertEquals(overlay.messages.single().id, preview.currentMessages.single().id)
        assertNotNull(buildStatusHudPresentation(preview))
    }
}
