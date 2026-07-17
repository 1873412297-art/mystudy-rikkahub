# Tavern Prompt Trace Console Phase A1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a read-only, branch-aware Tavern prompt diagnostics console that persists the exact semantic messages sent at every eligible provider call and explains their prompt sources and injection hits.

**Architecture:** `ChatService` decides Tavern eligibility and creates a lightweight `PromptTraceSeed`; `GenerationHandler` owns one `PromptTraceSession` per real provider invocation and passes it through `TransformerContext`. The session records source sections and structured injection provenance, sanitizes the final provider-bound `UIMessage` list, and writes lifecycle updates through an independent Room repository so tracing never changes generation content or control flow.

**Tech Stack:** Kotlin, Kotlin Serialization, Coroutines/Flow, Android Room 27→28 migration, Koin, Jetpack Compose Material 3, HugeIcons, JUnit 4, AndroidX Room testing, AndroidX Compose UI testing.

## Global Constraints

- Implement only Phase A1 persisted snapshots of requests that were actually sent; the Preview tab is an explicit A2 unavailable state and runs no transforms.
- Solo eligibility is exactly `activeAssistant.tavernCardJson != null`.
- Group eligibility is exactly “at least one enabled `GroupMember` resolves to a source assistant whose `tavernCardJson != null`”.
- `statusRenderJs`, generic HTML support, lorebook selection, and global runtime permissions do not make a conversation eligible.
- Persist one trace per actual `Provider.streamText()` or `Provider.generateText()` invocation, including every tool-loop provider step.
- Bind a trace to the exact generated `UIMessage.id`; keep `requestAnchorMessageId` as the fallback before a response exists.
- Retain the newest 20 provider-call traces per conversation, ordered by `created_at DESC, provider_step_index DESC`.
- Store final semantic text and order exactly, but exclude base64 bodies, binary bodies, credential-bearing metadata, provider headers, provider custom bodies, cookies, and raw HTTP/provider DTOs.
- Treat provider-returned `TokenUsage.promptTokens` as authoritative; label all local section/message counts with `约`.
- Trace persistence is observational and best-effort; non-cancellation trace failures are logged and never become chat-generation failures.
- Cancellation must still propagate through the normal generation path after a best-effort `CANCELLED` update.
- Store traces in an independent Room table with a conversation foreign-key cascade; do not add trace fields to `Conversation`, `MessageNode`, or `UIMessage`.
- Do not copy traces when forking conversations or exporting Tavern JSON/PNG cards.
- Keep existing Tavern Helper rendering/runtime, status rendering, group director, layered group context, provider implementations, and unrelated dirty-workspace changes intact.
- Use the verified top-bar icon import `me.rerere.hugeicons.stroke.Cards02`.
- Add base English strings and Simplified Chinese strings; other locales use the base fallback in A1.
- Use PowerShell/Windows Gradle commands in verification examples.

---

## File and Interface Map

### New domain files

- `app/src/main/java/me/rerere/rikkahub/data/ai/trace/PromptTraceModels.kt`
  - Versioned serializable payload, statuses, sections, hits, sanitized parts, runtime read result, seed, and source hint.
- `app/src/main/java/me/rerere/rikkahub/data/ai/trace/PromptTraceEligibility.kt`
  - Shared solo/group eligibility function used by chat UI and trace seed creation.
- `app/src/main/java/me/rerere/rikkahub/data/ai/trace/PromptTokenEstimator.kt`
  - Dependency-free deterministic approximate token estimator.
- `app/src/main/java/me/rerere/rikkahub/data/ai/trace/PromptTraceSanitizer.kt`
  - `UIMessage` to persisted diagnostic-message conversion and credential/binary stripping.
- `app/src/main/java/me/rerere/rikkahub/data/ai/trace/PromptTraceCopyFormatter.kt`
  - Plain-text full trace and single-message copy formatting.
- `app/src/main/java/me/rerere/rikkahub/data/ai/trace/PromptTraceSession.kt`
  - Per-provider-call collector, lifecycle state machine, best-effort store boundary, and session factory interface.

### New persistence files

- `app/src/main/java/me/rerere/rikkahub/data/db/entity/PromptTraceEntity.kt`
- `app/src/main/java/me/rerere/rikkahub/data/db/dao/PromptTraceDAO.kt`
- `app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_27_28.kt`
- `app/src/main/java/me/rerere/rikkahub/data/repository/PromptTraceRepository.kt`

### New console files

- `app/src/main/java/me/rerere/rikkahub/ui/pages/tavern/console/TavernPromptConsoleState.kt`
  - Pure selection and display-state mapping.
- `app/src/main/java/me/rerere/rikkahub/ui/pages/tavern/console/TavernPromptConsoleVM.kt`
  - Conversation load, trace observation, selected trace/tab, copy text, and clear action.
- `app/src/main/java/me/rerere/rikkahub/ui/pages/tavern/console/TavernPromptConsolePage.kt`
  - Route-level composable and full-screen scaffold.
- `app/src/main/java/me/rerere/rikkahub/ui/pages/tavern/console/TavernPromptOverview.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/tavern/console/TavernPromptHits.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/tavern/console/TavernPromptMessages.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/tavern/console/TavernPromptConsoleEntry.kt`

### Existing integration files

- `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt`
- `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/PromptInjectionTransformer.kt`
- `app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt`
- `app/src/main/java/me/rerere/rikkahub/service/group/GroupContextModels.kt`
- `app/src/main/java/me/rerere/rikkahub/service/group/GroupContextBuilder.kt`
- `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
- `app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt`
- `app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt`
- `app/src/main/java/me/rerere/rikkahub/di/RepositoryModule.kt`
- `app/src/main/java/me/rerere/rikkahub/di/ViewModelModule.kt`
- `app/src/main/java/me/rerere/rikkahub/di/AppModule.kt`
- `app/src/main/java/me/rerere/rikkahub/RouteActivity.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-zh/strings.xml`
- `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/28.json`

---

### Task 1: Define the trace contract, eligibility rule, and token estimator

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/ai/trace/PromptTraceModels.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/data/ai/trace/PromptTraceEligibility.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/data/ai/trace/PromptTokenEstimator.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/trace/PromptTraceEligibilityTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/trace/PromptTokenEstimatorTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/trace/PromptTraceModelsTest.kt`

**Interfaces:**
- Consumes: `Assistant`, `AssistantType`, `UIMessage`, `MessageRole`, `Uuid`.
- Produces:
  - `fun Assistant.isTavernPromptTraceEligible(allAssistants: List<Assistant>): Boolean`
  - `object PromptTokenEstimator { fun estimate(text: String): Int }`
  - `interface PromptTraceRecorder { fun recordInjectionHits(hits: List<PromptInjectionTrace>) }`
  - `PromptTraceSeed`, `PromptTraceSourceHint`, `PromptTracePayload`, `PromptTraceReadResult`, and all nested serializable trace types used by every later task.

- [ ] **Step 1: Write failing eligibility and estimator tests**

```kotlin
package me.rerere.rikkahub.data.ai.trace

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class PromptTraceEligibilityTest {
    @Test
    fun `solo requires a tavern card json value`() {
        assertTrue(Assistant(tavernCardJson = "{}").isTavernPromptTraceEligible(emptyList()))
        assertFalse(Assistant(tavernCardJson = null).isTavernPromptTraceEligible(emptyList()))
    }

    @Test
    fun `group is eligible when an enabled source member has a tavern card`() {
        val tavern = Assistant(tavernCardJson = "{}")
        val group = Assistant(
            assistantType = AssistantType.GROUP,
            groupMembers = listOf(GroupMember(assistantId = tavern.id, enabled = true)),
        )
        assertTrue(group.isTavernPromptTraceEligible(listOf(tavern)))
    }

    @Test
    fun `disabled tavern member does not make group eligible`() {
        val tavern = Assistant(tavernCardJson = "{}")
        val group = Assistant(
            assistantType = AssistantType.GROUP,
            groupMembers = listOf(GroupMember(assistantId = tavern.id, enabled = false)),
        )
        assertFalse(group.isTavernPromptTraceEligible(listOf(tavern)))
    }

    @Test
    fun `status renderer alone does not make assistant eligible`() {
        assertFalse(
            Assistant(statusRenderJs = "function renderStatus() {}")
                .isTavernPromptTraceEligible(emptyList())
        )
    }
}

class PromptTokenEstimatorTest {
    @Test
    fun `empty and whitespace text estimate zero`() {
        assertEquals(0, PromptTokenEstimator.estimate(""))
        assertEquals(0, PromptTokenEstimator.estimate(" \n\t"))
    }

    @Test
    fun `cjk kana and hangul count approximately one each`() {
        assertEquals(6, PromptTokenEstimator.estimate("中文かな한글"))
    }

    @Test
    fun `latin letters and digits use four code point buckets`() {
        assertEquals(1, PromptTokenEstimator.estimate("test"))
        assertEquals(2, PromptTokenEstimator.estimate("test1234"))
    }

    @Test
    fun `punctuation contributes conservatively`() {
        assertEquals(5, PromptTokenEstimator.estimate("Hi, 世界!"))
    }
}
```

- [ ] **Step 2: Run the tests and confirm the contract is absent**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*PromptTraceEligibilityTest" --tests "*PromptTokenEstimatorTest" --console=plain
```

Expected: FAIL with unresolved references for `isTavernPromptTraceEligible` and `PromptTokenEstimator`.

- [ ] **Step 3: Add the shared eligibility helper**

```kotlin
package me.rerere.rikkahub.data.ai.trace

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType

fun Assistant.isTavernPromptTraceEligible(allAssistants: List<Assistant>): Boolean {
    return when (assistantType) {
        AssistantType.SOLO -> tavernCardJson != null
        AssistantType.GROUP -> groupMembers
            .asSequence()
            .filter { it.enabled }
            .mapNotNull { member -> allAssistants.find { it.id == member.assistantId } }
            .any { source -> source.tavernCardJson != null }
    }
}
```

- [ ] **Step 4: Add the deterministic estimator**

```kotlin
package me.rerere.rikkahub.data.ai.trace

import kotlin.math.ceil

object PromptTokenEstimator {
    fun estimate(text: String): Int {
        var tokens = 0
        var latinRun = 0

        fun flushLatin() {
            if (latinRun > 0) {
                tokens += ceil(latinRun / 4.0).toInt()
                latinRun = 0
            }
        }

        text.codePoints().forEach { codePoint ->
            when {
                Character.isWhitespace(codePoint) -> flushLatin()
                isCjkKanaOrHangul(codePoint) -> {
                    flushLatin()
                    tokens += 1
                }
                Character.isLetterOrDigit(codePoint) -> latinRun += 1
                else -> {
                    flushLatin()
                    tokens += 1
                }
            }
        }
        flushLatin()
        return tokens
    }

    private fun isCjkKanaOrHangul(codePoint: Int): Boolean {
        return codePoint in 0x3400..0x4DBF ||
            codePoint in 0x4E00..0x9FFF ||
            codePoint in 0xF900..0xFAFF ||
            codePoint in 0x3040..0x30FF ||
            codePoint in 0x31F0..0x31FF ||
            codePoint in 0x1100..0x11FF ||
            codePoint in 0x3130..0x318F ||
            codePoint in 0xAC00..0xD7AF
    }
}
```

- [ ] **Step 5: Add the versioned domain model**

Create the following public contract in `PromptTraceModels.kt`; later tasks must use these names without aliases:

```kotlin
package me.rerere.rikkahub.data.ai.trace

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import kotlin.uuid.Uuid

@Serializable
enum class PromptTraceStatus { PREPARED, STREAMING, COMPLETED, CANCELLED, FAILED }

@Serializable
enum class PromptTraceSectionKind {
    ASSISTANT_OR_CARD_SYSTEM,
    CONVERSATION_SYSTEM_OVERRIDE,
    MEMORY,
    TOOL_PROMPT,
    GROUP_LAYERED_CONTEXT,
    MODE_INJECTION,
    LOREBOOK_INJECTION,
    HISTORY_MESSAGE,
    CURRENT_USER_MESSAGE,
    OTHER_TRANSFORMED_CONTENT,
}

@Serializable
enum class PromptInjectionSourceType { MODE, LOREBOOK }

@Serializable
enum class PromptInjectionMatchType { CONSTANT, KEYWORD, REGEX }

@Serializable
enum class PromptTraceAttachmentKind { IMAGE, VIDEO, AUDIO, DOCUMENT }

@Serializable
data class PromptTraceSourceHint(
    val messageId: Uuid,
    val kind: PromptTraceSectionKind,
    val label: String,
)

data class PromptTraceSeed(
    val conversationId: Uuid,
    val requestAnchorMessageId: Uuid?,
    val assistantId: Uuid,
    val modelId: Uuid,
    val isGroup: Boolean,
    val speakerMemberId: Uuid? = null,
    val speakerName: String? = null,
    val sourceHints: List<PromptTraceSourceHint> = emptyList(),
)

@Serializable
data class PromptTraceMetadata(
    val conversationId: Uuid,
    val assistantId: Uuid,
    val modelId: Uuid,
    val isGroup: Boolean,
    val speakerMemberId: Uuid? = null,
    val speakerName: String? = null,
    val providerName: String? = null,
    val providerStepIndex: Int,
    val requestAnchorMessageId: Uuid? = null,
    val responseMessageId: Uuid? = null,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long? = null,
    val status: PromptTraceStatus = PromptTraceStatus.PREPARED,
    val actualPromptTokens: Int? = null,
    val finalMessageCount: Int = 0,
)

@Serializable
data class PromptTraceSection(
    val kind: PromptTraceSectionKind,
    val label: String,
    val text: String,
    val active: Boolean = true,
    val characterCount: Int = text.length,
    val approximateTokens: Int = PromptTokenEstimator.estimate(text),
    val sourceMessageId: Uuid? = null,
    val targetMessageId: Uuid? = null,
    val targetMessageIndex: Int? = null,
)

@Serializable
data class PromptInjectionMatch(
    val type: PromptInjectionMatchType,
    val matchedTerms: List<String>,
    val scanDepth: Int,
    val scannedMessageIds: List<Uuid>,
    val caseSensitive: Boolean,
    val regexEnabled: Boolean,
)

@Serializable
data class PromptInjectionTrace(
    val injectionId: Uuid,
    val injectionName: String,
    val sourceType: PromptInjectionSourceType,
    val lorebookId: Uuid? = null,
    val lorebookName: String? = null,
    val match: PromptInjectionMatch? = null,
    val position: String,
    val role: MessageRole,
    val priority: Int,
    val injectDepth: Int,
    val content: String,
    val approximateTokens: Int = PromptTokenEstimator.estimate(content),
    val targetMessageId: Uuid? = null,
    val targetMessageIndex: Int? = null,
)

@Serializable
data class PromptTraceTextSummary(
    val preview: String,
    val originalLength: Int,
    val sha256: String,
    val truncated: Boolean,
)

@Serializable
data class PromptTraceAttachment(
    val kind: PromptTraceAttachmentKind,
    val uri: String? = null,
    val displayName: String? = null,
    val mimeType: String? = null,
    val byteLength: Long? = null,
    val sha256: String? = null,
)

@Serializable
sealed class PromptTracePart {
    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
        val approximateTokens: Int = PromptTokenEstimator.estimate(text),
    ) : PromptTracePart()

    @Serializable
    @SerialName("reasoning")
    data class Reasoning(
        val text: String,
        val approximateTokens: Int = PromptTokenEstimator.estimate(text),
    ) : PromptTracePart()

    @Serializable
    @SerialName("attachment")
    data class Attachment(val value: PromptTraceAttachment) : PromptTracePart()

    @Serializable
    @SerialName("tool")
    data class Tool(
        val toolCallId: String,
        val toolName: String,
        val approvalState: String,
        val input: PromptTraceTextSummary,
        val outputText: PromptTraceTextSummary?,
        val outputAttachments: List<PromptTraceAttachment>,
    ) : PromptTracePart()
}

@Serializable
data class PromptTraceMessage(
    val id: Uuid,
    val index: Int,
    val role: MessageRole,
    val memberId: Uuid? = null,
    val name: String? = null,
    val parts: List<PromptTracePart>,
    val characterCount: Int,
    val approximateTokens: Int,
)

@Serializable
data class PromptTracePayload(
    val schemaVersion: Int = 1,
    val metadata: PromptTraceMetadata,
    val sections: List<PromptTraceSection>,
    val injectionHits: List<PromptInjectionTrace>,
    val finalMessages: List<PromptTraceMessage>,
)

data class PromptTraceRecord(
    val traceId: Uuid,
    val payload: PromptTracePayload,
    val errorSummary: String? = null,
)

interface PromptTraceRecorder {
    fun recordInjectionHits(hits: List<PromptInjectionTrace>)
}

sealed interface PromptTraceReadResult {
    val traceId: Uuid
    val createdAtEpochMs: Long
    val responseMessageId: Uuid?

