# Group Chat Director Controls Design

**Date:** 2026-07-16
**Branch baseline:** `codex/port-private-to-2.4.1` at `0f157fbe`
**Status:** Approved during interactive design review

## Goal

Add a conversation-scoped director console for group chats so the user can pause automatic turn-taking, run exactly one round, skip the next candidate, nominate the next speaker once, and switch the effective turn-taking mode without changing the group assistant's defaults.

## Confirmed Product Decisions

1. The director entry point is a Material 3 floating action button near the chat input, using the verified HugeIcons `UserGroup03` glyph. It opens a compact bottom sheet instead of keeping a permanent control row on screen.
2. Pause is graceful: a reply already being generated finishes, then automatic chaining stops.
3. Director settings persist on the current conversation and do not affect other conversations or the assistant definition.
4. Nominating a member overrides only the next reply. Normal scheduling resumes afterwards.
5. One-round execution lets every member enabled at the start of the round speak at most once. An early moderator `STOP` ends the round immediately.

## Scope

### Included

- Conversation-scoped mode override for `MANUAL`, `AUTO_ROUND_ROBIN`, and `AUTO_MODERATOR`.
- Graceful pause and resume state.
- One-round execution with a stable enabled-member snapshot.
- Skip-next and one-shot next-speaker commands.
- Floating director button, status indicator, and bottom sheet.
- Persistence, backwards-compatible serialization, state sanitization, unit tests, Compose/instrumentation tests, and emulator smoke verification.

### Excluded

- Per-member TTS voices.
- Per-assistant web-search persistence.
- Web UI director controls.
- Immediate cancellation of an in-flight model response.
- Changes to the group assistant's default `turnTakingStrategy`.
- Unrelated Tavern Helper, SillyTavern rendering, database, or workspace refactors.

## Architecture

### Persistent state

Add a serializable director state to `GroupRuntimeState`:

```kotlin
@Serializable
data class GroupDirectorState(
    val modeOverride: TurnTakingStrategy? = null,
    val playbackState: GroupPlaybackState = GroupPlaybackState.RUNNING,
    val oneShotNextMemberId: Uuid? = null,
    val oneShotReturnToPaused: Boolean = false,
    val oneRoundActive: Boolean = false,
    val oneRoundRemainingMemberIds: List<Uuid> = emptyList(),
    val skipNextRequested: Boolean = false,
)

@Serializable
enum class GroupPlaybackState {
    RUNNING,
    PAUSE_AFTER_CURRENT,
    PAUSED,
}
```

`GroupRuntimeState` receives:

```kotlin
val director: GroupDirectorState = GroupDirectorState()
```

Default values preserve existing behavior for new and previously serialized conversations.

The current `ConversationRepository` does not map `Conversation.groupRuntimeState` into `ConversationEntity`, so model-level serialization alone does not survive a process restart. This feature therefore also completes the intended runtime-state persistence path:

- Add a non-null `group_runtime_state` TEXT column to `ConversationEntity`, with SQL default `{}`.
- Increment the Room database from version 26 to 27 and add the corresponding 26-to-27 migration.
- Encode `Conversation.groupRuntimeState` into that column on writes.
- Decode it with `runCatching` and fall back to `GroupRuntimeState()` for missing or malformed legacy data.
- Update the exported Room schema and add repository/migration round-trip coverage.

This is a focused persistence correction required by the approved conversation-scoped behavior, not a broader database refactor.

### Pure director engine

Create `GroupDirectorEngine` under `service/group`. It owns deterministic state transitions and contains no model-provider, database, or Compose dependencies.

Its responsibilities are:

- Reduce a `GroupDirectorCommand` into a sanitized `GroupDirectorState`.
- Resolve `modeOverride ?: groupAssistant.turnTakingStrategy`.
- Sanitize member references against the currently enabled member list.
- Apply one-shot and skip-next rules to a scheduler candidate.
- Decide whether automatic chaining may continue after a reply.
- Consume the current speaker from a one-round snapshot.
- Return the post-reply state, including the graceful pause transition.

The command set is:

```kotlin
sealed interface GroupDirectorCommand {
    data object PauseAfterCurrent : GroupDirectorCommand
    data object ContinueOneRound : GroupDirectorCommand
    data object SkipNext : GroupDirectorCommand
    data class QueueMemberOnce(val memberId: Uuid) : GroupDirectorCommand
    data class SetMode(val strategy: TurnTakingStrategy) : GroupDirectorCommand
}
```

Command context supplies whether generation is active and the ordered enabled-member IDs. This keeps race-sensitive UI facts outside the persisted model while preserving pure transition tests.

### Service integration

`ChatService` exposes one conversation-scoped director command entry point. Commands and reply-completion updates use the existing conversation mutation path and are serialized per conversation so rapid taps cannot overwrite a simultaneous generation-completion update.

