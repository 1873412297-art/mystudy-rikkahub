package me.rerere.rikkahub.ui.pages.chat.tavern

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/** Visible debug fixture for the real conversation host's failure/retry UI. */
class TavernConversationRecoveryActivity : ComponentActivity() {
    val renderStatuses = CopyOnWriteArrayList<TavernConversationRenderStatus>()
    val fallbackCount = AtomicInteger(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val snapshot = TavernConversationSnapshot(
            conversationId = CONVERSATION_ID.toString(),
            nodes = listOf(
                TavernConversationNode(
                    id = NODE_ID.toString(),
                    selectedIndex = 0,
                    branchCount = 1,
                    selectedMessage = TavernConversationMessage(
                        id = MESSAGE_ID.toString(),
                        role = MessageRole.ASSISTANT,
                        name = "Recovery Character",
                        parts = listOf(
                            TavernConversationTextPart(
                                text = FALLBACK_TEXT,
                                renderMode = UIMessagePart.RenderMode.MARKDOWN,
                            ),
                        ),
                    ),
                ),
            ),
            userName = "Device User",
            characterName = "Recovery Character",
            themeCssVariables = emptyMap(),
            cardCss = "",
            streaming = false,
        )
        val context = buildJsonObject {
            put("marker", "recovery")
            put("chat", JsonArray(emptyList()))
        }
        val currentMessage = buildJsonObject {
            put("id", "recovery-current")
            put("role", "ASSISTANT")
            put("content", FALLBACK_TEXT)
        }
        val actions = object : TavernConversationActions {
            override fun onMessageLongPress(messageId: Uuid) = Unit
            override fun onSelectBranch(nodeId: Uuid, index: Int) = Unit
            override fun onOpenHtml(messageId: Uuid) = Unit
            override fun onFallbackRequested() {
                fallbackCount.incrementAndGet()
            }
        }

        setContent {
            MaterialTheme {
                TavernConversationWebView(
                    snapshot = snapshot,
                    contextSnapshot = context,
                    currentMessage = currentMessage,
                    headerSource = { emptyList() },
                    actions = actions,
                    onRenderStatus = { renderStatuses += it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    companion object {
        const val FALLBACK_TEXT = "preserved raw fallback text"
        private val CONVERSATION_ID = Uuid.parse("00000000-0000-0000-0000-000000000201")
        private val NODE_ID = Uuid.parse("00000000-0000-0000-0000-000000000202")
        private val MESSAGE_ID = Uuid.parse("00000000-0000-0000-0000-000000000203")
    }
}