    data class Available(val record: PromptTraceRecord) : PromptTraceReadResult {
        override val traceId: Uuid = record.traceId
        override val createdAtEpochMs: Long = record.payload.metadata.startedAtEpochMs
        override val responseMessageId: Uuid? = record.payload.metadata.responseMessageId
    }

    data class Unavailable(
        override val traceId: Uuid,
        override val createdAtEpochMs: Long,
        override val responseMessageId: Uuid?,
        val status: PromptTraceStatus,
        val errorSummary: String?,
    ) : PromptTraceReadResult
}
```

- [ ] **Step 6: Add a serialization compatibility test**

```kotlin
package me.rerere.rikkahub.data.ai.trace

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class PromptTraceModelsTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `payload round trip and unknown fields remain compatible`() {
        val metadata = PromptTraceMetadata(
            conversationId = Uuid.random(),
            assistantId = Uuid.random(),
            modelId = Uuid.random(),
            isGroup = false,
            providerStepIndex = 0,
            startedAtEpochMs = 123L,
        )
        val payload = PromptTracePayload(metadata = metadata, sections = emptyList(), injectionHits = emptyList(), finalMessages = emptyList())
        val encoded = json.encodeToString(PromptTracePayload.serializer(), payload)
        val withUnknown = encoded.dropLast(1) + ",\"future_field\":true}"

        assertEquals(payload, json.decodeFromString(PromptTracePayload.serializer(), withUnknown))
    }
}
```

- [ ] **Step 7: Run focused tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*PromptTraceEligibilityTest" --tests "*PromptTokenEstimatorTest" --tests "*PromptTraceModelsTest" --console=plain
```

Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git add app/src/main/java/me/rerere/rikkahub/data/ai/trace app/src/test/java/me/rerere/rikkahub/data/ai/trace
git commit -m "feat: define tavern prompt trace contract"
```

---

### Task 2: Sanitize provider-bound semantic messages and format readable copies

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/ai/trace/PromptTraceSanitizer.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/data/ai/trace/PromptTraceCopyFormatter.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/trace/PromptTraceSanitizerTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/trace/PromptTraceCopyFormatterTest.kt`

**Interfaces:**
- Consumes: Task 1 trace models, `UIMessage`, all current `UIMessagePart` variants.
- Produces:
  - `object PromptTraceSanitizer`
  - `fun sanitizeMessages(messages: List<UIMessage>): List<PromptTraceMessage>`
  - `fun sanitizeError(error: Throwable): String`
  - `object PromptTraceCopyFormatter`
  - `fun format(record: PromptTraceRecord): String`
  - `fun formatMessage(message: PromptTraceMessage): String`

- [ ] **Step 1: Write failing sanitizer tests**

```kotlin
package me.rerere.rikkahub.data.ai.trace

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

class PromptTraceSanitizerTest {
    @Test
    fun `ordinary text and semantic order are preserved`() {
        val source = listOf(
            UIMessage.system("system"),
            UIMessage.user("用户文本"),
            UIMessage.assistant("assistant"),
        )
        val result = PromptTraceSanitizer.sanitizeMessages(source)

        assertEquals(listOf(MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT), result.map { it.role })
        assertEquals("用户文本", (result[1].parts.single() as PromptTracePart.Text).text)
    }

    @Test
    fun `data image stores metadata without base64 body`() {
        val encoded = "aGVsbG8="
        val message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Image("data:image/png;base64,$encoded")),
        )
        val attachment = (PromptTraceSanitizer.sanitizeMessages(listOf(message)).single().parts.single() as PromptTracePart.Attachment).value

        assertNull(attachment.uri)
        assertEquals("image/png", attachment.mimeType)
        assertEquals(5L, attachment.byteLength)
        assertFalse(attachment.sha256.isNullOrBlank())
    }

    @Test
    fun `network query and fragment are stripped`() {
        val message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Image("https://example.com/a.png?token=secret#frag")),
        )
        val attachment = (PromptTraceSanitizer.sanitizeMessages(listOf(message)).single().parts.single() as PromptTracePart.Attachment).value

        assertEquals("https://example.com/a.png", attachment.uri)
    }

    @Test
    fun `reasoning metadata and credential tool keys are excluded`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(
                    reasoning = "private chain summary",
                    metadata = buildJsonObject { put("signature", "opaque-signature") },
                ),
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "fetch",
                    input = """{"apiKey":"secret","query":"weather","url":"https://example.com/data?token=secret","blob":"data:application/octet-stream;base64,aGVsbG8="}""",
                    output = listOf(UIMessagePart.Text("""{"authorization":"Bearer secret","ok":true}""")),
                    approvalState = ToolApprovalState.Approved,
                ),
            ),
        )
        val parts = PromptTraceSanitizer.sanitizeMessages(listOf(message)).single().parts
        val tool = parts.filterIsInstance<PromptTracePart.Tool>().single()
        val persisted = tool.input.preview + tool.outputText?.preview.orEmpty()

        assertTrue(parts.filterIsInstance<PromptTracePart.Reasoning>().single().text.contains("private chain summary"))
        assertFalse(persisted.contains("secret"))
        assertTrue(persisted.contains("[redacted]"))
        assertFalse(persisted.contains("aGVsbG8="))
        assertTrue(persisted.contains("[stripped"))
        assertFalse(persisted.contains("?token="))
        assertFalse(persisted.contains("opaque-signature"))
    }
}
```

- [ ] **Step 2: Run the sanitizer tests and confirm failure**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*PromptTraceSanitizerTest" --console=plain
```

Expected: FAIL with unresolved reference `PromptTraceSanitizer`.

- [ ] **Step 3: Implement deterministic message sanitization**

Implement these rules in `PromptTraceSanitizer.kt`:

```kotlin
package me.rerere.rikkahub.data.ai.trace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.net.URI
import java.security.MessageDigest
import java.util.Base64

object PromptTraceSanitizer {
    private const val TOOL_PREVIEW_LIMIT = 4 * 1024
    private val json = Json { ignoreUnknownKeys = true }
    private val secretKey = Regex(
        pattern = """(?i)^(api[-_]?key|authorization|cookie|password|secret|token|access[-_]?token|refresh[-_]?token)$"""
    )
    private val embeddedDataUri = Regex(
        pattern = """data:([^;,\s]+);base64,([A-Za-z0-9+/=\r\n]+)"""
    )
    private val networkUrl = Regex("""https?://[^\s"'<>]+""")

    fun sanitizeMessages(messages: List<UIMessage>): List<PromptTraceMessage> {
        return messages.mapIndexed { index, message ->
            val parts = message.parts.map { sanitizePart(it) }
            val text = parts.joinToString("\n") { part ->
                when (part) {
                    is PromptTracePart.Text -> part.text
                    is PromptTracePart.Reasoning -> part.text
                    is PromptTracePart.Tool -> buildString {
                        append(part.input.preview)
                        part.outputText?.let { append(it.preview) }
                    }
                    is PromptTracePart.Attachment -> ""
                }
            }
            PromptTraceMessage(
                id = message.id,
                index = index,
                role = message.role,
                memberId = message.memberId,
                name = message.name,
                parts = parts,
                characterCount = text.length,
                approximateTokens = PromptTokenEstimator.estimate(text),
            )
        }
    }

    fun sanitizeError(error: Throwable): String {
        return redactLooseText(error.message.orEmpty())
            .replace(Regex("""https?://\S+""")) { stripNetworkQuery(it.value) }
            .take(240)
            .ifBlank { error::class.java.simpleName }
    }

    private fun sanitizePart(part: UIMessagePart): PromptTracePart {
        return when (part) {
            is UIMessagePart.Text -> PromptTracePart.Text(part.text)
            is UIMessagePart.Reasoning -> PromptTracePart.Reasoning(part.reasoning)
            is UIMessagePart.Image -> PromptTracePart.Attachment(
                sanitizeAttachment(PromptTraceAttachmentKind.IMAGE, part.url, null, null)
            )
            is UIMessagePart.Video -> PromptTracePart.Attachment(
                sanitizeAttachment(PromptTraceAttachmentKind.VIDEO, part.url, null, null)
            )
            is UIMessagePart.Audio -> PromptTracePart.Attachment(
                sanitizeAttachment(PromptTraceAttachmentKind.AUDIO, part.url, null, null)
            )
            is UIMessagePart.Document -> PromptTracePart.Attachment(
                sanitizeAttachment(PromptTraceAttachmentKind.DOCUMENT, part.url, part.fileName, part.mime)
            )
            is UIMessagePart.Tool -> PromptTracePart.Tool(
                toolCallId = part.toolCallId,
                toolName = part.toolName,
                approvalState = approvalName(part.approvalState),
                input = summarizeText(sanitizeDiagnosticText(part.input)),
                outputText = part.output
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }
                    .takeIf { it.isNotEmpty() }
                    ?.let(::sanitizeDiagnosticText)
                    ?.let(::summarizeText),
                outputAttachments = part.output.mapNotNull { output ->
                    when (output) {
                        is UIMessagePart.Image -> sanitizeAttachment(PromptTraceAttachmentKind.IMAGE, output.url, null, null)
                        is UIMessagePart.Video -> sanitizeAttachment(PromptTraceAttachmentKind.VIDEO, output.url, null, null)
                        is UIMessagePart.Audio -> sanitizeAttachment(PromptTraceAttachmentKind.AUDIO, output.url, null, null)
                        is UIMessagePart.Document -> sanitizeAttachment(
                            PromptTraceAttachmentKind.DOCUMENT,
                            output.url,
                            output.fileName,
                            output.mime,
                        )
                        else -> null
                    }
                },
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
                stripNetworkUrls(stripEmbeddedDataUris(part.htmlContent))
            )
            is UIMessagePart.Search -> PromptTracePart.Text("[search]")
        }
    }

    private fun sanitizeAttachment(
        kind: PromptTraceAttachmentKind,
        rawUrl: String,
        displayName: String?,
        declaredMime: String?,
    ): PromptTraceAttachment {
        if (rawUrl.startsWith("data:")) {
            val header = rawUrl.substringBefore(',')
            val body = rawUrl.substringAfter(',', "")
            val mime = header.removePrefix("data:").substringBefore(';').ifBlank { declaredMime }
            val bytes = runCatching {
                if (header.contains(";base64")) Base64.getDecoder().decode(body) else body.toByteArray()
            }.getOrNull()
            return PromptTraceAttachment(
                kind = kind,
                displayName = displayName,
                mimeType = mime,
                byteLength = bytes?.size?.toLong(),
                sha256 = bytes?.let(::sha256),
            )
        }
        val sanitizedUri = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
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

    private fun stripNetworkQuery(url: String): String {
        return runCatching {
            val uri = URI(url)
            URI(uri.scheme, uri.authority, uri.path, null, null).toString()
        }.getOrElse { url.substringBefore('?').substringBefore('#') }
    }

    private fun redactStructuredText(text: String): String {
        val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
        return if (parsed == null) redactLooseText(text) else redactJson(parsed).toString()
    }

    private fun sanitizeDiagnosticText(text: String): String {
        return stripNetworkUrls(stripEmbeddedDataUris(redactStructuredText(text)))
    }

    private fun stripEmbeddedDataUris(text: String): String {
        return embeddedDataUri.replace(text) { match ->
            val mime = match.groupValues[1]
            val bytes = runCatching {
                Base64.getMimeDecoder().decode(match.groupValues[2])
            }.getOrNull()
            val details = if (bytes == null) {
                "[stripped]"
            } else {
                "[stripped bytes=${bytes.size} sha256=${sha256(bytes)}]"
            }
            "data:$mime;base64,$details"
        }
    }

    private fun stripNetworkUrls(text: String): String {
        return networkUrl.replace(text) { match -> stripNetworkQuery(match.value) }
    }

    private fun redactJson(element: JsonElement): JsonElement {
        return when (element) {
            is JsonObject -> JsonObject(
                element.mapValues { (key, value) ->
                    if (secretKey.matches(key)) JsonPrimitive("[redacted]") else redactJson(value)
                }
            )
            is JsonArray -> JsonArray(element.map(::redactJson))
            else -> element
        }
    }

    private fun redactLooseText(text: String): String {
        return text.replace(
            Regex("""(?i)(api[-_]?key|authorization|cookie|password|secret|token)\s*[:=]\s*([^\s,;]+)"""),
            "$1=[redacted]",
        )
    }

    private fun summarizeText(text: String): PromptTraceTextSummary {
        return PromptTraceTextSummary(
            preview = text.take(TOOL_PREVIEW_LIMIT),
            originalLength = text.length,
            sha256 = sha256(text.toByteArray()),
            truncated = text.length > TOOL_PREVIEW_LIMIT,
        )
    }

    private fun approvalName(state: ToolApprovalState): String = when (state) {
        ToolApprovalState.Auto -> "AUTO"
        ToolApprovalState.Pending -> "PENDING"
        ToolApprovalState.Approved -> "APPROVED"
        is ToolApprovalState.Denied -> "DENIED"
        is ToolApprovalState.Answered -> "ANSWERED"
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
```

- [ ] **Step 4: Write failing copy-format tests**

```kotlin
package me.rerere.rikkahub.data.ai.trace

import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class PromptTraceCopyFormatterTest {
    @Test
    fun `full copy contains headings and sanitized final messages`() {
        val metadata = PromptTraceMetadata(
            conversationId = Uuid.random(),
            assistantId = Uuid.random(),
            modelId = Uuid.random(),
            isGroup = false,
            providerStepIndex = 0,
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
        val record = PromptTraceRecord(
            traceId = Uuid.random(),
            payload = PromptTracePayload(metadata = metadata, sections = emptyList(), injectionHits = emptyList(), finalMessages = listOf(message)),
        )

        val copied = PromptTraceCopyFormatter.format(record)
        assertTrue(copied.contains("Tavern Prompt Trace"))
        assertTrue(copied.contains("Actual prompt tokens: 42"))
        assertTrue(copied.contains("1. SYSTEM"))
        assertTrue(copied.contains("system text"))
        assertFalse(copied.contains("Authorization"))
    }
}
```

- [ ] **Step 5: Implement readable copy formatting**

```kotlin
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
                    if (hit.match?.matchedTerms?.isNotEmpty() == true) {
                        appendLine("  matched: ${hit.match.matchedTerms.joinToString(", ")}")
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
                        val value = part.value
                        appendLine(
                            "[${value.kind}] ${value.displayName ?: value.uri ?: value.mimeType ?: "binary reference"}"
                        )
                    }
                    is PromptTracePart.Tool -> {
                        appendLine("[Tool ${part.toolName} / ${part.approvalState}]")
                        appendLine("Input: ${part.input.preview}")
                        part.outputText?.let { appendLine("Output: ${it.preview}") }
                        part.outputAttachments.forEach { attachment ->
                            appendLine(
                                "[Tool output ${attachment.kind}] " +
                                    (attachment.displayName ?: attachment.uri ?: attachment.mimeType ?: "binary reference")
                            )
                        }
                    }
                }
            }
        }.trimEnd()
    }
}
```

- [ ] **Step 6: Run focused tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*PromptTraceSanitizerTest" --tests "*PromptTraceCopyFormatterTest" --console=plain
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/me/rerere/rikkahub/data/ai/trace/PromptTraceSanitizer.kt app/src/main/java/me/rerere/rikkahub/data/ai/trace/PromptTraceCopyFormatter.kt app/src/test/java/me/rerere/rikkahub/data/ai/trace
git commit -m "feat: sanitize and format prompt traces"
```

---

### Task 3: Refactor prompt injection selection to emit the exact applied provenance

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt:11-78`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/PromptInjectionTransformer.kt:19-116`
- Modify: `app/src/test/java/me/rerere/rikkahub/data/ai/transformers/PromptInjectionTransformerTest.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/data/ai/transformers/PromptInjectionTraceTest.kt`

**Interfaces:**
- Consumes:
  - `PromptTraceRecorder.recordInjectionHits(hits: List<PromptInjectionTrace>)` from Task 1.
  - Task 1 injection trace models.
- Produces:
  - `TransformerContext.promptTraceSession: PromptTraceRecorder?`
  - `transformMessagesWithTrace`: structured injection result plus transformed messages.
  - Existing `transformMessages`: unchanged message text/order behavior.

- [ ] **Step 1: Add failing structured-match tests**

