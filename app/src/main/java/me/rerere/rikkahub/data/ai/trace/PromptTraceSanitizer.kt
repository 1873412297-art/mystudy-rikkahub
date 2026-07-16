package me.rerere.rikkahub.data.ai.trace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Base64

object PromptTraceSanitizer {
    private const val TOOL_PREVIEW_LIMIT = 4 * 1024
    private const val ERROR_SUMMARY_LIMIT = 240

    private val json = Json { ignoreUnknownKeys = true }
    private val sensitiveKeys = setOf(
        "auth",
        "authorization",
        "cookie",
        "cookies",
        "cookiejar",
        "password",
        "passwd",
        "secret",
        "token",
        "apikey",
        "xapikey",
        "accesstoken",
        "refreshtoken",
        "idtoken",
        "clientsecret",
        "privatekey",
        "credential",
        "credentials",
        "signature",
        "headers",
        "customheaders",
        "providerheaders",
        "requestheaders",
        "custombody",
        "providercustombody",
        "providerbody",
    )
    private val embeddedDataUri = Regex("""(?i)data:[^\s"'<>]+""")
    private val networkUrl = Regex("""(?i)https?://[^\s"'<>]+""")
    private val looseCredential = Regex(
        pattern = """
            (?ix)
            ["']?
            (
                auth(?:orization)? |
                cookies? |
                pass(?:word|wd) |
                credentials? |
                custom[-_]?body |
                (?:[a-z0-9]+[-_]?)?headers? |
                (?:[a-z0-9]+[-_]?)?
                (?:
                    api[-_]?key |
                    token |
                    secret |
                    private[-_]?key |
                    signature
                )
            )
            ["']?
            \s*[:=]\s*
            (?:
                "(?:\\.|[^"])*" |
                '(?:\\.|[^'])*' |
                Bearer\s+[^\s,;]+ |
                [^\s,;]+
            )
        """.trimIndent(),
    )
    private val standaloneBearer = Regex("""(?i)\bBearer\s+[^\s,;]+""")

    fun sanitizeMessages(messages: List<UIMessage>): List<PromptTraceMessage> {
        return messages.mapIndexed { index, message ->
            val parts = message.parts.map(::sanitizePart)
            val diagnosticText = parts.mapNotNull { part ->
                when (part) {
                    is PromptTracePart.Text -> part.text
                    is PromptTracePart.Reasoning -> part.text
                    is PromptTracePart.Tool -> buildString {
                        append(part.input.preview)
                        part.outputText?.let { append(it.preview) }
                    }
                    is PromptTracePart.Attachment -> null
                }
            }.joinToString("\n")
            PromptTraceMessage(
                id = message.id,
                index = index,
                role = message.role,
                memberId = message.memberId,
                name = message.name,
                parts = parts,
                characterCount = diagnosticText.length,
                approximateTokens = PromptTokenEstimator.estimate(diagnosticText),
            )
        }
    }

    fun sanitizeError(error: Throwable): String {
        return sanitizeDiagnosticText(error.message.orEmpty())
            .take(ERROR_SUMMARY_LIMIT)
            .ifBlank { error::class.java.simpleName }
    }

