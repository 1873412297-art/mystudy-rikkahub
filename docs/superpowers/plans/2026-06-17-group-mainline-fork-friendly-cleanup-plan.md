# Group Mainline Fork-Friendly Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clean up the customized group-chat mainline so it is more stable, easier to test, and easier to keep in sync with upstream `rikkahub` without changing current behavior.

**Architecture:** Keep `ChatService` as the orchestration layer and move or retain deterministic group behavior in `service/group/*`. Use regression tests to lock down addressed-role flow, continuation behavior, context filtering, and runtime-state updates before deleting duplicate helpers or tightening local branches. Make only small focused edits in upstream-heavy files and prefer additive helper functions over structural rewrites.

**Tech Stack:** Kotlin, kotlinx.serialization, JUnit, existing `ChatService`, `Conversation`, `Assistant`, and `UIMessage` models, Android/Compose project build with Gradle.

---

## Scope Split

This plan covers only Stage 1 from the approved spec:

- Group mode mainline stabilization and cleanup

This plan does not cover:

- Rendering and chat UI cleanup
- Shared/common-layer cleanup unrelated to the group mainline

If later stages are needed, create separate plan files rather than extending this one.

## File Structure

Modify:

- `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
  - Remove leftover local duplicate helper logic and tighten group orchestration branches without deep restructuring.
- `app/src/main/java/me/rerere/rikkahub/service/group/GroupMessageContextFilter.kt`
  - Remain the single shared implementation for member-aware message filtering.
- `app/src/main/java/me/rerere/rikkahub/service/group/DynamicGroupContextResolver.kt`
  - Accept small low-risk cleanup if repeated work can be removed without changing behavior.
- `app/src/main/java/me/rerere/rikkahub/service/group/GroupRuntimeStateUpdater.kt`
  - Accept only narrow cleanup required by tests or clarified state ownership.
- `app/src/test/java/me/rerere/rikkahub/service/ChatServiceTest.kt`
  - Add regression tests around addressed turns and auto-chain boundaries.
- `app/src/test/java/me/rerere/rikkahub/service/group/DynamicGroupContextResolverTest.kt`
  - Add regression coverage for addressed/core and isolated behavior when cleanup lands.
- `app/src/test/java/me/rerere/rikkahub/service/group/GroupRuntimeStateUpdaterTest.kt`
  - Add focused state-update assertions if current cleanup changes write boundaries.
- `docs/superpowers/plans/2026-06-17-dynamic-group-context-implementation-plan.md`
  - Update manual/cleanup status if this cleanup resolves the dead-helper follow-up.

Keep unchanged unless a task explicitly requires them:

- Rendering/UI files
- Non-group settings/datastore files
- Common/shared model packages not directly blocking Stage 1

## Task 1: Lock The Current Group Mainline Behavior With Regression Tests

**Files:**
- Modify: `app/src/test/java/me/rerere/rikkahub/service/ChatServiceTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/service/group/DynamicGroupContextResolverTest.kt`

- [ ] **Step 1: Add a failing ChatService regression test for addressed turns suppressing auto-chain**

Add a test shaped like this to `app/src/test/java/me/rerere/rikkahub/service/ChatServiceTest.kt`:

```kotlin
@Test
fun `addressed user turn only generates one group reply`() = runTest {
    val assistant = buildGroupAssistant(
        turnTakingStrategy = TurnTakingStrategy.AUTO,
        groupReplyOptions = GroupReplyOptions(maxAutoRepliesPerUserTurn = 3),
    )
    val conversation = buildConversation(assistant = assistant)
    val alice = assistant.groupMembers.first().copy(displayName = "Alice")
    val bob = assistant.groupMembers.drop(1).first().copy(displayName = "Bob")

    repository.save(
        conversation.copy(
            assistantId = assistant.id,
            groupRuntimeState = GroupRuntimeState(),
        )
    )

    chatService.sendMessage(
        conversation.id,
        listOf(UIMessagePart.Text("@Alice speak next")),
        answer = true,
    )

    advanceUntilIdle()

    val updated = repository.getConversation(conversation.id)!!
    val assistantReplies = updated.currentMessages.filter { it.role == MessageRole.ASSISTANT }
    assertEquals(1, assistantReplies.size)
    assertEquals(alice.id, assistantReplies.single().memberId)
    assertTrue(assistantReplies.none { it.memberId == bob.id })
}
```

- [ ] **Step 2: Run the targeted test and verify the current failure mode**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests me.rerere.rikkahub.service.ChatServiceTest
```

