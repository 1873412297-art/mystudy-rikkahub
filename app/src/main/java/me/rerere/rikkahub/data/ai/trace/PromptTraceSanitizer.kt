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
    private const val MAX_DATA_URI_DECODE_CHARS = 1024 * 1024
    private const val MAX_STRUCTURED_PARSE_CHARS = 256 * 1024

    private val json = Json { ignoreUnknownKeys = true }
    private val sensitiveKeys = setOf(
        "auth",
        "authorization",
        "cookie",
        "cookies",
        "cookiejar",
        "setcookie",
        "setcookies",
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
        "secretkey",
        "accesskeyid",
    )
    private val networkUrl = Regex("""(?i)https?://[^\s"'<>]+""")
    private val looseKeyValue = Regex(
        """(?i)(?<![A-Za-z0-9_-])["']?([A-Za-z][A-Za-z0-9_-]*)["']?\s*[:=]\s*""",
    )
    private val standaloneAuthorization = Regex("""(?i)\b(?:Bearer|Basic)\s+[^\s,;]+""")

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
            is UIMessagePart.ServerTool -> PromptTracePart.Tool(
                toolCallId = part.toolCallId,
                toolName = part.toolName,
                approvalState = part.status.name,
                input = summarizeText(sanitizeDiagnosticText(part.input?.toString().orEmpty())),
                outputText = part.output?.toString()?.let(::sanitizeDiagnosticText)?.let(::summarizeText),
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
            val data = parseDataUri(rawUrl, 0, rawUrl.length)
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
        if (text.length > MAX_STRUCTURED_PARSE_CHARS) return text
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
            normalized.endsWith("headers") ||
            normalized.endsWith("custombody") ||
            normalized.endsWith("providerbody") ||
            normalized.endsWith("secretkey") ||
            normalized.endsWith("accesskeyid")
    }

    private fun redactLooseText(text: String): String {
        val result = StringBuilder(text.length.coerceAtMost(MAX_STRUCTURED_PARSE_CHARS))
        var copiedUntil = 0
        var searchFrom = 0
        while (searchFrom < text.length) {
            val match = looseKeyValue.find(text, searchFrom) ?: break
            val key = match.groupValues[1]
            if (!isSensitiveKey(key)) {
                searchFrom = match.range.last + 1
                continue
            }
            val valueStart = match.range.last + 1
            val valueEnd = sensitiveValueEnd(
                text = text,
                valueStart = valueStart,
                key = key,
                headerStyle = match.value.contains(':'),
            )
            result.append(text, copiedUntil, match.range.first)
            result.append(key).append("=[redacted]")
            copiedUntil = valueEnd
            searchFrom = valueEnd
        }
        result.append(text, copiedUntil, text.length)
        return standaloneAuthorization.replace(result.toString()) { match ->
            match.value.substringBefore(' ') + " [redacted]"
        }
    }

    private fun stripEmbeddedDataUris(text: String): String {
        val result = StringBuilder(text.length.coerceAtMost(MAX_STRUCTURED_PARSE_CHARS))
        var copiedUntil = 0
        var searchFrom = 0
        while (searchFrom < text.length) {
            val start = text.indexOf("data:", searchFrom, ignoreCase = true)
            if (start < 0) break
            val end = findDataUriEnd(text, start)
            if (end <= start + 5) {
                searchFrom = start + 5
                continue
            }
            val data = parseDataUri(text, start, end)
            result.append(text, copiedUntil, start)
            val prefix = buildString {
                append("data:")
                append(data.mimeType ?: "application/octet-stream")
                if (data.isBase64) append(";base64")
                append(',')
            }
            val details = data.bytes?.let { bytes ->
                "[stripped bytes=${bytes.size} sha256=${sha256(bytes)}]"
            } ?: "[stripped]"
            result.append(prefix).append(details)
            copiedUntil = end
            searchFrom = end
        }
        result.append(text, copiedUntil, text.length)
        return result.toString()
    }

    private fun stripNetworkUrls(text: String): String {
        return networkUrl.replace(text) { match -> stripNetworkQuery(match.value) }
    }

    private fun stripNetworkQuery(url: String): String {
        return runCatching {
            val uri = URI(url)
            val authority = requireNotNull(uri.rawAuthority).substringAfterLast('@')
            "${uri.scheme}://$authority${uri.rawPath.orEmpty()}"
        }.getOrElse {
            val withoutQuery = url.substringBefore('?').substringBefore('#')
            val schemeEnd = withoutQuery.indexOf("://")
            if (schemeEnd < 0) {
                withoutQuery
            } else {
                val authorityStart = schemeEnd + 3
                val pathStart = withoutQuery.indexOf('/', authorityStart).let {
                    if (it < 0) withoutQuery.length else it
                }
                withoutQuery.substring(0, authorityStart) +
                    withoutQuery.substring(authorityStart, pathStart).substringAfterLast('@') +
                    withoutQuery.substring(pathStart)
            }
        }
    }

    private fun parseDataUri(source: String, start: Int, end: Int): ParsedDataUri {
        val comma = source.indexOf(',', startIndex = start + 5).takeIf { it in (start + 5) until end }
            ?: return ParsedDataUri(mimeType = null, isBase64 = false, bytes = null)
        val mediaTypeAndParameters = source.substring(start + 5, comma)
        val mimeType = mediaTypeAndParameters.substringBefore(';').ifBlank { null }
        val isBase64 = mediaTypeAndParameters
            .split(';')
            .drop(1)
            .any { it.equals("base64", ignoreCase = true) }
        val bodyStart = comma + 1
        val bodyLength = end - bodyStart
        val bytes = if (bodyLength > MAX_DATA_URI_DECODE_CHARS) {
            null
        } else {
            runCatching {
                if (isBase64) {
                    Base64.getMimeDecoder().decode(source.substring(bodyStart, end))
                } else {
                    decodePercentEncoded(source, bodyStart, end)
                }
            }.getOrNull()
        }
        return ParsedDataUri(
            mimeType = mimeType,
            isBase64 = isBase64,
            bytes = bytes,
        )
    }

    private fun findDataUriEnd(text: String, start: Int): Int {
        val comma = text.indexOf(',', startIndex = start + 5)
        if (comma < 0) return start
        val header = text.substring(start + 5, comma)
        val isBase64 = header
            .split(';')
            .drop(1)
            .any { it.equals("base64", ignoreCase = true) }
        var index = comma + 1
        var padded = false
        while (index < text.length) {
            val char = text[index]
            val accepted = if (isBase64) {
                when {
                    char == '=' -> true
                    char == '\r' || char == '\n' -> true
                    padded -> false
                    else -> char.isLetterOrDigit() || char == '+' || char == '/'
                }
            } else {
                !char.isWhitespace() && char !in charArrayOf('"', '\'', '<', '>')
            }
            if (!accepted) break
            if (char == '=') padded = true
            index += 1
        }
        return index
    }

    private fun decodePercentEncoded(source: String, start: Int, end: Int): ByteArray {
        val output = ByteArrayOutputStream(end - start)
        var plainStart = start
        var index = start
        while (index < end) {
            if (source[index] == '%' && index + 2 < end) {
                val decoded = source.substring(index + 1, index + 3).toIntOrNull(16)
                if (decoded != null) {
                    if (plainStart < index) {
                        output.write(source.substring(plainStart, index).toByteArray())
                    }
                    output.write(decoded)
                    index += 3
                    plainStart = index
                    continue
                }
            }
            index += 1
        }
        if (plainStart < end) {
            output.write(source.substring(plainStart, end).toByteArray())
        }
        return output.toByteArray()
    }

    private fun sensitiveValueEnd(
        text: String,
        valueStart: Int,
        key: String,
        headerStyle: Boolean,
    ): Int {
        if (valueStart >= text.length) return valueStart
        val normalized = key.lowercase().filter(Char::isLetterOrDigit)
        val first = text[valueStart]
        return when {
            first == '{' || first == '[' -> findBalancedEnd(text, valueStart, first)
            first == '"' || first == '\'' -> findQuotedEnd(text, valueStart, first)
            normalized.contains("authorization") || normalized == "auth" ->
                findDelimitedValueEnd(text, valueStart)
            normalized.contains("cookie") && headerStyle -> findLineEnd(text, valueStart)
            normalized.contains("cookie") -> findDelimitedValueEnd(text, valueStart)
            normalized.endsWith("headers") ||
                normalized.endsWith("custombody") ||
                normalized.endsWith("providerbody") -> findLineEnd(text, valueStart)
            else -> {
                var index = valueStart
                while (index < text.length && !text[index].isWhitespace() &&
                    text[index] !in charArrayOf(',', ';', '}')
                ) {
                    index += 1
                }
                index
            }
        }
    }

    private fun findDelimitedValueEnd(text: String, start: Int): Int {
        var index = start
        while (index < text.length && text[index] != '\r' && text[index] != '\n' &&
            text[index] != ',' && text[index] != ';'
        ) {
            index += 1
        }
        return index
    }

    private fun findBalancedEnd(text: String, start: Int, opening: Char): Int {
        val closing = if (opening == '{') '}' else ']'
        var depth = 0
        var quote: Char? = null
        var escaped = false
        var index = start
        while (index < text.length) {
            val char = text[index]
            if (quote != null) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == quote -> quote = null
                }
            } else {
                when (char) {
                    '"', '\'' -> quote = char
                    opening -> depth += 1
                    closing -> {
                        depth -= 1
                        if (depth == 0) return index + 1
                    }
                }
            }
            index += 1
        }
        return findLineEnd(text, start)
    }

    private fun findQuotedEnd(text: String, start: Int, quote: Char): Int {
        var escaped = false
        var index = start + 1
        while (index < text.length) {
            val char = text[index]
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == quote -> return index + 1
            }
            index += 1
        }
        return findLineEnd(text, start)
    }

    private fun findLineEnd(text: String, start: Int): Int {
        var index = start
        while (index < text.length && text[index] != '\r' && text[index] != '\n') {
            index += 1
        }
        return index
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
