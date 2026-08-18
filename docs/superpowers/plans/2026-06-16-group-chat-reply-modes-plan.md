# Group Chat Reply Modes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Improve group chat reply modes so manual, round-robin, and automatic moderator replies are predictable, testable, and easier to tune without changing the app's native UI style.

**Architecture:** Extract group reply decision logic from `ChatService` into focused pure components, then add a hybrid auto mode that scores candidate speakers before optionally asking a moderator model. Keep message generation, context filtering, and API serialization compatible with the current conversation model.

**Tech Stack:** Kotlin, Android Compose, kotlinx.serialization, JUnit, AndroidX instrumentation where needed, existing `SettingsStore`, `Conversation`, `Assistant`, and `UIMessage` models.

---

## Current State Review

Current implementation points:

- `app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt`
  - `AssistantType.GROUP`
  - `GroupMember`
  - `ContextFilter`
  - `TurnTakingStrategy.MANUAL`
  - `TurnTakingStrategy.AUTO_ROUND_ROBIN`
  - `TurnTakingStrategy.AUTO_MODERATOR`
- `app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt`
  - `activeGroupMemberId`
  - `groupMemberQueue`
  - `groupMemberQueueIndex`
- `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
  - `triggerMemberReply`
  - `handleMessageComplete`
  - private `resolveNextSpeaker`
  - private `resolveNextSpeakerViaModerator`
  - private `applyGroupContextFilter`
  - private `applyGroupApiRewrite`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt`
  - `selectedGroupMemberIds`
  - `handleGroupSend`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/GroupMemberSelector.kt`
  - manual multi-member chip selector
  - saved member combo bar
- `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantGroupMembersPage.kt`
  - group strategy selector
  - member context filter editor

Observed limitations:

- The reply decision logic is embedded in `ChatService`, making it hard to test without generation dependencies.
- `AUTO_MODERATOR` only asks for a UUID and has no structured decision metadata such as reason, confidence, or fallback source.
- `AUTO_MODERATOR` has no explicit anti-repeat rule, cooldown, or "no one should reply" option.
- `AUTO_ROUND_ROBIN` persists queue state but does not rebuild safely when member order/enabled state changes.
- Manual batch send calls `triggerMemberReply` in a loop while `triggerMemberReply` cancels the current session job, so sequential member replies are vulnerable to cancellation/race behavior.
- Manual selected member state is stored only in `ChatVM`; it can retain deleted or disabled member IDs until the user changes the selection.
- Manual send with no selected member has no explicit user-facing guard, so the input can be sent without any member reply in manual mode.
- Manual member reorder is hidden behind long-pressing selected chips; it works, but it is easy to miss and hard to verify at a glance in longer groups.
- Group regenerate member picker lists all other members, including disabled members, instead of limiting choices to valid enabled speakers.
- `applyGroupContextFilter` and `applyGroupApiRewrite` are private top-level helpers in `ChatService`, so their behavior is hard to regression-test directly.
- UI only exposes strategy choice. It does not expose lightweight auto-mode controls such as maximum auto replies, repeat suppression, or moderator fallback.

## Recommended Approach

Use a staged refactor, not a wholesale rewrite.

Recommended user-facing mode model:

1. `MANUAL`: user chooses one or more members. Preserve existing selector and combos.
2. `AUTO_ROUND_ROBIN`: deterministic queue with member-order/enabled-state repair.
3. `AUTO_MODERATOR`: automatic arbitration. Internally, use local deterministic scoring first, then ask the moderator model only when needed.

Why this is better:

- Local scoring is cheap, deterministic, and testable.
- Moderator model is still available for nuanced roleplay conversations.
- A structured decision object gives logs, UI hints, and fallback behavior.
- Existing manual workflow stays unchanged for users who want control.

Do not add a fourth user-visible reply mode. Keep the existing enum values and improve their internals:

- Keep `MANUAL` as "手动选择".
- Keep `AUTO_ROUND_ROBIN` as "轮询模式".
- Keep `AUTO_MODERATOR` as "自动仲裁".
- Treat local scoring as an internal step of `AUTO_MODERATOR`, not as a separate mode.

## File Structure

Create:

- `app/src/main/java/me/rerere/rikkahub/service/group/GroupReplyDecision.kt`
  - Pure data models for candidate speakers, decision results, and reply mode options.
- `app/src/main/java/me/rerere/rikkahub/service/group/GroupReplyDecisionEngine.kt`
  - Pure deterministic decision engine for manual, round-robin, and scored auto decisions.
- `app/src/main/java/me/rerere/rikkahub/service/group/GroupMessageTransforms.kt`
  - Move `applyGroupContextFilter` and `applyGroupApiRewrite` here as internal testable extensions.
- `app/src/main/java/me/rerere/rikkahub/service/group/GroupManualSelection.kt`
  - Pure helpers for manual member selection cleanup, toggling, and reordering.
- `app/src/test/java/me/rerere/rikkahub/service/group/GroupReplyDecisionEngineTest.kt`
  - Unit tests for queue repair, repeat suppression, mention scoring, and fallback.
- `app/src/test/java/me/rerere/rikkahub/service/group/GroupMessageTransformsTest.kt`
  - Unit tests for context filtering and provider-safe API rewriting.
- `app/src/test/java/me/rerere/rikkahub/service/group/GroupManualSelectionTest.kt`
  - Unit tests for manual selection cleanup, toggling, and ordering.

Modify:

- `app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt`
  - Add optional group reply tuning model after the pure engine is in place.
- `app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt`
  - Add optional decision-state fields only if needed by tests in Task 3.
- `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
  - Replace private speaker resolution and group message transforms with the extracted engine.
