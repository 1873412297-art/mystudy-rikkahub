# Group Chat Correctness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make group context filtering, automatic reply limits, and persisted speaker queues deterministic and consistent with user settings.

**Architecture:** Keep `ChatService` as the generation orchestrator. Put deterministic filtering-pipeline and queue-selection rules in focused `service/group` helpers so JVM tests can exercise the same code used by production without constructing Android services.

**Tech Stack:** Kotlin, kotlinx.serialization models, JUnit 4, Android Gradle Plugin, AndroidX instrumentation tests, adb emulator QA.

## Global Constraints

- Work only in `C:\Users\18734\Desktop\HTML\rikkahub-port-2.4.1` on `codex/port-private-to-2.4.1`.
- Preserve existing manual multi-member selection and addressed-member precedence.
- Keep fork-specific rules under `app/src/main/java/me/rerere/rikkahub/service/group` where practical.
- Do not add a database migration; existing conversations repair their queue on the next automatic turn.
- Use test-first changes and small focused commits.
- Keep the existing Tavern Helper rendering/runtime work intact.

---

## File Structure

- Create `app/src/main/java/me/rerere/rikkahub/service/group/GroupContextPipeline.kt`
  - Owns the exactly-once choice between layered resolution and direct filtering.
- Create `app/src/main/java/me/rerere/rikkahub/service/group/GroupTurnScheduler.kt`
  - Owns enabled-member queue normalization, round-robin selection, different-speaker fallback, and reply-limit coercion.
- Modify `app/src/main/java/me/rerere/rikkahub/service/group/GroupMessageContextFilter.kt`
  - Applies strict chronological `maxMessages` limiting.
- Modify `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
  - Delegates to the new helpers and persists normalized selection state.
- Create `app/src/test/java/me/rerere/rikkahub/service/group/GroupMessageContextFilterTest.kt`
  - Covers order and strict upper-bound behavior.
- Create `app/src/test/java/me/rerere/rikkahub/service/group/GroupContextPipelineTest.kt`
  - Covers the production filtering decision and addressed `DIRECTED` behavior.
- Create `app/src/test/java/me/rerere/rikkahub/service/group/GroupTurnSchedulerTest.kt`
  - Covers queue repair, cursor semantics, different-speaker fallback, and reply limits.

---

### Task 1: Make `maxMessages` chronological and strict

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/service/group/GroupMessageContextFilter.kt:43-48`
- Create: `app/src/test/java/me/rerere/rikkahub/service/group/GroupMessageContextFilterTest.kt`

**Interfaces:**
- Consumes: `List<UIMessage>.applyGroupContextFilter(Assistant, Uuid?)`
- Produces: the same interface with source order preserved and result size bounded by `ContextFilter.maxMessages`

- [x] **Step 1: Write failing order and upper-bound tests**

Create `GroupMessageContextFilterTest.kt`:

```kotlin
package me.rerere.rikkahub.service.group

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.ContextFilter
import me.rerere.rikkahub.data.model.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class GroupMessageContextFilterTest {
    private val memberA = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val memberB = Uuid.parse("00000000-0000-0000-0000-000000000002")
    private val sourceAssistantId = Uuid.parse("00000000-0000-0000-0000-000000000010")

    @Test
    fun `max messages keeps the last messages in chronological order`() {
        val group = groupWithMaxMessages(3)
        val messages = listOf(
            UIMessage.user("u1"),
            assistantMessage(memberA, "a1"),
            UIMessage.user("u2"),
            assistantMessage(memberB, "b1"),
            UIMessage.user("u3"),
        )

        val result = messages.applyGroupContextFilter(group, memberA)

        assertEquals(listOf("u2", "b1", "u3"), result.map { it.toText() })
    }

    @Test
    fun `max messages remains a strict bound when user messages exceed it`() {
        val group = groupWithMaxMessages(2)
        val messages = listOf(
            UIMessage.user("u1"),
            UIMessage.user("u2"),
            UIMessage.user("u3"),
        )

        val result = messages.applyGroupContextFilter(group, memberA)

        assertEquals(2, result.size)
        assertEquals(listOf("u2", "u3"), result.map { it.toText() })
    }

    private fun groupWithMaxMessages(maxMessages: Int): Assistant = Assistant(
        assistantType = AssistantType.GROUP,
        groupMembers = listOf(
            GroupMember(
                id = memberA,
                assistantId = sourceAssistantId,
                displayName = "Alice",
                contextFilter = ContextFilter(maxMessages = maxMessages),
            ),
            GroupMember(id = memberB, assistantId = sourceAssistantId, displayName = "Bob"),
        ),
    )

    private fun assistantMessage(memberId: Uuid, text: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        memberId = memberId,
        parts = listOf(UIMessagePart.Text(text)),
    )
}
```