Expected:

- The new test fails because the current cleanup target is not fully locked by test coverage yet, or because existing fixtures need adjustment to expose the addressed-turn branch.

- [ ] **Step 3: Add a failing ChatService regression test for continuation preserving addressed target**

Add a second test to `app/src/test/java/me/rerere/rikkahub/service/ChatServiceTest.kt`:

```kotlin
@Test
fun `continuation prompt keeps previous addressed member for next user turn`() = runTest {
    val assistant = buildGroupAssistant(turnTakingStrategy = TurnTakingStrategy.AUTO)
    val conversation = buildConversation(assistant = assistant)
    val alice = assistant.groupMembers.first().copy(displayName = "Alice")

    repository.save(
        conversation.copy(
            assistantId = assistant.id,
            groupRuntimeState = GroupRuntimeState(activeAddressedMemberId = alice.id),
        )
    )

    chatService.sendMessage(
        conversation.id,
        listOf(UIMessagePart.Text("please continue")),
        answer = false,
    )

    advanceUntilIdle()

    val updated = repository.getConversation(conversation.id)!!
    assertEquals(alice.id, updated.groupRuntimeState.activeAddressedMemberId)
    assertNotNull(updated.groupRuntimeState.activeAddressedTurnId)
}
```

- [ ] **Step 4: Add a focused resolver regression test for isolated low-relevance members**

Append a test to `app/src/test/java/me/rerere/rikkahub/service/group/DynamicGroupContextResolverTest.kt`:

```kotlin
@Test
fun `isolated member result does not keep private notes or relationships`() {
    val assistant = buildGroupAssistant()
    val isolatedMember = assistant.groupMembers.last()
    val runtime = GroupRuntimeState(
        privateNotes = mapOf(isolatedMember.id to "secret note"),
        relationships = mapOf(
            GroupRelationshipKey(isolatedMember.id, assistant.groupMembers.first().id) to GroupRelationshipState(affinity = 4)
        ),
    )

    val result = DynamicGroupContextResolver().resolve(
        groupAssistant = assistant,
        messages = listOf(userMessage("plain user text")),
        effectiveMemberId = isolatedMember.id,
        runtimeState = runtime,
    )

    assertEquals(GroupContextLayer.ISOLATED, result.layer)
    assertTrue(result.adjustedRuntimeState.privateNotes.isEmpty())
    assertTrue(result.adjustedRuntimeState.relationships.isEmpty())
}
```

- [ ] **Step 5: Run the new focused tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests me.rerere.rikkahub.service.ChatServiceTest --tests me.rerere.rikkahub.service.group.DynamicGroupContextResolverTest
```

Expected:

- At least one new test fails before implementation cleanup is applied.

- [ ] **Step 6: Commit the regression-test baseline**

```bash
git add app/src/test/java/me/rerere/rikkahub/service/ChatServiceTest.kt app/src/test/java/me/rerere/rikkahub/service/group/DynamicGroupContextResolverTest.kt
git commit -m "test: lock group mainline cleanup behavior"
```

## Task 2: Remove The Local Duplicate Group Context Filter And Keep One Shared Implementation

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/group/GroupMessageContextFilter.kt`

- [ ] **Step 1: Make the shared filter the only implementation**

In `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`, remove the leftover local helper at file end:

```kotlin
private fun List<UIMessage>.applyGroupContextFilter(
    groupAssistant: Assistant,
    effectiveMemberId: Uuid?,
): List<UIMessage> { ... }
```