- `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantGroupMembersPage.kt`
  - Add native-style controls for auto reply tuning after behavior is covered by tests.
- `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantGroupMembersVM.kt`
  - Persist auto reply tuning settings.
- `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt`
  - Replace manual batch send loop with a service method that runs a stable member queue.
- `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/GroupMemberSelector.kt`
  - Filter disabled members from selectable chips, add deterministic selection cleanup, and expose clearer order controls.
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageActions.kt`
  - Filter disabled members from group regenerate picker.

## Proposed Data Model

Add after `TurnTakingStrategy` in `Assistant.kt`:

```kotlin
@Serializable
data class GroupReplyOptions(
    val maxAutoRepliesPerUserTurn: Int = 1,
    val allowConsecutiveSameSpeaker: Boolean = false,
    val moderatorEnabled: Boolean = true,
    val moderatorConfidenceThreshold: Float = 0.55f,
    val mentionBoost: Int = 4,
    val recentSpeakerPenalty: Int = 3,
    val roundRobinFallback: Boolean = true,
)
```

Add to `Assistant`:

```kotlin
val groupReplyOptions: GroupReplyOptions = GroupReplyOptions(),
```

Do not add this in Task 1. Add it in Task 3 after pure decision tests exist.

## Proposed Decision Types

Create `app/src/main/java/me/rerere/rikkahub/service/group/GroupReplyDecision.kt`:

```kotlin
package me.rerere.rikkahub.service.group

import kotlin.uuid.Uuid

internal enum class GroupReplyDecisionSource {
    MANUAL,
    ROUND_ROBIN,
    LOCAL_SCORE,
    MODERATOR,
    FALLBACK,
    NONE,
}

internal data class GroupSpeakerCandidate(
    val memberId: Uuid,
    val displayName: String,
    val enabled: Boolean,
    val mentionKeywords: List<String>,
)

internal data class GroupReplyDecision(
    val memberId: Uuid?,
    val source: GroupReplyDecisionSource,
    val confidence: Float,
    val reason: String,
)
```

Create `app/src/main/java/me/rerere/rikkahub/service/group/GroupReplyDecisionEngine.kt`:

```kotlin
package me.rerere.rikkahub.service.group

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

internal class GroupReplyDecisionEngine {
    fun repairQueue(enabledMemberIds: List<Uuid>, currentQueue: List<Uuid>): List<Uuid> {
        val enabledSet = enabledMemberIds.toSet()
        val preserved = currentQueue.filter { it in enabledSet }
        val missing = enabledMemberIds.filter { it !in preserved }
        return preserved + missing
    }

    fun nextRoundRobin(enabledMemberIds: List<Uuid>, currentQueue: List<Uuid>, currentIndex: Int): Pair<Uuid?, Int> {
        val queue = repairQueue(enabledMemberIds, currentQueue)
        if (queue.isEmpty()) return null to 0
        val nextIndex = (currentIndex + 1).floorMod(queue.size)
        return queue[nextIndex] to nextIndex
    }

    fun chooseByLocalScore(
        candidates: List<GroupSpeakerCandidate>,
        messages: List<UIMessage>,
        lastSpeakerId: Uuid?,
        allowConsecutiveSameSpeaker: Boolean,
        mentionBoost: Int,
        recentSpeakerPenalty: Int,
    ): GroupReplyDecision {
        val enabled = candidates.filter { it.enabled }
        if (enabled.isEmpty()) {
            return GroupReplyDecision(null, GroupReplyDecisionSource.NONE, 0f, "No enabled group members")
        }
        val recentText = messages.takeLast(4).joinToString("\n") { it.toText() }
        val scored = enabled.map { candidate ->
            var score = 1
            if (!allowConsecutiveSameSpeaker && candidate.memberId == lastSpeakerId) score -= recentSpeakerPenalty
            if (candidate.mentionKeywords.any { recentText.contains(it, ignoreCase = true) }) score += mentionBoost
            candidate to score
        }.sortedWith(compareByDescending<Pair<GroupSpeakerCandidate, Int>> { it.second }.thenBy { it.first.displayName })
        val best = scored.first()
        val confidence = (best.second.coerceAtLeast(0) / (mentionBoost + 1f)).coerceIn(0f, 1f)
        return GroupReplyDecision(best.first.memberId, GroupReplyDecisionSource.LOCAL_SCORE, confidence, "Best local score=${best.second}")
    }

