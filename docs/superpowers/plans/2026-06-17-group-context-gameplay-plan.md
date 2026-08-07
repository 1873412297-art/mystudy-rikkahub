# Group Context Gameplay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a richer group-chat context system where each character can have a private viewpoint, relationship state, layered memory, and motivation-based turn selection while keeping existing group modes usable.

**Architecture:** Extract group context construction out of `ChatService` into focused `service/group` units. Add a small persistent group runtime state to `Conversation`, then build a layered prompt context per speaking member from public history, private notes, relationships, scene state, and mention-relevant history. Keep API compatibility by preserving the existing OpenAI/Gemini-safe message rewrite as a final transport step, not as stored chat state.

**Tech Stack:** Kotlin, kotlinx.serialization, Jetpack Compose, JUnit, existing `UIMessage`/`Conversation`/`Assistant` models, existing provider abstraction in `ai`.

---

## Current State

The current group implementation already supports:

- Group assistant type via `Assistant.assistantType`.
- Per-member source assistant, display name, avatar, model override, enable flag.
- Per-member `ContextFilter` with `ALL`, `SELF`, `MEMBER_LIST`, `DIRECTED`, excluded members, mention keywords, and `maxMessages`.
- Turn taking modes: manual, auto round-robin, auto moderator.
- API rewrite in `ChatService.applyGroupApiRewrite()` to avoid invalid message ordering for Gemini/OpenAI-compatible chat-completions providers.

The current limitations:

- Context is mostly message filtering plus recent history, not layered roleplay state.
- A character does not have private knowledge or relationship-specific memory.
- Moderator only chooses a speaker, not an intent.
- Context rewrite logic lives in `ChatService`, making new gameplay rules hard to test.
- There is no persistent per-conversation group runtime state for relationships, secrets, scene tension, or per-member notes.

## Target Gameplay

The first implementation should support four concrete gameplay improvements:

1. **Private Viewpoint**
   Each group member can receive private context that only they see.

2. **Relationship Matrix**
   The conversation can track how each member feels about another member or the user.

3. **Layered Context**
   Each generated reply receives a structured context block:
   public recent conversation, current scene, speaker private memory, relationship notes, mention-relevant snippets, and current speaking intent.

4. **Motivation-Based Speaker Selection**
   Auto moderator can return both the next speaker and a speaking intent such as `answer_user`, `challenge`, `comfort`, `hide_secret`, `change_topic`, `observe`, or `escalate_conflict`.

## Non-Goals For This Plan

- Do not build a full visual novel engine.
- Do not add complicated graph visualization in the first pass.
- Do not require vector search or embeddings.
- Do not break existing manual, round-robin, or moderator modes.
- Do not store API-rewritten messages in `Conversation.messageNodes`.

## File Structure

Create focused files instead of expanding `ChatService.kt` further:

- `app/src/main/java/me/rerere/rikkahub/service/group/GroupContextModels.kt`
  - Runtime state models and context build result models.

- `app/src/main/java/me/rerere/rikkahub/service/group/GroupContextBuilder.kt`
  - Builds layered messages for the selected speaker.

- `app/src/main/java/me/rerere/rikkahub/service/group/GroupMessageTransportRewrite.kt`
  - Performs API-safe rewrite after context building.

- `app/src/main/java/me/rerere/rikkahub/service/group/GroupSpeakerScorer.kt`
  - Scores speaker candidates locally before optional moderator model call.

- `app/src/main/java/me/rerere/rikkahub/service/group/GroupRuntimeStateUpdater.kt`
  - Updates relationship notes and scene state after a reply.

- `app/src/test/java/me/rerere/rikkahub/service/group/GroupContextBuilderTest.kt`
  - Unit tests for layered context construction.

- `app/src/test/java/me/rerere/rikkahub/service/group/GroupMessageTransportRewriteTest.kt`
  - Unit tests proving API rewrite does not mutate persisted messages.

- `app/src/test/java/me/rerere/rikkahub/service/group/GroupSpeakerScorerTest.kt`
  - Unit tests for scoring and intent selection.

- Modify `app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt`
  - Add persistent `groupRuntimeState`.

- Modify `app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt`
  - Add lightweight group context options.

- Modify `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
  - Delegate group context building and transport rewrite to the new files.

- Modify `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantGroupMembersPage.kt`
  - Add simple controls for context depth and gameplay toggles.

---

### Task 1: Extract API Transport Rewrite Into A Testable Unit

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/service/group/GroupMessageTransportRewrite.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/service/group/GroupMessageTransportRewriteTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`

- [x] **Step 1: Write tests for API rewrite behavior**

Create `app/src/test/java/me/rerere/rikkahub/service/group/GroupMessageTransportRewriteTest.kt`:

```kotlin
package me.rerere.rikkahub.service.group

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class GroupMessageTransportRewriteTest {
    private val speakerA = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val speakerB = Uuid.parse("00000000-0000-0000-0000-000000000002")
    private val sourceAssistantId = Uuid.parse("00000000-0000-0000-0000-000000000010")

    private val group = Assistant(
        assistantType = AssistantType.GROUP,
        groupMembers = listOf(
            GroupMember(id = speakerA, assistantId = sourceAssistantId, displayName = "甲"),
            GroupMember(id = speakerB, assistantId = sourceAssistantId, displayName = "乙"),
        ),
    )

    @Test
    fun `other member assistant message becomes user transport message with speaker prefix`() {
        val original = UIMessage(
            role = MessageRole.ASSISTANT,
            memberId = speakerB,
            parts = listOf(UIMessagePart.Text("我不同意。")),
        )

        val rewritten = listOf(original).rewriteGroupMessagesForTransport(
            groupAssistant = group,
            effectiveMemberId = speakerA,
        )

        assertEquals(MessageRole.USER, rewritten.single().role)
        assertEquals("[乙] 我不同意。", rewritten.single().toText())
        assertNull(rewritten.single().name)
        assertEquals("persisted object must stay unchanged", MessageRole.ASSISTANT, original.role)
        assertEquals("我不同意。", original.toText())
    }

    @Test
    fun `real user message gets user speaker prefix for model only`() {
        val original = UIMessage.user("你好")

        val rewritten = listOf(original).rewriteGroupMessagesForTransport(
            groupAssistant = group,
            effectiveMemberId = speakerA,
        )

        assertEquals(MessageRole.USER, rewritten.single().role)
        assertEquals("[User] 你好", rewritten.single().toText())
        assertEquals("你好", original.toText())
    }

    @Test
    fun `current speaker member keeps assistant role and gets name`() {
        val original = UIMessage(
            role = MessageRole.ASSISTANT,
            memberId = speakerA,
            parts = listOf(UIMessagePart.Text("轮到我了。")),
        )

        val rewritten = listOf(original).rewriteGroupMessagesForTransport(
            groupAssistant = group,
            effectiveMemberId = speakerA,
        )

        assertEquals(MessageRole.ASSISTANT, rewritten.single().role)
        assertEquals("甲", rewritten.single().name)
    }

    @Test
    fun `assistant-ending transport appends empty user message`() {
        val original = UIMessage(
            role = MessageRole.ASSISTANT,
            memberId = speakerA,
            parts = listOf(UIMessagePart.Text("上一句。")),
        )

        val rewritten = listOf(original).rewriteGroupMessagesForTransport(
            groupAssistant = group,
            effectiveMemberId = speakerA,
        )

        assertEquals(2, rewritten.size)
        assertEquals(MessageRole.USER, rewritten.last().role)
        assertTrue(rewritten.last().toText().isBlank())
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew --no-daemon :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.group.GroupMessageTransportRewriteTest"
```