```kotlin
package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.trace.PromptInjectionMatchType
import me.rerere.rikkahub.data.ai.trace.PromptInjectionSourceType
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class PromptInjectionTraceTest {
    @Test
    fun `keyword match drives output and trace from one evaluation`() {
        val lorebookId = Uuid.random()
        val entry = PromptInjection.RegexInjection(
            name = "门",
            keywords = listOf("密门", "不存在"),
            content = "门后藏着线索。",
            scanDepth = 2,
        )
        val result = transformMessagesWithTrace(
            messages = listOf(UIMessage.user("我去找密门")),
            assistant = Assistant(lorebookIds = setOf(lorebookId)),
            modeInjections = emptyList(),
            lorebooks = listOf(Lorebook(id = lorebookId, name = "宅邸", entries = listOf(entry))),
        )

        assertTrue(result.messages.first().toText().contains("门后藏着线索。"))
        val applied = result.applied.single()
        assertEquals(PromptInjectionSourceType.LOREBOOK, applied.collected.sourceType)
        assertEquals(PromptInjectionMatchType.KEYWORD, applied.collected.match?.type)
        assertEquals(listOf("密门"), applied.collected.match?.matchedTerms)
        assertEquals(lorebookId, applied.collected.lorebookId)
    }

    @Test
    fun `constant and invalid regex are represented without duplicate trigger evaluation`() {
        val lorebookId = Uuid.random()
        val constant = PromptInjection.RegexInjection(
            name = "常驻",
            constantActive = true,
            content = "Always active",
        )
        val invalid = PromptInjection.RegexInjection(
            name = "坏正则",
            keywords = listOf("["),
            useRegex = true,
            content = "Never active",
        )
        val result = transformMessagesWithTrace(
            messages = listOf(UIMessage.user("hello")),
            assistant = Assistant(lorebookIds = setOf(lorebookId)),
            modeInjections = emptyList(),
            lorebooks = listOf(Lorebook(id = lorebookId, entries = listOf(constant, invalid))),
        )

        assertEquals(1, result.applied.size)
        assertEquals(PromptInjectionMatchType.CONSTANT, result.applied.single().collected.match?.type)
        assertNull(result.applied.find { it.collected.injection.id == invalid.id })
    }
}
```

- [ ] **Step 2: Run the new test and confirm failure**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*PromptInjectionTraceTest" --console=plain
```

Expected: FAIL with unresolved reference `transformMessagesWithTrace`.

- [ ] **Step 3: Add optional trace session propagation to transformer context**

Add the import and property:

```kotlin
import me.rerere.rikkahub.data.ai.trace.PromptTraceRecorder

class TransformerContext(
    val context: Context,
    val model: Model,
    val assistant: Assistant,
    val settings: Settings,
    val conversationModeInjectionIds: Set<Uuid> = emptySet(),
    val conversationLorebookIds: Set<Uuid> = emptySet(),
    val processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    val workspaceCwd: String? = null,
    val conversationId: Uuid? = null,
    val promptTraceSession: PromptTraceRecorder? = null,
)
```

Extend `transforms` with the same defaulted argument and forward it into `TransformerContext`:

```kotlin
suspend fun List<UIMessage>.transforms(
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
    processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    workspaceCwd: String? = null,
    conversationId: Uuid? = null,
    promptTraceSession: PromptTraceRecorder? = null,
): List<UIMessage> {
    val ctx = TransformerContext(
        context = context,
        model = model,
        assistant = assistant,
        settings = settings,
        conversationModeInjectionIds = conversationModeInjectionIds,
        conversationLorebookIds = conversationLorebookIds,
        processingStatus = processingStatus,
        workspaceCwd = workspaceCwd,
        conversationId = conversationId,
        promptTraceSession = promptTraceSession,
    )
    return transformers.fold(this) { acc, transformer -> transformer.transform(ctx, acc) }
}
```

- [ ] **Step 4: Introduce structured collection and application types**

Add these internal types near the top of `PromptInjectionTransformer.kt`:

```kotlin
internal data class CollectedPromptInjection(
    val injection: PromptInjection,
    val sourceType: PromptInjectionSourceType,
    val lorebookId: Uuid? = null,
    val lorebookName: String? = null,
    val match: PromptInjectionMatch? = null,
)

internal data class AppliedPromptInjection(
    val collected: CollectedPromptInjection,
    val targetMessageId: Uuid?,
    val targetMessageIndex: Int?,
)