    private fun Int.floorMod(divisor: Int): Int = ((this % divisor) + divisor) % divisor
}
```

## Task 1: Extract and Test Group Message Transforms

**Files:**

- Create: `app/src/main/java/me/rerere/rikkahub/service/group/GroupMessageTransforms.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/service/group/GroupMessageTransformsTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`

- [ ] **Step 1: Write failing tests for context filtering**

Add `GroupMessageTransformsTest.kt`:

```kotlin
package me.rerere.rikkahub.service.group

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.ContextFilter
import me.rerere.rikkahub.data.model.ContextScope
import me.rerere.rikkahub.data.model.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class GroupMessageTransformsTest {
    @Test
    fun `self context keeps user messages and current member messages`() {
        val memberA = Uuid.random()
        val memberB = Uuid.random()
        val assistant = Assistant(
            assistantType = AssistantType.GROUP,
            groupMembers = listOf(
                GroupMember(id = memberA, assistantId = Uuid.random(), displayName = "A", contextFilter = ContextFilter(scope = ContextScope.SELF)),
                GroupMember(id = memberB, assistantId = Uuid.random(), displayName = "B"),
            ),
        )
        val messages = listOf(
            UIMessage.user("hello"),
            UIMessage.assistant("from A").copy(memberId = memberA),
            UIMessage.assistant("from B").copy(memberId = memberB),
        )

        val result = messages.applyGroupContextFilter(assistant, memberA)

        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), result.map { it.role })
        assertEquals(listOf(null, memberA), result.map { it.memberId })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
$env:JAVA_HOME="$env:USERPROFILE\.gradle\jdks\jetbrains_s_r_o_-21-amd64-windows.2"; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat :app:testDebugUnitTest --tests me.rerere.rikkahub.service.group.GroupMessageTransformsTest -x :web:buildWebUi --no-daemon
```

Expected: compile failure because `applyGroupContextFilter` is not visible in `me.rerere.rikkahub.service.group`.

- [ ] **Step 3: Move transform functions into `GroupMessageTransforms.kt`**

Create:

```kotlin
package me.rerere.rikkahub.service.group

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.ContextScope
import kotlin.uuid.Uuid

internal fun List<UIMessage>.applyGroupContextFilter(
    groupAssistant: Assistant,
    effectiveMemberId: Uuid?,
): List<UIMessage> {
    if (groupAssistant.assistantType != AssistantType.GROUP) return this
    if (effectiveMemberId == null) return this
    val member = groupAssistant.groupMembers.find { it.id == effectiveMemberId } ?: return this
    val filter = member.contextFilter
    if (filter.scope == ContextScope.ALL &&
        filter.excludedMemberIds.isEmpty() &&
        !filter.mentionEnabled &&
        filter.maxMessages <= 0
    ) return this

    var result: List<UIMessage> = this
    result = when (filter.scope) {
        ContextScope.ALL -> result
        ContextScope.SELF -> result.filter { it.role == MessageRole.USER || it.memberId == effectiveMemberId }
        ContextScope.MEMBER_LIST -> result.filter { it.role == MessageRole.USER || it.memberId in filter.visibleMemberIds }
        ContextScope.DIRECTED -> result.filter { it.memberId == effectiveMemberId }
    }
    if (filter.excludedMemberIds.isNotEmpty()) {
        result = result.filter { it.memberId !in filter.excludedMemberIds }
    }
    if (filter.mentionEnabled && filter.mentionKeywords.isNotEmpty()) {
        result = result.filter { msg ->
            msg.role == MessageRole.USER || filter.mentionKeywords.any { kw ->
                msg.toText().contains(kw, ignoreCase = true)
            }
        }
    }
    if (filter.maxMessages > 0 && result.size > filter.maxMessages) {
        val users = result.filter { it.role == MessageRole.USER }
        val others = result.filter { it.role != MessageRole.USER }
        val keep = (filter.maxMessages - users.size).coerceAtLeast(0)
        result = others.takeLast(keep) + users
    }
    return result
}

internal fun List<UIMessage>.applyGroupApiRewrite(
    groupAssistant: Assistant,
    effectiveMemberId: Uuid?,
): List<UIMessage> {
    if (groupAssistant.assistantType != AssistantType.GROUP || effectiveMemberId == null) return this
    val rewritten = map { message ->
        when {
            message.role == MessageRole.ASSISTANT && message.memberId != null && message.memberId != effectiveMemberId -> {
                val member = groupAssistant.groupMembers.find { it.id == message.memberId }
                val prefix = member?.displayName?.takeIf { it.isNotBlank() }?.let { "[$it] " } ?: ""
                message.copy(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text(prefix + message.toText())),
                    name = null,
                )
            }
            message.memberId != null -> {
                val member = groupAssistant.groupMembers.find { it.id == message.memberId }
                val memberName = member?.displayName?.takeIf { it.isNotBlank() }
                if (memberName != null && message.name != memberName) message.copy(name = memberName) else message
            }
            message.role == MessageRole.USER && message.memberId == null -> {
                message.copy(parts = listOf(UIMessagePart.Text("[User] " + message.toText())))
            }
            else -> message
        }
    }
    return if (rewritten.isNotEmpty() && rewritten.last().role == MessageRole.ASSISTANT) {
        rewritten + UIMessage.user("")
    } else rewritten
}
```

- [ ] **Step 4: Import new helpers in `ChatService.kt` and remove old private copies**

Add imports:

```kotlin
import me.rerere.rikkahub.service.group.applyGroupApiRewrite
import me.rerere.rikkahub.service.group.applyGroupContextFilter
```

Remove the old private top-level definitions from `ChatService.kt`.

- [ ] **Step 5: Run tests**

Run:

```powershell
$env:JAVA_HOME="$env:USERPROFILE\.gradle\jdks\jetbrains_s_r_o_-21-amd64-windows.2"; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat :app:testDebugUnitTest --tests me.rerere.rikkahub.service.group.GroupMessageTransformsTest -x :web:buildWebUi --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

## Task 2: Extract Pure Reply Decision Engine

**Files:**

