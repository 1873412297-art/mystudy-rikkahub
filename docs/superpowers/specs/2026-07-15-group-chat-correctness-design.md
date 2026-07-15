# Group Chat Correctness Design

## Background

The ported group-chat runtime supports manual multi-member replies, round-robin turns, moderator-selected turns, per-member context filters, and layered runtime context. Emulator smoke tests cover the main modes, but a focused audit found correctness gaps in the boundaries between filtering, scheduling, and persisted queue state.

This design covers the first stabilization stage only. Relationship-memory generation, richer event extraction, and new group-control UI remain separate follow-up stages.

## Goals

- Apply each member's context filter exactly once per generation.
- Preserve chronological message order after filtering and limiting.
- Enforce `maxMessages` as a true upper bound.
- Make the configured auto-reply cap authoritative in every automatic mode.
- Make round-robin order deterministic from the first reply onward.
- Remove disabled, deleted, and duplicate members from persisted automatic queues before selection.
- Add focused tests for the complete deterministic pipeline rather than relying only on emulator observations.

## Non-Goals

- Redesign `ChatService` or the conversation persistence layer.
- Add model-based relationship extraction or private-memory generation.
- Add new group-chat controls or visual layouts.
- Change manual multi-member selection behavior.
- Change the existing addressed-member precedence rule.

## Approaches Considered

### 1. Focused helper extraction and pipeline correction

Keep `ChatService` as the orchestrator, move deterministic queue and reply-limit rules into small helpers under `service/group`, and make the filtering branch explicit. This has a small upstream merge surface and allows direct JVM coverage.

This is the selected approach.

### 2. Full group-turn scheduler

Introduce a state machine owning manual, round-robin, and moderator turns. This would produce a cleaner long-term architecture but would touch a large portion of the generation lifecycle and increase porting risk.

### 3. Inline patching inside `ChatService`

Correct each expression in place without adding helpers. This is the smallest immediate diff, but it leaves queue invariants and reply-limit semantics hard to test and easy to regress.

## Design

### Context filtering pipeline

`ChatService` will first select the unfiltered message range. It will then choose exactly one of two paths:

- Layered context enabled: pass the unfiltered range to `DynamicGroupContextResolver`, which owns filtering, addressed-prompt retention, layer classification, and visibility selection.
- Layered context disabled: call `applyGroupContextFilter` directly.

The resolver must receive the original selected range so that a narrow `DIRECTED` filter can restore the latest addressed user prompt before layer selection.

### Chronological message limiting

`applyGroupContextFilter` will perform scope, exclusion, and mention filtering first. When `maxMessages` is positive, it will return `takeLast(maxMessages)` from that already-filtered list.

This rule deliberately treats user and assistant messages uniformly. It preserves source order and makes the configured value a strict upper bound. Addressed-prompt retention remains the resolver's responsibility and happens after the base filter.

### Auto-reply cap

`maxAutoRepliesPerUserTurn.coerceAtLeast(1)` will be the only automatic reply cap for both round-robin and moderator modes. Moderator `STOP` may end the chain earlier, but member count will never raise the user-configured cap.

The cap counts stored assistant messages with a currently enabled group-member ID after the latest user message, matching the existing persistence model.

### Queue normalization

A deterministic helper will derive the effective queue from:

1. Persisted queue members that are still enabled, preserving their saved order.
2. Newly enabled members missing from the persisted queue, appended in current group-member order.
3. Duplicate and unknown IDs removed.

If no members remain, speaker resolution returns no selection.

The persisted cursor represents the index of the last selected member. Selection advances to the following queue entry. For a newly initialized queue, the cursor is treated as being before the first entry so the first enabled member speaks first. After selection, the stored cursor is the selected member's actual index.

Round-robin always advances through the normalized queue. `allowConsecutiveSameSpeaker` affects moderator selection, where the moderator may intentionally choose the active speaker again; it does not create duplicate turns in round-robin mode.

### Moderator integration

Moderator candidates and local fallback scoring will use enabled members only. The normalized queue supplies deterministic fallback order and consecutive-speaker replacement. Invalid moderator output continues to fall back locally.

### Persistence behavior

Queue normalization is persisted when a speaker is selected. Existing conversations repair themselves on their next automatic turn; no database migration is required.

## Error Handling

- An empty normalized queue ends automatic selection cleanly.
- A removed or disabled active member is ignored during consecutive-speaker checks.
- Invalid persisted cursor values are normalized from the active member when possible, otherwise to the position before the first entry.
- Invalid moderator output keeps the existing local fallback behavior.

## Testing

Focused JVM tests will cover:

- Layered generation receives the unfiltered selected range and retains an addressed user prompt under `DIRECTED` scope.
- `maxMessages` preserves chronological order and never exceeds the configured count.
- Round-robin starts with the first enabled member and then advances without duplicating the first turn.
- Queue normalization removes disabled, deleted, and duplicate IDs and appends newly enabled members.
- A stale active member does not stop automatic selection.
- Moderator mode respects a configured cap of one reply.
- Existing addressed-member, transport-rewrite, resolver, and runtime-state tests remain green.

Repository verification remains:

- `./gradlew test --console=plain`
- `./gradlew :app:assembleDebug --console=plain`
- `./gradlew connectedDebugAndroidTest --console=plain`
- Install and launch the universal debug APK on `emulator-5554`, then inspect focus and the crash buffer.

## Follow-Up Stages

After this correctness stage is stable:

1. Add lifecycle-aware private notes, relationships, focus expiry, and tension decay.
2. Define explicit composition rules for group world prompts, character prompts, lorebooks, memory, and tools.
3. Add an automatic-turn status bar with current speaker, reply count, skip, stop, and retry controls.
