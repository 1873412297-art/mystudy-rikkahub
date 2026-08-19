package me.rerere.rikkahub.ui.pages.chat

import java.security.MessageDigest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.status.StatusBlockExtractor
import me.rerere.rikkahub.data.ai.status.StatusOption
import me.rerere.rikkahub.data.ai.status.StatusSection
import me.rerere.rikkahub.data.model.Conversation

data class StatusHudPage(
    val name: String,
    val html: String,
)

data class StatusHudPresentation(
    val headerLine: String,
    val sections: List<StatusSection>,
    val pages: List<StatusHudPage>,
    val options: List<StatusOption>,
    val htmlContent: String?,
    val sourceMessage: UIMessage,
    val updateIdentity: String,
    val isUpdating: Boolean,
)

/** Selects the newest assistant status without mutating the conversation. */
fun buildStatusHudPresentation(conversation: Conversation): StatusHudPresentation? {
    conversation.currentMessages.asReversed().forEach { message ->
        if (message.role != MessageRole.ASSISTANT) return@forEach

        val text = message.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
        val extraction = StatusBlockExtractor.extract(text)
        val placeholder = message.parts.filterIsInstance<UIMessagePart.StatusPlaceholder>().lastOrNull()
        if (extraction.rawStatusText == null && placeholder == null) return@forEach

        val pages = placeholder?.characterPages.orEmpty().map { StatusHudPage(it.name, it.html) }
        val updatePayload = buildString {
            append(extraction.rawStatusText.orEmpty())
            append('\u0000')
            append(placeholder?.htmlContent.orEmpty())
            pages.forEach { page ->
                append('\u0000').append(page.name).append('\u0000').append(page.html)
            }
        }
        return StatusHudPresentation(
            headerLine = extraction.headerLine ?: "状态栏",
            sections = extraction.sections,
            pages = pages,
            options = extraction.options,
            htmlContent = placeholder?.htmlContent,
            sourceMessage = message,
            updateIdentity = "${message.id}:${updatePayload.sha256()}",
            isUpdating = message.finishedAt == null,
        )
    }
    return null
}

/** HUD choices are drafts: prefill first, then close the panel. There is deliberately no send callback. */
fun selectStatusHudOption(
    optionText: String,
    onPrefill: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    onPrefill(optionText)
    onDismiss()
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
