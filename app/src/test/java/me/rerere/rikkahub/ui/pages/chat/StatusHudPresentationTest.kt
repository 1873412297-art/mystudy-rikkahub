package me.rerere.rikkahub.ui.pages.chat

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class StatusHudPresentationTest {

    @Test
    fun `latest assistant status supplies the floating summary`() {
        val old = assistantStatus("00000000-0000-0000-0000-000000000011", "旧状态", "旧选项")
        val user = UIMessage(
            id = uuid("00000000-0000-0000-0000-000000000012"),
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("继续")),
        )
        val latest = assistantStatus("00000000-0000-0000-0000-000000000013", "最新状态", "最新选项")

        val presentation = buildStatusHudPresentation(conversation(old, user, latest))

        requireNotNull(presentation)
        assertEquals("『最新状态』", presentation.headerLine)
        assertEquals(latest.id, presentation.sourceMessage.id)
        assertEquals(listOf("最新选项"), presentation.options.map { it.text })
    }

    @Test
    fun `streaming changes keep source identity but advance update identity`() {
        val messageId = "00000000-0000-0000-0000-000000000021"
        val first = assistantStatus(messageId, "第 1 回合", "向左")
        val streamed = assistantStatus(messageId, "第 2 回合", "向右")
        val finished = streamed.copy(finishedAt = LocalDateTime(2026, 8, 20, 12, 0))

        val firstPresentation = requireNotNull(buildStatusHudPresentation(conversation(first)))
        val streamedPresentation = requireNotNull(buildStatusHudPresentation(conversation(streamed)))
        val finishedPresentation = requireNotNull(buildStatusHudPresentation(conversation(finished)))

        assertEquals(firstPresentation.sourceMessage.id, streamedPresentation.sourceMessage.id)
        assertNotEquals(firstPresentation.updateIdentity, streamedPresentation.updateIdentity)
        assertTrue(streamedPresentation.isUpdating)
        assertFalse(finishedPresentation.isUpdating)
    }

    @Test
    fun `multi character placeholder pages and complete html are preserved`() {
        val message = assistantStatus("00000000-0000-0000-0000-000000000031", "队伍状态", "前进").copy(
            parts = assistantStatus(
                "00000000-0000-0000-0000-000000000031",
                "队伍状态",
                "前进",
            ).parts + UIMessagePart.StatusPlaceholder(
                htmlContent = "<section id=\"world\">完整世界状态</section>",
                characterPages = listOf(
                    UIMessagePart.CharacterStatusPage("艾莉娅", "<article>HP 10</article>"),
                    UIMessagePart.CharacterStatusPage("守卫", "<article>HP 8</article>"),
                ),
            ),
        )

        val presentation = requireNotNull(buildStatusHudPresentation(conversation(message)))

        assertEquals("<section id=\"world\">完整世界状态</section>", presentation.htmlContent)
        assertEquals(listOf("艾莉娅", "守卫"), presentation.pages.map { it.name })
        assertEquals(listOf("<article>HP 10</article>", "<article>HP 8</article>"), presentation.pages.map { it.html })
    }

    @Test
    fun `option selection prefills input and closes without a send callback`() {
        val events = mutableListOf<String>()

        selectStatusHudOption(
            optionText = "调查脚印",
            onPrefill = { events += "prefill:$it" },
            onDismiss = { events += "dismiss" },
        )

        assertEquals(listOf("prefill:调查脚印", "dismiss"), events)
    }

    @Test
    fun `status hud gives the primary card the remaining adaptive panel height`() {
        val source = statusHudSource()

        assertTrue(source.contains("fillMaxHeight(policy.panelFraction)"))
        assertTrue(source.contains("maxHeightDp = null"))
        assertTrue(source.contains("contentDescription = \"全屏显示状态栏\""))
        assertTrue(source.contains("contentDescription = \"恢复角色卡显示默认设置\""))
        assertFalse(source.contains("maxHeightDp = 360"))
    }

    private fun statusHudSource(): String = listOf(
        java.io.File("src/main/java/me/rerere/rikkahub/ui/pages/chat/StatusHudBar.kt"),
        java.io.File("app/src/main/java/me/rerere/rikkahub/ui/pages/chat/StatusHudBar.kt"),
    ).firstOrNull { it.exists() }?.readText()
        ?: error("StatusHudBar.kt not found in test working dir")

    private fun assistantStatus(id: String, header: String, option: String) = UIMessage(
        id = uuid(id),
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Text(
                """
                正文
                <status_block>
                『$header』
                <details><summary>角色状态</summary><b>HP 10</b></details>
                1. [继续] $option
                </status_block>
                """.trimIndent(),
            ),
        ),
    )

    private fun conversation(vararg messages: UIMessage) = Conversation(
        id = uuid("00000000-0000-0000-0000-000000000001"),
        assistantId = uuid("00000000-0000-0000-0000-000000000002"),
        messageNodes = messages.map(MessageNode::of),
    )

    private fun uuid(value: String): Uuid = Uuid.parse(value)
}