Keep the explicit import alias already used near the top of the file:

```kotlin
import me.rerere.rikkahub.service.group.applyGroupContextFilter as applyDynamicGroupContextFilter
```

The call site inside `handleMessageComplete(...)` should remain:

```kotlin
val baseVisibleMessages = conversation.currentMessages
    .let { ... }
    .applyDynamicGroupContextFilter(groupAssistant, effectiveMemberId)
```

- [ ] **Step 2: Keep the shared implementation minimal and deterministic**

Verify `app/src/main/java/me/rerere/rikkahub/service/group/GroupMessageContextFilter.kt` remains the only implementation and preserves the existing four-layer behavior:

```kotlin
internal fun List<UIMessage>.applyGroupContextFilter(
    groupAssistant: Assistant,
    effectiveMemberId: Uuid?,
): List<UIMessage>
```

Do not rename the function. Do not change its signature in this task.

- [ ] **Step 3: Add a narrow test if deletion exposes visibility or behavior risk**

If needed, append a focused test to `app/src/test/java/me/rerere/rikkahub/service/group/DynamicGroupContextResolverTest.kt` or the existing group filter test file using this exact call shape:

```kotlin
val result = messages.applyGroupContextFilter(assistant, memberId)
assertEquals(expectedIds, result.map { it.id })
```

- [ ] **Step 4: Run targeted tests after helper deletion**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests me.rerere.rikkahub.service.group.DynamicGroupContextResolverTest --tests me.rerere.rikkahub.service.ChatServiceTest
```

Expected:

- PASS

- [ ] **Step 5: Commit the dead-helper cleanup**

```bash
git add app/src/main/java/me/rerere/rikkahub/service/ChatService.kt app/src/main/java/me/rerere/rikkahub/service/group/GroupMessageContextFilter.kt app/src/test/java/me/rerere/rikkahub/service/group/DynamicGroupContextResolverTest.kt
git commit -m "refactor: remove duplicate group context filter"
```

## Task 3: Tighten Addressed-Turn Orchestration In ChatService Without Rewriting It

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/service/ChatServiceTest.kt`

- [ ] **Step 1: Extract tiny private helpers for addressed-turn decisions**

Inside `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`, add small private helpers close to `handleMessageComplete(...)`:

```kotlin
private fun isInitialAddressedGroupTurn(
    assistant: Assistant,
    memberId: Uuid?,
    groupRepliesSinceLastUser: Int,
    runtimeState: GroupRuntimeState,
): Boolean {
    return assistant.assistantType == AssistantType.GROUP &&
        memberId == null &&
        groupRepliesSinceLastUser == 0 &&
        runtimeState.activeAddressedMemberId != null
}

private fun resolveRequestedGroupMemberId(
    explicitMemberId: Uuid?,
    runtimeState: GroupRuntimeState,
    isAddressedTurn: Boolean,
): Uuid? {
    return explicitMemberId ?: runtimeState.activeAddressedMemberId?.takeIf { isAddressedTurn }
}
```

- [ ] **Step 2: Replace inline addressed-turn branching with the helpers**

Replace the current inline branch in `handleMessageComplete(...)`:

```kotlin
val isAddressedTurn = ...
val resolvedMemberId = memberId
    ?: initialConversation.groupRuntimeState.activeAddressedMemberId
        ?.takeIf { isAddressedTurn }
    ?: resolveNextSpeaker(...)
```

with:

```kotlin
val isAddressedTurn = isInitialAddressedGroupTurn(
    assistant = groupAssistant,
    memberId = memberId,
    groupRepliesSinceLastUser = groupRepliesSinceLastUser,
    runtimeState = initialConversation.groupRuntimeState,
)
val requestedMemberId = resolveRequestedGroupMemberId(
    explicitMemberId = memberId,
    runtimeState = initialConversation.groupRuntimeState,
    isAddressedTurn = isAddressedTurn,
)
val resolvedMemberId = requestedMemberId ?: resolveNextSpeaker(
    conversation = initialConversation,
    groupAssistant = groupAssistant,
    settings = settings,
    allowModeratorStop = groupRepliesSinceLastUser > 0,
)
```