    @Suppress("DEPRECATION")
    private fun sanitizePart(part: UIMessagePart): PromptTracePart {
        return when (part) {
            is UIMessagePart.Text -> PromptTracePart.Text(part.text)
            is UIMessagePart.Reasoning -> PromptTracePart.Reasoning(part.reasoning)
            is UIMessagePart.Image -> PromptTracePart.Attachment(
                sanitizeAttachment(PromptTraceAttachmentKind.IMAGE, part.url, null, null),
            )
            is UIMessagePart.Video -> PromptTracePart.Attachment(
                sanitizeAttachment(PromptTraceAttachmentKind.VIDEO, part.url, null, null),
            )
            is UIMessagePart.Audio -> PromptTracePart.Attachment(
                sanitizeAttachment(PromptTraceAttachmentKind.AUDIO, part.url, null, null),
            )
            is UIMessagePart.Document -> PromptTracePart.Attachment(
                sanitizeAttachment(PromptTraceAttachmentKind.DOCUMENT, part.url, part.fileName, part.mime),
            )
            is UIMessagePart.Tool -> PromptTracePart.Tool(
                toolCallId = part.toolCallId,
                toolName = part.toolName,
                approvalState = approvalName(part.approvalState),
                input = summarizeText(sanitizeDiagnosticText(part.input)),
                outputText = part.output
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }
                    .takeIf(String::isNotEmpty)
                    ?.let(::sanitizeDiagnosticText)
                    ?.let(::summarizeText),
                outputAttachments = part.output.mapNotNull(::sanitizeOutputAttachment),
            )
            is UIMessagePart.ToolCall -> PromptTracePart.Tool(
                toolCallId = part.toolCallId,
                toolName = part.toolName,
                approvalState = approvalName(part.approvalState),
                input = summarizeText(sanitizeDiagnosticText(part.arguments)),
                outputText = null,
                outputAttachments = emptyList(),
            )
            is UIMessagePart.ToolResult -> PromptTracePart.Tool(
                toolCallId = part.toolCallId,
                toolName = part.toolName,
                approvalState = "EXECUTED",
                input = summarizeText(sanitizeDiagnosticText(part.arguments.toString())),
                outputText = summarizeText(sanitizeDiagnosticText(part.content.toString())),
                outputAttachments = emptyList(),
            )
            is UIMessagePart.StatusPlaceholder -> PromptTracePart.Text(
                sanitizeDiagnosticText(part.htmlContent),
            )
            is UIMessagePart.Search -> PromptTracePart.Text("[search]")
        }
    }

    private fun sanitizeOutputAttachment(part: UIMessagePart): PromptTraceAttachment? {
        return when (part) {
            is UIMessagePart.Image -> sanitizeAttachment(PromptTraceAttachmentKind.IMAGE, part.url, null, null)
            is UIMessagePart.Video -> sanitizeAttachment(PromptTraceAttachmentKind.VIDEO, part.url, null, null)
            is UIMessagePart.Audio -> sanitizeAttachment(PromptTraceAttachmentKind.AUDIO, part.url, null, null)
            is UIMessagePart.Document -> sanitizeAttachment(
                PromptTraceAttachmentKind.DOCUMENT,
                part.url,
                part.fileName,
                part.mime,
            )
            else -> null
        }
    }

    private fun sanitizeAttachment(
        kind: PromptTraceAttachmentKind,
        rawUrl: String,
        displayName: String?,
        declaredMime: String?,
    ): PromptTraceAttachment {
        if (rawUrl.startsWith("data:", ignoreCase = true)) {
            val data = parseDataUri(rawUrl)
            return PromptTraceAttachment(
                kind = kind,
                displayName = displayName,
                mimeType = data.mimeType ?: declaredMime,
                byteLength = data.bytes?.size?.toLong(),
                sha256 = data.bytes?.let(::sha256),
            )
        }
        val sanitizedUri = if (rawUrl.startsWith("http://", ignoreCase = true) ||
            rawUrl.startsWith("https://", ignoreCase = true)
        ) {
            stripNetworkQuery(rawUrl)
        } else {
            rawUrl
        }
        return PromptTraceAttachment(
            kind = kind,
            uri = sanitizedUri,
            displayName = displayName,
            mimeType = declaredMime,
        )
    }

    private fun sanitizeDiagnosticText(text: String): String {
        val structured = redactStructuredText(text)
        return redactLooseText(stripNetworkUrls(stripEmbeddedDataUris(structured)))
    }

    private fun redactStructuredText(text: String): String {
        val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
        return if (parsed == null) text else redactJson(parsed).toString()
    }

    private fun redactJson(element: JsonElement): JsonElement {
        return when (element) {
            is JsonObject -> JsonObject(
                element.mapValues { (key, value) ->
                    if (isSensitiveKey(key)) JsonPrimitive("[redacted]") else redactJson(value)
                },
            )
            is JsonArray -> JsonArray(element.map(::redactJson))
            else -> element
        }
    }

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase().filter(Char::isLetterOrDigit)
        return normalized in sensitiveKeys ||
            normalized.endsWith("authorization") ||
            normalized.endsWith("password") ||
            normalized.endsWith("secret") ||
            normalized.endsWith("token") ||
            normalized.endsWith("privatekey") ||
            normalized.endsWith("credential") ||
            normalized.endsWith("credentials") ||
            normalized.endsWith("signature") ||
            normalized.endsWith("headers")
    }

    private fun redactLooseText(text: String): String {
        val keyed = looseCredential.replace(text) { match ->
            "${match.groupValues[1]}=[redacted]"
        }
        return standaloneBearer.replace(keyed, "Bearer [redacted]")
    }

    private fun stripEmbeddedDataUris(text: String): String {
        return embeddedDataUri.replace(text) { match ->
            val data = parseDataUri(match.value)
            val prefix = buildString {
                append("data:")
                append(data.mimeType ?: "application/octet-stream")
                if (data.isBase64) append(";base64")
                append(',')
            }
            val details = data.bytes?.let { bytes ->
                "[stripped bytes=${bytes.size} sha256=${sha256(bytes)}]"
            } ?: "[stripped]"
            prefix + details
        }
    }

    private fun stripNetworkUrls(text: String): String {
        return networkUrl.replace(text) { match -> stripNetworkQuery(match.value) }
    }

    private fun stripNetworkQuery(url: String): String {
        return runCatching {
            val uri = URI(url)
            URI(uri.scheme, uri.authority, uri.path, null, null).toString()
        }.getOrElse {
            url.substringBefore('?').substringBefore('#')
        }
    }

    private fun parseDataUri(uri: String): ParsedDataUri {
        val header = uri.substringBefore(',')
        val body = uri.substringAfter(',', "")
        val mediaTypeAndParameters = header.drop(5)
        val mimeType = mediaTypeAndParameters.substringBefore(';').ifBlank { null }
        val isBase64 = mediaTypeAndParameters
            .split(';')
            .drop(1)
            .any { it.equals("base64", ignoreCase = true) }
        val bytes = runCatching {
            if (isBase64) {
                Base64.getMimeDecoder().decode(body)
            } else {
                decodePercentEncoded(body)
            }
        }.getOrNull()
        return ParsedDataUri(
            mimeType = mimeType,
            isBase64 = isBase64,
            bytes = bytes,
        )
    }

    private fun decodePercentEncoded(value: String): ByteArray {
        val output = ByteArrayOutputStream(value.length)
        var plainStart = 0
        var index = 0
        while (index < value.length) {
            if (value[index] == '%' && index + 2 < value.length) {
                val decoded = value.substring(index + 1, index + 3).toIntOrNull(16)
                if (decoded != null) {
                    if (plainStart < index) {
                        output.write(value.substring(plainStart, index).toByteArray())
                    }
                    output.write(decoded)
                    index += 3
                    plainStart = index
                    continue
                }
            }
            index += 1
        }
        if (plainStart < value.length) {
            output.write(value.substring(plainStart).toByteArray())
        }
        return output.toByteArray()
    }

    private fun summarizeText(text: String): PromptTraceTextSummary {
        return PromptTraceTextSummary(
            preview = text.take(TOOL_PREVIEW_LIMIT),
            originalLength = text.length,
            sha256 = sha256(text.toByteArray()),
            truncated = text.length > TOOL_PREVIEW_LIMIT,
        )
    }

    private fun approvalName(state: ToolApprovalState): String {
        return when (state) {
            ToolApprovalState.Auto -> "AUTO"
            ToolApprovalState.Pending -> "PENDING"
            ToolApprovalState.Approved -> "APPROVED"
            is ToolApprovalState.Denied -> "DENIED"
            is ToolApprovalState.Answered -> "ANSWERED"
        }
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private data class ParsedDataUri(
        val mimeType: String?,
        val isBase64: Boolean,
        val bytes: ByteArray?,
    )
}
