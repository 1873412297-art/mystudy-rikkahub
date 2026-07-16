package me.rerere.rikkahub.data.ai.trace

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class PromptTraceSanitizerTest {
    @Test
    fun `ordinary text and semantic order are preserved`() {
        val memberId = Uuid.random()
        val source = listOf(
            UIMessage.system("system"),
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(
                    UIMessagePart.Text("first"),
                    UIMessagePart.Reasoning("private chain summary"),
                    UIMessagePart.Text("用户文本"),
                ),
                memberId = memberId,
                name = "member",
            ),
            UIMessage.assistant("assistant"),
        )

        val result = PromptTraceSanitizer.sanitizeMessages(source)

        assertEquals(listOf(MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT), result.map { it.role })
        assertEquals(listOf(0, 1, 2), result.map { it.index })
        assertEquals(source.map { it.id }, result.map { it.id })
        assertEquals(memberId, result[1].memberId)
        assertEquals("member", result[1].name)
        assertEquals(
            listOf("first", "private chain summary", "用户文本"),
            result[1].parts.map {
                when (it) {
                    is PromptTracePart.Text -> it.text
                    is PromptTracePart.Reasoning -> it.text
                    else -> error("Unexpected part: $it")
                }
            },
        )
        assertEquals("first\nprivate chain summary\n用户文本".length, result[1].characterCount)
        assertEquals(
            PromptTokenEstimator.estimate("first\nprivate chain summary\n用户文本"),
            result[1].approximateTokens,
        )
    }

    @Test
    fun `data attachment stores metadata without encoded or plain body`() {
        val encoded = "aGVsbG8="
        val message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Image("data:image/png;base64,$encoded"),
                UIMessagePart.Document(
                    url = "data:text/plain,plain-secret-body",
                    fileName = "note.txt",
                    mime = "text/plain",
                ),
                UIMessagePart.Document(
                    url = "DaTa:text/plain;charset=UTF-8,hello%20world",
                    fileName = "encoded.txt",
                    mime = "text/plain",
                ),
            ),
        )

        val attachments = PromptTraceSanitizer.sanitizeMessages(listOf(message))
            .single()
            .parts
            .filterIsInstance<PromptTracePart.Attachment>()
            .map { it.value }

        assertNull(attachments[0].uri)
        assertEquals("image/png", attachments[0].mimeType)
        assertEquals(5L, attachments[0].byteLength)
        assertFalse(attachments[0].sha256.isNullOrBlank())
        assertNull(attachments[1].uri)
        assertEquals("text/plain", attachments[1].mimeType)
        assertEquals("note.txt", attachments[1].displayName)
        assertEquals("plain-secret-body".toByteArray().size.toLong(), attachments[1].byteLength)
        assertNull(attachments[2].uri)
        assertEquals("text/plain", attachments[2].mimeType)
        assertEquals(11L, attachments[2].byteLength)
        assertFalse(attachments.joinToString().contains(encoded))
        assertFalse(attachments.joinToString().contains("plain-secret-body"))
        assertFalse(attachments.joinToString().contains("hello%20world"))
    }

    @Test
    fun `network query and fragment are stripped from every attachment kind`() {
        val message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Image("https://example.com/a.png?token=image-secret#image-frag"),
                UIMessagePart.Video("HTTP://example.com/v.mp4?signature=video-secret#video-frag"),
                UIMessagePart.Audio("https://example.com/a.mp3?key=audio-secret#audio-frag"),
                UIMessagePart.Document(
                    url = "https://example.com/a.pdf?authorization=document-secret#document-frag",
                    fileName = "a.pdf",
                    mime = "application/pdf",
                ),
                UIMessagePart.Document(
                    url = "content://documents/1?local=reference#section",
                    fileName = "local.txt",
                    mime = "text/plain",
                ),
            ),
        )

        val attachments = PromptTraceSanitizer.sanitizeMessages(listOf(message))
            .single()
            .parts
            .filterIsInstance<PromptTracePart.Attachment>()
            .map { it.value }

        assertEquals(
            listOf(
                "https://example.com/a.png",
                "HTTP://example.com/v.mp4",
                "https://example.com/a.mp3",
                "https://example.com/a.pdf",
                "content://documents/1?local=reference#section",
            ),
            attachments.map { it.uri },
        )
        assertEquals(
            listOf(
                PromptTraceAttachmentKind.IMAGE,
                PromptTraceAttachmentKind.VIDEO,
                PromptTraceAttachmentKind.AUDIO,
                PromptTraceAttachmentKind.DOCUMENT,
                PromptTraceAttachmentKind.DOCUMENT,
            ),
            attachments.map { it.kind },
        )
    }

    @Suppress("DEPRECATION")
    @Test
    fun `every current message part variant is converted without metadata`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text(
                    text = "text",
                    metadata = buildJsonObject { put("authorization", "text-metadata-secret") },
                ),
                UIMessagePart.Image("file:///image.png"),
                UIMessagePart.Video("file:///video.mp4"),
                UIMessagePart.Audio("file:///audio.mp3"),
                UIMessagePart.Document("file:///document.pdf", "document.pdf", "application/pdf"),
                UIMessagePart.Reasoning(
                    reasoning = "reasoning",
                    metadata = buildJsonObject { put("signature", "opaque-signature") },
                ),
                UIMessagePart.StatusPlaceholder(
                    htmlContent = """<p>status</p><img src="data:image/png;base64,aGVsbG8="/>""",
                    metadata = buildJsonObject { put("cookie", "status-metadata-secret") },
                ),
                UIMessagePart.Search,
                UIMessagePart.ToolCall(
                    toolCallId = "legacy-call",
                    toolName = "legacy",
                    arguments = """{"password":"legacy-secret","query":"kept"}""",
                    approvalState = ToolApprovalState.Pending,
                    metadata = buildJsonObject { put("signature", "tool-call-signature") },
                ),
                UIMessagePart.ToolResult(
                    toolCallId = "legacy-result",
                    toolName = "legacy",
                    arguments = buildJsonObject {
                        put("access_token", "argument-secret")
                        put("query", "kept")
                    },
                    content = buildJsonObject {
                        put("refreshToken", "content-secret")
                        put("ok", true)
                    },
                    metadata = buildJsonObject { put("signature", "tool-result-signature") },
                ),
                UIMessagePart.Tool(
                    toolCallId = "call",
                    toolName = "fetch",
                    input = """{"query":"kept"}""",
                    approvalState = ToolApprovalState.Approved,
                    metadata = buildJsonObject { put("signature", "tool-signature") },
                ),
            ),
        )

        val result = PromptTraceSanitizer.sanitizeMessages(listOf(message)).single()
        val persisted = result.parts.joinToString()

        assertEquals(11, result.parts.size)
        assertTrue(result.parts[0] is PromptTracePart.Text)
        assertTrue(result.parts[1] is PromptTracePart.Attachment)
        assertTrue(result.parts[2] is PromptTracePart.Attachment)
        assertTrue(result.parts[3] is PromptTracePart.Attachment)
        assertTrue(result.parts[4] is PromptTracePart.Attachment)
        assertTrue(result.parts[5] is PromptTracePart.Reasoning)
        assertTrue(result.parts[6] is PromptTracePart.Text)
        assertEquals("[search]", (result.parts[7] as PromptTracePart.Text).text)
        assertTrue(result.parts[8] is PromptTracePart.Tool)
        assertTrue(result.parts[9] is PromptTracePart.Tool)
        assertTrue(result.parts[10] is PromptTracePart.Tool)
        assertFalse(persisted.contains("aGVsbG8="))
        assertFalse(persisted.contains("text-metadata-secret"))
        assertFalse(persisted.contains("opaque-signature"))
        assertFalse(persisted.contains("status-metadata-secret"))
        assertFalse(persisted.contains("tool-call-signature"))
        assertFalse(persisted.contains("tool-result-signature"))
        assertFalse(persisted.contains("tool-signature"))
    }

    @Test
    fun `credential tool keys bodies data uris and network secrets are excluded`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "fetch",
                    input = """
                        {
                          "apiKey":"api-value",
                          "headers":{"X-Api-Key":"header-value"},
                          "nested":{"client_secret":"client-value","query":"weather"},
                          "customBody":{"password":"body-value"},
                          "url":"https://example.com/data?token=url-value#frag",
                          "blob":"data:application/octet-stream;base64,aGVsbG8=",
                          "plain":"data:text/plain,plain-data-secret"
                        }
                    """.trimIndent(),
                    output = listOf(
                        UIMessagePart.Text(
                            "Authorization: Bearer bearer-value; cookie=session-value; ok=true",
                        ),
                        UIMessagePart.Image("https://example.com/output.png?signature=output-value#frag"),
                        UIMessagePart.Document(
                            "data:application/pdf;base64,aGVsbG8=",
                            "output.pdf",
                            "application/pdf",
                        ),
                    ),
                    approvalState = ToolApprovalState.Approved,
                ),
            ),
        )

        val tool = PromptTraceSanitizer.sanitizeMessages(listOf(message))
            .single()
            .parts
            .single() as PromptTracePart.Tool
        val persisted = buildString {
            append(tool.input.preview)
            append(tool.outputText?.preview)
            append(tool.outputAttachments)
        }

        listOf(
            "api-value",
            "header-value",
            "client-value",
            "body-value",
            "url-value",
            "aGVsbG8=",
            "plain-data-secret",
            "bearer-value",
            "session-value",
            "output-value",
        ).forEach { excluded ->
            assertFalse("Persisted trace leaked $excluded: $persisted", persisted.contains(excluded))
        }
        assertTrue(persisted.contains("[redacted]"))
        assertTrue(persisted.contains("[stripped"))
        assertTrue(persisted.contains("weather"))
        assertTrue("Expected safe output in $persisted", persisted.contains("ok=true"))
        assertEquals("https://example.com/output.png", tool.outputAttachments[0].uri)
        assertNull(tool.outputAttachments[1].uri)
        assertEquals(5L, tool.outputAttachments[1].byteLength)
    }

    @Test
    fun `wrapped base64 data uri is fully consumed in diagnostic and error text`() {
        val wrapped = "data:image/png;base64,aGVs\r\nbG8="
        val tool = PromptTraceSanitizer.sanitizeMessages(
            listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolCallId = "wrapped",
                            toolName = "inspect",
                            input = "before $wrapped after",
                        ),
                    ),
                ),
            ),
        ).single().parts.single() as PromptTracePart.Tool
        val error = PromptTraceSanitizer.sanitizeError(
            IllegalStateException("failed $wrapped tail"),
        )

        assertTrue(tool.input.preview.contains("[stripped bytes=5"))
        assertTrue(error.contains("[stripped bytes=5"))
        listOf("aGVs", "bG8=").forEach { fragment ->
            assertFalse(tool.input.preview.contains(fragment))
            assertFalse(error.contains(fragment))
        }
        assertTrue(tool.input.preview.endsWith(" after"))
        assertTrue(error.endsWith(" tail"))
    }

    @Test
    fun `loose credentials balanced custom body and network user info are excluded`() {
        val input = """
            Authorization: Basic dXNlcjpwYXNz
            Cookie: session=cookie-one; csrf=cookie-two; prefs=cookie-three
            tenantCustomBody={"safe":"body-safe","nested":{"secretKey":"body-secret"}}
            secretKey=loose-secret
            accessKeyId=loose-access
            url=https://net-user:net-pass@example.com/path?token=url-secret#frag
            next=visible
        """.trimIndent()
        val tool = PromptTraceSanitizer.sanitizeMessages(
            listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolCallId = "credentials",
                            toolName = "fetch",
                            input = input,
                        ),
                        UIMessagePart.Image(
                            "https://image-user:image-pass@example.com/a.png?token=image-secret#frag",
                        ),
                    ),
                ),
            ),
        ).single()
        val diagnostic = (tool.parts[0] as PromptTracePart.Tool).input.preview
        val attachment = (tool.parts[1] as PromptTracePart.Attachment).value

        listOf(
            "dXNlcjpwYXNz",
            "cookie-one",
            "cookie-two",
            "cookie-three",
            "body-safe",
            "body-secret",
            "loose-secret",
            "loose-access",
            "net-user",
            "net-pass",
            "url-secret",
            "image-user",
            "image-pass",
            "image-secret",
        ).forEach { excluded ->
            assertFalse("Persisted trace leaked $excluded: $diagnostic / $attachment", diagnostic.contains(excluded))
            assertFalse("Attachment leaked $excluded: $attachment", attachment.toString().contains(excluded))
        }
        assertTrue(diagnostic.contains("Authorization=[redacted]"))
        assertTrue(diagnostic.contains("Cookie=[redacted]"))
        assertTrue(diagnostic.contains("tenantCustomBody=[redacted]"))
        assertTrue(diagnostic.contains("secretKey=[redacted]"))
        assertTrue(diagnostic.contains("accessKeyId=[redacted]"))
        assertTrue(diagnostic.contains("url=https://example.com/path"))
        assertTrue(diagnostic.contains("next=visible"))
        assertEquals("https://example.com/a.png", attachment.uri)
    }

    @Test
    fun `oversized data uri drops body without decoding metadata`() {
        val oversizedBody = "A".repeat(2 * 1024 * 1024)
        val oversizedUri = "data:application/octet-stream;base64,$oversizedBody"
        val message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Document(
                    url = oversizedUri,
                    fileName = "large.bin",
                    mime = "application/octet-stream",
                ),
                UIMessagePart.Tool(
                    toolCallId = "large",
                    toolName = "inspect",
                    input = "payload=$oversizedUri tail=kept",
                ),
            ),
        )

        val parts = PromptTraceSanitizer.sanitizeMessages(listOf(message)).single().parts
        val attachment = (parts[0] as PromptTracePart.Attachment).value
        val tool = parts[1] as PromptTracePart.Tool

        assertNull(attachment.uri)
        assertEquals("application/octet-stream", attachment.mimeType)
        assertNull(attachment.byteLength)
        assertNull(attachment.sha256)
        assertTrue(tool.input.preview.contains("[stripped]"))
        assertTrue(tool.input.preview.endsWith(" tail=kept"))
        assertFalse(tool.input.preview.contains("AAAA"))
    }

    @Test
    fun `tool summaries report sanitized length hash and truncation`() {
        val raw = """{"query":"${"x".repeat(5000)}","token":"secret"}"""
        val tool = PromptTraceSanitizer.sanitizeMessages(
            listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolCallId = "call",
                            toolName = "long",
                            input = raw,
                            approvalState = ToolApprovalState.Denied("not now"),
                        ),
                    ),
                ),
            ),
        ).single().parts.single() as PromptTracePart.Tool

        assertEquals("DENIED", tool.approvalState)
        assertEquals(4 * 1024, tool.input.preview.length)
        assertTrue(tool.input.originalLength > tool.input.preview.length)
        assertTrue(tool.input.truncated)
        assertEquals(64, tool.input.sha256.length)
        assertFalse(tool.input.preview.contains("secret"))
    }

    @Test
    fun `error text is short and excludes credentials data bodies and network query`() {
        val error = IllegalStateException(
                "Authorization: Bearer bearer-value; token=token-value; " +
                "refreshToken=refresh-value; " +
                """detail={"clientSecret":"embedded-json-value"}; """ +
                "url=https://example.com/failure?apiKey=url-value#frag " +
                "payload=data:text/plain,error-data-secret " +
                "tail=${"x".repeat(300)}",
        )

        val sanitized = PromptTraceSanitizer.sanitizeError(error)

        assertTrue(sanitized.length <= 240)
        assertTrue(sanitized.contains("[redacted]"))
        assertTrue("Expected stripped data URI in $sanitized", sanitized.contains("[stripped"))
        assertTrue(sanitized.contains("https://example.com/failure"))
        assertFalse(sanitized.contains("bearer-value"))
        assertFalse(sanitized.contains("token-value"))
        assertFalse(sanitized.contains("refresh-value"))
        assertFalse(sanitized.contains("embedded-json-value"))
        assertFalse(sanitized.contains("error-data-secret"))
        assertFalse(sanitized.contains("url-value"))
        assertFalse(sanitized.contains("?"))
        assertFalse(sanitized.contains("#frag"))
        assertEquals("IllegalArgumentException", PromptTraceSanitizer.sanitizeError(IllegalArgumentException()))
    }

    @Suppress("DEPRECATION")
    @Test
    fun `legacy tool result recursively redacts sensitive json values`() {
        val result = UIMessagePart.ToolResult(
            toolCallId = "result",
            toolName = "legacy",
            arguments = buildJsonObject {
                put("safe", "kept")
                put("credentials", JsonPrimitive("credential-value"))
            },
            content = buildJsonObject {
                put("private_key", "private-key-value")
                put("safe", "also-kept")
            },
        )

        val tool = PromptTraceSanitizer.sanitizeMessages(
            listOf(UIMessage(role = MessageRole.TOOL, parts = listOf(result))),
        ).single().parts.single() as PromptTracePart.Tool
        val persisted = tool.input.preview + tool.outputText?.preview.orEmpty()

        assertEquals("EXECUTED", tool.approvalState)
        assertTrue(persisted.contains("kept"))
        assertTrue(persisted.contains("also-kept"))
        assertFalse(persisted.contains("credential-value"))
        assertFalse(persisted.contains("private-key-value"))
    }
}