- [ ] **Step 3: Keep auto-chain suppression behavior explicit**

Retain the existing guard:

```kotlin
!isAddressedTurn
```

inside the auto-chain branch, but keep it in the final conditional as its own line so the behavior is obvious:

```kotlin
} else if (
    allowAutoChain &&
    groupAssistant.assistantType == AssistantType.GROUP &&
    groupAssistant.turnTakingStrategy != TurnTakingStrategy.MANUAL &&
    !isAddressedTurn
) {
```

- [ ] **Step 4: Re-run the addressed-turn ChatService tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests me.rerere.rikkahub.service.ChatServiceTest
```

Expected:

- PASS

- [ ] **Step 5: Commit the addressed-turn orchestration cleanup**

```bash
git add app/src/main/java/me/rerere/rikkahub/service/ChatService.kt app/src/test/java/me/rerere/rikkahub/service/ChatServiceTest.kt
git commit -m "refactor: clarify addressed turn orchestration"
```

## Task 4: Reduce Repeated Group-Mainline Work Without Adding New Caches

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/group/DynamicGroupContextResolver.kt`

- [ ] **Step 1: Reuse resolved group member objects inside handleMessageComplete**

In `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`, keep the resolved group member lookup in one local variable and reuse it where the same member is needed later:

```kotlin
val resolvedMember = groupAssistant.groupMembers.find { it.id == resolvedMemberId }
    ?.takeIf { it.enabled }
    ?: return
```

Then reuse `resolvedMember` for:

```kotlin
val sourceAssistant = settings.getAssistantById(resolvedMember.assistantId) ?: groupAssistant
val modelId = resolvedMember.chatModelIdOverride ?: groupAssistant.chatModelId ?: settings.chatModelId
```

and later stamping:

```kotlin
val memberName = resolvedMember.displayName.takeIf { it.isNotBlank() }
```

- [ ] **Step 2: Avoid duplicate small text joins in withUpdatedGroupAddressedState**

Keep the user text derivation in one block:

```kotlin
val userText = userMessage.parts
    .filterIsInstance<UIMessagePart.Text>()
    .joinToString("\n") { it.text }
    .trim()
```

Do not recompute this value elsewhere in the same send path.

- [ ] **Step 3: Keep resolver scoring work local and reuse recent text window once**

In `app/src/main/java/me/rerere/rikkahub/service/group/DynamicGroupContextResolver.kt`, preserve the existing pattern of building:

```kotlin
val recentText = messages.takeLast(4).joinToString("\n") { it.toText() }
```

and reuse it for all member-name relevance checks in `scoreMembers(...)`. Do not introduce persistent memoization.

- [ ] **Step 4: Run the resolver and ChatService test suites**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests me.rerere.rikkahub.service.group.DynamicGroupContextResolverTest --tests me.rerere.rikkahub.service.ChatServiceTest
```

Expected:

- PASS

- [ ] **Step 5: Commit the low-risk performance cleanup**

```bash
git add app/src/main/java/me/rerere/rikkahub/service/ChatService.kt app/src/main/java/me/rerere/rikkahub/service/group/DynamicGroupContextResolver.kt
git commit -m "refactor: trim repeated group mainline work"
```

## Task 5: Tighten Runtime-State Write Boundaries And Verify No Regression

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/group/GroupRuntimeStateUpdater.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/service/group/GroupRuntimeStateUpdaterTest.kt`

- [ ] **Step 1: Assert the three allowed write points remain explicit**

Keep runtime-state writes limited to:

```kotlin
// user-send path
groupRuntimeState = groupRuntimeState.copy(
    activeAddressedMemberId = resolution?.memberId,
    activeAddressedTurnId = resolution?.memberId?.let { userMessage.id },
)
```

and:

```kotlin
// post-generation path
groupRuntimeState = GroupRuntimeStateUpdater().updateAfterReply(...)
```

