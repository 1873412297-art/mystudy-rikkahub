package me.rerere.rikkahub.ui.pages.chat.tavern

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
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
    fun `uses ST web for group assistants with a Tavern card`() {
        val decision = resolveTavernPresentation(
            assistant = tavernAssistant().copy(assistantType = AssistantType.GROUP),
            conversation = conversation(UIMessagePart.Text("Hello")),
        )

        assertEquals(TavernPresentationMode.ST_WEB, decision.mode)
        assertNull(decision.fallbackReason)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `keeps every message part family in ST web`() {
        val supportedParts = listOf(
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

        supportedParts.forEach { supportedPart ->
            val decision = resolveTavernPresentation(
                assistant = tavernAssistant(),
                conversation = conversation(UIMessagePart.Text("Hello"), supportedPart),
            )

            assertEquals("${supportedPart::class.simpleName} must stay in ST web", TavernPresentationMode.ST_WEB, decision.mode)
            assertNull("${supportedPart::class.simpleName} must not explain a fallback", decision.fallbackReason)
        }
    }

    @Test
    fun `presentation resolver accepts the assistant index required by mixed groups`() {
        val source = listOf(
            File("src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernPresentationContracts.kt"),
            File("app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernPresentationContracts.kt"),
        ).first { it.exists() }.readText()
        val chatPage = listOf(
            File("src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt"),
            File("app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt"),
        ).first { it.exists() }.readText()

        assertTrue(source.contains("assistantsById: Map<Uuid, Assistant>"))
        assertTrue(source.contains("member.assistantId"))
        assertTrue(chatPage.contains("assistantsById = assistantsById"))
    }

    @Test
    fun `tavern message area has no compose compatibility escape hatch`() {
        val sources = listOf(
            "app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt",
            "app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationWebView.kt",
            "app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationBridge.kt",
        ).map { path -> File(path).takeIf(File::exists) ?: File(path.removePrefix("app/")) }
            .joinToString("\n") { it.readText() }

        assertTrue(!sources.contains("forceComposeTavern"))
        assertTrue(!sources.contains("onFallbackRequested"))
        assertTrue(!sources.contains("requestFallback"))
        assertTrue(!sources.contains("切换兼容视图"))
        assertTrue(!sources.contains("已切换兼容视图"))
    }

    @Test
    fun `native top bar and chat input remain mounted for Tavern conversations`() {
        val chatPage = listOf(
            File("src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt"),
            File("app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt"),
        ).first { it.exists() }.readText()
        val topBarBlock = chatPage.substringAfter("topBar = {").substringBefore("bottomBar = {")
        val bottomBarBlock = chatPage.substringAfter("bottomBar = {").substringBefore("floatingActionButton = {")

        assertTrue(topBarBlock.contains("TopBar("))
        assertTrue(bottomBarBlock.contains("ChatInput("))
        assertFalse(topBarBlock.contains("immersiveTavernActive"))
        assertFalse(bottomBarBlock.contains("immersiveTavernActive"))
    }

    @Test
    fun `native top bar does not duplicate the inline opening selector`() {
        val chatPage = listOf(
            File("src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt"),
            File("app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt"),
        ).first { it.exists() }.readText()
        val topBarFunction = chatPage.substringAfter("private fun TopBar(")

        assertFalse(topBarFunction.contains("onOpenOpening"))
        assertFalse(topBarFunction.contains("HugeIcons.BookOpen01"))
        assertFalse(topBarFunction.contains("查看开场"))
    }

    @Test
    fun `opening stage routes one shot motion from the real selected candidate`() {
        val stage = sourceFile("TavernOpeningStage.kt")

        assertTrue(stage.contains("resolveTavernOpeningSelectionDirection(selectedIndex, index, candidates.size)"))
        assertTrue(stage.contains("TavernOpeningSelectionMotion("))
        assertTrue(stage.contains("openingSelectionMotion = selectionMotion.takeIf { index == selectedIndex }"))
        assertTrue(stage.contains("TavernConversationPane("))
    }

    @Test
    fun `conversation web view dispatches each opening motion once after ready`() {
        val webView = sourceFile("TavernConversationWebView.kt")

        assertTrue(webView.contains("openingSelectionMotion: TavernOpeningSelectionMotion? = null"))
        assertTrue(webView.contains("openingSelectionMotion = openingSelectionMotion"))
        assertTrue(webView.contains("deliveredOpeningMotionId"))
        assertTrue(webView.contains("renderState.status != TavernConversationRenderStatus.READY"))
        assertTrue(webView.contains("postOpeningSelectionMotion(openingSelectionMotion.direction)"))
        val dispatcher = webView.substringAfter("private fun WebView.postOpeningSelectionMotion")
            .substringBefore("private fun WebView.postRuntimeContext")
        assertTrue(dispatcher.contains("RikkahubConversationDocument.triggerOpeningTransition"))
        assertTrue(dispatcher.contains("postEvaluate("))
    }

    @Test
    fun `conversation web view injects the app surface into the sticky opening layer`() {
        val webView = sourceFile("TavernConversationWebView.kt")
        val themeVariables = webView.substringAfter("val themeVariables = mapOf(")
            .substringBefore("val visibleConversation")

        assertTrue(themeVariables.contains("\"--rikkahub-sticky-bg\" to hex(colorScheme.surface)"))
    }

    private fun tavernAssistant() = Assistant(tavernCardJson = "{\"name\":\"Card\"}")

    private fun sourceFile(name: String): String = listOf(
        File("src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/$name"),
        File("app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/$name"),
    ).firstOrNull { it.exists() }?.readText()
        ?: error("$name not found in test working dir")

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
