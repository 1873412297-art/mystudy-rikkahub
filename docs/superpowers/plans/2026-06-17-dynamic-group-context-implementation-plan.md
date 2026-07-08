# Dynamic Group Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the first pass of dynamic group context, addressed-role routing, and plain-text `@role` mentions for group chat.

**Architecture:** Extend conversation runtime with addressed-target and event-state persistence, add a deterministic dynamic resolver for group context slicing, then wire lightweight `@role` UX into the existing native chat input and avatar surfaces. Keep the fast path rule-based and preserve existing storage and rendering formats.

**Tech Stack:** Kotlin, kotlinx.serialization, Jetpack Compose, JUnit, existing `Conversation` / `Assistant` / `UIMessage` models, existing `ChatService` group runtime pipeline.

---

## Scope

This plan implements the approved first pass only:

- Addressed parsing
- Plain-text `@role` insertion
- Addressed runtime persistence
- Rule-based event extraction
- Four-layer dynamic context slicing
- Debug visibility for addressed/focus/layer state

This plan does not include:

- Always-on model-assisted tag extraction
- Rich mention chips
- Alias dictionaries beyond configured display names
- Large-scale memory refactors

## File Structure

Create:

- `app/src/main/java/me/rerere/rikkahub/service/group/GroupAddressing.kt`
  - Addressed-role parsing and continuation detection.
- `app/src/main/java/me/rerere/rikkahub/service/group/GroupEventExtraction.kt`
  - Rule-based event/tag extraction from a local message window.
- `app/src/main/java/me/rerere/rikkahub/service/group/DynamicGroupContextResolver.kt`
  - Dynamic per-member context slicing and debug payload construction.
- `app/src/test/java/me/rerere/rikkahub/service/group/GroupAddressingTest.kt`
  - Address parsing and continuation tests.
- `app/src/test/java/me/rerere/rikkahub/service/group/GroupEventExtractionTest.kt`
  - Rule-based event extraction tests.
- `app/src/test/java/me/rerere/rikkahub/service/group/DynamicGroupContextResolverTest.kt`
  - Layer classification and context assembly tests.

Modify:

- `app/src/main/java/me/rerere/rikkahub/service/group/GroupContextModels.kt`
  - Add runtime event state, addressed state, layer/debug models.
- `app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt`
  - Persist addressed member state inside group runtime state.
- `app/src/main/java/me/rerere/rikkahub/service/group/GroupRuntimeStateUpdater.kt`
  - Update runtime event state after replies.
- `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
  - Use addressed parsing and dynamic resolver during group generation.
- `app/src/main/java/me/rerere/rikkahub/ui/hooks/ChatInputState.kt`
  - Insert mention text at cursor.
- `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt`
  - Add `@` mention picker.
- `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`
  - Pass mention callbacks from group chat surface.
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageAvatar.kt`
  - Support long-press avatar mention insertion.
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/GroupContextDebugSheet.kt`
  - Show addressed target, focus tags, layers, and event counts.

## Task 1: Add Runtime Models For Addressed Target And Event State

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/service/group/GroupContextModels.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/service/group/GroupRuntimeStateTest.kt`

- [x] Add `GroupEventState`, `GroupEventRecord`, `GroupEventFocus`, `GroupContextLayer`, and `GroupContextScoreBreakdown`.
- [x] Extend `GroupRuntimeState` with `eventState`, `activeAddressedMemberId`, and `activeAddressedTurnId`.
- [x] Update serialization tests to cover the new fields.
- [x] Run `./gradlew :app:testDebugUnitTest --tests me.rerere.rikkahub.service.group.GroupRuntimeStateTest`.

## Task 2: Add Deterministic Addressed Parsing

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/service/group/GroupAddressing.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/service/group/GroupAddressingTest.kt`

- [x] Write failing tests for direct-name addressing, `@role`, and second-person continuation.
- [x] Implement addressed parsing against enabled group member display names.
- [x] Keep parsing plain-text only; do not add structured mention message parts.
- [x] Run `./gradlew :app:testDebugUnitTest --tests me.rerere.rikkahub.service.group.GroupAddressingTest`.

## Task 3: Add Rule-Based Event Extraction

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/service/group/GroupEventExtraction.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/service/group/GroupEventExtractionTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/group/GroupRuntimeStateUpdater.kt`