Do not add any additional `groupRuntimeState = ...copy(...)` writes elsewhere in this task.

- [ ] **Step 2: Add a narrow updater regression test if missing**

Append this to `app/src/test/java/me/rerere/rikkahub/service/group/GroupRuntimeStateUpdaterTest.kt` if equivalent coverage is missing:

```kotlin
@Test
fun `update after reply preserves addressed debug state when appending event state`() {
    val assistant = buildGroupAssistant()
    val previous = GroupRuntimeState(
        activeAddressedMemberId = assistant.groupMembers.first().id,
        lastResolverDebug = GroupResolverDebugState(
            speakerId = assistant.groupMembers.first().id,
            layer = GroupContextLayer.CORE.name,
        ),
    )

    val updated = GroupRuntimeStateUpdater().updateAfterReply(
        previous = previous,
        groupAssistant = assistant,
        messages = listOf(userMessage("hello"), assistantMessage("reply", assistant.groupMembers.first().id)),
        speakerId = assistant.groupMembers.first().id,
    )

    assertEquals(previous.activeAddressedMemberId, updated.activeAddressedMemberId)
    assertEquals(previous.lastResolverDebug, updated.lastResolverDebug)
    assertTrue(updated.eventState.recentEvents.isNotEmpty())
}
```

- [ ] **Step 3: Run the updater and ChatService test suites**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests me.rerere.rikkahub.service.group.GroupRuntimeStateUpdaterTest --tests me.rerere.rikkahub.service.ChatServiceTest
```

Expected:

- PASS

- [ ] **Step 4: Commit the runtime-state boundary cleanup**

```bash
git add app/src/main/java/me/rerere/rikkahub/service/ChatService.kt app/src/main/java/me/rerere/rikkahub/service/group/GroupRuntimeStateUpdater.kt app/src/test/java/me/rerere/rikkahub/service/group/GroupRuntimeStateUpdaterTest.kt
git commit -m "test: guard group runtime state boundaries"
```

## Task 6: Full Verification And Plan Status Update

**Files:**
- Modify: `docs/superpowers/plans/2026-06-17-dynamic-group-context-implementation-plan.md`

- [ ] **Step 1: Run the full group-related JVM test sweep**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests me.rerere.rikkahub.service.group.* --tests me.rerere.rikkahub.service.ChatServiceTest
```

Expected:

- PASS

- [ ] **Step 2: Run compile verification**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected:

- PASS

- [ ] **Step 3: Build the debug APK**

Run:

```bash
./gradlew :app:assembleDebug
```

Expected:

- PASS

- [ ] **Step 4: Update the earlier implementation-status plan**

In `docs/superpowers/plans/2026-06-17-dynamic-group-context-implementation-plan.md`, update the cleanup note so it no longer says the dead local helper still exists if Task 2 removed it successfully.

Replace:

```markdown
- Cleanup follow-up:
  - `ChatService.kt` still contains an older local `applyGroupContextFilter(...)` helper near file end; current runtime path is explicitly routed to the new shared helper via import alias, so behavior is correct, but the dead helper should be deleted in a later cleanup pass.
```

with:

```markdown
- Cleanup follow-up:
  - The older local `applyGroupContextFilter(...)` helper in `ChatService.kt` was removed during the fork-friendly group-mainline cleanup pass; the shared implementation in `service/group/GroupMessageContextFilter.kt` is now the only filter path.
```

- [ ] **Step 5: Commit the verification and status update**

```bash
git add docs/superpowers/plans/2026-06-17-dynamic-group-context-implementation-plan.md
git commit -m "docs: update group mainline cleanup status"
```

## Self-Review Checklist

Before executing this plan, verify:

- Every spec requirement for Stage 1 maps to one of the tasks above
- No task reaches into rendering/UI or common-layer cleanup
- No step introduces a deep `ChatService` rewrite
- No step adds a new cache layer
- The duplicate local `applyGroupContextFilter(...)` removal is covered by tests before deletion
- The plan remains merge-friendly for future upstream sync