- [x] **Step 2: Run the focused test and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.group.GroupMessageContextFilterTest" --console=plain
```

Expected: both tests fail because the current implementation moves all user messages to the end and may exceed the configured limit.

- [x] **Step 3: Implement strict chronological limiting**

Replace the current user/other recombination in `GroupMessageContextFilter.kt` with:

```kotlin
if (filter.maxMessages > 0 && result.size > filter.maxMessages) {
    result = result.takeLast(filter.maxMessages)
}
```

- [x] **Step 4: Run focused and existing resolver tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.group.GroupMessageContextFilterTest" --tests "me.rerere.rikkahub.service.group.DynamicGroupContextResolverTest" --console=plain
```

Expected: `BUILD SUCCESSFUL` with zero failed tests.

- [x] **Step 5: Commit the filter fix**

```powershell
git add app/src/main/java/me/rerere/rikkahub/service/group/GroupMessageContextFilter.kt app/src/test/java/me/rerere/rikkahub/service/group/GroupMessageContextFilterTest.kt
git commit -m "fix: preserve group context message order"
```

---

### Task 2: Apply group context filtering exactly once

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/service/group/GroupContextPipeline.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/service/group/GroupContextPipelineTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt:857-879`

**Interfaces:**
- Consumes: an unfiltered selected message range, group assistant, effective member ID, and `GroupRuntimeState`
- Produces: `GroupContextPipelineResult(visibleMessages, dynamicResult)`

- [x] **Step 1: Write the failing full-pipeline regression test**

Create `GroupContextPipelineTest.kt`:

```kotlin
package me.rerere.rikkahub.service.group

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.ContextFilter
import me.rerere.rikkahub.data.model.ContextScope
import me.rerere.rikkahub.data.model.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.uuid.Uuid

class GroupContextPipelineTest {
    private val memberA = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val memberB = Uuid.parse("00000000-0000-0000-0000-000000000002")
    private val sourceAssistantId = Uuid.parse("00000000-0000-0000-0000-000000000010")

    @Test
    fun `layered directed context retains the addressed user prompt`() {
        val group = Assistant(
            assistantType = AssistantType.GROUP,
            groupMembers = listOf(
                GroupMember(
                    id = memberA,
                    assistantId = sourceAssistantId,
                    displayName = "Alice",
                    contextFilter = ContextFilter(scope = ContextScope.DIRECTED),
                ),
                GroupMember(id = memberB, assistantId = sourceAssistantId, displayName = "Bob"),
            ),
        )
        val messages = listOf(
            UIMessage.user("Public setup"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                memberId = memberB,
                parts = listOf(UIMessagePart.Text("Public clue")),
            ),
            UIMessage.user("@Alice answer this"),
        )

        val result = resolveGroupContextMessages(
            groupAssistant = group,
            messages = messages,
            effectiveMemberId = memberA,
            runtimeState = GroupRuntimeState(activeAddressedMemberId = memberA),
        )

        assertNotNull(result.dynamicResult)
        assertEquals("@Alice answer this", result.visibleMessages.last().toText())
    }
}
```

- [x] **Step 2: Run the test and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.group.GroupContextPipelineTest" --console=plain
```

Expected: compilation fails because `resolveGroupContextMessages` and `GroupContextPipelineResult` do not exist.

- [x] **Step 3: Add the single-owner pipeline helper**

Create `GroupContextPipeline.kt`:

```kotlin
package me.rerere.rikkahub.service.group

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import kotlin.uuid.Uuid

internal data class GroupContextPipelineResult(
    val visibleMessages: List<UIMessage>,
    val dynamicResult: DynamicGroupContextResult? = null,
)

internal fun resolveGroupContextMessages(
    groupAssistant: Assistant,
    messages: List<UIMessage>,
    effectiveMemberId: Uuid?,
    runtimeState: GroupRuntimeState,
): GroupContextPipelineResult {
    if (groupAssistant.assistantType != AssistantType.GROUP || effectiveMemberId == null) {
        return GroupContextPipelineResult(visibleMessages = messages)
    }
    if (!groupAssistant.groupContextOptions.enableLayeredContext) {
        return GroupContextPipelineResult(
            visibleMessages = messages.applyGroupContextFilter(groupAssistant, effectiveMemberId),
        )
    }
    val dynamicResult = DynamicGroupContextResolver().resolve(
        groupAssistant = groupAssistant,
        messages = messages,
        effectiveMemberId = effectiveMemberId,
        runtimeState = runtimeState,
    )
    return GroupContextPipelineResult(
        visibleMessages = dynamicResult.visibleMessages,
        dynamicResult = dynamicResult,
    )
}
```

- [x] **Step 4: Route `ChatService` through the helper**

Add this import and remove the aliased `applyDynamicGroupContextFilter` import:

```kotlin
import me.rerere.rikkahub.service.group.resolveGroupContextMessages
```

Then replace the pre-filtered `baseVisibleMessages` branch with:

```kotlin
val selectedMessages = conversation.currentMessages.let {
    if (messageRange != null) {
        it.subList(messageRange.start, messageRange.endInclusive + 1)
    } else {
        it
    }
}
val groupContext = resolveGroupContextMessages(
    groupAssistant = groupAssistant,
    messages = selectedMessages,
    effectiveMemberId = effectiveMemberId,
    runtimeState = conversation.groupRuntimeState,
)
dynamicContextResult = groupContext.dynamicResult
val visibleMessages = groupContext.visibleMessages.applyEnhancementPrompt(assistant)
```

Remove the now-unused aliased `applyDynamicGroupContextFilter` import from `ChatService.kt`.

- [x] **Step 5: Run the group context suite**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.group.GroupContextPipelineTest" --tests "me.rerere.rikkahub.service.group.GroupMessageContextFilterTest" --tests "me.rerere.rikkahub.service.group.DynamicGroupContextResolverTest" --tests "me.rerere.rikkahub.service.group.GroupContextBuilderTest" --console=plain
```

Expected: `BUILD SUCCESSFUL` with the addressed user prompt retained.

- [x] **Step 6: Commit the exactly-once pipeline**

```powershell
git add app/src/main/java/me/rerere/rikkahub/service/ChatService.kt app/src/main/java/me/rerere/rikkahub/service/group/GroupContextPipeline.kt app/src/test/java/me/rerere/rikkahub/service/group/GroupContextPipelineTest.kt
git commit -m "fix: apply group context filtering once"
```

---

### Task 3: Normalize automatic speaker queues

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/service/group/GroupTurnScheduler.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/service/group/GroupTurnSchedulerTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt:1753-1859`

**Interfaces:**
- Produces: `normalizeGroupMemberQueue(List<Uuid>, List<Uuid>): List<Uuid>`
- Produces: `nextRoundRobinSelection(List<Uuid>, Int, Uuid?, List<Uuid>): GroupTurnSelection?`
- Produces: `nextDifferentGroupMember(List<Uuid>, Uuid?): Uuid?`
- Produces: `resolveGroupAutoReplyLimit(Int): Int`

- [x] **Step 1: Write failing scheduler tests**

Create `GroupTurnSchedulerTest.kt`:

```kotlin
package me.rerere.rikkahub.service.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class GroupTurnSchedulerTest {
    private val a = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val b = Uuid.parse("00000000-0000-0000-0000-000000000002")
    private val c = Uuid.parse("00000000-0000-0000-0000-000000000003")
    private val removed = Uuid.parse("00000000-0000-0000-0000-000000000099")

    @Test
    fun `queue repair removes disabled deleted and duplicate members then appends newly enabled members`() {
        assertEquals(
            listOf(b, a, c),
            normalizeGroupMemberQueue(
                persistedQueue = listOf(removed, b, b, a),
                enabledMemberIds = listOf(a, b, c),
            ),
        )
    }

    @Test
    fun `new round robin queue starts at first enabled member and advances`() {
        val first = nextRoundRobinSelection(emptyList(), 0, null, listOf(a, b))
        val second = nextRoundRobinSelection(
            persistedQueue = first!!.queue,
            persistedIndex = first.selectedIndex,
            activeMemberId = first.memberId,
            enabledMemberIds = listOf(a, b),
        )

        assertEquals(a, first.memberId)
        assertEquals(0, first.selectedIndex)
        assertEquals(b, second!!.memberId)
        assertEquals(1, second.selectedIndex)
    }

    @Test
    fun `stale active member does not block selection`() {
        val result = nextRoundRobinSelection(
            persistedQueue = listOf(removed, b),
            persistedIndex = 0,
            activeMemberId = removed,
            enabledMemberIds = listOf(a, b),
        )

        assertEquals(b, result!!.memberId)
        assertEquals(listOf(b, a), result.queue)
    }

    @Test
    fun `empty enabled members return no selection`() {
        assertNull(nextRoundRobinSelection(listOf(a), 0, a, emptyList()))
    }

    @Test
    fun `different member fallback changes speaker when possible`() {
        assertEquals(b, nextDifferentGroupMember(listOf(a, b), a))
        assertEquals(a, nextDifferentGroupMember(listOf(a), a))
    }

    @Test
    fun `reply limit respects configured value and floors invalid values`() {
        assertEquals(1, resolveGroupAutoReplyLimit(1))
        assertEquals(3, resolveGroupAutoReplyLimit(3))
        assertEquals(1, resolveGroupAutoReplyLimit(0))
    }
}
```

- [x] **Step 2: Run the scheduler test and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.group.GroupTurnSchedulerTest" --console=plain
```

Expected: compilation fails because the scheduler interfaces do not exist.

- [x] **Step 3: Implement the deterministic scheduler helper**

Create `GroupTurnScheduler.kt`:

```kotlin
package me.rerere.rikkahub.service.group

import kotlin.uuid.Uuid

internal data class GroupTurnSelection(
    val memberId: Uuid,
    val queue: List<Uuid>,
    val selectedIndex: Int,
)

internal fun normalizeGroupMemberQueue(
    persistedQueue: List<Uuid>,
    enabledMemberIds: List<Uuid>,
): List<Uuid> {
    val enabled = enabledMemberIds.distinct()
    val enabledSet = enabled.toSet()
    val retained = persistedQueue.filter { it in enabledSet }.distinct()
    return retained + enabled.filterNot { it in retained }
}

internal fun nextRoundRobinSelection(
    persistedQueue: List<Uuid>,
    persistedIndex: Int,
    activeMemberId: Uuid?,
    enabledMemberIds: List<Uuid>,
): GroupTurnSelection? {
    val queue = normalizeGroupMemberQueue(persistedQueue, enabledMemberIds)
    if (queue.isEmpty()) return null
    val cursorMemberId = persistedQueue.getOrNull(persistedIndex)?.takeIf { it in queue }
    val lastIndex = when {
        activeMemberId in queue -> queue.indexOf(activeMemberId)
        cursorMemberId != null -> queue.indexOf(cursorMemberId)
        else -> -1
    }
    val nextIndex = (lastIndex + 1) % queue.size
    return GroupTurnSelection(
        memberId = queue[nextIndex],
        queue = queue,
        selectedIndex = nextIndex,
    )
}

internal fun nextDifferentGroupMember(
    queue: List<Uuid>,
    currentMemberId: Uuid?,
): Uuid? = queue.firstOrNull { it != currentMemberId } ?: queue.firstOrNull()