- [x] Write failing tests for character/location/item/event/secret/emotion/conflict extraction.
- [x] Implement rule-based extraction from a small local message window.
- [x] Update runtime state updater to append normalized event records after group replies.
- [x] Run `./gradlew :app:testDebugUnitTest --tests me.rerere.rikkahub.service.group.GroupEventExtractionTest --tests me.rerere.rikkahub.service.group.GroupRuntimeStateUpdaterTest`.

## Task 4: Add Dynamic Context Resolver

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/service/group/DynamicGroupContextResolver.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/service/group/DynamicGroupContextResolverTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/group/GroupContextBuilder.kt`

- [x] Write failing tests for four-layer classification and context slicing.
- [x] Implement Core / Strongly Related / Weakly Related / Isolated context assembly.
- [x] Use aggressive history windows:
  - Core: 6 rounds default, 10 on strong event expansion
  - Strongly related: 2 rounds + scene summary + own latest reply
  - Weakly related: scene summary + own latest reply
  - Isolated: user last message only
- [x] Return debug metadata with layer, focus tags, and score breakdown.
- [x] Run `./gradlew :app:testDebugUnitTest --tests me.rerere.rikkahub.service.group.DynamicGroupContextResolverTest --tests me.rerere.rikkahub.service.group.GroupContextBuilderTest`.

## Task 5: Wire Dynamic Resolver Into Group Generation

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`

- [x] Replace the static group context filter path with addressed-aware dynamic resolution for group member generation.
- [x] Persist addressed target state on user send and clear it when the target changes.
- [x] In addressed mode, only generate the addressed role for that user turn.
- [x] Keep existing provider-safe transport rewrite and group member stamping intact.
- [x] Run `./gradlew :app:testDebugUnitTest --tests me.rerere.rikkahub.service.group.* --tests me.rerere.rikkahub.service.ChatServiceTest`.

## Task 6: Add Plain-Text `@role` Mention UX

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/hooks/ChatInputState.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageAvatar.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt`

- [x] Add cursor-position text insertion helper to `ChatInputState`.
- [x] Add `@` member picker in group chat input for enabled members only.
- [x] Insert `@DisplayName` as plain text and preserve it in history.
- [x] Add long-press avatar mention insertion for group member avatars.
- [x] Run `./gradlew :app:compileDebugKotlin`.

## Task 7: Expand Runtime Debug Visibility

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/GroupContextDebugSheet.kt`

- [x] Show addressed target, recent event count, focus tags, and role-layer debug details.
- [x] Keep the debug surface readable and native-looking.
- [x] Run `./gradlew :app:compileDebugKotlin`.

## Task 8: Verification

**Files:**
- Modify: `docs/superpowers/plans/2026-06-17-dynamic-group-context-implementation-plan.md`

- [x] Run `./gradlew :app:testDebugUnitTest --tests me.rerere.rikkahub.service.group.*`.
- [x] Run `./gradlew :app:assembleDebug`.
- [x] Install the updated debug APK to the running emulator if available.
- [x] Record which tasks passed and any remaining manual smoke gaps at the end of this plan.

## Manual Test Results Status

- [ ] `@role` insertion from input box
- [ ] Long-press avatar mention insertion
- [ ] Addressed turn only generating the addressed role
- [ ] Follow-up second-person continuation preserving addressed target
- [ ] Event-heavy scene causing Core history expansion
- [ ] Low-relevance role receiving isolated context only

## Execution Notes

- Completed in this pass:
  - Runtime addressed state, event state, layered resolver, and resolver debug state were implemented and covered by JVM tests.
  - Group generation now persists user-addressed targets and routes addressed turns through the dynamic resolver path.
  - Group chat input now supports plain-text `@DisplayName` completion, and group member avatars support long-press mention insertion.
  - The group runtime debug sheet now exposes addressed target, focus/event data, and last resolver layer/score details.
  - Verification passed:
    - `./gradlew :app:compileDebugKotlin`
    - `./gradlew :app:testDebugUnitTest --tests me.rerere.rikkahub.service.group.* --tests me.rerere.rikkahub.service.ChatServiceTest`
    - `./gradlew :app:assembleDebug`
    - `adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-x86_64-debug.apk`

- Still pending manual smoke:
  - The six checklist items above still need human in-app verification.
  - The app was launched on `emulator-5554`, but these interaction-level checks were not fully closed in this implementation pass.

- Cleanup follow-up:
  - `ChatService.kt` still contains an older local `applyGroupContextFilter(...)` helper near file end; current runtime path is explicitly routed to the new shared helper via import alias, so behavior is correct, but the dead helper should be deleted in a later cleanup pass.