- Create: `app/src/main/java/me/rerere/rikkahub/service/group/GroupReplyDecision.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/service/group/GroupReplyDecisionEngine.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/service/group/GroupReplyDecisionEngineTest.kt`

- [ ] **Step 1: Write failing tests for queue repair and local scoring**

Create `GroupReplyDecisionEngineTest.kt`:

```kotlin
package me.rerere.rikkahub.service.group

import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.uuid.Uuid

class GroupReplyDecisionEngineTest {
    private val engine = GroupReplyDecisionEngine()

    @Test
    fun `repairQueue drops disabled ids and appends new enabled ids`() {
        val a = Uuid.random()
        val b = Uuid.random()
        val c = Uuid.random()
        val stale = Uuid.random()

        val repaired = engine.repairQueue(
            enabledMemberIds = listOf(a, b, c),
            currentQueue = listOf(stale, b, a),
        )

        assertEquals(listOf(b, a, c), repaired)
    }

    @Test
    fun `local score boosts mentioned member and avoids immediate repeat`() {
        val a = Uuid.random()
        val b = Uuid.random()
        val result = engine.chooseByLocalScore(
            candidates = listOf(
                GroupSpeakerCandidate(a, "Alice", enabled = true, mentionKeywords = listOf("alice")),
                GroupSpeakerCandidate(b, "Bob", enabled = true, mentionKeywords = listOf("bob")),
            ),
            messages = listOf(UIMessage.user("Bob, what do you think?")),
            lastSpeakerId = a,
            allowConsecutiveSameSpeaker = false,
            mentionBoost = 4,
            recentSpeakerPenalty = 3,
        )

        assertEquals(b, result.memberId)
        assertEquals(GroupReplyDecisionSource.LOCAL_SCORE, result.source)
        assertNotEquals(0f, result.confidence)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
$env:JAVA_HOME="$env:USERPROFILE\.gradle\jdks\jetbrains_s_r_o_-21-amd64-windows.2"; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat :app:testDebugUnitTest --tests me.rerere.rikkahub.service.group.GroupReplyDecisionEngineTest -x :web:buildWebUi --no-daemon
```

Expected: compile failure because `GroupReplyDecisionEngine` does not exist.

- [ ] **Step 3: Add decision types and engine**

Use the code from "Proposed Decision Types" above.

- [ ] **Step 4: Run decision tests**

Run:

```powershell
$env:JAVA_HOME="$env:USERPROFILE\.gradle\jdks\jetbrains_s_r_o_-21-amd64-windows.2"; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat :app:testDebugUnitTest --tests me.rerere.rikkahub.service.group.GroupReplyDecisionEngineTest -x :web:buildWebUi --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

## Task 3: Add Group Reply Options

**Files:**

- Modify: `app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/service/group/GroupReplyDecisionEngineTest.kt`

- [ ] **Step 1: Write serialization/default test**

Add to `GroupReplyDecisionEngineTest.kt`:

```kotlin
@Test
fun `default group reply options prefer safe single auto reply`() {
    val options = me.rerere.rikkahub.data.model.GroupReplyOptions()

    assertEquals(1, options.maxAutoRepliesPerUserTurn)
    assertEquals(false, options.allowConsecutiveSameSpeaker)
    assertEquals(true, options.moderatorEnabled)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run the same `GroupReplyDecisionEngineTest` command.

Expected: compile failure because `GroupReplyOptions` does not exist.

- [ ] **Step 3: Add `GroupReplyOptions` to `Assistant.kt`**

Insert after `TurnTakingStrategy`:

```kotlin
@Serializable
data class GroupReplyOptions(
    val maxAutoRepliesPerUserTurn: Int = 1,
    val allowConsecutiveSameSpeaker: Boolean = false,
    val moderatorEnabled: Boolean = true,
    val moderatorConfidenceThreshold: Float = 0.55f,
    val mentionBoost: Int = 4,
    val recentSpeakerPenalty: Int = 3,
    val roundRobinFallback: Boolean = true,
)
```

Add to `Assistant` constructor near `turnTakingStrategy`:

```kotlin
val groupReplyOptions: GroupReplyOptions = GroupReplyOptions(),
```

- [ ] **Step 4: Run tests**

Run:

```powershell
$env:JAVA_HOME="$env:USERPROFILE\.gradle\jdks\jetbrains_s_r_o_-21-amd64-windows.2"; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat :app:testDebugUnitTest --tests me.rerere.rikkahub.service.group.GroupReplyDecisionEngineTest -x :web:buildWebUi --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

## Task 4: Route `AUTO_ROUND_ROBIN` Through the Engine

**Files:**

- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/service/group/GroupReplyDecisionEngineTest.kt`

- [ ] **Step 1: Add queue index test for changed member order**

Add:

```kotlin
@Test
fun `nextRoundRobin uses repaired queue when members change`() {
    val a = Uuid.random()
    val b = Uuid.random()
    val c = Uuid.random()

    val (next, nextIndex) = engine.nextRoundRobin(
        enabledMemberIds = listOf(a, b, c),
        currentQueue = listOf(b, a),
        currentIndex = 1,
    )

    assertEquals(c, next)
    assertEquals(2, nextIndex)
}
```

- [ ] **Step 2: Run test and fix engine if needed**

Run `GroupReplyDecisionEngineTest`.

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Update `resolveNextSpeaker` in `ChatService.kt`**

Add a property in `ChatService`:

```kotlin
private val groupReplyDecisionEngine = GroupReplyDecisionEngine()
```

Replace round-robin branch with:

```kotlin
TurnTakingStrategy.AUTO_ROUND_ROBIN -> {
    val enabledIds = groupAssistant.groupMembers.filter { it.enabled }.map { it.id }
    val repairedQueue = groupReplyDecisionEngine.repairQueue(enabledIds, conversation.groupMemberQueue)
    val (nextId, nextIndex) = groupReplyDecisionEngine.nextRoundRobin(
        enabledMemberIds = enabledIds,
        currentQueue = repairedQueue,
        currentIndex = conversation.groupMemberQueueIndex,
    )
    if (nextId != null) {
        saveConversation(
            conversation.id,
            conversation.copy(
                activeGroupMemberId = nextId,
                groupMemberQueue = repairedQueue,
                groupMemberQueueIndex = nextIndex,
            )
        )
    }
    return nextId
}
```

- [ ] **Step 4: Run focused tests**

Run:

```powershell
$env:JAVA_HOME="$env:USERPROFILE\.gradle\jdks\jetbrains_s_r_o_-21-amd64-windows.2"; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat :app:testDebugUnitTest --tests me.rerere.rikkahub.service.group.GroupReplyDecisionEngineTest --tests me.rerere.rikkahub.service.ChatServiceTest -x :web:buildWebUi --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

## Task 5: Make Moderator Decisions Structured

**Files:**

- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/group/GroupReplyDecision.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/service/group/GroupReplyDecisionEngineTest.kt`

- [ ] **Step 1: Add parser test**

Add a parser function to the engine:

```kotlin
fun parseModeratorDecision(responseText: String, enabledIds: List<Uuid>): GroupReplyDecision
```

Test:

```kotlin
@Test
fun `parseModeratorDecision accepts json with memberId confidence and reason`() {
    val id = Uuid.random()
    val result = engine.parseModeratorDecision(
        responseText = """{"memberId":"$id","confidence":0.72,"reason":"directly asked"}""",
        enabledIds = listOf(id),
    )

    assertEquals(id, result.memberId)
    assertEquals(GroupReplyDecisionSource.MODERATOR, result.source)
    assertEquals(0.72f, result.confidence, 0.001f)
}
```

- [ ] **Step 2: Run parser test and verify failure**

Run `GroupReplyDecisionEngineTest`.

Expected: compile failure because parser does not exist.

- [ ] **Step 3: Implement parser with UUID fallback**

Implement:

```kotlin
fun parseModeratorDecision(responseText: String, enabledIds: List<Uuid>): GroupReplyDecision {
    val trimmed = responseText.trim()
    val jsonMatch = Regex("""\{[\s\S]*}""").find(trimmed)?.value
    if (jsonMatch != null) {
        val memberId = Regex(""""memberId"\s*:\s*"([^"]+)"""").find(jsonMatch)?.groupValues?.get(1)
        val confidence = Regex(""""confidence"\s*:\s*([0-9.]+)""").find(jsonMatch)?.groupValues?.get(1)?.toFloatOrNull() ?: 0.5f
        val reason = Regex(""""reason"\s*:\s*"([^"]*)"""").find(jsonMatch)?.groupValues?.get(1).orEmpty()
        val id = memberId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        if (id != null && id in enabledIds) {
            return GroupReplyDecision(id, GroupReplyDecisionSource.MODERATOR, confidence.coerceIn(0f, 1f), reason)
        }
    }
    val id = enabledIds.firstOrNull { trimmed.contains(it.toString()) }
    return if (id != null) {
        GroupReplyDecision(id, GroupReplyDecisionSource.MODERATOR, 0.5f, "Parsed UUID from moderator text")
    } else {
        GroupReplyDecision(null, GroupReplyDecisionSource.FALLBACK, 0f, "Moderator response did not contain an enabled member")
    }
}
```

- [ ] **Step 4: Change moderator prompt in `ChatService.kt`**

Replace:

```kotlin
appendLine("Reply ONLY with the character ID (UUID).")
```

with:

```kotlin
appendLine("""Reply ONLY as compact JSON: {"memberId":"UUID","confidence":0.0-1.0,"reason":"short reason"}.""")
appendLine("""Use {"memberId":null,"confidence":0.0,"reason":"no suitable speaker"} if nobody should reply.""")
```

Use `parseModeratorDecision` on response. If decision is null or confidence is below `groupAssistant.groupReplyOptions.moderatorConfidenceThreshold`, fall back to local score or round-robin.

- [ ] **Step 5: Run tests**

Run:

```powershell
$env:JAVA_HOME="$env:USERPROFILE\.gradle\jdks\jetbrains_s_r_o_-21-amd64-windows.2"; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat :app:testDebugUnitTest --tests me.rerere.rikkahub.service.group.GroupReplyDecisionEngineTest -x :web:buildWebUi --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

## Task 6: Fix Manual Batch Reply Cancellation Risk

**Files:**

- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt`

- [ ] **Step 1: Add service method for ordered member batch**

Add to `ChatService`:

```kotlin
fun triggerMemberReplies(conversationId: Uuid, memberIds: List<Uuid>) {
    val session = getOrCreateSession(conversationId)
    session.getJob()?.cancel()
    val job = appScope.launch {
        try {
            finishInterruptedPendingTools(conversationId)
            memberIds.forEach { memberId ->
                handleMessageComplete(conversationId, memberId = memberId)
            }
            _generationDoneFlow.emit(conversationId)
        } catch (e: Exception) {
            e.printStackTrace()
            addError(e, conversationId, title = "群组成员批量回复失败")
        }
    }
    session.setJob(job)
}
```

- [ ] **Step 2: Change `ChatVM.handleGroupSend`**

Replace:

```kotlin
for (memberId in memberIds) {
    ensureActive()
    chatService.triggerMemberReply(_conversationId, memberId)
}
```

with:

```kotlin
ensureActive()
chatService.triggerMemberReplies(_conversationId, memberIds)
```

- [ ] **Step 3: Run compile-focused tests**

Run:

```powershell
$env:JAVA_HOME="$env:USERPROFILE\.gradle\jdks\jetbrains_s_r_o_-21-amd64-windows.2"; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat :app:testDebugUnitTest --tests me.rerere.rikkahub.service.ChatServiceTest -x :web:buildWebUi --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

## Task 7: Polish Manual Mode Ergonomics

**Files:**

- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/GroupMemberSelector.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageActions.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/service/group/GroupManualSelectionTest.kt`

- [x] **Step 1: Add pure manual selection helper tests**

Create `app/src/test/java/me/rerere/rikkahub/service/group/GroupManualSelectionTest.kt`:

```kotlin
package me.rerere.rikkahub.service.group

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class GroupManualSelectionTest {
    @Test
    fun `sanitizeManualSelection removes unavailable ids and preserves order`() {
        val a = Uuid.random()
        val b = Uuid.random()
        val c = Uuid.random()

        val result = sanitizeManualSelection(
            selectedIds = listOf(c, a, b),
            availableIds = listOf(a, c),
        )

        assertEquals(listOf(c, a), result)
    }

    @Test
    fun `toggleManualSelection appends new ids and removes existing ids`() {
        val a = Uuid.random()
        val b = Uuid.random()

        assertEquals(listOf(a, b), toggleManualSelection(listOf(a), b))
        assertEquals(emptyList<Uuid>(), toggleManualSelection(listOf(a), a))
    }

    @Test
    fun `moveManualSelection changes selected order`() {
        val a = Uuid.random()
        val b = Uuid.random()
        val c = Uuid.random()

        val result = moveManualSelection(listOf(a, b, c), fromIndex = 2, toIndex = 0)

        assertEquals(listOf(c, a, b), result)
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run:

```powershell
$env:JAVA_HOME="$env:USERPROFILE\.gradle\jdks\jetbrains_s_r_o_-21-amd64-windows.2"; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat :app:testDebugUnitTest --tests me.rerere.rikkahub.service.group.GroupManualSelectionTest -x :web:buildWebUi --no-daemon
```

Expected: compile failure because manual selection helpers do not exist.

- [x] **Step 3: Add manual selection helper functions**

Create `app/src/main/java/me/rerere/rikkahub/service/group/GroupManualSelection.kt`:

```kotlin
package me.rerere.rikkahub.service.group

import kotlin.uuid.Uuid

internal fun sanitizeManualSelection(
    selectedIds: List<Uuid>,
    availableIds: List<Uuid>,
): List<Uuid> {
    val available = availableIds.toSet()
    return selectedIds.filter { it in available }.distinct()
}

internal fun toggleManualSelection(
    selectedIds: List<Uuid>,
    memberId: Uuid,
): List<Uuid> {
    return if (memberId in selectedIds) {
        selectedIds.filter { it != memberId }
    } else {
        selectedIds + memberId
    }
}

internal fun moveManualSelection(
    selectedIds: List<Uuid>,
    fromIndex: Int,
    toIndex: Int,
): List<Uuid> {
    if (fromIndex !in selectedIds.indices || toIndex !in selectedIds.indices) return selectedIds
    val mutable = selectedIds.toMutableList()
    val item = mutable.removeAt(fromIndex)
    mutable.add(toIndex, item)
    return mutable
}
```

- [x] **Step 4: Use helpers in `ChatVM`**

Import:

```kotlin
import me.rerere.rikkahub.service.group.sanitizeManualSelection
import me.rerere.rikkahub.service.group.toggleManualSelection
```

Replace `toggleGroupMember` with:

```kotlin
fun toggleGroupMember(memberId: Uuid) {
    _selectedGroupMemberIds.update { ids -> toggleManualSelection(ids, memberId) }
}
```

Add:

```kotlin
fun sanitizeGroupMemberSelection(availableIds: List<Uuid>) {
    _selectedGroupMemberIds.update { ids -> sanitizeManualSelection(ids, availableIds) }
}
```

- [x] **Step 5: Clean selected IDs from `ChatPage` when group membership changes**

In `ChatPage`, compute available manual member IDs:

```kotlin
val availableManualMemberIds = remember(ga?.groupMembers) {
    ga?.groupMembers?.filter { it.enabled }?.map { it.id }.orEmpty()
}
```

Add a `LaunchedEffect` near the selector:

```kotlin
LaunchedEffect(availableManualMemberIds) {
    vm.sanitizeGroupMemberSelection(availableManualMemberIds)
}
```

Pass only enabled members to `GroupMemberSelector`:

```kotlin
members = ga!!.groupMembers.filter { it.enabled },
```

- [x] **Step 6: Guard manual send with no selected member**

In `ChatVM.handleGroupSend`, after computing `isEmpty`, add:

```kotlin
if (memberIds.isEmpty()) {
    addError(
        IllegalStateException("请先选择至少一个群组成员"),
        _conversationId,
        title = "未选择群组成员",
    )
    return@launch
}
```

If `addError` is not visible in `ChatVM`, add a small `ChatService.reportError` wrapper instead:

```kotlin
fun reportError(conversationId: Uuid, title: String, error: Throwable) {
    addError(error, conversationId, title = title)
}
```

Then call:

```kotlin
chatService.reportError(_conversationId, "未选择群组成员", IllegalStateException("请先选择至少一个群组成员"))
```

- [x] **Step 7: Make selected order easier to adjust**

In `GroupMemberSelector`, keep the existing long-press behavior and add small icon buttons for selected members:

```kotlin
if (isSelected) {
    IconButton(
        onClick = {
            if (orderIndex > 0) {
                val cur = selectedMemberIds.toMutableList()
                val item = cur.removeAt(orderIndex)
                cur.add(orderIndex - 1, item)
                onSelectionChange(cur)
            }
        },
        modifier = Modifier.size(28.dp),
    ) {
        Icon(HugeIcons.ArrowLeft01, contentDescription = "提前发言", modifier = Modifier.size(14.dp))
    }
    IconButton(
        onClick = {
            if (orderIndex in 0 until selectedMemberIds.lastIndex) {
                val cur = selectedMemberIds.toMutableList()
                val item = cur.removeAt(orderIndex)
                cur.add(orderIndex + 1, item)
                onSelectionChange(cur)
            }
        },
        modifier = Modifier.size(28.dp),
    ) {
        Icon(HugeIcons.ArrowRight01, contentDescription = "延后发言", modifier = Modifier.size(14.dp))
    }
}
```

Use the icon set already used in this repository. If `HugeIcons.ArrowLeft01` or `HugeIcons.ArrowRight01` is unavailable, use the closest existing left/right arrow icons found by the `find-hugeicons` skill.

- [x] **Step 8: Filter regenerate member picker to enabled members**

In `ChatMessageActions.kt`, change:

```kotlin
assistant.groupMembers.filter { it.id != currentMemberId }.forEach { member ->
```

to:

```kotlin
assistant.groupMembers.filter { it.enabled && it.id != currentMemberId }.forEach { member ->
```

Also change:

```kotlin
if (assistant.groupMembers.any { it.id != currentMemberId }) {
```

to:

```kotlin
if (assistant.groupMembers.any { it.enabled && it.id != currentMemberId }) {
```

- [x] **Step 9: Run focused tests and compile**

Run:

```powershell
$env:JAVA_HOME="$env:USERPROFILE\.gradle\jdks\jetbrains_s_r_o_-21-amd64-windows.2"; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat :app:testDebugUnitTest --tests me.rerere.rikkahub.service.group.GroupManualSelectionTest -x :web:buildWebUi --no-daemon
$env:JAVA_HOME="$env:USERPROFILE\.gradle\jdks\jetbrains_s_r_o_-21-amd64-windows.2"; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat :app:compileDebugKotlin -x :web:buildWebUi --no-daemon
```

Expected: both commands return `BUILD SUCCESSFUL`.

**Execution note:** Task 7 was implemented and verified with `GroupManualSelectionTest` plus `:app:compileDebugKotlin`. Manual mode now sanitizes selection state, exposes explicit reorder controls, filters disabled members in the regenerate picker, and surfaces a clear empty-selection error path.

## Task 8: Add Native-Style Auto Reply Controls

**Files:**

- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantGroupMembersPage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantGroupMembersVM.kt`

- [x] **Step 1: Add VM update helper**

Add:

```kotlin
fun updateGroupReplyOptions(options: GroupReplyOptions) {
    val current = assistant.value ?: return
    updateAssistant(current.copy(groupReplyOptions = options))
}
```

Import:

```kotlin
import me.rerere.rikkahub.data.model.GroupReplyOptions
```

- [x] **Step 2: Add CardGroup controls under strategy section**

In `AssistantGroupMembersPage`, after strategy `CardGroup`, add only when strategy is not manual:

```kotlin
if (currentAssistant.turnTakingStrategy != TurnTakingStrategy.MANUAL) {
    val options = currentAssistant.groupReplyOptions
    CardGroup {
        item(
            headlineContent = { Text("避免同一成员连续发言") },
            supportingContent = { Text("自动模式下优先换人回复，除非只有一个可用成员") },
            trailingContent = {
                Switch(
                    checked = !options.allowConsecutiveSameSpeaker,
                    onCheckedChange = { checked ->
                        vm.updateGroupReplyOptions(options.copy(allowConsecutiveSameSpeaker = !checked))
                    },
                )
            },
        )
        item(
            headlineContent = { Text("每轮自动回复上限") },
            supportingContent = { Text("${options.maxAutoRepliesPerUserTurn} 条") },
        )
    }
}
```

Keep styling consistent with existing `CardGroup` list items. Do not introduce a new design system.

- [x] **Step 3: Run Compose compile**

Run:

```powershell
$env:JAVA_HOME="$env:USERPROFILE\.gradle\jdks\jetbrains_s_r_o_-21-amd64-windows.2"; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat :app:compileDebugKotlin -x :web:buildWebUi --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

**Execution note:** Task 8 was implemented with persisted `GroupReplyOptions`, a settings UI in `AssistantGroupMembersPage`, `allowConsecutiveSameSpeaker` handling in `ChatService`, and a chained auto-reply cap driven by `maxAutoRepliesPerUserTurn`.

## Task 9: Verification Matrix

**Files:**

- Modify this plan with results after execution.

- [x] **Step 1: JVM regression**

Run:

```powershell
$env:JAVA_HOME="$env:USERPROFILE\.gradle\jdks\jetbrains_s_r_o_-21-amd64-windows.2"; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat :app:testDebugUnitTest --tests me.rerere.rikkahub.service.group.* --tests me.rerere.rikkahub.service.ChatServiceTest -x :web:buildWebUi --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

Result: `BUILD SUCCESSFUL` from `:app:testDebugUnitTest --tests me.rerere.rikkahub.service.group.* --tests me.rerere.rikkahub.service.ChatServiceTest -x :web:buildWebUi --no-daemon`.

- [x] **Step 2: Android connected smoke**

Run:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
$env:JAVA_HOME="$env:USERPROFILE\.gradle\jdks\jetbrains_s_r_o_-21-amd64-windows.2"; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat :app:connectedDebugAndroidTest -x :web:buildWebUi --no-daemon
```

Expected: emulator listed as `device`, then `BUILD SUCCESSFUL`.

Result: `adb devices` reported `emulator-5554	device`, and `:app:connectedDebugAndroidTest` returned `BUILD SUCCESSFUL`.

- [x] **Step 3: Install Debug APK**

Run:

```powershell
$env:JAVA_HOME="$env:USERPROFILE\.gradle\jdks\jetbrains_s_r_o_-21-amd64-windows.2"; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat :app:installDebug -x :web:buildWebUi --no-daemon
```

Expected: `Installed on 1 device`.

Result: `Installed on 1 device` from `:app:installDebug`.

## Manual QA Checklist

- [ ] Create a group assistant with three enabled members.
- [ ] Manual mode: select two members and send one user message; both selected members reply in selected order.
- [ ] Manual mode: saved combo applies the same ordered member list.
- [ ] Manual mode: press send with no selected member; app shows a clear error and does not silently do nothing.
- [ ] Manual mode: disable or delete a selected member; the selected member strip removes that member automatically.
- [ ] Manual mode: reorder selected members with visible controls and verify the reply order follows the new order.
- [ ] Manual mode: regenerate assistant reply and verify the member picker excludes disabled members.
- [ ] Round-robin mode: members reply in configured order.
- [ ] Round-robin mode: disable one member and verify the queue skips it without stalling.
- [ ] Auto moderator mode: direct a question to one member by name and verify that member is selected.
- [ ] Auto moderator mode: ask a general question and verify fallback is deterministic.
- [ ] Context filter `SELF`: selected member sees user messages and its own messages only.
- [ ] Context filter `MEMBER_LIST`: selected member sees user messages and listed member messages only.
- [ ] API rewrite: other member messages are sent as user-role speaker-prefixed text.

### Manual QA Notes

- Verified on emulator `emulator-5554` that the assistant settings surface opens correctly and that the group assistant creation sheet exposes the expected native controls for assistant type, turn-taking strategy, member count, cancel, and save.
- Verified the settings data currently contains two single assistants, but their visible names are blank in the member-picker flow, which makes the selection UI appear empty and blocks reliable manual QA.
- Added fallback display labels for blank assistant names in the single-assistant creator, group assistant add-member sheet, manual member selector, and regenerate picker, then recompiled and reinstalled the debug APK.
- The group member picker still needs a follow-up emulator pass after the fallback labels are exercised on-device. If the picker remains empty after relaunch, inspect the underlying assistant dataset rather than the layout.
- `runtime.ping()` has a unit-test assertion in `TavernRuntimeControllerTest`, but the emulator smoke surface still needs a direct UI or bridge entry point before it can be marked complete.
- If the emulator path remains blocked, record the exact screen state and resume once a visible member-selection path or a dedicated runtime smoke trigger is available.

## Risk Notes

- Do not change persisted enum serial names for `TurnTakingStrategy`; existing settings depend on them.
- Do not remove `triggerMemberReply`; message action menus still use one-member regeneration.
- Keep `applyGroupApiRewrite` behavior compatible with providers that reject consecutive assistant messages.
- Keep UI native: use existing `CardGroup`, `Switch`, `Text`, and list item patterns.
- Current repository has unrelated dirty changes. Do not revert unrelated files while implementing this plan.

## Self-Review

Spec coverage:

- Manual batch reply stability is covered by Task 6.
- Manual mode ergonomics are covered by Task 7 and Manual QA.
- Round-robin stability is covered by Task 2 and Task 4.
- Auto moderator quality is covered by Task 5.
- Context filtering and API serialization are covered by Task 1.
- Native auto-mode UI controls are covered by Task 8.
- Verification and emulator upload are covered by Task 9.

Placeholder scan:

- This plan contains no `TBD`, `TODO`, or open-ended implementation placeholders.

Type consistency:

- `GroupReplyOptions`, `GroupReplyDecision`, `GroupSpeakerCandidate`, and `GroupReplyDecisionEngine` are introduced before use.
- Existing model names match repository code: `Assistant`, `Conversation`, `GroupMember`, `ContextFilter`, `TurnTakingStrategy`, and `UIMessage`.
