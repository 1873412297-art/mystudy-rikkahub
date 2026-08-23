package me.rerere.rikkahub.ui.pages.chat.tavern

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.status.TavernHostEventBus
import me.rerere.rikkahub.data.ai.status.TavernHostEventType
import org.koin.compose.koinInject
import org.koin.android.ext.android.inject
import kotlin.uuid.Uuid

/** Visible debug fixture for the real conversation host's failure/retry UI. */
class TavernConversationRecoveryActivity : ComponentActivity() {
    private val hostEventBus by inject<TavernHostEventBus>()
    val renderStatuses = CopyOnWriteArrayList<TavernConversationRenderStatus>()
    val richOpeningSelections = CopyOnWriteArrayList<Int>()
    val richOpeningSelectedIndex = AtomicInteger(0)
    val richOpeningSwipeEvents = AtomicInteger(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_RICH_OPENING_FIXTURE, false)) {
            lifecycleScope.launch {
                hostEventBus.events.collect { event ->
                    if (event.type == TavernHostEventType.MESSAGE_SWIPED && event.conversationId == CONVERSATION_ID) {
                        richOpeningSwipeEvents.incrementAndGet()
                    }
                }
            }
        }
        val recoverySnapshot = TavernConversationSnapshot(
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
            override fun onToolApproval(toolCallId: String, approved: Boolean, reason: String) = Unit
            override fun onToolAnswer(toolCallId: String, answer: String) = Unit
        }

        setContent {
            MaterialTheme {
                if (intent.getBooleanExtra(EXTRA_RICH_OPENING_FIXTURE, false)) {
                    RichOpeningFixture()
                } else {
                TavernConversationWebView(
                    snapshot = recoverySnapshot,
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
    }

    @androidx.compose.runtime.Composable
    private fun RichOpeningFixture() {
        val hostEventBus: TavernHostEventBus = koinInject()
        val openings = remember { (0..2).map(::richOpeningHtml) }
        var selectedIndex by remember { mutableIntStateOf(0) }
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
                        name = "Rich Character",
                        parts = listOf(
                            TavernConversationTextPart(
                                text = resolveTavernDisplayText(
                                    openings[selectedIndex],
                                    userName = "Device User",
                                    characterName = "Rich Character",
                                ),
                                renderMode = UIMessagePart.RenderMode.HTML,
                            ),
                        ),
                    ),
                ),
            ),
            userName = "Device User",
            characterName = "Rich Character",
            themeCssVariables = mapOf(
                "--SmartThemeBodyColor" to "rgb(225, 225, 218)",
                "--SmartThemeQuoteColor" to "rgb(225, 138, 36)",
                "--rikkahub-text" to "rgb(225, 225, 218)",
            ),
            cardCss = ".mes { border-radius: 18px; }",
            streaming = false,
            openingSwipe = TavernOpeningSwipe(
                index = selectedIndex,
                count = openings.size,
                ready = true,
                swipes = openings.map { resolveTavernDisplayText(it, "Device User", "Rich Character") },
            ),
            revision = selectedIndex.toLong(),
        )
        val context = buildJsonObject {
            put("marker", "rich-opening")
            put("chat", JsonArray(emptyList()))
        }
        val currentMessage = buildJsonObject {
            put("id", MESSAGE_ID.toString())
            put("role", "ASSISTANT")
            put("content", "rich opening ${selectedIndex + 1}")
        }
        val actions = object : TavernConversationActions {
            override fun onMessageLongPress(messageId: Uuid) = Unit
            override fun onSelectBranch(nodeId: Uuid, index: Int) = Unit
            override fun onOpenHtml(messageId: Uuid) = Unit
            override fun onToolApproval(toolCallId: String, approved: Boolean, reason: String) = Unit
            override fun onToolAnswer(toolCallId: String, answer: String) = Unit
            override fun onSelectGreeting(index: Int) {
                if (index !in openings.indices || index == selectedIndex) return
                selectedIndex = index
                richOpeningSelectedIndex.set(index)
                richOpeningSelections += index
                hostEventBus.emit(
                    type = TavernHostEventType.MESSAGE_SWIPED,
                    conversationId = CONVERSATION_ID,
                    payload = buildJsonObject {
                        put("nodeId", NODE_ID.toString())
                        put("selectIndex", index)
                        put("opening", true)
                    },
                )
            }
        }
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

    companion object {
        const val FALLBACK_TEXT = "preserved raw fallback text"
        const val EXTRA_RICH_OPENING_FIXTURE = "rich_opening_fixture"
        private val CONVERSATION_ID = Uuid.parse("00000000-0000-0000-0000-000000000201")
        private val NODE_ID = Uuid.parse("00000000-0000-0000-0000-000000000202")
        private val MESSAGE_ID = Uuid.parse("00000000-0000-0000-0000-000000000203")

        private fun richOpeningHtml(selectedIndex: Int): String {
            val labels = listOf("One", "Two", "Three")
            val cards = (0..2).joinToString("") { index ->
                "<button class=\"card ${if (index == selectedIndex) "active" else ""}\" " +
                    "onclick=\"switchToOpening($index)\"><img class=\"portrait\" " +
                    "src=\"data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==\">" +
                    "<b>Choose ${labels[index]}</b><small> Path ${index + 1}</small></button>"
            }
            return """
            <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              :root{--ink:#eee9df;--quote:#e18a24;--panel:rgba(22,27,38,.92);--accent:#66d1ff}
              *{box-sizing:border-box}body{margin:0;padding:12px;color:var(--ink);font-family:system-ui,sans-serif;
                background:radial-gradient(circle at 15% 0,#294561,#111621 58%);transition:filter .25s ease}
              body.warm{filter:sepia(.28)}header{display:flex;justify-content:space-between;align-items:center;margin-bottom:10px}
              .grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px}.card{min-height:142px;padding:8px;
                border:1px solid rgba(255,255,255,.2);border-radius:14px;color:inherit;background:var(--panel);
                transform:translateY(0);transition:transform .18s ease,border-color .18s ease;text-align:left}
              .card:active{transform:scale(.96)}.card.active{border-color:var(--accent);box-shadow:0 0 18px rgba(102,209,255,.28)}
              .portrait{display:block;width:100%;height:64px;object-fit:cover;border-radius:9px;background:#24364a}
              q{color:var(--quote)}details{margin-top:10px;padding:8px;border-radius:10px;background:rgba(0,0,0,.22)}
              @keyframes enter{from{opacity:0;transform:translateY(8px)}to{opacity:1;transform:none}}main{animation:enter .28s ease}
            </style>
            <script>
              async function switchToOpening(index){
                var messages=await getChatMessages('0',{include_swipes:true});
                await setChatMessage(messages[0].swipes[index],0,{swipe_id:index,refresh:'display_and_render_current'});
              }
              addEventListener('DOMContentLoaded',function(){
                document.getElementById('theme-toggle').onclick=function(){document.body.classList.toggle('warm')};
                if(window.eventSource&&window.event_types){
                  eventSource.on(event_types.MESSAGE_SWIPED,function(){
                    document.body.dataset.swiped='true';
                    TavernHelperCompat.variables.set('rich_fixture_swiped',true);
                  });
                }
              });
            </script></head><body><main>
              <header><strong>Welcome, {user}</strong><button id="theme-toggle">Theme</button></header>
              <div class="grid">
                $cards
              </div>
              <p id="opening-copy">Opening ${selectedIndex + 1}: <q>Distinct dialogue colour</q></p>
              <details><summary>Character status</summary><p>Affinity ${30 + selectedIndex * 20}%</p></details>
            </main></body></html>
            """.trimIndent()
        }
    }
}
