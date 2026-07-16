package me.rerere.rikkahub.data.ai.trace

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class PromptTraceCopyFormatterTest {
    @Test
    fun `full copy contains metadata hits headings and sanitized final messages`() {
        val metadata = PromptTraceMetadata(
            conversationId = Uuid.random(),
            assistantId = Uuid.random(),
            modelId = Uuid.random(),
            isGroup = true,
            speakerMemberId = Uuid.random(),
            speakerName = "Alice",
            providerStepIndex = 2,
            startedAtEpochMs = 1L,
            status = PromptTraceStatus.COMPLETED,
            actualPromptTokens = 42,
        )
        val message = PromptTraceMessage(
            id = Uuid.random(),
            index = 0,
            role = MessageRole.SYSTEM,
            parts = listOf(PromptTracePart.Text("system text")),
            characterCount = 11,
            approximateTokens = 3,
        )
        val hit = PromptInjectionTrace(
            injectionId = Uuid.random(),
            injectionName = "Weather lore",
            sourceType = PromptInjectionSourceType.LOREBOOK,
            match = PromptInjectionMatch(
                type = PromptInjectionMatchType.KEYWORD,
                matchedTerms = listOf("rain", "storm"),
                scanDepth = 4,
                scannedMessageIds = emptyList(),
                caseSensitive = false,
                regexEnabled = false,
            ),
            position = "RELATIVE",
            role = MessageRole.SYSTEM,
            priority = 10,
            injectDepth = 0,
            content = "Carry an umbrella.",
        )
        val record = PromptTraceRecord(
            traceId = Uuid.random(),
            payload = PromptTracePayload(
                metadata = metadata,
                sections = emptyList(),
                injectionHits = listOf(hit),
                finalMessages = listOf(message),
            ),
        )

        val copied = PromptTraceCopyFormatter.format(record)

        assertTrue(copied.contains("Tavern Prompt Trace"))
        assertTrue(copied.contains("Conversation: ${metadata.conversationId}"))
        assertTrue(copied.contains("Assistant: ${metadata.assistantId}"))
        assertTrue(copied.contains("Model: ${metadata.modelId}"))
        assertTrue(copied.contains("Speaker: Alice"))
        assertTrue(copied.contains("Status: COMPLETED"))
        assertTrue(copied.contains("Actual prompt tokens: 42"))
        assertTrue(copied.contains("[Injection hits]"))
        assertTrue(copied.contains("- LOREBOOK: Weather lore"))
        assertTrue(copied.contains("matched: rain, storm"))
        assertTrue(copied.contains("Carry an umbrella."))
        assertTrue(copied.contains("[Final provider-bound messages]"))
        assertTrue(copied.contains("1. SYSTEM"))
        assertTrue(copied.contains("system text"))
        assertFalse(copied.contains("Authorization"))
    }

    @Test
    fun `full copy renders empty hits and unavailable prompt usage readably`() {
        val metadata = PromptTraceMetadata(
            conversationId = Uuid.random(),
            assistantId = Uuid.random(),
            modelId = Uuid.random(),
            isGroup = false,
            providerStepIndex = 0,
            startedAtEpochMs = 1L,
        )
        val record = PromptTraceRecord(
            traceId = Uuid.random(),
            payload = PromptTracePayload(
                metadata = metadata,
                sections = emptyList(),
                injectionHits = emptyList(),
                finalMessages = emptyList(),
            ),
        )

        val copied = PromptTraceCopyFormatter.format(record)

        assertTrue(copied.contains("Speaker: -"))
        assertTrue(copied.contains("Actual prompt tokens: Not provided"))
        assertTrue(copied.contains("[Injection hits]\n(none)"))
    }

    @Test
    fun `message copy formats every sanitized part without excluded content`() {
        val source = UIMessage(
            role = MessageRole.ASSISTANT,
            name = "Alice",
            parts = listOf(
                UIMessagePart.Text("answer"),
                UIMessagePart.Reasoning(
                    reasoning = "reasoning summary",
                    metadata = buildJsonObject { put("signature", "opaque-signature") },
                ),
                UIMessagePart.Image("data:image/png;base64,aGVsbG8="),
                UIMessagePart.Tool(
                    toolCallId = "call",
                    toolName = "fetch",
                    input = """
                        {
                          "authorization":"Bearer tool-secret",
                          "url":"https://example.com/input?token=url-secret#frag",
                          "blob":"data:text/plain,plain-data-secret"
                        }
                    """.trimIndent(),
                    output = listOf(
                        UIMessagePart.Text("""{"cookie":"cookie-secret","ok":true}"""),
                        UIMessagePart.Image("https://example.com/output.png?key=output-secret#frag"),
                        UIMessagePart.Document(
                            "data:application/pdf;base64,aGVsbG8=",
                            "report.pdf",
                            "application/pdf",
                        ),
                    ),
                    approvalState = ToolApprovalState.Answered("private answer"),
                ),
            ),
        )
        val sanitized = PromptTraceSanitizer.sanitizeMessages(listOf(source)).single()

        val copied = PromptTraceCopyFormatter.formatMessage(sanitized)

        assertTrue(copied.contains("1. ASSISTANT"))
        assertTrue(copied.contains("Name: Alice"))
        assertTrue(copied.contains("answer"))
        assertTrue(copied.contains("[Reasoning]"))
        assertTrue(copied.contains("reasoning summary"))
        assertTrue(copied.contains("[IMAGE] image/png"))
        assertTrue(copied.contains("[Tool fetch / ANSWERED]"))
        assertTrue(copied.contains("Input:"))
        assertTrue(copied.contains("Output:"))
        assertTrue(copied.contains("[Tool output IMAGE] https://example.com/output.png"))
        assertTrue(copied.contains("[Tool output DOCUMENT] report.pdf"))
        assertTrue(copied.contains("[redacted]"))
        assertTrue(copied.contains("[stripped"))
        listOf(
            "opaque-signature",
            "tool-secret",
            "url-secret",
            "plain-data-secret",
            "cookie-secret",
            "output-secret",
            "private answer",
            "aGVsbG8=",
            "?token=",
            "?key=",
            "#frag",
        ).forEach { excluded ->
            assertFalse("Copied text leaked $excluded: $copied", copied.contains(excluded))
        }
    }

    @Test
    fun `copy excludes wrapped data fragments loose credentials and network user info`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "copy",
                    toolName = "fetch",
                    input = """
                        Authorization: Basic dXNlcjpwYXNz
                        Cookie: session=cookie-one; csrf=cookie-two
                        tenantCustomBody={"secretKey":"body-secret","safe":"body-safe"}
                        accessKeyId=access-id
                        blob=data:image/png;base64,aGVs
                        bG8=
                        url=https://copy-user:copy-pass@example.com/a?token=url-secret#frag
                    """.trimIndent(),
                ),
            ),
        )
        val copied = PromptTraceCopyFormatter.formatMessage(
            PromptTraceSanitizer.sanitizeMessages(listOf(message)).single(),
        )

        assertTrue("Expected complete wrapped data stripping in $copied", copied.contains("[stripped bytes=5"))
        assertTrue(copied.contains("https://example.com/a"))
        listOf(
            "aGVs",
            "bG8=",
            "dXNlcjpwYXNz",
            "cookie-one",
            "cookie-two",
            "body-secret",
            "body-safe",
            "access-id",
            "copy-user",
            "copy-pass",
            "url-secret",
        ).forEach { excluded ->
            assertFalse("Copied text leaked $excluded: $copied", copied.contains(excluded))
        }
    }
}