The existing group scheduler remains responsible for normal round-robin and moderator selection. Director behavior wraps it in this order:

1. Sanitize director state against enabled members.
2. Resolve the effective conversation strategy.
3. Use `oneShotNextMemberId` when present; otherwise run the normal scheduler.
4. If `skipNextRequested` is present, exclude the first candidate and choose a deterministic replacement from the normalized queue.
5. Persist the selected member and consumed director commands together.
6. Generate and store the reply through the existing group pipeline.
7. Feed the completed speaker into `GroupDirectorEngine.afterReply`.
8. Continue only when both the director engine and the existing strategy rules permit it.

The existing configured `maxAutoRepliesPerUserTurn` remains authoritative for ordinary user-message auto chains. A user-initiated one-round run uses its own remaining-member snapshot instead, because it is an explicit request to let each enabled member speak once.

## Behavior

### Pause

- When no reply is active, `PauseAfterCurrent` transitions directly to `PAUSED`.
- During generation it transitions to `PAUSE_AFTER_CURRENT`; the current reply completes and `afterReply` changes it to `PAUSED`.
- Repeating the command is idempotent.
- The service never schedules another automatic reply while `PAUSED` or after completing a `PAUSE_AFTER_CURRENT` reply.

### Continue one round

- The action is enabled only while paused or idle.
- It snapshots the ordered, enabled member IDs at command time and sets `oneRoundActive`.
- Every completed group reply removes its speaker from `oneRoundRemainingMemberIds`, preventing duplicate speakers even if the moderator selects one twice.
- Members enabled after the snapshot wait until the next round. Disabled or deleted members are removed by sanitization.
- The run ends paused when the snapshot is empty, the moderator returns `STOP`, or no valid member remains.
- If the process is recreated during a round, no generation starts automatically. Remaining members stay visible and the action label becomes `继续本轮`.

### One-shot member nomination

- While automatic generation is active, the member becomes the next candidate after the in-flight reply.
- While paused, that member produces one reply and the conversation returns to paused; `oneShotReturnToPaused` records this behavior across the asynchronous generation boundary.
- `oneShotNextMemberId` is consumed atomically when selection is committed. `oneShotReturnToPaused` remains set until that reply completes, then `afterReply` clears it and restores `PAUSED`.
- A disabled or missing member clears the command and falls back to normal scheduling.

### Skip next

- In round-robin mode, skip the next queue candidate and choose the following valid member.
- In moderator mode, obtain the moderator candidate once, exclude it, and choose a deterministic replacement from the normalized queue. The moderator model is not called repeatedly.
- If there is no different enabled member, clear the pending skip request and return a user-visible `暂无其他角色` result; it must not unexpectedly skip a member added later.
- The current in-flight reply is never truncated.

### Mode override

- `SetMode` changes only `director.modeOverride`.
- Switching to `MANUAL` during generation lets the current reply finish, then blocks automatic chaining.
- Switching from manual to an automatic mode does not start generation by itself. A new user message, one-round command, or one-shot nomination starts work.
- The existing manual member selector uses the effective conversation strategy instead of only the assistant default.

### Restoration and sanitization

- `PAUSE_AFTER_CURRENT` with no active generation normalizes to `PAUSED` after process or page restoration.
- A restored one-round run stays paused until the user chooses `继续本轮`.
- Invalid one-shot, queue, and remaining-round member IDs are removed.
- When a group has no enabled members, commands return a user-visible result and do not create an empty message.

## UI Design

### Original RikkaHub visual language

The director UI must look like a native part of the existing application rather than a themed add-on:

- Use standard Compose Material 3 components already present in RikkaHub: `FloatingActionButton`, `ModalBottomSheet`, `SingleChoiceSegmentedButtonRow`, `SegmentedButton`, `FilledTonalButton`, `IconButton`, and existing avatar components.
- Use `MaterialTheme.colorScheme`, `MaterialTheme.typography`, and the application's dynamic light/dark theme. Do not introduce fixed purple/black palettes, gradients, glass effects, custom shadows, or a separate director color system.
- Follow the existing 8 dp spacing rhythm: 16 dp sheet/content padding, 8 dp control gaps, and the same touch-target sizing used by current chat input actions.
- Reuse the existing bottom-sheet pattern used by pickers: standard drag handle, existing `rememberBottomSheetState`, expanded/hidden states, and a maximum content height near 70% of the screen.
- Use `SingleChoiceSegmentedButtonRow` for the three modes, matching existing settings and context-compression controls.
- Use the existing `UIAvatar`/group-member avatar rendering rather than custom colored initials.
- Use verified HugeIcons stroke icons instead of emoji: `UserGroup03` for the director entry, `Pause` for graceful pause, `Play` for one-round continuation, and `Next` for skip-next. Icon names must be imported from `me.rerere.hugeicons.stroke`.
- Keep motion limited to existing Compose patterns such as sheet transitions, `AnimatedVisibility`, and small content-state changes. No decorative animation is added.
- Put director labels and accessibility descriptions in the existing string-resource structure; do not hardcode visual copy inside reusable composables.