internal data class PromptInjectionTransformResult(
    val messages: List<UIMessage>,
    val applied: List<AppliedPromptInjection>,
)
```

Replace the current `transformMessages` body with a compatibility wrapper:

```kotlin
internal fun transformMessages(
    messages: List<UIMessage>,
    assistant: Assistant,
    modeInjections: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
): List<UIMessage> = transformMessagesWithTrace(
    messages = messages,
    assistant = assistant,
    modeInjections = modeInjections,
    lorebooks = lorebooks,
    conversationModeInjectionIds = conversationModeInjectionIds,
    conversationLorebookIds = conversationLorebookIds,
).messages
```

- [ ] **Step 5: Implement one-pass trigger collection**

Use the scanned message list both for the boolean decision and trace details:

```kotlin
internal fun collectInjectionMatches(
    messages: List<UIMessage>,
    assistant: Assistant,
    modeInjections: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
): List<CollectedPromptInjection> {
    val effectiveModeIds = if (assistant.allowConversationPromptInjection) {
        conversationModeInjectionIds
    } else {
        assistant.modeInjectionIds
    }
    val effectiveLorebookIds = if (assistant.allowConversationPromptInjection) {
        conversationLorebookIds
    } else {
        assistant.lorebookIds
    }
    val collected = mutableListOf<CollectedPromptInjection>()

    modeInjections
        .filter { it.enabled && it.id in effectiveModeIds }
        .forEach { injection ->
            collected += CollectedPromptInjection(
                injection = injection,
                sourceType = PromptInjectionSourceType.MODE,
            )
        }

    val nonSystemMessages = messages.filter { it.role != MessageRole.SYSTEM }
    lorebooks
        .filter { it.enabled && it.id in effectiveLorebookIds }
        .forEach { lorebook ->
            lorebook.entries.filter { it.enabled }.forEach { entry ->
                val scannedMessages = nonSystemMessages.takeLast(entry.scanDepth)
                val scannedContext = scannedMessages.joinToString("\n") { it.toText() }
                val matchedTerms = when {
                    entry.constantActive -> emptyList()
                    entry.useRegex -> entry.keywords.filter { keyword ->
                        runCatching {
                            val options = if (entry.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                            Regex(keyword, options).containsMatchIn(scannedContext)
                        }.getOrDefault(false)
                    }
                    else -> entry.keywords.filter { keyword ->
                        scannedContext.contains(keyword, ignoreCase = !entry.caseSensitive)
                    }
                }
                val triggered = entry.constantActive || matchedTerms.isNotEmpty()
                if (triggered) {
                    collected += CollectedPromptInjection(
                        injection = entry,
                        sourceType = PromptInjectionSourceType.LOREBOOK,
                        lorebookId = lorebook.id,
                        lorebookName = lorebook.name,
                        match = PromptInjectionMatch(
                            type = when {
                                entry.constantActive -> PromptInjectionMatchType.CONSTANT
                                entry.useRegex -> PromptInjectionMatchType.REGEX
                                else -> PromptInjectionMatchType.KEYWORD
                            },
                            matchedTerms = matchedTerms,
                            scanDepth = entry.scanDepth,
                            scannedMessageIds = scannedMessages.map { it.id },
                            caseSensitive = entry.caseSensitive,
                            regexEnabled = entry.useRegex,
                        ),
                    )
                }
            }
        }
    return collected
}
```

Keep the existing test-facing `collectInjections` signature:

```kotlin
internal fun collectInjections(
    messages: List<UIMessage>,
    assistant: Assistant,
    modeInjections: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
): List<PromptInjection> = collectInjectionMatches(
    messages,
    assistant,
    modeInjections,
    lorebooks,
    conversationModeInjectionIds,
    conversationLorebookIds,
).map { it.injection }
```

- [ ] **Step 6: Drive existing application logic from the structured result and record targets**

```kotlin
internal fun transformMessagesWithTrace(
    messages: List<UIMessage>,
    assistant: Assistant,
    modeInjections: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
): PromptInjectionTransformResult {
    val collected = collectInjectionMatches(
        messages = messages,
        assistant = assistant,
        modeInjections = modeInjections,
        lorebooks = lorebooks,
        conversationModeInjectionIds = conversationModeInjectionIds,
        conversationLorebookIds = conversationLorebookIds,
    )
    if (collected.isEmpty()) return PromptInjectionTransformResult(messages, emptyList())

    val transformed = applyInjections(
        messages = messages,
        byPosition = collected
            .sortedByDescending { it.injection.priority }
            .groupBy { it.injection.position }
            .mapValues { (_, values) -> values.map { it.injection } },
    )
    val applied = collected.map { item ->
        val candidates = transformed.withIndex().filter { (_, message) ->
            message.toText().contains(item.injection.content)
        }
        val target = candidates.singleOrNull()
        AppliedPromptInjection(
            collected = item,
            targetMessageId = target?.value?.id,
            targetMessageIndex = target?.index,
        )
    }
    return PromptInjectionTransformResult(transformed, applied)
}
```

Update the transformer entry point:

```kotlin
override suspend fun transform(
    ctx: TransformerContext,
    messages: List<UIMessage>,
): List<UIMessage> {
    val result = transformMessagesWithTrace(
        messages = messages,
        assistant = ctx.assistant,
        modeInjections = ctx.settings.modeInjections,
        lorebooks = ctx.settings.lorebooks,
        conversationModeInjectionIds = ctx.conversationModeInjectionIds,
        conversationLorebookIds = ctx.conversationLorebookIds,
    )
    ctx.promptTraceSession?.recordInjectionHits(
        result.applied.map { applied ->
            val item = applied.collected
            PromptInjectionTrace(
                injectionId = item.injection.id,
                injectionName = item.injection.name,
                sourceType = item.sourceType,
                lorebookId = item.lorebookId,
                lorebookName = item.lorebookName,
                match = item.match,
                position = item.injection.position.name,
                role = item.injection.role,
                priority = item.injection.priority,
                injectDepth = item.injection.injectDepth,
                content = item.injection.content,
                targetMessageId = applied.targetMessageId,
                targetMessageIndex = applied.targetMessageIndex,
            )
        }
    )
    return result.messages
}
```

- [ ] **Step 7: Run the complete injection suite**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*PromptInjectionTransformerTest" --tests "*PromptInjectionTraceTest" --console=plain
```

Expected: PASS, including all existing position, priority, scan-depth, invalid-regex, and safe-tool-boundary tests.

- [ ] **Step 8: Commit**

```powershell
git add app/src/main/java/me/rerere/rikkahub/data/ai/transformers app/src/test/java/me/rerere/rikkahub/data/ai/transformers
git commit -m "refactor: expose prompt injection trace provenance"
```

---

### Task 4: Add the Room trace table, DAO, schema 28, and migration coverage

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/db/entity/PromptTraceEntity.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/data/db/dao/PromptTraceDAO.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_27_28.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt:9-82`
- Modify: `app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt:23-151`
- Create: `app/src/androidTest/java/me/rerere/rikkahub/data/db/migrations/Migration_27_28_Test.kt`
- Create: `app/src/androidTest/java/me/rerere/rikkahub/data/db/dao/PromptTraceDAOTest.kt`
- Generate: `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/28.json`

**Interfaces:**
- Consumes: Task 1 status names as persisted strings.
- Produces:
  - `PromptTraceEntity`
  - `PromptTraceDAO.observeByConversation`
  - lifecycle update queries
  - retention, message cleanup, and clear queries
  - `AppDatabase.promptTraceDao()`

- [ ] **Step 1: Write a failing migration test**

Use the existing `Migration_26_27_Test` pattern and verify legacy rows survive:

```kotlin
package me.rerere.rikkahub.data.db.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class Migration_27_28_Test {
    private val databaseName = "migration-27-28"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate27To28_preservesConversationAndCreatesTraceIndices() {
        val conversationId = Uuid.random().toString()
        val nodeId = Uuid.random().toString()
        helper.createDatabase(databaseName, 27).apply {
            insert(
                "ConversationEntity",
                SQLiteDatabase.CONFLICT_NONE,
                ContentValues().apply {
                    put("id", conversationId)
                    put("assistant_id", Uuid.random().toString())
                    put("title", "Legacy")
                    put("nodes", "[]")
                    put("create_at", 1L)
                    put("update_at", 1L)
                    put("suggestions", "[]")
                    put("is_pinned", 0)
                },
            )
            insert(
                "message_node",
                SQLiteDatabase.CONFLICT_NONE,
                ContentValues().apply {
                    put("id", nodeId)
                    put("conversation_id", conversationId)
                    put("node_index", 0)
                    put("messages", "[]")
                    put("select_index", 0)
                },
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(databaseName, 28, true, Migration_27_28)
        db.query("SELECT COUNT(*) FROM ConversationEntity").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM message_node").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
        val indices = buildSet {
            db.query("PRAGMA index_list(`prompt_trace`)").use { cursor ->
                val name = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(name))
            }
        }
        assertTrue("index_prompt_trace_conversation_id" in indices)
        assertTrue("index_prompt_trace_response_message_id" in indices)
        assertTrue("index_prompt_trace_conversation_id_created_at" in indices)
        db.close()
    }
}
```

- [ ] **Step 2: Run migration test and confirm failure**

Run:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.data.db.migrations.Migration_27_28_Test" --console=plain
```

Expected: FAIL because database version 28 and `Migration_27_28` do not exist.

- [ ] **Step 3: Add the Room entity**

```kotlin
package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "prompt_trace",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("conversation_id"),
        Index("response_message_id"),
        Index(value = ["conversation_id", "created_at"]),
    ],
)
data class PromptTraceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("conversation_id") val conversationId: String,
    @ColumnInfo("request_anchor_message_id") val requestAnchorMessageId: String?,
    @ColumnInfo("response_message_id") val responseMessageId: String?,
    @ColumnInfo("assistant_id") val assistantId: String,
    @ColumnInfo("model_id") val modelId: String,
    @ColumnInfo("speaker_member_id") val speakerMemberId: String?,
    @ColumnInfo("provider_step_index") val providerStepIndex: Int,
    @ColumnInfo("status") val status: String,
    @ColumnInfo("actual_prompt_tokens") val actualPromptTokens: Int?,
    @ColumnInfo("error_summary") val errorSummary: String?,
    @ColumnInfo("payload_json") val payloadJson: String,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long,
)
```

- [ ] **Step 4: Add the DAO with atomic insert/retention and cleanup semantics**

```kotlin
package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.PromptTraceEntity

@Dao
interface PromptTraceDAO {
    @Query(
        """
        SELECT * FROM prompt_trace
        WHERE conversation_id = :conversationId
        ORDER BY created_at DESC, provider_step_index DESC
        """
    )
    fun observeByConversation(conversationId: String): Flow<List<PromptTraceEntity>>

    @Query("SELECT * FROM prompt_trace WHERE id = :traceId LIMIT 1")
    suspend fun getById(traceId: String): PromptTraceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PromptTraceEntity)

    @Query(
        """
        UPDATE prompt_trace
        SET response_message_id = :responseMessageId,
            status = 'STREAMING',
            actual_prompt_tokens = COALESCE(:actualPromptTokens, actual_prompt_tokens),
            updated_at = :updatedAt
        WHERE id = :traceId
        """
    )
    suspend fun markStreaming(
        traceId: String,
        responseMessageId: String,
        actualPromptTokens: Int?,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE prompt_trace
        SET actual_prompt_tokens = :actualPromptTokens,
            updated_at = :updatedAt
        WHERE id = :traceId
        """
    )
    suspend fun updateActualPromptTokens(traceId: String, actualPromptTokens: Int, updatedAt: Long)

    @Query(
        """
        UPDATE prompt_trace
        SET status = :status,
            error_summary = :errorSummary,
            updated_at = :updatedAt
        WHERE id = :traceId
        """
    )
    suspend fun markTerminal(traceId: String, status: String, errorSummary: String?, updatedAt: Long)

    @Query(
        """
        DELETE FROM prompt_trace
        WHERE conversation_id = :conversationId
          AND id NOT IN (
              SELECT id FROM prompt_trace
              WHERE conversation_id = :conversationId
              ORDER BY created_at DESC, provider_step_index DESC
              LIMIT :keep
          )
        """
    )
    suspend fun pruneConversation(conversationId: String, keep: Int)

    @Query(
        """
        DELETE FROM prompt_trace
        WHERE conversation_id = :conversationId
          AND (
              response_message_id IN (:messageIds)
              OR (
                  response_message_id IS NULL
                  AND request_anchor_message_id IN (:messageIds)
              )
          )
        """
    )
    suspend fun deleteForRemovedMessages(conversationId: String, messageIds: List<String>)

    @Query("DELETE FROM prompt_trace WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Transaction
    suspend fun insertAndPrune(entity: PromptTraceEntity, keep: Int = 20) {
        insert(entity)
        pruneConversation(entity.conversationId, keep)
    }
}
```

- [ ] **Step 5: Add the 27→28 SQL migration**

```kotlin
package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `prompt_trace` (
                `id` TEXT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `request_anchor_message_id` TEXT,
                `response_message_id` TEXT,
                `assistant_id` TEXT NOT NULL,
                `model_id` TEXT NOT NULL,
                `speaker_member_id` TEXT,
                `provider_step_index` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `actual_prompt_tokens` INTEGER,
                `error_summary` TEXT,
                `payload_json` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_prompt_trace_conversation_id` ON `prompt_trace` (`conversation_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_prompt_trace_response_message_id` ON `prompt_trace` (`response_message_id`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_prompt_trace_conversation_id_created_at` " +
                "ON `prompt_trace` (`conversation_id`, `created_at`)"
        )
    }
}
```

- [ ] **Step 6: Register entity, DAO, version, migration, and Koin DAO binding**

In `AppDatabase.kt`:

```kotlin
import me.rerere.rikkahub.data.db.dao.PromptTraceDAO
import me.rerere.rikkahub.data.db.entity.PromptTraceEntity

@Database(
    entities = [
        ConversationEntity::class,
        MemoryEntity::class,
        GenMediaEntity::class,
        MessageNodeEntity::class,
        ManagedFileEntity::class,
        FavoriteEntity::class,
        WorkspaceEntity::class,
        FolderEntity::class,
        PromptTraceEntity::class,
    ],
    version = 28,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9, spec = Migration_8_9::class),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 16, to = 17, spec = Migration_16_17::class),
        AutoMigration(from = 17, to = 18),
        AutoMigration(from = 18, to = 19),
        AutoMigration(from = 19, to = 20),
        AutoMigration(from = 20, to = 21),
        AutoMigration(from = 21, to = 22),
        AutoMigration(from = 22, to = 23, spec = Migration_22_23::class),
        AutoMigration(from = 23, to = 24),
        AutoMigration(from = 24, to = 25),
    ],
)
@TypeConverters(TokenUsageConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDAO
    abstract fun memoryDao(): MemoryDAO
    abstract fun genMediaDao(): GenMediaDAO
    abstract fun messageNodeDao(): MessageNodeDAO
    abstract fun managedFileDao(): ManagedFileDAO
    abstract fun favoriteDao(): FavoriteDAO
    abstract fun workspaceDao(): WorkspaceDAO
    abstract fun folderDao(): FolderDAO
    abstract fun promptTraceDao(): PromptTraceDAO
}
```

In `DataSourceModule.kt`, import/register `Migration_27_28` after `Migration_26_27` and add:

```kotlin
single {
    get<AppDatabase>().promptTraceDao()
}
```

- [ ] **Step 7: Add DAO retention and cascade tests**

`PromptTraceDAOTest` must:

1. Create an in-memory `AppDatabase`.
2. Insert one `ConversationEntity`.
3. Insert 21 traces with increasing `createdAt`.
4. Assert only IDs 2–21 remain after `insertAndPrune`.
5. Delete the conversation and assert trace count becomes zero.
6. Insert bound and unbound traces, call `deleteForRemovedMessages`, and verify only the matching response plus unbound matching anchor are removed.

Use this entity factory:

```kotlin
private fun trace(conversationId: String, index: Int) = PromptTraceEntity(
    id = "trace-$index",
    conversationId = conversationId,
    requestAnchorMessageId = "user-$index",
    responseMessageId = "assistant-$index",
    assistantId = "assistant",
    modelId = "model",
    speakerMemberId = null,
    providerStepIndex = index,
    status = "COMPLETED",
    actualPromptTokens = index,
    errorSummary = null,
    payloadJson = "{}",
    createdAt = index.toLong(),
    updatedAt = index.toLong(),
)
```

- [ ] **Step 8: Generate schema 28 and run persistence tests**

Run:

```powershell
.\gradlew.bat :app:kspDebugKotlin --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.data.db.migrations.Migration_27_28_Test,me.rerere.rikkahub.data.db.dao.PromptTraceDAOTest" --console=plain
```

Expected:
- `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/28.json` is generated.
- Both instrumentation classes PASS.

- [ ] **Step 9: Commit**

```powershell
git add app/src/main/java/me/rerere/rikkahub/data/db app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt app/src/androidTest/java/me/rerere/rikkahub/data/db app/schemas/me.rerere.rikkahub.data.db.AppDatabase/28.json
git commit -m "feat: persist tavern prompt traces"
```

---

### Task 5: Implement repository decoding, lifecycle session, retention, and failure isolation

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/repository/PromptTraceRepository.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/data/ai/trace/PromptTraceSession.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/di/RepositoryModule.kt:5-48`
- Modify: `app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt:145-166`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/trace/PromptTraceSessionTest.kt`
- Test: `app/src/androidTest/java/me/rerere/rikkahub/data/repository/PromptTraceRepositoryTest.kt`

**Interfaces:**
- Consumes: Tasks 1, 2, and 4.
- Produces:
  - `interface PromptTraceStore`
  - `interface PromptTraceSessionFactory`
  - `class DefaultPromptTraceSessionFactory`
  - `class PromptTraceSession`
  - `class PromptTraceRepository : PromptTraceStore`
  - `observeConversation`, `clearConversation`, and `deleteForRemovedMessages`.

- [ ] **Step 1: Write failing lifecycle tests with an in-memory fake store**

```kotlin
package me.rerere.rikkahub.data.ai.trace

import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class PromptTraceSessionTest {
    @Test
    fun `session progresses prepared streaming completed and keeps prompt usage`() = runBlocking {
        val store = RecordingTraceStore()
        val input = UIMessage.user("hello")
        val session = PromptTraceSession(
            seed = seed(),
            providerStepIndex = 1,
            providerName = "OpenAI",
            store = store,
            now = { 100L + store.events.size },
        )
        session.recordInputMessages(listOf(input))
        session.prepare(listOf(input))
        val response = UIMessage.assistant("hi").copy(usage = me.rerere.ai.core.TokenUsage(promptTokens = 12))
        session.observeProviderMessages(listOf(input, response))
        session.complete()

        assertEquals(listOf("PREPARED", "STREAMING:12", "COMPLETED"), store.events)
        assertEquals(response.id, store.responseMessageId)
    }

    @Test
    fun `failure before first response preserves null binding and sanitized summary`() = runBlocking {
        val store = RecordingTraceStore()
        val session = PromptTraceSession(seed(), 0, "Fake", store, now = { 1L })
        session.prepare(listOf(UIMessage.user("hello")))
        session.fail(IllegalStateException("token=secret https://example.com/a?key=1"))

        assertNull(store.responseMessageId)
        assertEquals("FAILED", store.events.last())
        assertEquals("token=[redacted] https://example.com/a", store.errorSummary)
    }

    @Test
    fun `non cancellation store failures are swallowed`() = runBlocking {
        val session = PromptTraceSession(seed(), 0, "Fake", ThrowingTraceStore(), now = { 1L })
        session.prepare(listOf(UIMessage.user("hello")))
        session.complete()
    }

    private fun seed() = PromptTraceSeed(
        conversationId = Uuid.random(),
        requestAnchorMessageId = Uuid.random(),
        assistantId = Uuid.random(),
        modelId = Uuid.random(),
        isGroup = false,
    )
}

private class RecordingTraceStore : PromptTraceStore {
    val events = mutableListOf<String>()
    var responseMessageId: Uuid? = null
    var errorSummary: String? = null

    override suspend fun insertPrepared(traceId: Uuid, payload: PromptTracePayload) {
        events += "PREPARED"
    }

    override suspend fun markStreaming(
        traceId: Uuid,
        responseMessageId: Uuid,
        actualPromptTokens: Int?,
    ) {
        this.responseMessageId = responseMessageId
        events += "STREAMING:${actualPromptTokens ?: 0}"
    }

    override suspend fun updateActualPromptTokens(traceId: Uuid, actualPromptTokens: Int) {
        events += "TOKENS:$actualPromptTokens"
    }

    override suspend fun markTerminal(
        traceId: Uuid,
        status: PromptTraceStatus,
        errorSummary: String?,
    ) {
        this.errorSummary = errorSummary
        events += status.name
    }
}

private class ThrowingTraceStore : PromptTraceStore {
    override suspend fun insertPrepared(traceId: Uuid, payload: PromptTracePayload) {
        error("trace store failure")
    }

    override suspend fun markStreaming(
        traceId: Uuid,
        responseMessageId: Uuid,
        actualPromptTokens: Int?,
    ) {
        error("trace store failure")
    }

    override suspend fun updateActualPromptTokens(traceId: Uuid, actualPromptTokens: Int) {
        error("trace store failure")
    }

    override suspend fun markTerminal(
        traceId: Uuid,
        status: PromptTraceStatus,
        errorSummary: String?,
    ) {
        error("trace store failure")
    }
}
```

- [ ] **Step 2: Run the session tests and confirm failure**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*PromptTraceSessionTest" --console=plain
```

Expected: FAIL because lifecycle types do not exist.

- [ ] **Step 3: Define the store and factory boundaries**

```kotlin
package me.rerere.rikkahub.data.ai.trace

interface PromptTraceStore {
    suspend fun insertPrepared(traceId: Uuid, payload: PromptTracePayload)
    suspend fun markStreaming(traceId: Uuid, responseMessageId: Uuid, actualPromptTokens: Int?)
    suspend fun updateActualPromptTokens(traceId: Uuid, actualPromptTokens: Int)
    suspend fun markTerminal(traceId: Uuid, status: PromptTraceStatus, errorSummary: String?)
}

interface PromptTraceSessionFactory {
    fun create(
        seed: PromptTraceSeed,
        providerStepIndex: Int,
        providerName: String?,
    ): PromptTraceSession
}

class DefaultPromptTraceSessionFactory(
    private val repository: PromptTraceRepository,
) : PromptTraceSessionFactory {
    override fun create(
        seed: PromptTraceSeed,
        providerStepIndex: Int,
        providerName: String?,
    ): PromptTraceSession = PromptTraceSession(
        seed = seed,
        providerStepIndex = providerStepIndex,
        providerName = providerName,
        store = repository,
    )
}
```

- [ ] **Step 4: Implement the per-call session**

The session owns mutable collection only until `prepare`; store calls remain best-effort:

```kotlin
class PromptTraceSession(
    private val seed: PromptTraceSeed,
    private val providerStepIndex: Int,
    private val providerName: String?,
    private val store: PromptTraceStore,
    private val now: () -> Long = System::currentTimeMillis,
) : PromptTraceRecorder {
    val traceId: Uuid = Uuid.random()
    private val startedAt = now()
    private val sections = mutableListOf<PromptTraceSection>()
    private val injectionHits = mutableListOf<PromptInjectionTrace>()
    private val responseBaselineMessageIds = mutableSetOf<Uuid>()
    private val sourceInputMessageIds = mutableSetOf<Uuid>()
    private val inputTextById = mutableMapOf<Uuid, String>()
    private var responseMessageId: Uuid? = null
    private var actualPromptTokens: Int? = null
    private var prepared = false

    fun recordSection(section: PromptTraceSection) {
        if (!prepared) sections += section
    }

    fun recordResponseBaseline(messages: List<UIMessage>) {
        if (!prepared) responseBaselineMessageIds += messages.map { it.id }
    }

    fun recordInputMessages(messages: List<UIMessage>) {
        if (prepared) return
        val hints = seed.sourceHints.associateBy { it.messageId }
        messages.forEach { message ->
            responseBaselineMessageIds += message.id
            sourceInputMessageIds += message.id
            inputTextById[message.id] = message.toText()
            val hint = hints[message.id]
            val kind = when {
                hint != null -> hint.kind
                message.id == seed.requestAnchorMessageId -> PromptTraceSectionKind.CURRENT_USER_MESSAGE
                else -> PromptTraceSectionKind.HISTORY_MESSAGE
            }
            val text = message.toText()
            if (text.isNotEmpty()) {
                sections += PromptTraceSection(
                    kind = kind,
                    label = hint?.label ?: if (kind == PromptTraceSectionKind.CURRENT_USER_MESSAGE) "Current user input" else "History message",
                    text = text,
                    sourceMessageId = message.id,
                    targetMessageId = message.id,
                )
            }
        }
    }

    override fun recordInjectionHits(hits: List<PromptInjectionTrace>) {
        if (prepared) return
        injectionHits += hits
        hits.forEach { hit ->
            sections += PromptTraceSection(
                kind = if (hit.sourceType == PromptInjectionSourceType.MODE) {
                    PromptTraceSectionKind.MODE_INJECTION
                } else {
                    PromptTraceSectionKind.LOREBOOK_INJECTION
                },
                label = hit.injectionName.ifBlank { hit.injectionId.toString() },
                text = hit.content,
                targetMessageId = hit.targetMessageId,
                targetMessageIndex = hit.targetMessageIndex,
            )
        }
    }

    suspend fun prepare(finalMessages: List<UIMessage>) {
        if (prepared) return
        prepared = true
        val sanitized = PromptTraceSanitizer.sanitizeMessages(finalMessages)
        val knownIds = sourceInputMessageIds + sections.mapNotNull { it.targetMessageId }
        finalMessages.forEachIndexed { index, message ->
            val finalText = message.toText()
            val inputText = inputTextById[message.id]
            val isNewMessage = message.id !in knownIds
            val existingMessageChanged = inputText != null && inputText != finalText
            if ((isNewMessage || existingMessageChanged) && finalText.isNotEmpty()) {
                sections += PromptTraceSection(
                    kind = PromptTraceSectionKind.OTHER_TRANSFORMED_CONTENT,
                    label = if (existingMessageChanged) "Transformed message" else "Transformer output",
                    text = finalText,
                    sourceMessageId = message.id.takeIf { existingMessageChanged },
                    targetMessageId = message.id,
                    targetMessageIndex = index,
                )
            }
        }
        val payload = PromptTracePayload(
            metadata = PromptTraceMetadata(
                conversationId = seed.conversationId,
                assistantId = seed.assistantId,
                modelId = seed.modelId,
                isGroup = seed.isGroup,
                speakerMemberId = seed.speakerMemberId,
                speakerName = seed.speakerName,
                providerName = providerName,
                providerStepIndex = providerStepIndex,
                requestAnchorMessageId = seed.requestAnchorMessageId,
                startedAtEpochMs = startedAt,
                finalMessageCount = sanitized.size,
            ),
            sections = sections.toList(),
            injectionHits = injectionHits.toList(),
            finalMessages = sanitized,
        )
        bestEffort { store.insertPrepared(traceId, payload) }
    }

    suspend fun observeProviderMessages(messages: List<UIMessage>) {
        val response = messages.lastOrNull {
            it.id !in responseBaselineMessageIds && it.role == MessageRole.ASSISTANT
        }
        val promptTokens = response?.usage?.promptTokens?.takeIf { it > 0 }
        if (response != null && responseMessageId == null) {
            responseMessageId = response.id
            actualPromptTokens = promptTokens
            bestEffort { store.markStreaming(traceId, response.id, promptTokens) }
        }
        if (promptTokens != null && promptTokens != actualPromptTokens) {
            actualPromptTokens = promptTokens
            bestEffort { store.updateActualPromptTokens(traceId, promptTokens) }
        }
    }

    suspend fun complete() = bestEffort {
        store.markTerminal(traceId, PromptTraceStatus.COMPLETED, null)
    }

    suspend fun cancel() = bestEffort {
        store.markTerminal(traceId, PromptTraceStatus.CANCELLED, null)
    }

    suspend fun fail(error: Throwable) = bestEffort {
        store.markTerminal(
            traceId,
            PromptTraceStatus.FAILED,
            PromptTraceSanitizer.sanitizeError(error),
        )
    }

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w("PromptTraceSession", "Trace persistence failed", error)
        }
    }
}
```

Add imports for `android.util.Log`, `CancellationException`, `MessageRole`, `UIMessage`, `PromptTraceRepository`, and `Uuid`.

- [ ] **Step 5: Implement repository mapping with row columns as lifecycle authority**

`PromptTraceRepository` must:

```kotlin
class PromptTraceRepository(
    private val dao: PromptTraceDAO,
    baseJson: Json,
) : PromptTraceStore {
    private val json = Json(baseJson) {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun observeConversation(conversationId: Uuid): Flow<List<PromptTraceReadResult>> {
        return dao.observeByConversation(conversationId.toString()).map { rows ->
            rows.map(::decode)
        }
    }

    suspend fun clearConversation(conversationId: Uuid) {
        dao.deleteByConversation(conversationId.toString())
    }

    suspend fun deleteForRemovedMessages(conversationId: Uuid, messageIds: Set<Uuid>) {
        if (messageIds.isEmpty()) return
        dao.deleteForRemovedMessages(conversationId.toString(), messageIds.map(Uuid::toString))
    }

    override suspend fun insertPrepared(traceId: Uuid, payload: PromptTracePayload) {
        val metadata = payload.metadata
        dao.insertAndPrune(
            PromptTraceEntity(
                id = traceId.toString(),
                conversationId = metadata.conversationId.toString(),
                requestAnchorMessageId = metadata.requestAnchorMessageId?.toString(),
                responseMessageId = null,
                assistantId = metadata.assistantId.toString(),
                modelId = metadata.modelId.toString(),
                speakerMemberId = metadata.speakerMemberId?.toString(),
                providerStepIndex = metadata.providerStepIndex,
                status = PromptTraceStatus.PREPARED.name,
                actualPromptTokens = null,
                errorSummary = null,
                payloadJson = json.encodeToString(PromptTracePayload.serializer(), payload),
                createdAt = metadata.startedAtEpochMs,
                updatedAt = metadata.startedAtEpochMs,
            ),
            keep = 20,
        )
    }

    override suspend fun markStreaming(traceId: Uuid, responseMessageId: Uuid, actualPromptTokens: Int?) {
        dao.markStreaming(traceId.toString(), responseMessageId.toString(), actualPromptTokens, System.currentTimeMillis())
    }

    override suspend fun updateActualPromptTokens(traceId: Uuid, actualPromptTokens: Int) {
        dao.updateActualPromptTokens(traceId.toString(), actualPromptTokens, System.currentTimeMillis())
    }

    override suspend fun markTerminal(traceId: Uuid, status: PromptTraceStatus, errorSummary: String?) {
        val row = dao.getById(traceId.toString())
        dao.markTerminal(traceId.toString(), status.name, errorSummary, System.currentTimeMillis())
        row?.let { dao.pruneConversation(it.conversationId, 20) }
    }

    private fun decode(entity: PromptTraceEntity): PromptTraceReadResult {
        val traceId = Uuid.parse(entity.id)
        return runCatching {
            val payload = json.decodeFromString(PromptTracePayload.serializer(), entity.payloadJson)
            val metadata = payload.metadata.copy(
                responseMessageId = entity.responseMessageId?.let(Uuid::parse),
                finishedAtEpochMs = if (entity.status in setOf("COMPLETED", "CANCELLED", "FAILED")) entity.updatedAt else null,
                status = PromptTraceStatus.valueOf(entity.status),
                actualPromptTokens = entity.actualPromptTokens,
            )
            PromptTraceReadResult.Available(
                PromptTraceRecord(
                    traceId = traceId,
                    payload = payload.copy(metadata = metadata),
                    errorSummary = entity.errorSummary,
                )
            )
        }.getOrElse {
            PromptTraceReadResult.Unavailable(
                traceId = traceId,
                createdAtEpochMs = entity.createdAt,
                responseMessageId = entity.responseMessageId?.let(Uuid::parse),
                status = runCatching { PromptTraceStatus.valueOf(entity.status) }.getOrDefault(PromptTraceStatus.FAILED),
                errorSummary = entity.errorSummary,
            )
        }
    }
}
```

- [ ] **Step 6: Register repository and factory in Koin**

In `RepositoryModule.kt`:

```kotlin
single {
    PromptTraceRepository(
        dao = get(),
        baseJson = get(),
    )
}
```

In `DataSourceModule.kt`, before constructing `GenerationHandler`:

```kotlin
single<PromptTraceSessionFactory> {
    DefaultPromptTraceSessionFactory(repository = get())
}
```

- [ ] **Step 7: Add repository instrumentation tests**

Add this test body to `PromptTraceRepositoryTest`; reuse the in-memory database and
conversation-row setup from `PromptTraceDAOTest`:

```kotlin
@Test
fun validAndMalformedPayloadsReturnTypedResults() = runBlocking {
    val conversationId = insertConversation(Uuid.random().toString())
    val validId = Uuid.random()
    val malformedId = Uuid.random()
    val valid = traceEntity(
        id = validId.toString(),
        conversationId = conversationId,
        payloadJson = json.encodeToString(PromptTracePayload.serializer(), payload(conversationId)),
    )
    val malformed = traceEntity(
        id = malformedId.toString(),
        conversationId = conversationId,
        payloadJson = "{broken",
        createdAt = 2L,
    )
    dao.insert(valid)
    dao.insert(malformed)

    val results = repository.observeConversation(Uuid.parse(conversationId)).first()
    assertTrue(results.any { it is PromptTraceReadResult.Available && it.traceId == validId })
    assertTrue(results.any { it is PromptTraceReadResult.Unavailable && it.traceId == malformedId })
}

@Test
fun clearAndRemovedMessageCleanupStayConversationScoped() = runBlocking {
    val firstConversation = insertConversation(Uuid.random().toString())
    val secondConversation = insertConversation(Uuid.random().toString())
    val removedResponse = Uuid.random()
    val removedAnchor = Uuid.random()
    dao.insert(traceEntity("bound", firstConversation, responseMessageId = removedResponse.toString()))
    dao.insert(traceEntity("unbound", firstConversation, requestAnchorMessageId = removedAnchor.toString()))
    dao.insert(traceEntity("survivor", secondConversation))

    repository.deleteForRemovedMessages(
        conversationId = Uuid.parse(firstConversation),
        messageIds = setOf(removedResponse, removedAnchor),
    )
    assertTrue(repository.observeConversation(Uuid.parse(firstConversation)).first().isEmpty())
    assertEquals(1, repository.observeConversation(Uuid.parse(secondConversation)).first().size)

    repository.clearConversation(Uuid.parse(secondConversation))
    assertTrue(repository.observeConversation(Uuid.parse(secondConversation)).first().isEmpty())
}
```

The `traceEntity` helper must accept explicit `id`, `conversationId`,
`requestAnchorMessageId`, `responseMessageId`, `payloadJson`, and `createdAt`
arguments so every assertion is deterministic.

- [ ] **Step 8: Run session and repository tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*PromptTraceSessionTest" --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.data.repository.PromptTraceRepositoryTest" --console=plain
```

Expected: PASS.

- [ ] **Step 9: Commit**

```powershell
git add app/src/main/java/me/rerere/rikkahub/data/ai/trace/PromptTraceSession.kt app/src/main/java/me/rerere/rikkahub/data/repository/PromptTraceRepository.kt app/src/main/java/me/rerere/rikkahub/di app/src/test/java/me/rerere/rikkahub/data/ai/trace/PromptTraceSessionTest.kt app/src/androidTest/java/me/rerere/rikkahub/data/repository/PromptTraceRepositoryTest.kt
git commit -m "feat: add prompt trace lifecycle repository"
```

---

### Task 6: Capture one trace around every real GenerationHandler provider invocation

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt:66-187,351-468`
- Modify: `app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt:154-166`
- Create: `app/src/androidTest/java/me/rerere/rikkahub/data/ai/GenerationHandlerPromptTraceTest.kt`

**Interfaces:**
- Consumes:
  - `PromptTraceSeed`
  - `PromptTraceSessionFactory.create(seed, providerStepIndex, providerName)`
  - `TransformerContext.promptTraceSession`
- Produces:
  - `GenerationHandler.generateText` with defaulted `promptTraceSeed: PromptTraceSeed? = null`.
  - Exact PREPARED/STREAMING/terminal lifecycle around each provider call.

- [ ] **Step 1: Write a failing streaming provider-bound capture test**

Create an instrumentation test with:

```kotlin
private class RecordingOpenAIProvider(
    private val response: MessageChunk,
) : Provider<ProviderSetting.OpenAI> {
    var capturedMessages: List<UIMessage> = emptyList()

    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk {
        capturedMessages = messages
        return response
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> {
        capturedMessages = messages
        return flowOf(response)
    }
}
```

The test must:

1. Build an in-memory Room database and `PromptTraceRepository`.
2. Replace `"openai"` in `ProviderManager` with `RecordingOpenAIProvider`.
3. Use a Tavern assistant with system prompt `"card system"`.
4. Add an input transformer that appends `" transformed"` to the user message.
5. Collect `generateText` with a non-null `PromptTraceSeed`.
6. Assert the stored `finalMessages` equal `capturedMessages` by IDs, roles, and text.
7. Assert status `COMPLETED`, response ID binding, and actual prompt tokens from a response usage of 17.

- [ ] **Step 2: Run the new GenerationHandler test and confirm failure**

Run:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.data.ai.GenerationHandlerPromptTraceTest" --console=plain
```

Expected: FAIL because `promptTraceSeed` and session-factory wiring are absent.

- [ ] **Step 3: Inject the session factory and extend the public generation signature**

Update the constructor:

```kotlin
class GenerationHandler(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryRepo: MemoryRepository,
    private val promptTraceSessionFactory: PromptTraceSessionFactory,
)
```

Add the defaulted parameter:

```kotlin
fun generateText(
    settings: Settings,
    model: Model,
    messages: List<UIMessage>,
    inputTransformers: List<InputMessageTransformer> = emptyList(),
    outputTransformers: List<OutputMessageTransformer> = emptyList(),
    assistant: Assistant,
    memories: List<AssistantMemory>? = null,
    tools: List<Tool> = emptyList(),
    maxSteps: Int = 256,
    processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    conversationSystemPrompt: String? = null,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
    workspaceCwd: String? = null,
    conversationId: Uuid? = null,
    memberId: Uuid? = null,
    promptTraceSeed: PromptTraceSeed? = null,
): Flow<GenerationChunk>
```

- [ ] **Step 4: Create a provider-call index and one session only when a provider call occurs**

Immediately before the loop:

```kotlin
var providerCallIndex = 0
```

Inside `if (pendingTools.isEmpty())`, before `generateInternal`:

```kotlin
val promptTraceSession = promptTraceSeed?.let { seed ->
    promptTraceSessionFactory.create(
        seed = seed,
        providerStepIndex = providerCallIndex++,
        providerName = provider.name,
    )
}
```

Do not increment `providerCallIndex` in the pending-tool-resume branch because that branch performs no provider request.

- [ ] **Step 5: Record semantic sources before transformations**

In `generateInternal`, compute source strings once and preserve current request text:

```kotlin
val assistantSystemPrompt = assistant.normalizedSystemPromptForGeneration(
    userName = settings.displaySetting.userNickname.ifBlank { "user" },
)
val usesConversationOverride =
    assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()
val effectiveSystemPrompt = if (usesConversationOverride) {
    conversationSystemPrompt.orEmpty()
} else {
    assistantSystemPrompt
}
val memoryPrompt = if (assistant.enableMemory) buildMemoryPrompt(memories) else ""
val toolPrompts = tools.mapNotNull { tool ->
    tool.systemPrompt(model, messages).takeIf { it.isNotBlank() }?.let { tool.name to it }
}
val limitedMessages = messages.limitContext(assistant.contextMessageSize)
val systemText = buildString {
    if (effectiveSystemPrompt.isNotBlank()) append(effectiveSystemPrompt)
    if (memoryPrompt.isNotBlank()) {
        appendLine()
        append(memoryPrompt)
    }
    toolPrompts.forEach { (_, prompt) ->
        appendLine()
        append(prompt)
    }
}
val systemMessage = systemText.takeIf { it.isNotBlank() }?.let(UIMessage::system)

promptTraceSession?.recordSection(
    PromptTraceSection(
        kind = PromptTraceSectionKind.ASSISTANT_OR_CARD_SYSTEM,
        label = "Assistant or card system prompt",
        text = assistantSystemPrompt,
        active = !usesConversationOverride,
        targetMessageId = systemMessage?.id?.takeIf { !usesConversationOverride },
        targetMessageIndex = 0.takeIf { systemMessage != null && !usesConversationOverride },
    )
)
if (usesConversationOverride) {
    promptTraceSession?.recordSection(
        PromptTraceSection(
            kind = PromptTraceSectionKind.CONVERSATION_SYSTEM_OVERRIDE,
            label = "Conversation system override",
            text = effectiveSystemPrompt,
            targetMessageId = systemMessage?.id,
            targetMessageIndex = 0.takeIf { systemMessage != null },
        )
    )
}
if (memoryPrompt.isNotBlank()) {
    promptTraceSession?.recordSection(
        PromptTraceSection(
            kind = PromptTraceSectionKind.MEMORY,
            label = "Memory",
            text = memoryPrompt,
            targetMessageId = systemMessage?.id,
            targetMessageIndex = 0.takeIf { systemMessage != null },
        )
    )
}
toolPrompts.forEach { (name, text) ->
    promptTraceSession?.recordSection(
        PromptTraceSection(
            kind = PromptTraceSectionKind.TOOL_PROMPT,
            label = "Tool prompt: $name",
            text = text,
            targetMessageId = systemMessage?.id,
            targetMessageIndex = 0.takeIf { systemMessage != null },
        )
    )
}
promptTraceSession?.recordResponseBaseline(messages)
promptTraceSession?.recordInputMessages(limitedMessages)
```

Build the system message from these exact variables:

```kotlin
val internalMessages = buildList {
    systemMessage?.let(::add)
    addAll(limitedMessages)
}.transforms(
    transformers = transformers,
    context = context,
    model = model,
    assistant = assistant,
    settings = settings,
    conversationModeInjectionIds = conversationModeInjectionIds,
    conversationLorebookIds = conversationLorebookIds,
    processingStatus = processingStatus,
    workspaceCwd = workspaceCwd,
    conversationId = conversationId,
    promptTraceSession = promptTraceSession,
)

promptTraceSession?.prepare(internalMessages)
```

- [ ] **Step 6: Bind response and usage before output transformers**

Inside `onUpdateMessages`, before applying output transformers:

```kotlin
promptTraceSession?.observeProviderMessages(it)
```

This uses raw merged provider messages, preserving response ID and usage even if an output transformer changes visible content.

- [ ] **Step 7: Mark terminal state without changing cancellation semantics**

Wrap the provider step plus output-finalization block:

```kotlin
try {
    generateInternal(
        assistant = assistant,
        settings = settings,
        messages = messages,
        onUpdateMessages = {
            promptTraceSession?.observeProviderMessages(it)
            messages = it.transforms(
                transformers = outputTransformers,
                context = context,
                model = model,
                assistant = assistant,
                settings = settings,
            )
            emit(
                GenerationChunk.Messages(
                    messages.visualTransforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings,
                    )
                )
            )
        },
        transformers = inputTransformers,
        model = model,
        providerImpl = providerImpl,
        provider = provider,
        tools = toolsInternal,
        memories = memories.orEmpty(),
        stream = assistant.streamOutput,
        processingStatus = processingStatus,
        conversationSystemPrompt = conversationSystemPrompt,
        conversationModeInjectionIds = conversationModeInjectionIds,
        conversationLorebookIds = conversationLorebookIds,
        workspaceCwd = workspaceCwd,
        conversationId = conversationId,
        promptTraceSession = promptTraceSession,
    )
    messages = messages.visualTransforms(outputTransformers, context, model, assistant, settings)
    messages = messages.onGenerationFinish(outputTransformers, context, model, assistant, settings)
    messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
        finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    )
    emit(GenerationChunk.Messages(messages))
    promptTraceSession?.complete()
} catch (cancelled: CancellationException) {
    withContext(NonCancellable) {
        promptTraceSession?.cancel()
    }
    throw cancelled
} catch (error: Throwable) {
    promptTraceSession?.fail(error)
    throw error
}
```

Add `promptTraceSession: PromptTraceSession? = null` to `generateInternal`.

- [ ] **Step 8: Update Koin construction**

```kotlin
GenerationHandler(
    context = get(),
    providerManager = get(),
    json = get(),
    memoryRepo = get(),
    promptTraceSessionFactory = get(),
)
```

- [ ] **Step 9: Run GenerationHandler and transformer regression tests**

Run:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.data.ai.GenerationHandlerPromptTraceTest" --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests "*PromptInjectionTransformerTest" --tests "*PromptInjectionTraceTest" --console=plain
```

Expected: PASS.

- [ ] **Step 10: Commit**

```powershell
git add app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt app/src/androidTest/java/me/rerere/rikkahub/data/ai/GenerationHandlerPromptTraceTest.kt
git commit -m "feat: trace provider-bound prompt calls"
```

---

### Task 7: Create Tavern trace seeds in ChatService, expose group context IDs, and clean removed branches

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/ai/trace/PromptTraceSeedFactory.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/group/GroupContextModels.kt:160-172`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/group/GroupContextBuilder.kt:5-77`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt:81-129,257-274,1024-1145,1639-1653`
- Modify: `app/src/main/java/me/rerere/rikkahub/di/AppModule.kt:91-109`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/trace/PromptTraceSeedFactoryTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/service/group/GroupContextBuilderTest.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/service/PromptTraceCleanupTest.kt`

**Interfaces:**
- Consumes: shared eligibility helper and `GenerationHandler.promptTraceSeed`.
- Produces:
  - `buildPromptTraceSeed`
  - `GroupContextBuildResult.syntheticMessageId`
  - `removedMessageIds(before, after)`
  - ChatService passes `conversationId` and eligible seed for every normal generation call.

- [ ] **Step 1: Write failing seed, group-ID, and cleanup tests**

```kotlin
package me.rerere.rikkahub.data.ai.trace

import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class PromptTraceSeedFactoryTest {
    @Test
    fun `eligible group seed keeps actual speaker and real user anchor`() {
        val source = Assistant(name = "甲", tavernCardJson = "{}")
        val member = GroupMember(assistantId = source.id, displayName = "甲")
        val group = Assistant(assistantType = AssistantType.GROUP, groupMembers = listOf(member))
        val user = UIMessage.user("继续")
        val seed = buildPromptTraceSeed(
            conversationId = Uuid.random(),
            conversationAssistant = group,
            generatingAssistant = source,
            model = Model(),
            visibleMessages = listOf(user),
            allAssistants = listOf(source),
            speakerMemberId = member.id,
            speakerName = member.displayName,
            sourceHints = emptyList(),
        )

        assertNotNull(seed)
        assertEquals(user.id, seed?.requestAnchorMessageId)
        assertEquals(member.id, seed?.speakerMemberId)
    }

    @Test
    fun `non tavern conversation returns no seed`() {
        assertNull(
            buildPromptTraceSeed(
                conversationId = Uuid.random(),
                conversationAssistant = Assistant(),
                generatingAssistant = Assistant(),
                model = Model(),
                visibleMessages = listOf(UIMessage.user("hello")),
                allAssistants = emptyList(),
            )
        )
    }
}
```

Add to `GroupContextBuilderTest`:

```kotlin
assertEquals(result.messages.first().id, result.syntheticMessageId)
```

Add `PromptTraceCleanupTest`:

```kotlin
@Test
fun `removed ids include deleted alternative and truncated tail but not branch selection`() {
    val first = UIMessage.user("one")
    val altA = UIMessage.assistant("A")
    val altB = UIMessage.assistant("B")
    val tail = UIMessage.user("two")
    val before = Conversation(
        assistantId = Uuid.random(),
        messageNodes = listOf(
            MessageNode.of(first),
            MessageNode(messages = listOf(altA, altB), selectIndex = 0),
            MessageNode.of(tail),
        )
    )
    val branchSelected = before.copy(
        messageNodes = before.messageNodes.mapIndexed { index, node ->
            if (index == 1) node.copy(selectIndex = 1) else node
        }
    )
    val truncated = before.copy(messageNodes = before.messageNodes.take(2))

    assertEquals(emptySet<Uuid>(), removedMessageIds(before, branchSelected))
    assertEquals(setOf(tail.id), removedMessageIds(before, truncated))
}
```

- [ ] **Step 2: Run focused tests and confirm failure**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*PromptTraceSeedFactoryTest" --tests "*GroupContextBuilderTest" --tests "*PromptTraceCleanupTest" --console=plain
```

Expected: FAIL for absent factory, synthetic ID, and cleanup helper.

- [ ] **Step 3: Implement the pure seed factory and cleanup helper**

```kotlin
package me.rerere.rikkahub.data.ai.trace

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

fun buildPromptTraceSeed(
    conversationId: Uuid,
    conversationAssistant: Assistant,
    generatingAssistant: Assistant,
    model: Model,
    visibleMessages: List<UIMessage>,
    allAssistants: List<Assistant>,
    speakerMemberId: Uuid? = null,
    speakerName: String? = null,
    sourceHints: List<PromptTraceSourceHint> = emptyList(),
): PromptTraceSeed? {
    if (!conversationAssistant.isTavernPromptTraceEligible(allAssistants)) return null
    val anchor = visibleMessages.lastOrNull { message ->
        message.role == MessageRole.USER && !message.parts.isEmptyInputMessage()
    }
    return PromptTraceSeed(
        conversationId = conversationId,
        requestAnchorMessageId = anchor?.id,
        assistantId = generatingAssistant.id,
        modelId = model.id,
        isGroup = conversationAssistant.assistantType == me.rerere.rikkahub.data.model.AssistantType.GROUP,
        speakerMemberId = speakerMemberId,
        speakerName = speakerName,
        sourceHints = sourceHints,
    )
}

fun removedMessageIds(before: Conversation, after: Conversation): Set<Uuid> {
    val beforeIds = before.messageNodes.flatMap { node -> node.messages }.map { it.id }.toSet()
    val afterIds = after.messageNodes.flatMap { node -> node.messages }.map { it.id }.toSet()
    return beforeIds - afterIds
}
```

- [ ] **Step 4: Expose the layered-context synthetic message ID**

Change the result model:

```kotlin
data class GroupContextBuildResult(
    val messages: List<UIMessage>,
    val debugSections: List<String>,
    val syntheticMessageId: Uuid? = null,
)
```

Change the builder:

```kotlin
val syntheticMessage = system.takeIf { it.isNotBlank() }?.let(UIMessage::system)
val messages = if (syntheticMessage == null) {
    input.visibleMessages
} else {
    listOf(syntheticMessage) + input.visibleMessages
}
return GroupContextBuildResult(
    messages = messages,
    debugSections = if (system.isBlank()) emptyList() else listOf(system),
    syntheticMessageId = syntheticMessage?.id,
)
```

- [ ] **Step 5: Inject `PromptTraceRepository` into ChatService**

Add to the constructor:

```kotlin
private val promptTraceRepository: PromptTraceRepository,
```

Add to `AppModule.kt`:

```kotlin
promptTraceRepository = get(),
```

- [ ] **Step 6: Preserve the full group build result and create source hints**

Replace the current direct `.messages` assignment:

```kotlin
val groupContextBuildResult = if (
    effectiveMemberId != null &&
    groupAssistant.assistantType == AssistantType.GROUP &&
    groupAssistant.groupContextOptions.enableLayeredContext
) {
    GroupContextBuilder().build(
        GroupContextBuildInput(
            visibleMessages = visibleMessages,
            groupAssistant = groupAssistant,
            effectiveMemberId = effectiveMemberId,
            runtimeState = dynamicContextResult?.adjustedRuntimeState ?: conversation.groupRuntimeState,
            contextOptions = groupAssistant.groupContextOptions,
            speakingIntent = speakingIntent,
        )
    )
} else {
    null
}
val layeredMessages = groupContextBuildResult?.messages ?: visibleMessages
val sourceHints = groupContextBuildResult?.syntheticMessageId?.let { messageId ->
    listOf(
        PromptTraceSourceHint(
            messageId = messageId,
            kind = PromptTraceSectionKind.GROUP_LAYERED_CONTEXT,
            label = "Group layered context",
        )
    )
}.orEmpty()
```

- [ ] **Step 7: Build and pass the eligible seed**

Before `generationHandler.generateText`:

```kotlin
val memberName = effectiveMemberId
    ?.let { id -> groupAssistant.groupMembers.find { it.id == id } }
    ?.displayName
    ?.takeIf { it.isNotBlank() }

val promptTraceSeed = buildPromptTraceSeed(
    conversationId = conversationId,
    conversationAssistant = groupAssistant,
    generatingAssistant = assistant,
    model = model,
    visibleMessages = visibleMessages,
    allAssistants = settings.assistants,
    speakerMemberId = effectiveMemberId,
    speakerName = memberName,
    sourceHints = sourceHints,
)
```

Pass:

```kotlin
conversationId = conversationId,
memberId = effectiveMemberId,
promptTraceSeed = promptTraceSeed,
```

The existing `memberName` calculation used while stamping output can reuse this variable.

- [ ] **Step 8: Centralize branch/tail trace cleanup in `saveConversation`**

```kotlin
suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
    val exists = conversationRepo.existsConversationById(conversation.id)
    if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) return

    val previous = getConversationFlow(conversationId).value
    val updatedConversation = conversation.copy()
    val removedIds = if (previous.id == updatedConversation.id) {
        removedMessageIds(previous, updatedConversation)
    } else {
        emptySet()
    }

    updateConversation(conversationId, updatedConversation)
    if (!exists) {
        conversationRepo.insertConversation(updatedConversation)
    } else {
        conversationRepo.updateConversation(updatedConversation)
    }
    if (removedIds.isNotEmpty()) {
        runCatching {
            promptTraceRepository.deleteForRemovedMessages(conversationId, removedIds)
        }.onFailure { error ->
            Log.w(TAG, "Prompt trace cleanup failed", error)
        }
    }
}
```

This leaves branch selection untouched because the set of message IDs does not change. Fork insertion uses a new conversation ID and therefore copies no trace rows.

- [ ] **Step 9: Run focused tests and compile ChatService**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*PromptTraceSeedFactoryTest" --tests "*GroupContextBuilderTest" --tests "*PromptTraceCleanupTest" --console=plain
.\gradlew.bat :app:compileDebugKotlin -x :web:buildWebUi --console=plain
```

Expected: PASS.

- [ ] **Step 10: Commit**

```powershell
git add app/src/main/java/me/rerere/rikkahub/data/ai/trace/PromptTraceSeedFactory.kt app/src/main/java/me/rerere/rikkahub/service app/src/main/java/me/rerere/rikkahub/di/AppModule.kt app/src/test/java/me/rerere/rikkahub/data/ai/trace/PromptTraceSeedFactoryTest.kt app/src/test/java/me/rerere/rikkahub/service
git commit -m "feat: trace eligible tavern conversations"
```

---

### Task 8: Build pure console selection state and ViewModel behavior

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/tavern/console/TavernPromptConsoleState.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/tavern/console/TavernPromptConsoleVM.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/di/ViewModelModule.kt:1-90`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/tavern/console/TavernPromptConsoleStateTest.kt`

**Interfaces:**
- Consumes: `PromptTraceRepository.observeConversation`, `ConversationRepository.getConversationById`, and `SettingsStore.settingsFlow`.
- Produces:
  - `enum class TavernPromptConsoleTab`
  - `TavernPromptConsoleUiState`
  - `selectDefaultTraceId`
  - `TavernPromptConsoleVM.selectTrace`, `selectTab`, `clearConversationTraces`, `copySelectedTrace`, and `copyMessage`.

- [ ] **Step 1: Write failing branch-default and empty-state tests**

```kotlin
package me.rerere.rikkahub.ui.pages.tavern.console

import me.rerere.rikkahub.data.ai.trace.PromptTraceReadResult
import me.rerere.rikkahub.data.ai.trace.PromptTraceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class TavernPromptConsoleStateTest {
    @Test
    fun `selected reply branch wins over newest trace`() {
        val selectedReply = Uuid.random()
        val newest = unavailable(created = 20, response = Uuid.random())
        val branch = unavailable(created = 10, response = selectedReply)

        assertEquals(branch.traceId, selectDefaultTraceId(listOf(newest, branch), selectedReply))
    }

    @Test
    fun `newest trace is fallback and empty list has no selection`() {
        val newest = unavailable(created = 20, response = null)
        val older = unavailable(created = 10, response = null)

        assertEquals(newest.traceId, selectDefaultTraceId(listOf(newest, older), Uuid.random()))
        assertNull(selectDefaultTraceId(emptyList(), null))
    }

    private fun unavailable(created: Long, response: Uuid?) = PromptTraceReadResult.Unavailable(
        traceId = Uuid.random(),
        createdAtEpochMs = created,
        responseMessageId = response,
        status = PromptTraceStatus.COMPLETED,
        errorSummary = null,
    )
}
```

- [ ] **Step 2: Run the state test and confirm failure**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernPromptConsoleStateTest" --console=plain
```

Expected: FAIL because state declarations do not exist.

- [ ] **Step 3: Add the pure UI state and selection rule**

```kotlin
package me.rerere.rikkahub.ui.pages.tavern.console

import me.rerere.rikkahub.data.ai.trace.PromptTraceReadResult
import kotlin.uuid.Uuid

enum class TavernPromptConsoleTab { OVERVIEW, HITS, SENT_MESSAGES, PREVIEW }

data class TavernPromptConsoleUiState(
    val loading: Boolean = true,
    val conversationTitle: String = "",
    val assistantName: String = "",
    val traces: List<PromptTraceReadResult> = emptyList(),
    val selectedTraceId: Uuid? = null,
    val selectedTrace: PromptTraceReadResult? = null,
    val selectedTab: TavernPromptConsoleTab = TavernPromptConsoleTab.OVERVIEW,
    val selectedBranchHasTrace: Boolean = false,
)

fun selectDefaultTraceId(
    traces: List<PromptTraceReadResult>,
    selectedResponseMessageId: Uuid?,
): Uuid? {
    return selectedResponseMessageId
        ?.let { responseId -> traces.firstOrNull { it.responseMessageId == responseId } }
        ?.traceId
        ?: traces.firstOrNull()?.traceId
}
```

- [ ] **Step 4: Implement the ViewModel**

```kotlin
package me.rerere.rikkahub.ui.pages.tavern.console

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.ai.trace.PromptTraceCopyFormatter
import me.rerere.rikkahub.data.ai.trace.PromptTraceReadResult
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.PromptTraceRepository
import kotlin.uuid.Uuid

class TavernPromptConsoleVM(
    conversationId: String,
    private val promptTraceRepository: PromptTraceRepository,
    private val conversationRepository: ConversationRepository,
    settingsStore: SettingsStore,
) : ViewModel() {
    private val conversationId = Uuid.parse(conversationId)
    private val conversation = MutableStateFlow<Conversation?>(null)
    private val explicitSelection = MutableStateFlow<Uuid?>(null)
    private val selectedTab = MutableStateFlow(TavernPromptConsoleTab.OVERVIEW)
    private val traces = promptTraceRepository.observeConversation(this.conversationId)

    val uiState: StateFlow<TavernPromptConsoleUiState> = combine(
        conversation,
        traces,
        explicitSelection,
        selectedTab,
        settingsStore.settingsFlow,
    ) { currentConversation, traceItems, requestedTraceId, tab, settings ->
        if (currentConversation == null) return@combine TavernPromptConsoleUiState()
        val selectedReplyId = currentConversation.currentMessages
            .lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.id
        val selectedId = requestedTraceId
            ?.takeIf { id -> traceItems.any { it.traceId == id } }
            ?: selectDefaultTraceId(traceItems, selectedReplyId)
        val assistant = settings.assistants.find { it.id == currentConversation.assistantId }
        TavernPromptConsoleUiState(
            loading = false,
            conversationTitle = currentConversation.title,
            assistantName = assistant?.name.orEmpty(),
            traces = traceItems,
            selectedTraceId = selectedId,
            selectedTrace = traceItems.find { it.traceId == selectedId },
            selectedTab = tab,
            selectedBranchHasTrace = selectedReplyId != null && traceItems.any { it.responseMessageId == selectedReplyId },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TavernPromptConsoleUiState(),
    )

    init {
        viewModelScope.launch {
            conversation.value = conversationRepository.getConversationById(this@TavernPromptConsoleVM.conversationId)
        }
    }

    fun selectTrace(traceId: Uuid) {
        explicitSelection.value = traceId
    }

    fun selectTab(tab: TavernPromptConsoleTab) {
        selectedTab.value = tab
    }

    fun clearConversationTraces() {
        viewModelScope.launch {
            promptTraceRepository.clearConversation(conversationId)
            explicitSelection.value = null
        }
    }

    fun copySelectedTrace(): String? {
        val record = (uiState.value.selectedTrace as? PromptTraceReadResult.Available)?.record ?: return null
        return PromptTraceCopyFormatter.format(record)
    }

    fun copyMessage(index: Int): String? {
        val record = (uiState.value.selectedTrace as? PromptTraceReadResult.Available)?.record ?: return null
        return record.payload.finalMessages.getOrNull(index)?.let(PromptTraceCopyFormatter::formatMessage)
    }
}
```

- [ ] **Step 5: Register the parameterized ViewModel**

```kotlin
import me.rerere.rikkahub.ui.pages.tavern.console.TavernPromptConsoleVM

viewModel<TavernPromptConsoleVM> { params ->
    TavernPromptConsoleVM(
        conversationId = params.get(),
        promptTraceRepository = get(),
        conversationRepository = get(),
        settingsStore = get(),
    )
}
```

- [ ] **Step 6: Run state tests and compile ViewModel**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernPromptConsoleStateTest" --console=plain
.\gradlew.bat :app:compileDebugKotlin -x :web:buildWebUi --console=plain
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/me/rerere/rikkahub/ui/pages/tavern/console/TavernPromptConsoleState.kt app/src/main/java/me/rerere/rikkahub/ui/pages/tavern/console/TavernPromptConsoleVM.kt app/src/main/java/me/rerere/rikkahub/di/ViewModelModule.kt app/src/test/java/me/rerere/rikkahub/ui/pages/tavern/console
git commit -m "feat: add tavern prompt console state"
```

---

### Task 9: Add the full-screen Compose console, route, top-bar entry, copy, and clear flow

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/tavern/console/TavernPromptConsolePage.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/tavern/console/TavernPromptOverview.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/tavern/console/TavernPromptHits.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/tavern/console/TavernPromptMessages.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/tavern/console/TavernPromptConsoleEntry.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/RouteActivity.kt:129-131,343-442,615-684`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt:276-394,806-888`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Create: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/tavern/console/TavernPromptConsoleContentTest.kt`
- Create: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/tavern/console/TavernPromptConsoleEntryTest.kt`

**Interfaces:**
- Consumes: Task 8 state/VM and shared eligibility helper.
- Produces:
  - `Screen.TavernPromptConsole(conversationId: String)`
  - `TavernPromptConsolePage`
  - `TavernPromptConsoleContent`
  - top-bar `Cards02` entry visible only for shared eligible conversations.

- [ ] **Step 1: Add failing Compose tests for entry visibility and degraded/preview states**

`TavernPromptConsoleEntryTest`:

```kotlin
@RunWith(AndroidJUnit4::class)
class TavernPromptConsoleEntryTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun visibleEntryDispatchesOpenExactlyOnce() {
        var opens = 0
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            TavernPromptConsoleEntry(visible = true, onOpen = { opens++ })
        }
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.tavern_prompt_console_open))
            .performClick()
        assertEquals(1, opens)
    }

    @Test
    fun hiddenEntryDoesNotExist() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            TavernPromptConsoleEntry(visible = false, onOpen = {})
        }
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.tavern_prompt_console_open))
            .assertDoesNotExist()
    }
}
```

`TavernPromptConsoleContentTest` must render:

- no traces → `No prompt traces yet`;
- `Unavailable` selected trace → `Trace payload unavailable`;
- Preview tab → `Draft preview arrives in A2`;
- available group trace → speaker name;
- clear icon → confirmation dialog before callback;
- copy-full callback only for available trace.

- [ ] **Step 2: Run Compose tests and confirm failure**

Run:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.pages.tavern.console.TavernPromptConsoleEntryTest,me.rerere.rikkahub.ui.pages.tavern.console.TavernPromptConsoleContentTest" --console=plain
```

