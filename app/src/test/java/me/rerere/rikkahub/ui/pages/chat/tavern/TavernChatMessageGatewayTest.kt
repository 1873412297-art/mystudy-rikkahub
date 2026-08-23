package me.rerere.rikkahub.ui.pages.chat.tavern

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernChatMessageGatewayTest {

    @Test
    fun `setChatMessage selects matching visual opening through authoritative callback`() {
        val openings = listOf("<p>一</p>", "<p>二</p>", "<p>三</p>")
        val selections = mutableListOf<Triple<Int, Int, Long>>()
        val gateway = TavernConversationMessageGateway(
            snapshotProvider = { snapshotWithOpenings(openings, selectedIndex = 0, revision = 9) },
            dispatchGreeting = { index, count, revision -> selections += Triple(index, count, revision) },
        )

        val result = gateway.setChatMessage(setOpeningParams(openings[2], swipeIndex = 2))

        assertEquals(TavernChatMutationResult.Accepted, result)
        assertEquals(listOf(Triple(2, 3, 9L)), selections)
    }

    @Test
    fun `setChatMessage rejects forged opening text and invalid refresh without dispatch`() {
        val openings = listOf("<p>一</p>", "<p>二</p>")
        val selections = mutableListOf<Int>()
        val gateway = TavernConversationMessageGateway(
            snapshotProvider = { snapshotWithOpenings(openings, selectedIndex = 0, revision = 4) },
            dispatchGreeting = { index, _, _ -> selections += index },
        )

        val forged = gateway.setChatMessage(setOpeningParams("<p>伪造</p>", swipeIndex = 1))
        val invalidRefresh = gateway.setChatMessage(
            setOpeningParams(openings[1], swipeIndex = 1, refresh = "reload_everything"),
        )
        val invalidIndex = gateway.setChatMessage(setOpeningParams(openings[1], swipeIndex = 7))

        assertEquals("MESSAGE_MISMATCH", (forged as TavernChatMutationResult.Rejected).code)
        assertEquals("BAD_REQUEST", (invalidRefresh as TavernChatMutationResult.Rejected).code)
        assertEquals("INDEX_OUT_OF_RANGE", (invalidIndex as TavernChatMutationResult.Rejected).code)
        assertTrue(selections.isEmpty())
    }

    @Test
    fun `setChatMessages validates entire batch before dispatching`() {
        val openings = listOf("<p>一</p>", "<p>二</p>")
        val selections = mutableListOf<Int>()
        val gateway = TavernConversationMessageGateway(
            snapshotProvider = { snapshotWithOpenings(openings, selectedIndex = 0, revision = 5) },
            dispatchGreeting = { index, _, _ -> selections += index },
        )
        val params = buildJsonObject {
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("message_id", 0)
                    put("message", openings[1])
                    put("swipe_id", 1)
                })
                add(buildJsonObject {
                    put("message_id", 4)
                    put("message", "not present")
                })
            })
            putJsonObject("options") { put("refresh", "affected") }
        }

        val result = gateway.setChatMessages(params)

        assertEquals("MESSAGE_NOT_FOUND", (result as TavernChatMutationResult.Rejected).code)
        assertTrue(selections.isEmpty())
    }

    @Test
    fun `opening query returns SillyTavern swipe shape`() {
        val openings = listOf("<article>第一幕</article>", "<article>第二幕</article>", "<article>第三幕</article>")
        val gateway = TavernConversationMessageGateway(
            snapshotProvider = { snapshotWithOpenings(openings, selectedIndex = 1, revision = 9) },
        )

        val messages = gateway.getChatMessages(
            range = "0",
            options = TavernChatQueryOptions(includeSwipes = true),
        )

        val first = messages.single().jsonObject
        assertEquals(0, first["message_id"]!!.jsonPrimitive.content.toInt())
        assertEquals("assistant", first["role"]!!.jsonPrimitive.content)
        assertEquals("false", first["is_hidden"]!!.jsonPrimitive.content)
        assertEquals(1, first["swipe_id"]!!.jsonPrimitive.content.toInt())
        assertEquals(3, first["swipes"]!!.jsonArray.size)
        assertEquals("<article>第二幕</article>", first["message"]!!.jsonPrimitive.content)
        assertEquals("<article>第二幕</article>", first["swipes"]!!.jsonArray[1].jsonPrimitive.content)
        assertEquals(3, first["swipes_data"]!!.jsonArray.size)
        assertEquals(3, first["swipes_info"]!!.jsonArray.size)
    }

    @Test
    fun `query normalizes negative and reversed ranges then filters roles`() {
        val gateway = TavernConversationMessageGateway(
            snapshotProvider = {
                snapshot(
                    node("n0", message("m0", MessageRole.SYSTEM, "system")),
                    node("n1", message("m1", MessageRole.USER, "user")),
                    node("n2", message("m2", MessageRole.ASSISTANT, "assistant")),
                )
            },
        )

        val reversed = gateway.getChatMessages("2-0", TavernChatQueryOptions())
        assertEquals(listOf("0", "1", "2"), reversed.messageIds())

        val lastAssistant = gateway.getChatMessages(
            "-1",
            TavernChatQueryOptions(role = "assistant", hideState = "unhidden"),
        )
        assertEquals(listOf("2"), lastAssistant.messageIds())

        val hidden = gateway.getChatMessages("0-2", TavernChatQueryOptions(hideState = "hidden"))
        assertEquals(listOf("0"), hidden.messageIds())
        assertTrue(gateway.getChatMessages("invalid", TavernChatQueryOptions()).isEmpty())
    }

    private fun JsonArray.messageIds(): List<String> = map {
        it.jsonObject["message_id"]!!.jsonPrimitive.content
    }

    private fun setOpeningParams(
        message: String,
        swipeIndex: Int,
        refresh: String = "display_and_render_current",
    ) = buildJsonObject {
        put("field_values", buildJsonObject { put("message", message) })
        put("message_id", 0)
        putJsonObject("options") {
            put("swipe_id", swipeIndex)
            put("refresh", refresh)
        }
    }

    private fun snapshotWithOpenings(
        texts: List<String>,
        selectedIndex: Int,
        revision: Long,
    ): TavernConversationSnapshot = TavernConversationSnapshot(
        conversationId = "00000000-0000-0000-0000-000000000001",
        nodes = listOf(
            node(
                "00000000-0000-0000-0000-000000000101",
                message(
                    "00000000-0000-0000-0000-000000000201",
                    MessageRole.ASSISTANT,
                    texts[selectedIndex],
                    UIMessagePart.RenderMode.HTML,
                ),
            ),
        ),
        userName = "阿澈",
        characterName = "白露",
        themeCssVariables = emptyMap(),
        cardCss = "",
        streaming = false,
        revision = revision,
        openingSwipe = TavernOpeningSwipe(
            index = selectedIndex,
            count = texts.size,
            ready = true,
            swipes = texts,
        ),
    )

    private fun snapshot(vararg nodes: TavernConversationNode) = TavernConversationSnapshot(
        conversationId = "00000000-0000-0000-0000-000000000001",
        nodes = nodes.toList(),
        userName = "阿澈",
        characterName = "白露",
        themeCssVariables = emptyMap(),
        cardCss = "",
        streaming = false,
    )

    private fun node(id: String, message: TavernConversationMessage) = TavernConversationNode(
        id = id,
        selectedIndex = 0,
        branchCount = 1,
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
        name = when (role) {
            MessageRole.USER -> "阿澈"
            MessageRole.ASSISTANT -> "白露"
            MessageRole.SYSTEM -> "System"
            MessageRole.TOOL -> "Tool"
        },
        parts = listOf(TavernConversationTextPart(text, renderMode)),
    )
}