### Floating entry point

`GroupDirectorFab` appears only for group-assistant conversations. It uses the same Material 3 FAB treatment already used by RikkaHub feature pages, sits above the chat input on the trailing edge, and does not replace the existing send/cancel button.

Visual states:

- Running: `HugeIcons.UserGroup03` with the current theme's standard FAB colors.
- Paused: play overlay.
- Waiting to pause: small pending indicator.
- One-round active: progress badge such as `1/3`.

### Bottom sheet

`GroupDirectorSheet` contains:

1. Header: `导演台` and effective mode/next-speaker status.
2. Primary actions: `说完暂停`, `继续一轮` or `继续本轮`, and `跳过下一位`.
3. Conversation-only segmented mode control: `手动`, `轮询`, `主持人`.
4. Horizontally scrollable enabled-member avatars. Tapping one displays it as the one-time next speaker.

The UI consumes a derived `GroupDirectorUiState`. Compose does not reproduce scheduling rules. The state mapper combines the conversation director state, group assistant, current queue, and whether a generation job is active.

On narrow screens the member row scrolls horizontally. All icon-only controls receive complete accessibility descriptions such as `当前角色回复完成后暂停` and `指定艾琳下一位发言`.

## Data Flow

```text
Director UI
  -> ChatVM command
  -> ChatService serialized conversation mutation
  -> GroupDirectorEngine.reduce/sanitize
  -> persisted Conversation.groupRuntimeState.director
  -> scheduler candidate + director override
  -> existing group generation/context/transport pipeline
  -> GroupDirectorEngine.afterReply
  -> persisted state and derived GroupDirectorUiState
```

Closing the sheet or leaving the chat page does not clear director state. Opening a conversation never starts generation implicitly.

## Error Handling and Concurrency

- Director mutations for a conversation are serialized with reply completion so last-writer races do not lose a pause or nomination.
- Commands are idempotent where repetition is meaningful.
- Validation happens both when accepting a command and immediately before member selection.
- A stale member, empty group, missing moderator result, or exhausted queue produces a typed outcome that the ViewModel maps to a toast/snackbar.
- Provider failures follow the existing chat error path. Director state remains paused for one-shot-paused and one-round runs so retrying is explicit.
- Director logic does not swallow cancellation or generation errors.

## Testing Strategy

### Pure JVM tests

Add focused tests for:

- Idle pause, pause-after-current, post-reply transition, and repeated pause.
- One-round snapshot order, one reply per member, new-member exclusion, stale-member cleanup, and early moderator `STOP`.
- One-shot consumption in running and paused modes.
- Skip-next behavior for round-robin, moderator fallback, and single-member groups.
- Effective strategy resolution and switching to manual during generation.
- Process restoration normalization.

### Serialization, repository, and migration tests

- Round-trip every director state field through the conversation serializer.
- Decode a prior conversation payload with no director field and assert default behavior.
- Round-trip `GroupRuntimeState`, including director state, through `ConversationRepository` entity mapping.
- Migrate a version-26 database to version 27 and assert the new column defaults to `{}` and decodes safely.

### Service and scheduler tests

- Verify ChatService uses the effective conversation strategy.
- Verify a graceful pause blocks the next `handleMessageComplete` auto-chain.
- Verify one-round continuation uses the remaining-member snapshot instead of the ordinary auto-reply cap.
- Verify one-shot and skip state are consumed in the same persisted selection update.
- Keep all existing group context, transport, manual selection, and scheduler suites green.

### Compose and instrumentation tests

- Director FAB is visible only for group conversations.
- Sheet labels, enabled states, progress, and pending-pause state match `GroupDirectorUiState`.
- Switching to the effective manual mode reveals the existing member selector.
- Member avatar nomination updates the one-shot indicator.

### Final verification

Run:

```powershell
.\gradlew.bat test :app:assembleDebug --console=plain
.\gradlew.bat connectedDebugAndroidTest --console=plain
```

Install the universal Debug APK on `emulator-5554` and manually verify pause, one round, skip, one-shot nomination, all three modes, process/page restoration, focused activity, and an empty crash buffer.

## Compatibility and Migration

- The added serializable fields all have defaults.
- Database version 27 adds `group_runtime_state` as non-null TEXT with default `{}`; version-26 rows decode to `GroupRuntimeState()`.
- The assistant model and its default strategy are unchanged.
- Existing queue fields remain in place and are reused by the director engine.
- Existing conversations without director state behave exactly as before.
- Tavern Helper/ST rendering and layered group-context behavior remain outside the director state machine and must stay green in regression verification.