Expected: FAIL because console composables do not exist.

- [ ] **Step 3: Add all required base and Chinese strings**

Base English:

```xml
<string name="tavern_prompt_console_title">Tavern console</string>
<string name="tavern_prompt_console_open">Open Tavern console</string>
<string name="tavern_prompt_console_overview">Overview</string>
<string name="tavern_prompt_console_hits">Hits</string>
<string name="tavern_prompt_console_messages">Sent messages</string>
<string name="tavern_prompt_console_preview">Preview</string>
<string name="tavern_prompt_console_actual_tokens">Actual prompt tokens</string>
<string name="tavern_prompt_console_not_provided">Not provided</string>
<string name="tavern_prompt_console_approx">Approx. %1$d</string>
<string name="tavern_prompt_console_no_traces">No prompt traces yet</string>
<string name="tavern_prompt_console_branch_missing">The selected reply branch has no trace; showing the newest available call.</string>
<string name="tavern_prompt_console_payload_unavailable">Trace payload unavailable</string>
<string name="tavern_prompt_console_cancelled">Cancelled before a response branch was created</string>
<string name="tavern_prompt_console_preview_a2">Draft preview arrives in A2</string>
<string name="tavern_prompt_console_preview_a2_body">This tab does not run transformers or create a trace in A1.</string>
<string name="tavern_prompt_console_clear">Clear this conversation’s traces</string>
<string name="tavern_prompt_console_clear_title">Clear prompt traces?</string>
<string name="tavern_prompt_console_clear_body">Chat messages are kept. Only diagnostics for this conversation are deleted.</string>
<string name="tavern_prompt_console_copy_all">Copy full trace</string>
<string name="tavern_prompt_console_copy_message">Copy message</string>
<string name="tavern_prompt_console_copied">Copied</string>
<string name="tavern_prompt_console_estimate_notice">Approximate section counts may differ from provider usage because protocol overhead, tools, caching, and multimodal input are provider-specific.</string>
<string name="tavern_prompt_console_trace_selector">Select provider call</string>
<string name="tavern_prompt_console_no_hits">No mode or lorebook hits</string>
```