internal fun resolveGroupAutoReplyLimit(configuredLimit: Int): Int = configuredLimit.coerceAtLeast(1)
```

- [x] **Step 4: Run the scheduler tests and verify GREEN**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.group.GroupTurnSchedulerTest" --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 5: Replace round-robin selection in `ChatService`**

Add these imports:

```kotlin
import me.rerere.rikkahub.service.group.nextDifferentGroupMember
import me.rerere.rikkahub.service.group.nextRoundRobinSelection
import me.rerere.rikkahub.service.group.normalizeGroupMemberQueue
import me.rerere.rikkahub.service.group.resolveGroupAutoReplyLimit
```

Remove `getNextSpeakerRoundRobin` and `getNextDifferentSpeaker` after their call sites are replaced.

In the round-robin branch, use:

```kotlin
val selection = nextRoundRobinSelection(
    persistedQueue = conversation.groupMemberQueue,
    persistedIndex = conversation.groupMemberQueueIndex,
    activeMemberId = conversation.activeGroupMemberId,
    enabledMemberIds = groupAssistant.groupMembers.filter { it.enabled }.map { it.id },
) ?: return null
saveConversation(
    conversation.id,
    conversation.copy(
        activeGroupMemberId = selection.memberId,
        groupMemberQueue = selection.queue,
        groupMemberQueueIndex = selection.selectedIndex,
    ),
)
return selection.memberId
```

Replace the moderator branch in `resolveNextSpeaker` with:

```kotlin
TurnTakingStrategy.AUTO_MODERATOR -> {
    val enabledMemberIds = groupAssistant.groupMembers.filter { it.enabled }.map { it.id }
    val queue = normalizeGroupMemberQueue(
        persistedQueue = conversation.groupMemberQueue,
        enabledMemberIds = enabledMemberIds,
    )
    if (queue.isEmpty()) return null
    val resolved = resolveNextSpeakerViaModerator(
        conversation = conversation,
        groupAssistant = groupAssistant,
        settings = settings,
        allowStop = allowModeratorStop,
    )
    val activeId = conversation.activeGroupMemberId?.takeIf { it in queue }
    val nextId = when {
        resolved == null -> null
        groupAssistant.groupReplyOptions.allowConsecutiveSameSpeaker -> resolved
        resolved == activeId -> nextDifferentGroupMember(queue, activeId)
        else -> resolved
    }
    if (nextId != null) {
        val selectedIndex = queue.indexOf(nextId).takeIf { it >= 0 } ?: return null
        saveConversation(
            conversation.id,
            conversation.copy(
                activeGroupMemberId = nextId,
                groupMemberQueue = queue,
                groupMemberQueueIndex = selectedIndex,
            ),
        )
    }
    return nextId
}
```

Inside `resolveNextSpeakerViaModerator`, replace the old round-robin fallback expression with:

```kotlin
val queueFallback = nextRoundRobinSelection(
    persistedQueue = conversation.groupMemberQueue,
    persistedIndex = conversation.groupMemberQueueIndex,
    activeMemberId = conversation.activeGroupMemberId,
    enabledMemberIds = enabled.map { it.id },
)?.memberId
val localFallback = localScores.firstOrNull()?.memberId ?: queueFallback
```

- [x] **Step 6: Make the automatic reply cap authoritative**

Replace the member-count-based cap block in `ChatService` with:

```kotlin
val maxReplies = resolveGroupAutoReplyLimit(
    groupAssistant.groupReplyOptions.maxAutoRepliesPerUserTurn,
)
```

- [x] **Step 7: Run scheduler, moderator, and service tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.group.GroupTurnSchedulerTest" --tests "me.rerere.rikkahub.service.group.GroupModeratorDecisionTest" --tests "me.rerere.rikkahub.service.group.GroupSpeakerScorerTest" --tests "me.rerere.rikkahub.service.ChatServiceTest" --console=plain
```

Expected: `BUILD SUCCESSFUL` with no duplicate first round-robin turn and no raised moderator cap.

- [x] **Step 8: Commit scheduler integration**

```powershell
git add app/src/main/java/me/rerere/rikkahub/service/ChatService.kt app/src/main/java/me/rerere/rikkahub/service/group/GroupTurnScheduler.kt app/src/test/java/me/rerere/rikkahub/service/group/GroupTurnSchedulerTest.kt
git commit -m "fix: normalize automatic group turns"
```

---

### Task 4: Full verification and emulator regression

**Files:**
- Modify: `docs/superpowers/plans/2026-07-15-group-chat-correctness-plan.md`

**Interfaces:**
- Consumes: all changes from Tasks 1-3
- Produces: verified commits and a dated execution-result block

- [x] **Step 1: Run the complete JVM test suite and Debug build**

Run:

```powershell
.\gradlew.bat test :app:assembleDebug --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 2: Run all Android instrumentation tests**

Confirm `emulator-5554` is online with `adb devices`, then run:

```powershell
.\gradlew.bat connectedDebugAndroidTest --console=plain
```

Expected: all module tests finish with `BUILD SUCCESSFUL`.

- [x] **Step 3: Install and launch the current universal APK**

Run:

```powershell
adb -s emulator-5554 logcat -c
adb -s emulator-5554 install -r -d app\build\outputs\apk\debug\app-universal-debug.apk
adb -s emulator-5554 shell am force-stop me.rerere.rikkahub.debug
adb -s emulator-5554 shell am start -n me.rerere.rikkahub.debug/me.rerere.rikkahub.RouteActivity
Start-Sleep -Seconds 8
adb -s emulator-5554 shell pidof -s me.rerere.rikkahub.debug
adb -s emulator-5554 shell dumpsys window | Select-String -Pattern 'mCurrentFocus|mFocusedApp'
adb -s emulator-5554 logcat -b crash -d
```

Expected: a live process ID, `RouteActivity` focused, and an empty crash buffer.

- [x] **Step 4: Record the execution result**

Append a dated `2026-07-15 Execution Result` section to this plan containing:

```markdown
- Context filtering: pass, exactly-once pipeline and addressed DIRECTED regression test
- Message limiting: pass, chronological order and strict bound tests
- Round-robin queue: pass, initialization, advancement, and stale-member repair tests
- Auto-reply cap: pass, configured value remains authoritative
- JVM tests: command and BUILD SUCCESSFUL
- Instrumentation: command, emulator ID, and BUILD SUCCESSFUL
- APK smoke: exact APK path, focused activity, and crash-buffer result
```

- [x] **Step 5: Check the final diff and commit verification records**

Run:

```powershell
git diff --check
git status --short
git add docs/superpowers/plans/2026-07-15-group-chat-correctness-plan.md
git commit -m "docs: record group chat correctness verification"
git push
```

Expected: clean status on `codex/port-private-to-2.4.1` after the push.

## 2026-07-15 Execution Result

- Context filtering: pass. `GroupContextPipelineTest` completed 1 test with 0 failures/errors; the exactly-once layered pipeline retained the addressed `DIRECTED` user prompt.
- Message limiting: pass. `GroupMessageContextFilterTest` completed 2 tests with 0 failures/errors, covering chronological order and the strict `maxMessages` bound.
- Round-robin queue: pass. `GroupTurnSchedulerTest` completed 14 tests with 0 failures/errors, including initialization, advancement, stale-member repair, cursor repair, and moderator selection consistency.
- Auto-reply cap: pass. The scheduler suite verified that the configured value remains authoritative and that invalid non-positive values are floored to 1.
- JVM tests and Debug build: `.\gradlew.bat test :app:assembleDebug --console=plain` exited 0 with `BUILD SUCCESSFUL in 19s` (`288 actionable tasks: 13 executed, 275 up-to-date`).
- Instrumentation: `emulator-5554` was online as `sdk_gphone64_x86_64`, `sys.boot_completed=1`. `.\gradlew.bat connectedDebugAndroidTest --console=plain` exited 0 with all module tests finishing and `BUILD SUCCESSFUL in 1m 17s` (`550 actionable tasks: 20 executed, 530 up-to-date`); Gradle identified the device as `RikkaHub(AVD) - 15`.
- APK smoke: installed `C:\Users\18734\Desktop\HTML\rikkahub-port-2.4.1\app\build\outputs\apk\debug\app-universal-debug.apk` with `Success`, then launched `me.rerere.rikkahub.debug/me.rerere.rikkahub.RouteActivity`. After 8 seconds, PID was `13661`; both `mCurrentFocus` and `mFocusedApp` named `RouteActivity`; `adb logcat -b crash -d` returned an empty buffer.
- UI render evidence: `adb -s emulator-5554 shell uiautomator dump /sdcard/task-4-window.xml` created the device hierarchy, and `adb -s emulator-5554 pull /sdcard/task-4-window.xml .superpowers\sdd\task-4-window.xml` copied it to the local artifact path `.superpowers\sdd\task-4-window.xml` (15385 bytes). The hierarchy contained package `me.rerere.rikkahub.debug`, 46 nodes, and visible chat-home text including `新聊天`, `默认助手 / Auto (RikkaHub)`, and `输入消息与AI聊天`. The artifact is stored under the locally ignored `.superpowers` directory and is not part of this commit.
- Verification record: `git diff --check` exited 0 before staging; this task records the result in one local documentation commit, with push reserved for the controlling agent.
