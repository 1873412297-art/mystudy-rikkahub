package me.rerere.rikkahub.data.ai.trace

object PromptTraceCopyFormatter {
    fun format(record: PromptTraceRecord): String {
        val metadata = record.payload.metadata
        return buildString {
            appendLine("Tavern Prompt Trace")
            appendLine("Conversation: ${metadata.conversationId}")
            appendLine("Assistant: ${metadata.assistantId}")
            appendLine("Model: ${metadata.modelId}")
            appendLine("Speaker: ${metadata.speakerName ?: metadata.speakerMemberId ?: "-"}")
            appendLine("Status: ${metadata.status}")
            appendLine("Actual prompt tokens: ${metadata.actualPromptTokens ?: "Not provided"}")
            appendLine()
            appendLine("[Injection hits]")
            if (record.payload.injectionHits.isEmpty()) {
                appendLine("(none)")
            } else {
                record.payload.injectionHits.forEach { hit ->
                    appendLine("- ${hit.sourceType}: ${hit.injectionName.ifBlank { hit.injectionId.toString() }}")
                    val matchedTerms = hit.match?.matchedTerms.orEmpty()
                    if (matchedTerms.isNotEmpty()) {
                        appendLine("  matched: ${matchedTerms.joinToString(", ")}")
                    }
                    appendLine(hit.content)
                }
            }
            appendLine()
            appendLine("[Final provider-bound messages]")
            record.payload.finalMessages.forEach { message ->
                appendLine(formatMessage(message))
            }
        }.trimEnd()
    }

    fun formatMessage(message: PromptTraceMessage): String {
        return buildString {
            appendLine("${message.index + 1}. ${message.role}")
            message.name?.let { appendLine("Name: $it") }
            message.parts.forEach { part ->
                when (part) {
                    is PromptTracePart.Text -> appendLine(part.text)
                    is PromptTracePart.Reasoning -> {
                        appendLine("[Reasoning]")
                        appendLine(part.text)
                    }
                    is PromptTracePart.Attachment -> {
                        appendLine("[${part.value.kind}] ${attachmentLabel(part.value)}")
                    }
                    is PromptTracePart.Tool -> {
                        appendLine("[Tool ${part.toolName} / ${part.approvalState}]")
                        appendLine("Input: ${part.input.preview}")
                        part.outputText?.let { appendLine("Output: ${it.preview}") }
                        part.outputAttachments.forEach { attachment ->
                            appendLine("[Tool output ${attachment.kind}] ${attachmentLabel(attachment)}")
                        }
                    }
                }
            }
        }.trimEnd()
    }

    private fun attachmentLabel(attachment: PromptTraceAttachment): String {
        return attachment.displayName
            ?: attachment.uri
            ?: attachment.mimeType
            ?: "binary reference"
    }
}