Simplified Chinese:

```xml
<string name="tavern_prompt_console_title">酒馆控制台</string>
<string name="tavern_prompt_console_open">打开酒馆控制台</string>
<string name="tavern_prompt_console_overview">概览</string>
<string name="tavern_prompt_console_hits">命中</string>
<string name="tavern_prompt_console_messages">发送消息</string>
<string name="tavern_prompt_console_preview">预览</string>
<string name="tavern_prompt_console_actual_tokens">实际提示词 Token</string>
<string name="tavern_prompt_console_not_provided">未提供</string>
<string name="tavern_prompt_console_approx">约 %1$d</string>
<string name="tavern_prompt_console_no_traces">本会话还没有提示词记录</string>
<string name="tavern_prompt_console_branch_missing">当前回复分支没有记录，正在显示最新一次调用。</string>
<string name="tavern_prompt_console_payload_unavailable">记录内容解析异常</string>
<string name="tavern_prompt_console_cancelled">在创建回复分支前已取消</string>
<string name="tavern_prompt_console_preview_a2">草稿预览将在 A2 开放</string>
<string name="tavern_prompt_console_preview_a2_body">A1 中此页签不会运行转换器，也不会创建记录。</string>
<string name="tavern_prompt_console_clear">清空本会话记录</string>
<string name="tavern_prompt_console_clear_title">清空提示词记录？</string>
<string name="tavern_prompt_console_clear_body">聊天消息会保留，只删除本会话的诊断记录。</string>
<string name="tavern_prompt_console_copy_all">复制完整记录</string>
<string name="tavern_prompt_console_copy_message">复制消息</string>
<string name="tavern_prompt_console_copied">已复制</string>
<string name="tavern_prompt_console_estimate_notice">分段估算可能与提供商用量不同，因为协议开销、工具、缓存和多模态输入由提供商计算。</string>
<string name="tavern_prompt_console_trace_selector">选择模型调用</string>
<string name="tavern_prompt_console_no_hits">没有模式或世界书命中</string>
```