Expected result: compilation fails because `rewriteGroupMessagesForTransport` does not exist.

- [x] **Step 3: Implement transport rewrite**

Create `app/src/main/java/me/rerere/rikkahub/service/group/GroupMessageTransportRewrite.kt`:

```kotlin
package me.rerere.rikkahub.service.group

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import kotlin.uuid.Uuid

fun List<UIMessage>.rewriteGroupMessagesForTransport(
    groupAssistant: Assistant,
    effectiveMemberId: Uuid?,
): List<UIMessage> {
    if (groupAssistant.assistantType != AssistantType.GROUP || effectiveMemberId == null) return this
    val rewritten = map { message ->
        when {
            message.role == MessageRole.ASSISTANT &&
                message.memberId != null &&
                message.memberId != effectiveMemberId -> {
                val member = groupAssistant.groupMembers.find { it.id == message.memberId }
                val prefix = member?.displayName?.takeIf { it.isNotBlank() }?.let { "[$it] " }.orEmpty()
                message.copy(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text(prefix + message.toText())),
                    name = null,
                )
            }

            message.memberId != null -> {
                val member = groupAssistant.groupMembers.find { it.id == message.memberId }
                val memberName = member?.displayName?.takeIf { it.isNotBlank() }
                if (memberName != null && message.name != memberName) {
                    message.copy(name = memberName)
                } else {
                    message
                }
            }

            message.role == MessageRole.USER && message.memberId == null -> {
                message.copy(parts = listOf(UIMessagePart.Text("[User] " + message.toText())))
            }

            else -> message
        }
    }
    return if (rewritten.isNotEmpty() && rewritten.last().role == MessageRole.ASSISTANT) {
        rewritten + UIMessage.user("")
    } else {
        rewritten
    }
}
```

- [x] **Step 4: Replace `ChatService.applyGroupApiRewrite()` call**

Modify `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`:

```kotlin
import me.rerere.rikkahub.service.group.rewriteGroupMessagesForTransport
```

Replace:

```kotlin
val messagesForGeneration = visibleMessages.applyGroupApiRewrite(groupAssistant, effectiveMemberId)
```

With:

```kotlin
val messagesForGeneration = visibleMessages.rewriteGroupMessagesForTransport(groupAssistant, effectiveMemberId)
```

Remove the private `applyGroupApiRewrite()` function from `ChatService.kt` after tests pass.

- [x] **Step 5: Run tests**

Run:

```powershell
.\gradlew --no-daemon :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.group.GroupMessageTransportRewriteTest"
```

Expected result: all tests pass.

- [x] **Step 6: Commit**

```powershell
git add app/src/main/java/me/rerere/rikkahub/service/group/GroupMessageTransportRewrite.kt app/src/test/java/me/rerere/rikkahub/service/group/GroupMessageTransportRewriteTest.kt app/src/main/java/me/rerere/rikkahub/service/ChatService.kt
git commit -m "refactor: extract group transport rewrite"
```

---