- [ ] **Step 4: Implement the reusable top-bar entry**

```kotlin
package me.rerere.rikkahub.ui.pages.tavern.console

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cards02
import me.rerere.rikkahub.R

@Composable
fun TavernPromptConsoleEntry(
    visible: Boolean,
    onOpen: () -> Unit,
) {
    if (!visible) return
    IconButton(onClick = onOpen) {
        Icon(
            imageVector = HugeIcons.Cards02,
            contentDescription = stringResource(R.string.tavern_prompt_console_open),
        )
    }
}
```

- [ ] **Step 5: Add the route**

In `Screen`:

```kotlin
@Serializable
data class TavernPromptConsole(val conversationId: String) : Screen
```

In the route entry provider:

```kotlin
entry<Screen.TavernPromptConsole> { key ->
    TavernPromptConsolePage(conversationId = key.conversationId)
}
```

Import `TavernPromptConsolePage`.

- [ ] **Step 6: Wire shared eligibility into the chat top bar**

In `ChatPageContent`:

```kotlin
val tavernPromptTraceEligible = remember(assistant, setting.assistants) {
    assistant.isTavernPromptTraceEligible(setting.assistants)
}
```

Pass to `TopBar`:

```kotlin
tavernPromptTraceEligible = tavernPromptTraceEligible,
onOpenTavernPromptConsole = {
    navController.navigate(Screen.TavernPromptConsole(conversation.id.toString()))
},
```

Extend `TopBar`:

```kotlin
private fun TopBar(
    settings: Settings,
    conversation: Conversation,
    drawerState: DrawerState,
    bigScreen: Boolean,
    previewMode: Boolean,
    tavernPromptTraceEligible: Boolean,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onOpenTavernPromptConsole: () -> Unit,
    onUpdateTitle: (String) -> Unit,
)
```

Add as the first action:

```kotlin
TavernPromptConsoleEntry(
    visible = tavernPromptTraceEligible,
    onOpen = onOpenTavernPromptConsole,
)
```

- [ ] **Step 7: Implement route-level page, clipboard, selector, tabs, and clear confirmation**

The route-level file must use:

```kotlin
@Composable
fun TavernPromptConsolePage(conversationId: String) {
    val vm: TavernPromptConsoleVM = koinViewModel(
        parameters = { parametersOf(conversationId) }
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toaster = LocalToaster.current

    TavernPromptConsoleContent(
        state = state,
        onBack = { navController.popBackStack() },
        onSelectTrace = vm::selectTrace,
        onSelectTab = vm::selectTab,
        onCopyAll = {
            vm.copySelectedTrace()?.let { text ->
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Tavern Prompt Trace", text)))
                    toaster.show(context.getString(R.string.tavern_prompt_console_copied))
                }
            }
        },
        onCopyMessage = { index ->
            vm.copyMessage(index)?.let { text ->
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Tavern Prompt Message", text)))
                    toaster.show(context.getString(R.string.tavern_prompt_console_copied))
                }
            }
        },
        onClear = vm::clearConversationTraces,
    )
}
```

`TavernPromptConsoleContent` must:

- use `Scaffold` and `TopAppBar`;
- use `BackButton`;
- show a clear `IconButton` only when traces are non-empty;
- show an `AlertDialog` before `onClear`;
- show assistant/group name and selected status/speaker;
- present the newest-first trace selector;
- use `SecondaryTabRow` with the four fixed tabs;
- route content to `TavernPromptOverview`, `TavernPromptHits`, `TavernPromptMessages`, or the A2 state;
- show `tavern_prompt_console_branch_missing` when traces exist but `selectedBranchHasTrace == false`.

- [ ] **Step 8: Implement Overview**

`TavernPromptOverview` must accept `PromptTraceRecord` and render:

```kotlin
@Composable
fun TavernPromptOverview(record: PromptTraceRecord, modifier: Modifier = Modifier) {
    val metadata = record.payload.metadata
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.tavern_prompt_console_actual_tokens), style = MaterialTheme.typography.labelLarge)
                    Text(
                        metadata.actualPromptTokens?.toString()
                            ?: stringResource(R.string.tavern_prompt_console_not_provided),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text("Model: ${metadata.modelId}")
                    Text("Provider step: ${metadata.providerStepIndex + 1}")
                    Text("Messages: ${metadata.finalMessageCount}")
                    metadata.speakerName?.let { Text("Speaker: $it") }
                }
            }
        }
        items(record.payload.sections) { section ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(section.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.tavern_prompt_console_approx, section.approximateTokens),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (!section.active) Text("Inactive", color = MaterialTheme.colorScheme.outline)
                    Text(section.text)
                }
            }
        }
        item {
            Text(
                stringResource(R.string.tavern_prompt_console_estimate_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

- [ ] **Step 9: Implement Hits and Sent Messages**

Implement `TavernPromptHits` with lorebook entries first and mode entries second:

```kotlin
@Composable
fun TavernPromptHits(
    hits: List<PromptInjectionTrace>,
    modifier: Modifier = Modifier,
) {
    val ordered = remember(hits) {
        hits.sortedWith(
            compareBy<PromptInjectionTrace>(
                { it.sourceType == PromptInjectionSourceType.MODE },
                { it.lorebookName.orEmpty() },
                { -it.priority },
            )
        )
    }
    if (ordered.isEmpty()) {
        TavernPromptEmptyState(title = stringResource(R.string.tavern_prompt_console_no_hits))
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(ordered, key = { it.injectionId.toString() }) { hit ->
            var expanded by rememberSaveable(hit.injectionId.toString()) { mutableStateOf(false) }
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { expanded = !expanded },
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        hit.lorebookName ?: hit.injectionName.ifBlank { hit.sourceType.name },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text("${hit.position} · ${hit.role} · priority ${hit.priority} · depth ${hit.injectDepth}")
                    hit.match?.let { match ->
                        Text("${match.type} · scan ${match.scannedMessageIds.size}/${match.scanDepth}")
                        if (match.matchedTerms.isNotEmpty()) {
                            Text(match.matchedTerms.joinToString(", "))
                        }
                    }
                    Text(stringResource(R.string.tavern_prompt_console_approx, hit.approximateTokens))
                    if (expanded) {
                        HorizontalDivider()
                        Text(hit.content)
                    }
                }
            }
        }
    }
}
```

Implement `TavernPromptMessages` and its local part renderer:

```kotlin
@Composable
fun TavernPromptMessages(
    messages: List<PromptTraceMessage>,
    onCopyAll: () -> Unit,
    onCopyMessage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            FilledTonalButton(onClick = onCopyAll) {
                Text(stringResource(R.string.tavern_prompt_console_copy_all))
            }
        }
        items(messages.sortedBy { it.index }, key = { it.id.toString() }) { message ->
            var expanded by rememberSaveable(message.id.toString()) { mutableStateOf(false) }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${message.index + 1}. ${message.role}", style = MaterialTheme.typography.titleSmall)
                            message.name?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                            Text(stringResource(R.string.tavern_prompt_console_approx, message.approximateTokens))
                        }
                        IconButton(onClick = { onCopyMessage(message.index) }) {
                            Icon(HugeIcons.Copy01, stringResource(R.string.tavern_prompt_console_copy_message))
                        }
                    }
                    val visibleParts = if (expanded) message.parts else message.parts.take(2)
                    visibleParts.forEach { part -> Text(promptTracePartText(part)) }
                    if (message.parts.size > 2 || message.characterCount > 800) {
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(if (expanded) "Collapse" else "Expand")
                        }
                    }
                }
            }
        }
    }
}

private fun promptTracePartText(part: PromptTracePart): String = when (part) {
    is PromptTracePart.Text -> part.text
    is PromptTracePart.Reasoning -> "[Reasoning]\n${part.text}"
    is PromptTracePart.Attachment -> buildString {
        append("[${part.value.kind}] ")
        append(part.value.displayName ?: part.value.uri ?: part.value.mimeType ?: "binary reference")
        part.value.byteLength?.let { append(" ($it bytes)") }
        part.value.sha256?.let { append(" sha256=$it") }
    }
    is PromptTracePart.Tool -> buildString {
        appendLine("[Tool ${part.toolName} / ${part.approvalState}]")
        appendLine("Input: ${part.input.preview}")
        part.outputText?.let { appendLine("Output: ${it.preview}") }
        part.outputAttachments.forEach { attachment ->
            appendLine(
                "[Tool output ${attachment.kind}] " +
                    (attachment.displayName ?: attachment.uri ?: attachment.mimeType ?: "binary reference")
            )
        }
    }.trimEnd()
}
```

- [ ] **Step 10: Handle empty, malformed, cancelled, and A2 states**

In `TavernPromptConsoleContent`:

```kotlin
when {
    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
    state.traces.isEmpty() -> TavernPromptEmptyState(
        title = stringResource(R.string.tavern_prompt_console_no_traces)
    )
    state.selectedTab == TavernPromptConsoleTab.PREVIEW -> {
        TavernPromptEmptyState(
            title = stringResource(R.string.tavern_prompt_console_preview_a2),
            body = stringResource(R.string.tavern_prompt_console_preview_a2_body),
        )
    }
    state.selectedTrace is PromptTraceReadResult.Unavailable -> {
        TavernPromptEmptyState(
            title = stringResource(R.string.tavern_prompt_console_payload_unavailable)
        )
    }
    else -> {
        val record = (state.selectedTrace as PromptTraceReadResult.Available).record
        when (state.selectedTab) {
            TavernPromptConsoleTab.OVERVIEW -> TavernPromptOverview(record)
            TavernPromptConsoleTab.HITS -> TavernPromptHits(record.payload.injectionHits)
            TavernPromptConsoleTab.SENT_MESSAGES -> TavernPromptMessages(
                messages = record.payload.finalMessages,
                onCopyAll = onCopyAll,
                onCopyMessage = onCopyMessage,
            )
            TavernPromptConsoleTab.PREVIEW -> Unit
        }
    }
}