### Task 2: Add Persistent Group Runtime State

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/service/group/GroupContextModels.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/service/group/GroupRuntimeStateTest.kt`

- [x] **Step 1: Write serialization tests**

Create `app/src/test/java/me/rerere/rikkahub/service/group/GroupRuntimeStateTest.kt`:

```kotlin
package me.rerere.rikkahub.service.group

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class GroupRuntimeStateTest {
    private val memberA = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val memberB = Uuid.parse("00000000-0000-0000-0000-000000000002")

    @Test
    fun `runtime state serializes private notes relationships and scene`() {
        val state = GroupRuntimeState(
            privateNotes = mapOf(memberA to "A knows the hidden door."),
            relationships = mapOf(
                GroupRelationshipKey(memberA, memberB) to GroupRelationshipState(
                    affinity = 2,
                    tension = 4,
                    note = "A distrusts B but listens carefully.",
                )
            ),
            scene = GroupSceneState(
                summary = "Night meeting in the shrine.",
                tension = 6,
                activeSecrets = listOf("The guest is not human."),
            ),
        )

        val json = Json.encodeToString(state)
        val decoded = Json.decodeFromString<GroupRuntimeState>(json)

        assertEquals("A knows the hidden door.", decoded.privateNotes[memberA])
        assertEquals(4, decoded.relationships[GroupRelationshipKey(memberA, memberB)]?.tension)
        assertTrue(decoded.scene.activeSecrets.contains("The guest is not human."))
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew --no-daemon :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.group.GroupRuntimeStateTest"
```

Expected result: compilation fails because `GroupRuntimeState` and related types do not exist.

- [x] **Step 3: Add runtime state models**

Create `app/src/main/java/me/rerere/rikkahub/service/group/GroupContextModels.kt`:

```kotlin
package me.rerere.rikkahub.service.group

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class GroupRuntimeState(
    val privateNotes: Map<Uuid, String> = emptyMap(),
    val relationships: Map<GroupRelationshipKey, GroupRelationshipState> = emptyMap(),
    val scene: GroupSceneState = GroupSceneState(),
)

@Serializable
data class GroupRelationshipKey(
    val fromMemberId: Uuid,
    val toMemberId: Uuid,
)

@Serializable
data class GroupRelationshipState(
    val affinity: Int = 0,
    val tension: Int = 0,
    val note: String = "",
)

@Serializable
data class GroupSceneState(
    val summary: String = "",
    val tension: Int = 0,
    val activeSecrets: List<String> = emptyList(),
)

@Serializable
data class GroupSpeakingIntent(
    val speakerId: Uuid,
    val intent: String,
    val reason: String,
)

data class GroupContextBuildInput(
    val visibleMessages: List<me.rerere.ai.ui.UIMessage>,
    val groupAssistant: me.rerere.rikkahub.data.model.Assistant,
    val effectiveMemberId: Uuid,
    val runtimeState: GroupRuntimeState,
    val speakingIntent: GroupSpeakingIntent? = null,
)

data class GroupContextBuildResult(
    val messages: List<me.rerere.ai.ui.UIMessage>,
    val debugSections: List<String>,
)
```

- [x] **Step 4: Add runtime state to `Conversation`**

Modify `app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt`:

```kotlin
import me.rerere.rikkahub.service.group.GroupRuntimeState
```

Add this property near existing group runtime fields:

```kotlin
val groupRuntimeState: GroupRuntimeState = GroupRuntimeState(),
```

The constructor section should include:

```kotlin
// Group assistant runtime state: private notes, relationship matrix, and scene summary.
val groupRuntimeState: GroupRuntimeState = GroupRuntimeState(),
val activeGroupMemberId: Uuid? = null,
val groupMemberQueue: List<Uuid> = emptyList(),
val groupMemberQueueIndex: Int = 0,
```

- [x] **Step 5: Run tests**

Run:

```powershell
.\gradlew --no-daemon :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.group.GroupRuntimeStateTest"
```

Expected result: tests pass.

- [x] **Step 6: Run a debug assemble**

Run:

```powershell
.\gradlew --no-daemon assembleDebug
```

Expected result: build succeeds. Existing conversations deserialize with default `GroupRuntimeState()` because the property has a default value.

- [x] **Step 7: Commit**

```powershell
git add app/src/main/java/me/rerere/rikkahub/service/group/GroupContextModels.kt app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt app/src/test/java/me/rerere/rikkahub/service/group/GroupRuntimeStateTest.kt
git commit -m "feat: add group runtime state"
```

---

### Task 3: Build Layered Per-Speaker Context

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/service/group/GroupContextBuilder.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/service/group/GroupContextBuilderTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`

- [x] **Step 1: Write layered context tests**

Create `app/src/test/java/me/rerere/rikkahub/service/group/GroupContextBuilderTest.kt`:

```kotlin
package me.rerere.rikkahub.service.group

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class GroupContextBuilderTest {
    private val speakerA = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val speakerB = Uuid.parse("00000000-0000-0000-0000-000000000002")
    private val sourceAssistantId = Uuid.parse("00000000-0000-0000-0000-000000000010")

    private val group = Assistant(
        assistantType = AssistantType.GROUP,
        groupMembers = listOf(
            GroupMember(id = speakerA, assistantId = sourceAssistantId, displayName = "甲"),
            GroupMember(id = speakerB, assistantId = sourceAssistantId, displayName = "乙"),
        ),
    )

    @Test
    fun `builder prepends private viewpoint system message`() {
        val input = GroupContextBuildInput(
            visibleMessages = listOf(UIMessage.user("你们怎么看？")),
            groupAssistant = group,
            effectiveMemberId = speakerA,
            runtimeState = GroupRuntimeState(
                privateNotes = mapOf(speakerA to "甲知道密门在佛堂后方。"),
                scene = GroupSceneState(summary = "众人夜谈。", tension = 5),
            ),
            speakingIntent = GroupSpeakingIntent(
                speakerId = speakerA,
                intent = "hide_secret",
                reason = "User asked about the shrine.",
            ),
        )

        val result = GroupContextBuilder().build(input)

        assertEquals(MessageRole.SYSTEM, result.messages.first().role)
        val system = result.messages.first().toText()
        assertTrue(system.contains("Private viewpoint for 甲"))
        assertTrue(system.contains("甲知道密门在佛堂后方。"))
        assertTrue(system.contains("Scene: 众人夜谈。"))
        assertTrue(system.contains("Speaking intent: hide_secret"))
        assertEquals("你们怎么看？", result.messages.last().toText())
    }

    @Test
    fun `builder includes relationship note for visible participants`() {
        val input = GroupContextBuildInput(
            visibleMessages = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    memberId = speakerB,
                    parts = listOf(UIMessagePart.Text("我怀疑这里有问题。")),
                )
            ),
            groupAssistant = group,
            effectiveMemberId = speakerA,
            runtimeState = GroupRuntimeState(
                relationships = mapOf(
                    GroupRelationshipKey(speakerA, speakerB) to GroupRelationshipState(
                        affinity = -1,
                        tension = 3,
                        note = "甲觉得乙敏锐但危险。",
                    )
                )
            ),
        )

        val result = GroupContextBuilder().build(input)

        val system = result.messages.first().toText()
        assertTrue(system.contains("Relationship notes"))
        assertTrue(system.contains("toward 乙"))
        assertTrue(system.contains("甲觉得乙敏锐但危险。"))
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew --no-daemon :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.group.GroupContextBuilderTest"
```

Expected result: compilation fails because `GroupContextBuilder` does not exist.

- [x] **Step 3: Implement `GroupContextBuilder`**

Create `app/src/main/java/me/rerere/rikkahub/service/group/GroupContextBuilder.kt`:

```kotlin
package me.rerere.rikkahub.service.group

import me.rerere.ai.ui.UIMessage

class GroupContextBuilder {
    fun build(input: GroupContextBuildInput): GroupContextBuildResult {
        val speaker = input.groupAssistant.groupMembers.find { it.id == input.effectiveMemberId }
        val speakerName = speaker?.displayName?.takeIf { it.isNotBlank() } ?: "Current speaker"
        val visibleMemberIds = input.visibleMessages.mapNotNull { it.memberId }.toSet()

        val system = buildString {
            appendLine("Private viewpoint for $speakerName")
            appendLine("Use this context as hidden roleplay state. Do not quote section labels directly.")
            appendLine()

            val privateNote = input.runtimeState.privateNotes[input.effectiveMemberId].orEmpty()
            if (privateNote.isNotBlank()) {
                appendLine("Private memory:")
                appendLine(privateNote)
                appendLine()
            }

            if (input.runtimeState.scene.summary.isNotBlank() || input.runtimeState.scene.activeSecrets.isNotEmpty()) {
                appendLine("Scene: ${input.runtimeState.scene.summary.ifBlank { "No scene summary." }}")
                appendLine("Scene tension: ${input.runtimeState.scene.tension}")
                if (input.runtimeState.scene.activeSecrets.isNotEmpty()) {
                    appendLine("Active secrets:")
                    input.runtimeState.scene.activeSecrets.forEach { appendLine("- $it") }
                }
                appendLine()
            }

            val relationships = input.runtimeState.relationships.filterKeys { key ->
                key.fromMemberId == input.effectiveMemberId && key.toMemberId in visibleMemberIds
            }
            if (relationships.isNotEmpty()) {
                appendLine("Relationship notes:")
                relationships.forEach { (key, state) ->
                    val target = input.groupAssistant.groupMembers.find { it.id == key.toMemberId }
                    val targetName = target?.displayName?.takeIf { it.isNotBlank() } ?: key.toMemberId.toString()
                    appendLine("- toward $targetName: affinity=${state.affinity}, tension=${state.tension}, note=${state.note}")
                }
                appendLine()
            }

            input.speakingIntent?.let { intent ->
                appendLine("Speaking intent: ${intent.intent}")
                appendLine("Intent reason: ${intent.reason}")
                appendLine()
            }
        }.trim()

        val messages = if (system.isBlank()) {
            input.visibleMessages
        } else {
            listOf(UIMessage.system(system)) + input.visibleMessages
        }
        return GroupContextBuildResult(
            messages = messages,
            debugSections = if (system.isBlank()) emptyList() else listOf(system),
        )
    }
}
```

- [x] **Step 4: Wire builder into `ChatService`**

Modify `ChatService.handleMessageComplete()` after `visibleMessages` is created:

```kotlin
val speakingIntent = effectiveMemberId?.let {
    GroupSpeakingIntent(
        speakerId = it,
        intent = "respond",
        reason = "Manual or existing turn-taking selected this speaker.",
    )
}
val layeredMessages = if (effectiveMemberId != null && groupAssistant.assistantType == AssistantType.GROUP) {
    GroupContextBuilder().build(
        GroupContextBuildInput(
            visibleMessages = visibleMessages,
            groupAssistant = groupAssistant,
            effectiveMemberId = effectiveMemberId,
            runtimeState = conversation.groupRuntimeState,
            speakingIntent = speakingIntent,
        )
    ).messages
} else {
    visibleMessages
}
val messagesForGeneration = layeredMessages.rewriteGroupMessagesForTransport(groupAssistant, effectiveMemberId)
```

Add imports:

```kotlin
import me.rerere.rikkahub.service.group.GroupContextBuildInput
import me.rerere.rikkahub.service.group.GroupContextBuilder
import me.rerere.rikkahub.service.group.GroupSpeakingIntent
```

- [x] **Step 5: Run tests**

Run:

```powershell
.\gradlew --no-daemon :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.group.GroupContextBuilderTest"
```

Expected result: tests pass.

- [x] **Step 6: Run assemble**

Run:

```powershell
.\gradlew --no-daemon assembleDebug
```

Expected result: build succeeds.

- [x] **Step 7: Commit**

```powershell
git add app/src/main/java/me/rerere/rikkahub/service/group/GroupContextBuilder.kt app/src/test/java/me/rerere/rikkahub/service/group/GroupContextBuilderTest.kt app/src/main/java/me/rerere/rikkahub/service/ChatService.kt
git commit -m "feat: build layered group context"
```

---

### Task 4: Add Motivation-Based Speaker Scoring

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/service/group/GroupSpeakerScorer.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/service/group/GroupSpeakerScorerTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`

- [x] **Step 1: Write scoring tests**

Create `app/src/test/java/me/rerere/rikkahub/service/group/GroupSpeakerScorerTest.kt`:

```kotlin
package me.rerere.rikkahub.service.group

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class GroupSpeakerScorerTest {
    private val memberA = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val memberB = Uuid.parse("00000000-0000-0000-0000-000000000002")
    private val sourceAssistantId = Uuid.parse("00000000-0000-0000-0000-000000000010")

    private val group = Assistant(
        assistantType = AssistantType.GROUP,
        groupMembers = listOf(
            GroupMember(id = memberA, assistantId = sourceAssistantId, displayName = "甲"),
            GroupMember(id = memberB, assistantId = sourceAssistantId, displayName = "乙"),
        ),
    )

    @Test
    fun `mentioned member receives highest score`() {
        val result = GroupSpeakerScorer().score(
            groupAssistant = group,
            messages = listOf(UIMessage.user("乙，你怎么看？")),
            runtimeState = GroupRuntimeState(),
            activeMemberId = memberA,
        )

        assertEquals(memberB, result.first().memberId)
        assertEquals("answer_user", result.first().intent)
        assertTrue(result.first().score > result.last().score)
    }

    @Test
    fun `high tension relationship increases challenge intent`() {
        val result = GroupSpeakerScorer().score(
            groupAssistant = group,
            messages = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    memberId = memberA,
                    parts = listOf(UIMessagePart.Text("我不相信你。")),
                )
            ),
            runtimeState = GroupRuntimeState(
                relationships = mapOf(
                    GroupRelationshipKey(memberB, memberA) to GroupRelationshipState(
                        affinity = -2,
                        tension = 8,
                        note = "乙认为甲在隐瞒。",
                    )
                )
            ),
            activeMemberId = memberA,
        )

        assertEquals(memberB, result.first().memberId)
        assertEquals("challenge", result.first().intent)
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew --no-daemon :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.group.GroupSpeakerScorerTest"
```

Expected result: compilation fails because `GroupSpeakerScorer` does not exist.

- [x] **Step 3: Implement scorer**

Create `app/src/main/java/me/rerere/rikkahub/service/group/GroupSpeakerScorer.kt`:

```kotlin
package me.rerere.rikkahub.service.group

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

data class GroupSpeakerScore(
    val memberId: Uuid,
    val score: Int,
    val intent: String,
    val reason: String,
)

class GroupSpeakerScorer {
    fun score(
        groupAssistant: Assistant,
        messages: List<UIMessage>,
        runtimeState: GroupRuntimeState,
        activeMemberId: Uuid?,
    ): List<GroupSpeakerScore> {
        val recentText = messages.takeLast(8).joinToString("\n") { it.toText() }
        val lastMemberId = messages.lastOrNull { it.memberId != null }?.memberId

        return groupAssistant.groupMembers
            .filter { it.enabled }
            .map { member ->
                val name = member.displayName
                val mentioned = name.isNotBlank() && recentText.contains(name, ignoreCase = true)
                val relationship = activeMemberId?.let { runtimeState.relationships[GroupRelationshipKey(member.id, it)] }
                val tension = relationship?.tension ?: 0
                val affinity = relationship?.affinity ?: 0
                val consecutivePenalty = if (member.id == lastMemberId) -3 else 0
                val mentionBoost = if (mentioned) 10 else 0
                val tensionBoost = if (tension >= 6) 5 else 0
                val affinityBoost = if (affinity >= 4) 2 else 0
                val score = mentionBoost + tensionBoost + affinityBoost + consecutivePenalty
                val intent = when {
                    mentioned -> "answer_user"
                    tension >= 6 -> "challenge"
                    affinity >= 4 -> "comfort"
                    else -> "respond"
                }
                val reason = when (intent) {
                    "answer_user" -> "The user or another speaker mentioned $name."
                    "challenge" -> "Relationship tension is high."
                    "comfort" -> "Relationship affinity is high."
                    else -> "Default participation."
                }
                GroupSpeakerScore(member.id, score, intent, reason)
            }
            .sortedWith(compareByDescending<GroupSpeakerScore> { it.score }.thenBy { it.memberId.toString() })
    }
}
```

- [x] **Step 4: Use scorer as moderator fallback**

Modify `ChatService.resolveNextSpeakerViaModerator()` fallback path:

```kotlin
val localScores = GroupSpeakerScorer().score(
    groupAssistant = groupAssistant,
    messages = conversation.currentMessages,
    runtimeState = conversation.groupRuntimeState,
    activeMemberId = conversation.activeGroupMemberId,
)
val localFallback = localScores.firstOrNull()?.memberId ?: getNextSpeakerRoundRobin(conversation)
```

Then replace fallback returns inside `resolveNextSpeakerViaModerator()`:

```kotlin
?: localFallback
```

In catch:

```kotlin
localFallback
```

Add import:

```kotlin
import me.rerere.rikkahub.service.group.GroupSpeakerScorer
```

- [x] **Step 5: Pass scorer intent into context builder**

In `handleMessageComplete()`, before building `speakingIntent`, compute:

```kotlin
val localSpeakerScore = if (effectiveMemberId != null && groupAssistant.assistantType == AssistantType.GROUP) {
    GroupSpeakerScorer().score(
        groupAssistant = groupAssistant,
        messages = conversation.currentMessages,
        runtimeState = conversation.groupRuntimeState,
        activeMemberId = conversation.activeGroupMemberId,
    ).firstOrNull { it.memberId == effectiveMemberId }
} else {
    null
}
val speakingIntent = effectiveMemberId?.let {
    GroupSpeakingIntent(
        speakerId = it,
        intent = localSpeakerScore?.intent ?: "respond",
        reason = localSpeakerScore?.reason ?: "Manual or existing turn-taking selected this speaker.",
    )
}
```

- [x] **Step 6: Run tests**

Run:

```powershell
.\gradlew --no-daemon :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.group.GroupSpeakerScorerTest"
```

Expected result: tests pass.

- [x] **Step 7: Run assemble**

Run:

```powershell
.\gradlew --no-daemon assembleDebug
```

Expected result: build succeeds.

- [x] **Step 8: Commit**

```powershell
git add app/src/main/java/me/rerere/rikkahub/service/group/GroupSpeakerScorer.kt app/src/test/java/me/rerere/rikkahub/service/group/GroupSpeakerScorerTest.kt app/src/main/java/me/rerere/rikkahub/service/ChatService.kt
git commit -m "feat: score group speaker motivation"
```

---

### Task 5: Add Runtime State Updater

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/service/group/GroupRuntimeStateUpdater.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/service/group/GroupRuntimeStateUpdaterTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`

- [x] **Step 1: Write update tests**

Create `app/src/test/java/me/rerere/rikkahub/service/group/GroupRuntimeStateUpdaterTest.kt`:

```kotlin
package me.rerere.rikkahub.service.group

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class GroupRuntimeStateUpdaterTest {
    private val memberA = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val memberB = Uuid.parse("00000000-0000-0000-0000-000000000002")
    private val sourceAssistantId = Uuid.parse("00000000-0000-0000-0000-000000000010")
    private val group = Assistant(
        assistantType = AssistantType.GROUP,
        groupMembers = listOf(
            GroupMember(id = memberA, assistantId = sourceAssistantId, displayName = "甲"),
            GroupMember(id = memberB, assistantId = sourceAssistantId, displayName = "乙"),
        ),
    )

    @Test
    fun `updates scene summary from latest group reply`() {
        val updated = GroupRuntimeStateUpdater().updateAfterReply(
            previous = GroupRuntimeState(),
            groupAssistant = group,
            messages = listOf(
                UIMessage.user("发生了什么？"),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    memberId = memberA,
                    parts = listOf(UIMessagePart.Text("甲低声说佛堂后方传来异响。")),
                ),
            ),
            speakerId = memberA,
        )

        assertTrue(updated.scene.summary.contains("佛堂后方传来异响"))
    }

    @Test
    fun `increases tension when reply contains conflict markers`() {
        val updated = GroupRuntimeStateUpdater().updateAfterReply(
            previous = GroupRuntimeState(),
            groupAssistant = group,
            messages = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    memberId = memberA,
                    parts = listOf(UIMessagePart.Text("我不相信乙说的话。")),
                ),
            ),
            speakerId = memberA,
        )

        assertEquals(1, updated.scene.tension)
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew --no-daemon :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.group.GroupRuntimeStateUpdaterTest"
```

Expected result: compilation fails because `GroupRuntimeStateUpdater` does not exist.

- [x] **Step 3: Implement deterministic updater**

Create `app/src/main/java/me/rerere/rikkahub/service/group/GroupRuntimeStateUpdater.kt`:

```kotlin
package me.rerere.rikkahub.service.group

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

class GroupRuntimeStateUpdater {
    fun updateAfterReply(
        previous: GroupRuntimeState,
        groupAssistant: Assistant,
        messages: List<UIMessage>,
        speakerId: Uuid,
    ): GroupRuntimeState {
        val latestText = messages.lastOrNull { it.memberId == speakerId }?.toText()?.trim().orEmpty()
        if (latestText.isBlank()) return previous

        val conflictMarkers = listOf("不相信", "怀疑", "反驳", "敌意", "危险", "背叛")
        val secretMarkers = listOf("秘密", "隐瞒", "不能说", "不要告诉", "藏")
        val tensionDelta = if (conflictMarkers.any { latestText.contains(it) }) 1 else 0
        val activeSecrets = if (secretMarkers.any { latestText.contains(it) }) {
            (previous.scene.activeSecrets + latestText.take(80)).distinct().takeLast(8)
        } else {
            previous.scene.activeSecrets
        }
        val speakerName = groupAssistant.groupMembers.find { it.id == speakerId }?.displayName?.ifBlank { null }
            ?: "角色"
        val summaryLine = "$speakerName: ${latestText.take(120)}"
        val newSummary = listOf(previous.scene.summary, summaryLine)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .takeLast(800)

        return previous.copy(
            scene = previous.scene.copy(
                summary = newSummary,
                tension = (previous.scene.tension + tensionDelta).coerceIn(0, 10),
                activeSecrets = activeSecrets,
            )
        )
    }
}
```

- [x] **Step 4: Update runtime state after completed group reply**

In `ChatService.handleMessageComplete()`, after `val finalConversation = getConversationFlow(conversationId).value`, add:

```kotlin
val conversationAfterRuntimeUpdate = if (
    groupAssistant.assistantType == AssistantType.GROUP &&
    effectiveMemberId != null
) {
    finalConversation.copy(
        groupRuntimeState = GroupRuntimeStateUpdater().updateAfterReply(
            previous = finalConversation.groupRuntimeState,
            groupAssistant = groupAssistant,
            messages = finalConversation.currentMessages,
            speakerId = effectiveMemberId,
        )
    )
} else {
    finalConversation
}
if (conversationAfterRuntimeUpdate !== finalConversation) {
    updateConversation(conversationId, conversationAfterRuntimeUpdate)
}
```

Use `conversationAfterRuntimeUpdate` for subsequent auto-chain calculations in the same method.

Add import:

```kotlin
import me.rerere.rikkahub.service.group.GroupRuntimeStateUpdater
```

- [x] **Step 5: Run tests**

Run:

```powershell
.\gradlew --no-daemon :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.group.GroupRuntimeStateUpdaterTest"
```

Expected result: tests pass.

- [x] **Step 6: Run assemble**

Run:

```powershell
.\gradlew --no-daemon assembleDebug
```

Expected result: build succeeds.

- [x] **Step 7: Commit**

```powershell
git add app/src/main/java/me/rerere/rikkahub/service/group/GroupRuntimeStateUpdater.kt app/src/test/java/me/rerere/rikkahub/service/group/GroupRuntimeStateUpdaterTest.kt app/src/main/java/me/rerere/rikkahub/service/ChatService.kt
git commit -m "feat: update group runtime scene state"
```

---

### Task 6: Add User-Facing Group Context Options

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantGroupMembersPage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantGroupMembersVM.kt`

- [x] **Step 1: Add options model**

Modify `Assistant.kt` near `GroupReplyOptions`:

```kotlin
@Serializable
data class GroupContextOptions(
    val enableLayeredContext: Boolean = true,
    val enablePrivateViewpoint: Boolean = true,
    val enableRelationshipNotes: Boolean = true,
    val enableSceneState: Boolean = true,
    val enableMotivationScoring: Boolean = true,
    val maxPrivateNoteChars: Int = 800,
    val maxSceneSummaryChars: Int = 800,
)
```

Add property to `Assistant`:

```kotlin
val groupContextOptions: GroupContextOptions = GroupContextOptions(),
```

- [x] **Step 2: Respect options in `GroupContextBuilder`**

Modify `GroupContextBuildInput`:

```kotlin
val contextOptions: me.rerere.rikkahub.data.model.GroupContextOptions =
    me.rerere.rikkahub.data.model.GroupContextOptions(),
```

In `GroupContextBuilder.build()`, wrap sections:

```kotlin
if (input.contextOptions.enablePrivateViewpoint) {
    val privateNote = input.runtimeState.privateNotes[input.effectiveMemberId].orEmpty()
    if (privateNote.isNotBlank()) {
        appendLine("Private memory:")
        appendLine(privateNote.take(input.contextOptions.maxPrivateNoteChars))
        appendLine()
    }
}
```

Apply the same pattern for relationship notes and scene state.

- [x] **Step 3: Pass options from `ChatService`**

When creating `GroupContextBuildInput`, add:

```kotlin
contextOptions = groupAssistant.groupContextOptions,
```

If `enableLayeredContext` is false, skip `GroupContextBuilder` and pass `visibleMessages` directly.

- [x] **Step 4: Add VM updater**

In `AssistantGroupMembersVM.kt`, add:

```kotlin
fun updateGroupContextOptions(options: GroupContextOptions) {
    val current = assistant.value ?: return
    updateAssistant(current.copy(groupContextOptions = options))
}
```

Import:

```kotlin
import me.rerere.rikkahub.data.model.GroupContextOptions
```

- [x] **Step 5: Add simple UI toggles**

In `AssistantGroupMembersPage.kt`, add a card below reply options:

```kotlin
val contextOptions = currentAssistant.groupContextOptions
ElevatedCard(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("群组上下文玩法", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("分层上下文", modifier = Modifier.weight(1f))
            Switch(
                checked = contextOptions.enableLayeredContext,
                onCheckedChange = {
                    vm.updateGroupContextOptions(contextOptions.copy(enableLayeredContext = it))
                }
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("私有视角", modifier = Modifier.weight(1f))
            Switch(
                checked = contextOptions.enablePrivateViewpoint,
                onCheckedChange = {
                    vm.updateGroupContextOptions(contextOptions.copy(enablePrivateViewpoint = it))
                }
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("关系备注", modifier = Modifier.weight(1f))
            Switch(
                checked = contextOptions.enableRelationshipNotes,
                onCheckedChange = {
                    vm.updateGroupContextOptions(contextOptions.copy(enableRelationshipNotes = it))
                }
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("场景状态", modifier = Modifier.weight(1f))
            Switch(
                checked = contextOptions.enableSceneState,
                onCheckedChange = {
                    vm.updateGroupContextOptions(contextOptions.copy(enableSceneState = it))
                }
            )
        }
    }
}
```

- [x] **Step 6: Run assemble**

Run:

```powershell
.\gradlew --no-daemon assembleDebug
```

Expected result: build succeeds.

- [x] **Step 7: Commit**

```powershell
git add app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantGroupMembersPage.kt app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantGroupMembersVM.kt app/src/main/java/me/rerere/rikkahub/service/group/GroupContextModels.kt app/src/main/java/me/rerere/rikkahub/service/group/GroupContextBuilder.kt app/src/main/java/me/rerere/rikkahub/service/ChatService.kt
git commit -m "feat: add group context gameplay options"
```

---

### Task 7: Add Runtime Debug Visibility

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageActions.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/ui/components/message/GroupContextDebugSheet.kt`

- [x] **Step 1: Add debug sheet composable**

Create `GroupContextDebugSheet.kt`:

```kotlin
package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.service.group.GroupRuntimeState

@Composable
fun GroupContextDebugSheet(
    runtimeState: GroupRuntimeState,
    onDismissRequest: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("群组运行状态")
            Spacer(Modifier.height(12.dp))
            Text("场景摘要")
            Text(runtimeState.scene.summary.ifBlank { "空" })
            Spacer(Modifier.height(12.dp))
            Text("场景紧张度：${runtimeState.scene.tension}")
            Spacer(Modifier.height(12.dp))
            Text("活跃秘密")
            Text(runtimeState.scene.activeSecrets.joinToString("\n").ifBlank { "空" })
            Spacer(Modifier.height(12.dp))
            Text("私有记忆数量：${runtimeState.privateNotes.size}")
            Text("关系记录数量：${runtimeState.relationships.size}")
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onDismissRequest) {
                Text("关闭")
            }
        }
    }
}
```

- [x] **Step 2: Add action entry point**

In `ChatMessageActions.kt`, add a new optional parameter:

```kotlin
runtimeState: me.rerere.rikkahub.service.group.GroupRuntimeState? = null,
```

Add state:

```kotlin
var showGroupContextDebug by remember { mutableStateOf(false) }
```

Add an action icon or text button inside the existing action row when `runtimeState != null`:

```kotlin
if (runtimeState != null && isGroupMode) {
    Text(
        text = "上下文",
        modifier = Modifier
            .clip(CircleShape)
            .clickable { showGroupContextDebug = true }
            .padding(8.dp),
        color = actionIconColor,
    )
}
```

At the end of the composable:

```kotlin
if (showGroupContextDebug && runtimeState != null) {
    GroupContextDebugSheet(
        runtimeState = runtimeState,
        onDismissRequest = { showGroupContextDebug = false },
    )
}
```

- [x] **Step 3: Pass runtime state from `ChatList` to `ChatMessage`**

In `ChatMessage.kt`, add an optional parameter to `ChatMessage`:

```kotlin
runtimeState: me.rerere.rikkahub.service.group.GroupRuntimeState? = null,
```

Pass it into `ChatMessageActionButtons`:

```kotlin
ChatMessageActionButtons(
    message = message,
    onRegenerate = onRegenerate,
    node = node,
    onUpdate = onUpdate,
    onOpenActionSheet = {
        showActionsSheet = true
    },
    onTranslate = onTranslate,
    onClearTranslation = onClearTranslation,
    assistant = assistant,
    settingsForGroup = fullSettings,
    runtimeState = runtimeState,
)
```

In `ChatList.kt`, pass the conversation runtime state at the existing `ChatMessage(...)` call site:

```kotlin
ChatMessage(
    node = node,
    model = node.currentMessage.modelId?.let(modelById::get),
    assistant = assistant,
    runtimeState = if (assistant?.assistantType == AssistantType.GROUP) {
        conversation.groupRuntimeState
    } else {
        null
    },
    loading = loading && index == lastMessageIndex,
    onRegenerate = { _ ->
        onRegenerate(node.currentMessage)
    },
    onEdit = {
        onEdit(node.currentMessage)
    },
    onFork = {
        onForkMessage(node.currentMessage)
    },
    onDelete = {
        onDelete(node.currentMessage)
    },
    onShare = {
        selecting = true
        selectedItems.clear()
        selectedItems.addAll(
            conversation.messageNodes.map { it.id }
                .subList(0, conversation.messageNodes.indexOf(node) + 1)
        )
    },
    onUpdate = {
        onUpdateMessage(it)
    },
    isFavorite = node.isFavorite,
    onToggleFavorite = {
        onToggleFavorite?.invoke(node)
    },
    onTranslate = onTranslate,
    onClearTranslation = onClearTranslation,
    onToolApproval = onToolApproval,
    onToolAnswer = onToolAnswer,
    lastMessage = index == lastMessageIndex,
)
```

If `AssistantType` is not already imported in `ChatList.kt`, add:

```kotlin
import me.rerere.rikkahub.data.model.AssistantType
```

Expected result: group chats show the `上下文` action entry on message actions; solo chats do not receive runtime state and keep the existing native action layout.

- [x] **Step 4: Run assemble**

Run:

```powershell
.\gradlew --no-daemon assembleDebug
```

Expected result: build succeeds; group chats can open the runtime debug sheet from the message action row.

- [x] **Step 5: Commit**

```powershell
git add app/src/main/java/me/rerere/rikkahub/ui/components/message/GroupContextDebugSheet.kt app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageActions.kt app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt
git commit -m "feat: add group context debug sheet"
```

---

### Task 8: Manual Compatibility Matrix

**Files:**
- Modify: `docs/superpowers/plans/2026-06-17-group-context-gameplay-plan.md`

- [x] **Step 1: Build and install**

Run:

```powershell
$env:Path = "C:\Users\18734\AppData\Roaming\npm;$env:Path"
.\gradlew --no-daemon assembleDebug
adb -s emulator-5554 install -r app\build\outputs\apk\debug\app-x86_64-debug.apk
```

Expected result:

```text
BUILD SUCCESSFUL
Success
```

- [x] **Step 2: Manual mode smoke test**

In the app:

1. Create or open a group assistant.
2. Set mode to manual.
3. Select two members.
4. Send `你们分别怎么看？`.

Expected:

- User message appears on the right.
- Member replies appear on the left.
- Visible message text does not show `[User]` or `[member]` prefixes.
- The second selected member can see the first selected member's reply through the transport rewrite.

- [x] **Step 3: Round-robin smoke test**

In the app:

1. Set group mode to auto round-robin.
2. Set max auto replies per user turn to `2`.
3. Send `继续讨论这个计划。`.

Expected:

- Two member replies are generated.
- Consecutive same speaker is avoided when `allowConsecutiveSameSpeaker=false`.
- Replies remain on the left.

- [x] **Step 4: Moderator smoke test**

In the app:

1. Set group mode to auto moderator.
2. Send an unaddressed message so the moderator path executes. Explicit member names are routed deterministically before moderator selection.

Expected:

- The moderator selects a member and the chain stops through `STOP` or the configured cap.
- If moderator output is invalid, the local scorer fallback picks a member instead of aborting (covered by `GroupModeratorDecisionTest`).

- [x] **Step 5: Runtime state smoke test**

In the app:

1. Send a message that creates conflict, such as `我不相信你们其中一个人。`.
2. Trigger a member reply containing conflict language.
3. Continue one more turn.

Expected:

- Build and logs show no serialization crash.
- The next prompt includes scene or relationship context when layered context is enabled.

- [x] **Step 6: Record results**

After executing the matrix, append a dated result block under `Manual Test Results Status` at the end of this document:

```markdown
### 2026-06-17 Execution Result

- Manual mode: pass/fail, device, notes
- Round-robin mode: pass/fail, device, notes
- Moderator mode: pass/fail, device, notes
- Runtime state: pass/fail, device, notes
- Emulator: device id, Android version
- APK: exact path installed
- Blockers: none, or concrete blocker with command output summary
```

- [x] **Step 7: Commit**

```powershell
git add docs/superpowers/plans/2026-06-17-group-context-gameplay-plan.md
git commit -m "docs: record group context gameplay manual test results"
```

---

## Rollout Notes

- Keep all new behavior enabled by default only if the first manual smoke test is stable.
- If prompts become too long, disable `enableRelationshipNotes` by default before release.
- If a provider rejects system messages inserted after the initial system prompt, merge the layered context into the main effective system prompt instead of adding a new `UIMessage.system`.
- If old polluted messages still appear, add a one-time visual-only cleanup in message rendering rather than rewriting stored history.

## Risk Register

- **Risk:** Layered context increases token usage.
  - **Mitigation:** Each section has character limits, and UI options can disable relationship or scene sections.

- **Risk:** Relationship updates become too deterministic and feel fake.
  - **Mitigation:** The first updater is conservative. A model-based summarizer is a separate enhancement after this plan is complete.

- **Risk:** Moderator model output is invalid.
  - **Mitigation:** Local `GroupSpeakerScorer` always provides fallback.

- **Risk:** API rewrite leaks into stored messages again.
  - **Mitigation:** `GroupMessageTransportRewriteTest` verifies original messages are unchanged, and `ChatService` filters original message IDs during streaming merge.

## Suggested Future Extensions

- Add private chat lanes where only user and one member can see a message.
- Add `hiddenFromMemberIds` per message for misunderstanding and eavesdropping gameplay.
- Add relationship matrix editing UI.
- Add a scene director model that proposes scene events without directly speaking.
- Add per-member emotional state such as `calm`, `jealous`, `suspicious`, `protective`, and `afraid`.

## Manual Test Results Status

### 2026-06-17 Execution Result

- Manual mode: partial, `emulator-5554`, app launches and group chat UI renders with selected members; `adb shell input text` can populate the Compose input but `adb` taps did not reliably trigger the in-app send action, so left/right bubble verification remains pending.
- Round-robin mode: not completed, `emulator-5554`, blocked by the same unreliable `adb` -> Compose send automation path.
- Moderator mode: not completed, `emulator-5554`, blocked by the same unreliable send path and by Chinese text input corruption when trying named-member prompts through `adb`.
- Runtime state: partial, `emulator-5554`, persistence models, builder, scorer, updater, and UI debug entry are implemented and covered by unit tests; end-to-end multi-turn manual verification is still pending because message send could not be driven reliably from `adb`.
- Emulator: `emulator-5554`, Android 15, package `me.rerere.rikkahub.debug`, activity `me.rerere.rikkahub.RouteActivity`
- APK: `C:\Users\18734\Desktop\HTML\rikkahub-source\app\build\outputs\apk\debug\app-x86_64-debug.apk`
- Implementation commit: `33d763d0` (`feat: add group context gameplay runtime`)
- Blockers:
  - `adb shell input text` corrupts non-ASCII / spaced prompts on this emulator-Gboard setup.
  - `adb` coordinate taps on the Compose send affordance leave the draft text in place, so the send event is not firing reliably during automated smoke.
  - Observed `INVALID_ARGUMENT` entries in logcat were emitted by Google system components (`GlsClientGrpc` / app credential header), not by `me.rerere.rikkahub.debug` during this test pass.

### 2026-07-01 Execution Result

- Manual mode: pass, `emulator-5554`; selected both members, verified the plain-text mention picker and long-press mention insertion, and successfully dispatched group turns after committing the IME composition with Back. Addressed sends produced only the named member even while both manual members remained selected.
- Addressed continuation: pass after a focused fix; bare English `continue` now preserves the previous addressed member. The runtime sheet showed `当前点名角色: 辉夜`, `当前回复角色: 辉夜`, `上下文层级: CORE`, and two consecutive event records for the addressed `ping` plus continuation turns.
- Round-robin mode: pass, `emulator-5554`; with max auto replies set to `2` and consecutive same-speaker replies disabled, `roundfix` generated 八千代 followed by 辉夜 and stopped normally.
- Moderator mode: pass, `emulator-5554`; unaddressed `modtest` exercised the moderator path, selected 辉夜 for one reply, then stopped normally without exceeding the cap. Explicit names intentionally use deterministic addressing before moderator selection; invalid moderator output falls back locally and is covered by `GroupModeratorDecisionTest`.
- Runtime state: pass, `emulator-5554`; `betrayal` produced conflict focus `危险 / betrayal`, secret focus `betrayal / 背叛`, and a 辉夜 resolver score of `event=14, recent=3, relation=0, total=17` with layer `CORE`. A fresh conversation with 八千代 and `hello` produced no event/conflict focus, score `0`, and layer `ISOLATED`. Both were real provider-backed turns with no serialization crash.
- Emulator: `emulator-5554`, Android 15, package `me.rerere.rikkahub.debug`, activity `me.rerere.rikkahub.RouteActivity`.
- APK: `C:\Users\18734\Desktop\HTML\rikkahub-source\app\build\outputs\apk\debug\app-x86_64-debug.apk`.
- Verification:
  - `./gradlew :app:testDebugUnitTest --tests 'me.rerere.rikkahub.service.group.*' --tests 'me.rerere.rikkahub.service.ChatServiceTest'`
  - `./gradlew :app:assembleDebug`
  - `adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-x86_64-debug.apk`
- Regression fixed during smoke: the addressing parser recognized only Chinese continuation phrases, so English `continue` cleared the active target and fell back to both manually selected members. A failing JVM test was added first, followed by the minimal case-insensitive English continuation match.
- Regression fixed during round-robin smoke: generation startup cleared suggestions by writing a stale pre-resolution `Conversation`, which overwrote the newly persisted active speaker and queue index and selected the same member again. `conversationAtGenerationStart(...)` now starts from the freshly resolved conversation; a focused `ChatServiceTest` was added before the fix and the reinstalled APK produced 八千代 -> 辉夜.
- Automation notes:
  - `adb input text` leaves Gboard text in composition state; send becomes reliable after `adb shell input keyevent 4` commits/hides the IME.
  - Manual member selection is ViewModel state and resets when the app process restarts; smoke scripts must reselect members before sending.
  - Do not run overlapping `uiautomator dump` commands; Android 15 can reject the second shell automation service registration even though the RikkaHub process remains healthy.

### 2026-07-15 Port Verification Result

- Branch: `codex/port-private-to-2.4.1`, based on upstream `2.4.1`.
- Emulator: `emulator-5554`, Android 15; the ported debug APK installed over existing data, launched into `RouteActivity`, rendered the chat page, and produced no crash-buffer entries.
- Database upgrade: the old private v25 database migrated to v26 with `folder_id`, `conversation_folder`, and Room identity hash `f0b200e6a24ae0931995e0b76fecfa13` preserved.
- Instrumentation: `./gradlew connectedDebugAndroidTest --console=plain` passed across all modules after adding the missing AndroidX Test dependencies to `material3`, `highlight`, and `search`, and updating the renamed `speech` module's package assertion.
- APK: `C:\Users\18734\Desktop\HTML\rikkahub-port-2.4.1\app\build\outputs\apk\debug\app-universal-debug.apk`.

### 2026-07-22 Execution Result (post-director re-verification on private-main)

Re-verified all four scenarios on the current private-main build, which includes the
2026-07-15 automatic-turn normalization, the 2026-07-16 group director state machine,
and the 2026-07-17 handoff hardening that landed after the 2026-07-01 pass.

- Approach: no user credentials were available on the emulator (app data had been
  `pm clear`'d earlier), so generation was driven through a local mock
  OpenAI-compatible provider on the host (`http://10.0.2.2:8787/v1`,
  `verification-screenshots/group/mock_openai_server.py`). Provider, model
  selection, two member assistants (辉夜 / 八千代), and the group assistant were
  injected by patching `settings.preferences_pb` directly
  (`verification-screenshots/group/patch_prefs.py`, generic wire-format
  pass-through preserving all other keys). The mock logs every request to
  `mock_requests.jsonl` and answers moderator prompts with a rotating member UUID.
- Manual mode: pass, `emulator-5554`; selected both members via the
  `GroupMemberSelector` chips, sent `hello`; both members replied left-side in
  selection order, user bubble right, no `[User]`/`[member]` prefixes visible.
  Mock log proves the transport rewrite: 八千代's request shows 辉夜's reply as
  `[辉夜] ...`. Screenshots `g02`–`g03`.
- Addressing/IME automation note: Gboard pinyin composition must be committed by
  tapping the first candidate strip item (not `keyevent 4` alone); after that the
  send affordance tap dispatches reliably. This supersedes the 2026-06-17 blocker
  note for ASCII single-word prompts.
- Round-robin mode: pass, `emulator-5554`; `maxAutoRepliesPerUserTurn=2`,
  `allowConsecutiveSameSpeaker=false`; unaddressed `round fix` produced 辉夜 then
  八千代 and stopped at the cap. Screenshot `g09`, mock log sequence.
- Moderator mode: pass, `emulator-5554`; unaddressed message produced two
  model-driven moderator decisions (mock log: non-stream single-message calls
  with the exact `You are a conversation moderator` prompt; first without STOP,
  second with STOP allowed), 辉夜 then 八千代 replied, chain stopped at cap 2
  without fallback. Screenshot `g10`. STOP/invalid-output fallback remains
  covered by `GroupModeratorDecisionTest`.
- Runtime state: pass, `emulator-5554`; neutral `hello` turn kept tension 0,
  layer `ISOLATED`, 2 event records (screenshot `g04b`); conflict `betrayal` turn
  raised 场景紧张度 to 2, added the 活跃秘密 line, 近期事件数 4, 焦点秘密
  `betrayal / 背叛 / 秘密 / 不能说`, 焦点情绪 `怀疑`, 焦点冲突
  `betrayal / 危险 / 怀疑`, layer escalated to `CORE` with resolver score
  `event=14, recent=1, relation=0, total=15` (screenshots `g07`, `g07b`).
  No serialization crash; logcat clean of FATAL entries for the whole session.
- Emulator: `emulator-5554`, Android 15, package `me.rerere.rikkahub.debug`,
  activity `me.rerere.rikkahub.RouteActivity`.
- APK: `C:\Users\18734\Desktop\HTML\rikkahub-source\app\build\outputs\apk\debug\app-universal-debug.apk`
  (built 2026-07-20 04:26, newer than all group sources; installed as an upgrade
  over the integration-branch build — Room migrated v25 → v29 without error).
- Evidence: `verification-screenshots/group/g00`–`g10*.png`,
  `verification-screenshots/group/mock_requests.jsonl`.
- Blockers: none. The 2026-06-17 blockers are fully resolved by the
  candidate-strip IME commit + node-bounds send tap; non-ASCII prompts were not
  needed (ASCII single-word prompts plus mock-side Chinese replies covered every
  scenario).
- Caveat: mock-driven generation proves the app's group pipeline
  (addressing, scheduler, director, moderator prompt, transport rewrite, runtime
  state updater, debug sheet, rendering) but not real-provider streaming quirks;
  member reply text is mock-canned by design.