@Composable
internal fun TavernPromptEmptyState(
    title: String,
    body: String? = null,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            body?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
```

For a valid `CANCELLED` trace with no response ID, show the cancelled string in the header while still allowing its prepared payload to be inspected.

- [ ] **Step 11: Run Compose tests and compile**

Run:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.pages.tavern.console.TavernPromptConsoleEntryTest,me.rerere.rikkahub.ui.pages.tavern.console.TavernPromptConsoleContentTest" --console=plain
.\gradlew.bat :app:compileDebugKotlin -x :web:buildWebUi --console=plain
```

Expected: PASS.

- [ ] **Step 12: Commit**

```powershell
git add app/src/main/java/me/rerere/rikkahub/ui/pages/tavern/console app/src/main/java/me/rerere/rikkahub/RouteActivity.kt app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml app/src/androidTest/java/me/rerere/rikkahub/ui/pages/tavern/console
git commit -m "feat: add tavern prompt trace console"
```

---

### Task 10: Prove multi-step, cancellation, failure, branch retention, and final app behavior

**Files:**
- Extend: `app/src/androidTest/java/me/rerere/rikkahub/data/ai/GenerationHandlerPromptTraceTest.kt`
- Extend: `app/src/androidTest/java/me/rerere/rikkahub/data/repository/PromptTraceRepositoryTest.kt`
- Create: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/tavern/console/TavernPromptConsoleFlowTest.kt`
- Create: `app/src/androidTest/java/me/rerere/rikkahub/service/PromptTraceConversationPersistenceTest.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/service/PromptTraceConversationPersistence.kt`
- Update: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
- Update: `docs/superpowers/plans/2026-07-17-tavern-prompt-trace-console-plan.md`

**Interfaces:**
- Consumes: complete A1 implementation.
- Produces: evidence that all success criteria pass without changing chat generation.

- [x] **Step 1: Add a two-call tool-loop provider test**

Extend the fake provider so call 1 returns an assistant `UIMessagePart.Tool` and call 2 returns final text. Supply a real test `Tool` whose execute callback returns `UIMessagePart.Text("tool result")`.

Assert:

```kotlin
val traces = repository.observeConversation(conversationId).first()
assertEquals(2, traces.size)
val available = traces.map { (it as PromptTraceReadResult.Available).record }
assertEquals(listOf(1, 0), available.map { it.payload.metadata.providerStepIndex })
assertTrue(available.all { it.payload.metadata.status == PromptTraceStatus.COMPLETED })
assertTrue(available[0].payload.finalMessages.any { message ->
    message.parts.filterIsInstance<PromptTracePart.Tool>().any { it.outputText?.preview?.contains("tool result") == true }
})
```

- [x] **Step 2: Add cancellation-before-binding and cancellation-after-binding tests**

Use `runBlocking`, launch collection, and a fake provider Flow:

- before-binding provider delays before first emit;
- after-binding provider emits one assistant chunk, then delays;
- cancel and join the collection job;
- assert status `CANCELLED`;
- assert response ID is null before binding and non-null after binding;
- assert no chat-generation error conversion occurs.

```kotlin
@Test
fun cancellationBeforeFirstChunkKeepsAnchorWithoutResponseBinding() = runBlocking {
    val harness = createHarness(provider = DelayedProvider(emitBeforeDelay = false))
    val job = launch {
        harness.generate(promptTraceSeed = harness.seed).collect()
    }
    delay(100)
    job.cancelAndJoin()

    val record = harness.repository.observeConversation(harness.conversationId)
        .first()
        .single() as PromptTraceReadResult.Available
    assertEquals(PromptTraceStatus.CANCELLED, record.record.payload.metadata.status)
    assertNull(record.record.payload.metadata.responseMessageId)
    assertEquals(harness.seed.requestAnchorMessageId, record.record.payload.metadata.requestAnchorMessageId)
}

@Test
fun cancellationAfterFirstChunkKeepsBoundResponse() = runBlocking {
    val harness = createHarness(provider = DelayedProvider(emitBeforeDelay = true))
    val job = launch {
        harness.generate(promptTraceSeed = harness.seed).collect()
    }
    delay(100)
    job.cancelAndJoin()

    val record = harness.repository.observeConversation(harness.conversationId)
        .first()
        .single() as PromptTraceReadResult.Available
    assertEquals(PromptTraceStatus.CANCELLED, record.record.payload.metadata.status)
    assertNotNull(record.record.payload.metadata.responseMessageId)
}
```

`DelayedProvider` emits a single assistant `MessageChunk` before its long delay only
when `emitBeforeDelay == true`; otherwise it delays before any emit.

- [x] **Step 3: Add provider failure and trace-store failure tests**

Provider failure:

```kotlin
override suspend fun streamText(
    providerSetting: ProviderSetting.OpenAI,
    messages: List<UIMessage>,
    params: TextGenerationParams,
): Flow<MessageChunk> = flow {
    throw IllegalStateException("authorization=secret")
}
```

Assert one `FAILED` trace exists, prepared final messages remain readable, and `errorSummary` contains `[redacted]`.

Trace-store failure:

- build `GenerationHandler` with a `PromptTraceSessionFactory` that creates a session backed by a throwing store;
- use a successful provider;
- assert generated chunks are identical to a run with tracing disabled.

- [x] **Step 4: Add exact branch and cleanup tests**

Repository/flow tests must cover:

1. Two response branches in one `MessageNode` map to distinct trace IDs.
2. Selecting another branch changes default selection but deletes nothing.
3. Deleting one alternative removes only its bound trace.
4. Regenerating from an earlier user message removes traces bound to the truncated tail.
5. An unbound failed/cancelled trace is removed only when its anchor is removed.
6. Forking a conversation creates no rows for the new conversation.
7. The 21st call removes the oldest row.

```kotlin
@Test
fun branchSelectionDoesNotDeleteAndBranchRemovalDeletesOnlyBoundTrace() = runBlocking {
    val conversationId = insertConversation(Uuid.random().toString())
    val responseA = Uuid.random()
    val responseB = Uuid.random()
    val traceA = Uuid.random()
    val traceB = Uuid.random()
    dao.insert(traceEntity(traceA.toString(), conversationId, responseMessageId = responseA.toString()))
    dao.insert(traceEntity(traceB.toString(), conversationId, responseMessageId = responseB.toString(), createdAt = 2L))

    assertEquals(2, repository.observeConversation(Uuid.parse(conversationId)).first().size)
    repository.deleteForRemovedMessages(Uuid.parse(conversationId), setOf(responseA))

    val remaining = repository.observeConversation(Uuid.parse(conversationId)).first()
    assertEquals(listOf(traceB), remaining.map { it.traceId })
}

@Test
fun unboundAttemptFollowsAnchorAndForkStartsWithoutTraces() = runBlocking {
    val sourceConversationId = insertConversation(Uuid.random().toString())
    val forkConversationId = insertConversation(Uuid.random().toString())
    val anchor = Uuid.random()
    dao.insert(
        traceEntity(
            id = Uuid.random().toString(),
            conversationId = sourceConversationId,
            requestAnchorMessageId = anchor.toString(),
            responseMessageId = null,
        )
    )

    assertTrue(repository.observeConversation(Uuid.parse(forkConversationId)).first().isEmpty())
    repository.deleteForRemovedMessages(Uuid.parse(sourceConversationId), setOf(anchor))
    assertTrue(repository.observeConversation(Uuid.parse(sourceConversationId)).first().isEmpty())
}
```

- [x] **Step 5: Add a navigation/content instrumentation flow**

`TavernPromptConsoleFlowTest` must:

- render an eligible Tavern chat top bar;
- click the `Cards02` entry callback;
- render console state for the same conversation ID;
- verify branch-default selection;
- switch Overview → Hits → Sent Messages → Preview;
- switch historical traces without changing `MessageNode.selectIndex`;
- copy one message and a full trace;
- clear after confirmation;
- verify chat messages are unchanged.

```kotlin
@Test
fun consoleTabsHistoryCopyAndClearDoNotMutateConversationBranch() {
    val responseId = Uuid.random()
    val trace = availableTrace(responseId)
    val originalSelectIndex = 1
    var selectedTab by mutableStateOf(TavernPromptConsoleTab.OVERVIEW)
    var selectedTraceId by mutableStateOf<Uuid?>(trace.traceId)
    var copiedMessages = 0
    var cleared = 0
    val context = InstrumentationRegistry.getInstrumentation().targetContext

    composeRule.setContent {
        TavernPromptConsoleContent(
            state = TavernPromptConsoleUiState(
                loading = false,
                conversationTitle = "Tavern test",
                assistantName = "Character",
                traces = listOf(trace),
                selectedTraceId = selectedTraceId,
                selectedTrace = trace,
                selectedTab = selectedTab,
                selectedBranchHasTrace = true,
            ),
            onBack = {},
            onSelectTrace = { selectedTraceId = it },
            onSelectTab = { selectedTab = it },
            onCopyAll = { copiedMessages++ },
            onCopyMessage = { copiedMessages++ },
            onClear = { cleared++ },
        )
    }

    composeRule
        .onNodeWithText(context.getString(R.string.tavern_prompt_console_messages))
        .performClick()
    composeRule
        .onNodeWithContentDescription(context.getString(R.string.tavern_prompt_console_copy_message))
        .performClick()
    composeRule
        .onNodeWithText(context.getString(R.string.tavern_prompt_console_preview))
        .performClick()
    composeRule
        .onNodeWithText(context.getString(R.string.tavern_prompt_console_preview_a2))
        .assertExists()
    assertEquals(1, copiedMessages)
    assertEquals(0, cleared)
    assertEquals(1, originalSelectIndex)
}

private fun availableTrace(responseId: Uuid): PromptTraceReadResult.Available {
    val metadata = PromptTraceMetadata(
        conversationId = Uuid.random(),
        assistantId = Uuid.random(),
        modelId = Uuid.random(),
        isGroup = false,
        providerStepIndex = 0,
        responseMessageId = responseId,
        startedAtEpochMs = 1L,
        status = PromptTraceStatus.COMPLETED,
    )
    val message = PromptTraceMessage(
        id = responseId,
        index = 0,
        role = MessageRole.ASSISTANT,
        parts = listOf(PromptTracePart.Text("reply")),
        characterCount = 5,
        approximateTokens = 2,
    )
    return PromptTraceReadResult.Available(
        PromptTraceRecord(
            traceId = Uuid.random(),
            payload = PromptTracePayload(
                metadata = metadata,
                sections = emptyList(),
                injectionHits = emptyList(),
                finalMessages = listOf(message),
            ),
        )
    )
}
```

- [x] **Step 6: Run all focused JVM tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*PromptTrace*" --tests "*PromptInjectionTransformerTest" --tests "*GroupContextBuilderTest" --console=plain
```

Expected: PASS.

- [x] **Step 7: Run all focused instrumentation tests**

Run:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.data.db.migrations.Migration_27_28_Test,me.rerere.rikkahub.data.db.dao.PromptTraceDAOTest,me.rerere.rikkahub.data.repository.PromptTraceRepositoryTest,me.rerere.rikkahub.data.ai.GenerationHandlerPromptTraceTest,me.rerere.rikkahub.service.PromptTraceConversationPersistenceTest,me.rerere.rikkahub.ui.pages.tavern.console.TavernPromptConsoleEntryTest,me.rerere.rikkahub.ui.pages.tavern.console.TavernPromptConsoleContentTest,me.rerere.rikkahub.ui.pages.tavern.console.TavernPromptConsoleFlowTest" --console=plain
```

Expected: PASS.

- [x] **Step 8: Run full unit tests, lint, and Debug assembly**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:lintDebug --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
```

Expected:
- JVM suite PASS.
- Lint has no new fatal issue from A1.
- Debug APK builds successfully.

Execution note: JVM and assembly passed. Lint completed with the repository baseline of 101 errors and 289 warnings; the first failure is `local.properties` `PropertyEscape`, and none of the three Task 10 test files appears in the lint findings.

- [ ] **Step 9: Install and execute the emulator smoke matrix**

Run:

```powershell
adb -s emulator-5554 install -r app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5554 shell am force-stop me.rerere.rikkahub.debug
adb -s emulator-5554 shell monkey -p me.rerere.rikkahub.debug -c android.intent.category.LAUNCHER 1
```

Manually verify and record the observed result beside each checkbox:

Execution note: installed `app-x86_64-debug.apk` because this build emits ABI-split APKs rather than `app-debug.apk`. Launch, process health, crash buffer, and non-Tavern top-bar visibility were exercised. The installed data set contains only the default non-Tavern assistant and no configured provider/Tavern smoke fixture. No provider request was generated, so trace non-creation was not established and all twelve live request scenarios remain open.


- [ ] Tavern solo request shows exact provider-bound semantic messages.
- [ ] Tavern group request shows the actual speaker and layered context.
- [ ] Constant, keyword, and regex lorebook hits match the injected output.
- [ ] Alternative regenerated reply has a distinct bound trace.
- [ ] Tool-assisted run shows one trace per provider call.
- [ ] Cancellation before and after first response produces the correct status.
- [ ] Provider failure leaves a readable prepared trace and sanitized error.
- [ ] App restart restores traces.
- [ ] Branch deletion and conversation deletion remove the expected rows.
- [ ] The 21st call removes the oldest trace.
- [ ] Preview remains an A2 state and sends no request.
- [ ] Non-Tavern chat has no top-bar entry and creates no trace. Top-bar absence was observed, but no provider request was generated; trace non-creation remains open.

- [ ] **Step 10: Inspect the database for prohibited content**

Stop the app, then stream the database and WAL files directly from the debug
application sandbox:

```powershell
New-Item -ItemType Directory -Force .\build\prompt-trace-db | Out-Null
adb -s emulator-5554 shell am force-stop me.rerere.rikkahub.debug
cmd /c "adb -s emulator-5554 exec-out run-as me.rerere.rikkahub.debug cat databases/rikka_hub > build\prompt-trace-db\rikka_hub"
cmd /c "adb -s emulator-5554 exec-out run-as me.rerere.rikkahub.debug cat databases/rikka_hub-wal > build\prompt-trace-db\rikka_hub-wal"
cmd /c "adb -s emulator-5554 exec-out run-as me.rerere.rikkahub.debug cat databases/rikka_hub-shm > build\prompt-trace-db\rikka_hub-shm"
```

Query with a local SQLite client or Android Studio Database Inspector and verify:

- no `data:*;base64,` body in `payload_json`;
- no URL query token from the smoke attachment;
- no provider API key/header/custom-body value;
- no opaque reasoning signature;
- row count per tested conversation is at most 20.

Execution note: the streamed debug database contained the `prompt_trace` table with zero rows. This confirms schema presence only; it is not evidence for persisted-row sanitization or 20-row retention. A populated live database fixture is still required for this step.

- [x] **Step 11: Update the plan’s execution record**

At the bottom of this file, append the heading below, then append the real output of
`git log --format="- %h %s" --reverse 4d21c10e..HEAD` under “Implementation commits”.
After all gates pass, append the fixed verification lines shown below:

```markdown
## Execution Record

### Implementation commits

### Verification

- Focused JVM tests: PASS
- Focused instrumentation tests: PASS
- Full JVM tests: PASS
- Lint: completed; any pre-existing findings are recorded in the execution worklog
- Debug assembly: PASS
- Emulator: `emulator-5554`
- Manual smoke: PASS with the twelve matrix results above
- Database sanitization inspection: PASS only with populated trace rows
```

- [x] **Step 12: Final commit**

```powershell
git add app/src/androidTest/java/me/rerere/rikkahub docs/superpowers/plans/2026-07-17-tavern-prompt-trace-console-plan.md
git commit -m "test: verify tavern prompt trace console"
```

---

## Completion Gate

Do not declare Phase A1 complete until all of the following are true:

- [x] The shared eligibility helper controls both top-bar visibility and trace creation.
- [x] The exact final semantic message list is captured immediately before the provider adapter call.
- [x] Injection diagnostics come from the same one-pass selection result that drives actual injection.
- [x] Provider prompt usage updates the trace bound to the generated response ID.
- [x] Every real provider invocation in a tool loop creates a separate trace.
- [x] Prepared, streaming, completed, cancelled, and failed states are covered.
- [x] Trace-store failures leave generated chunks and chat error handling unchanged.
- [x] Room migration 27→28 preserves existing conversations/message branches.
- [ ] Retention, branch deletion, tail truncation, clear, and conversation cascade behave as specified. Automated fixtures cover these paths; populated emulator evidence for retention remains open.
- [ ] Base64 bodies, credential metadata, query strings, and provider-private metadata are absent from persisted/copied output. Automated sanitizer fixtures pass; populated emulator database inspection remains open.
- [x] The full-screen console handles no-data, historical pre-trace, branch-missing, cancelled, malformed, and A2 Preview states.
- [ ] Tavern solo, eligible group, and non-Tavern cases pass automated and emulator verification.

## Execution Record

### Implementation commits

- 6281fd7c docs: plan tavern prompt trace console
- 21e5ce54 feat: define tavern prompt trace contract
- 9487784a feat: sanitize and format prompt traces
- 21c38255 fix: harden prompt trace sanitization
- eea50614 fix: redact response cookies in prompt traces
- 1fb1d629 refactor: expose prompt injection trace provenance
- 38372b3a fix: isolate prompt injection trace recording
- 37c244f1 fix: preserve cancellation during prompt tracing
- 5b355673 feat: persist tavern prompt traces
- 913d55ee feat: add prompt trace lifecycle repository
- 72ab39be fix: serialize prompt trace persistence
- 517b60b5 fix: persist final prompt trace usage
- 12c97e1f feat: trace provider-bound prompt calls
- 6d0a518d fix: preserve tool prompt request spacing
- 3ed08a2c feat: trace eligible tavern conversations
- 4f799237 fix: scope prompt trace cleanup to removals
- 6b520d4e fix: clean traces for filtered messages
- b622ff79 feat: add tavern prompt console state
- 8d087922 feat: add tavern prompt trace console
- 33c69edf fix: harden tavern prompt console previews

The Task 10 final commit contains this execution record, so its self-referential hash is intentionally omitted.

### Verification

- Focused JVM tests: PASS
- Group mode and sanitization JVM matrix: PASS, 57 tests
- Focused instrumentation tests: PASS, 41 tests
- Full JVM tests: PASS
- Full instrumentation tests: PASS, 54 tests
- Lint: completed with the repository baseline of 101 errors and 289 warnings; no Task 10 test-file finding
- Debug assembly: PASS
- Emulator: `emulator-5554`; ABI-split APK installed and launched without a crash-buffer entry
- Manual smoke: top-bar absence observed for the default non-Tavern assistant; all twelve request-dependent live cases remain open
- Database sanitization inspection: OPEN; the installed database had zero trace rows and provides no populated-row evidence

### Task 10 review follow-up

- Replaced the local-state console flow with a real in-memory Room repository and `TavernPromptConsoleVM` flow through the production entry/content callbacks.
- Extracted regeneration/fork conversation building and persistence cleanup wiring from `ChatService`, then exercised it with real `PromptTraceRepository` rows.
- Removed direct-DAO regeneration/fork assertions that did not execute production wiring.
- Review TDD: RED at missing production seams; GREEN 3/3 after extraction and integration.
- Focused JVM: PASS.
- Focused instrumentation: PASS, 41/41.
- Full JVM: PASS.
- Full instrumentation: PASS, 54/54.
- Debug assembly: PASS.
- Live non-Tavern trace non-creation and populated database sanitization/retention remain open; zero rows are not counted as evidence.
