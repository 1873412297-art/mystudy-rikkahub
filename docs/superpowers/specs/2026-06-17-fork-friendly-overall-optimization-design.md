# Fork-Friendly Overall Optimization Design

## Background

This repository is a fork of the upstream `rikkahub` project. The local fork already contains substantial custom work in three areas:

- Group chat runtime and gameplay behavior
- Tavern Helper / SillyTavern-style rendering and runtime support
- Chat UI interaction changes around grouped characters and runtime debugging

The user wants a broad code quality pass, but also needs this fork to stay easy to sync with future upstream `rikkahub` releases. That requirement changes the optimization strategy: the goal is not to maximize local architectural purity, but to improve stability and maintainability while minimizing future merge and rebase pain.

## Goals

The optimization work should:

- Improve stability in the customized group-chat execution path
- Reduce duplicated logic, dead code, and scattered fork-specific rules
- Make the customized behavior easier to test and reason about
- Preserve current user-facing behavior unless a change fixes an obvious bug
- Reduce conflict surface when pulling in future upstream updates

## Non-Goals

This optimization pass will not:

- Perform a deep rewrite of `ChatService`
- Re-architect the entire app around new abstractions
- Rework all common models or settings systems
- Optimize every performance hotspot in the project
- Normalize the entire codebase for style alone

The work is intentionally staged. Only the pieces that materially improve the fork's long-term maintainability are in scope.

## Constraints

### Upstream Sync Constraint

The fork must remain compatible with future upstream updates. This means:

- Prefer additive helpers over large rewrites in upstream-owned files
- Keep fork-specific rules concentrated in fork-specific files where possible
- Avoid broad reordering of large files unless there is a clear payoff
- Avoid changing public or cross-cutting interfaces without strong need

### Risk Constraint

The existing fork already includes many concurrent local changes. Optimization should therefore:

- Use small focused edits
- Avoid reverting or reshaping unrelated work
- Add regression tests around customized logic before or alongside cleanup

## Optimization Strategy

The overall optimization work will follow a balanced strategy:

- Use minimal intrusion as the default
- Improve local structure only where the maintenance payoff is clear
- Treat user-facing correctness and stability as higher priority than cosmetic cleanup

This leads to a three-stage plan:

1. Group mode mainline stabilization and cleanup
2. Rendering and chat UI cleanup
3. Small common-layer cleanup only where it directly supports the first two stages

## Stage 1: Group Mode Mainline

This is the highest-value stage because the fork's most custom behavior currently lives here and the group-chat path already carries stateful behavior that is easy to regress.

### Objectives

- Make the group-chat mainline easier to read and test
- Reduce duplicate and dead logic in the send/generate/update path
- Tighten state ownership for addressed-role and runtime-state behavior
- Keep `ChatService` as an orchestrator rather than a rules container

### Scope

Primary files:

- `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
- `app/src/main/java/me/rerere/rikkahub/service/group/*`
- `app/src/test/java/me/rerere/rikkahub/service/group/*`
- `app/src/test/java/me/rerere/rikkahub/service/ChatServiceTest.kt`

### Design

#### 1. Keep `ChatService` shallow

`ChatService` should remain the place where the send/generation pipeline is orchestrated, but not the place where fork-specific group rules are re-implemented.

Concretely:

- Remove leftover dead helpers that duplicate extracted group logic
- Keep only small private orchestration helpers inside `ChatService`
- Route group-specific decisions to `service/group/*` helpers whenever the logic is deterministic and self-contained

This preserves upstream compatibility by limiting the size of changes inside a high-churn upstream file.

#### 2. Concentrate fork behavior in `service/group/*`

The fork-specific rule set should live in dedicated group runtime files:

- Address parsing and continuation rules
- Context filtering
- Dynamic layered context resolution
- Event extraction
- Runtime-state updates
- Speaker or turn-selection helpers

The design principle is simple: `ChatService` decides when a group step happens; `service/group/*` decides how that step behaves.

This makes future upstream merges easier because most fork behavior remains isolated from the upstream core pipeline.

#### 3. Tighten state boundaries

Group runtime state is now powerful enough that scattered writes become dangerous. Stage 1 should clarify when state changes are allowed:

- User-send path may update addressed-target state
- Generation setup may read addressed-target state and dynamic context state
- Post-generation path may update scene/event/runtime summaries

The implementation should avoid multiple competing write paths for the same logical state.

#### 4. Prefer low-risk performance cleanup

Stage 1 may improve performance, but only where it does not add caching complexity or lifecycle risk. Examples:

- Avoid repeated message filtering
- Avoid repeated member lookups in the same scope
- Avoid repeated text joins when one computed value is enough

No new cache layer or cross-turn memoization should be introduced in this phase.

### Stage 1 Acceptance Criteria

- No duplicate local implementation of extracted group helpers remains in `ChatService`
- Group send/generate/update boundaries are clearer and easier to follow
- Group runtime behavior remains covered by targeted JVM tests
- Build and existing group-related tests pass
- Upstream conflict surface in `ChatService` is reduced or at least not expanded

## Stage 2: Rendering And Chat UI

This stage focuses on the fork's custom rendering and interaction behavior without attempting a visual redesign.

### Objectives

- Make rendering entry points more consistent
- Reduce UI-side duplication around group interactions
- Keep native app look and feel
- Protect current SillyTavern/Tavern Helper behavior from accidental regressions

### Scope

Primary files:

- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/*`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/*`
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/*`
- Related rendering and UI tests

### Design

#### 1. Normalize rendering entry points

The fork now supports multiple rendering behaviors. Optimization should ensure the entry points are easy to follow:

- Detect which content should go through ST/runtime-aware rendering
- Keep mode selection logic centralized
- Avoid scattering render-policy decisions across many UI files

#### 2. Consolidate group UI interactions

The following should remain consistent and locally understandable:

- Group member message placement logic
- Visible speaker label stripping/preservation rules
- Mention insertion from chat input and avatar long-press
- Runtime debug sheet access and display

If helper extraction is needed, it should happen in UI-local files rather than broad app-wide abstractions.

#### 3. Preserve native styling

UI optimization in this phase is structural, not stylistic. The app should continue to feel like the original software rather than a forked redesign.

### Stage 2 Acceptance Criteria

- Rendering decisions are easier to trace from input to display
- Group UI behavior is more consistent and less duplicated
- No visible regression in current native UI behavior
- Relevant rendering/UI tests continue to pass

## Stage 3: Small Common-Layer Cleanup

This stage is intentionally narrow. It exists to support the first two stages, not to launch a new architecture effort.

### Objectives

- Clean up only the shared utilities or models that directly obstruct Stage 1 or 2
- Avoid turning this effort into a general-purpose refactor

### Examples Of Acceptable Work

- Tiny utility extraction used by both group-chat and UI logic
- Small model cleanup where current duplication causes bugs or confusion
- Narrow settings cleanup if required by group or rendering behavior

### Examples Of Out-Of-Scope Work

- Reworking all datastore patterns
- Reorganizing all model packages
- Global state-management changes
- Broad naming-only churn

## Testing Strategy

The optimization work should rely primarily on targeted regression coverage:

- Group runtime tests for addressed role, continuation, context isolation, and runtime-state updates
- Chat service tests for orchestration boundaries
- Rendering tests for structured status/runtime content behavior
- UI-adjacent JVM tests where existing patterns allow them

Manual smoke tests remain necessary for:

- Group UI interaction flows
- Mention UX
- Runtime debug display
- End-to-end grouped conversation behavior on emulator

## Merge-Friendly Implementation Rules

The implementation plan derived from this design should follow these rules:

- Prefer new helper files over large rewrites in upstream-heavy files
- Prefer extraction over reordering
- Avoid mixed-purpose commits
- Keep cleanup commits scoped by subsystem
- Update plan documents as work progresses so paused work is resumable

## Recommended Execution Order

1. Stage 1 group mainline cleanup
2. Verify tests and debug APK
3. Stage 2 rendering/UI cleanup
4. Verify tests and manual smoke
5. Stage 3 small shared cleanup only if still needed

## Risks

### Risk: Hidden behavioral regression

The group-chat pipeline is stateful and customized. Even harmless-looking cleanup can change who replies, what context is visible, or when runtime state mutates.

Mitigation:

- Add or preserve focused regression tests
- Keep cleanup incremental
- Avoid semantic rewrites disguised as refactors

### Risk: Upstream merge pain caused by cleanup itself

If `ChatService` or other upstream-heavy files are heavily reformatted, future merges become more expensive.

Mitigation:

- Restrict changes in high-churn files to local, purposeful edits
- Move complexity outward, not inward

### Risk: Scope creep

"Overall optimization" can easily expand into indefinite refactoring.

Mitigation:

- Use the staged structure above
- Treat Stage 3 as optional and narrow
- Stop when the current stage achieves its acceptance criteria

## Decision Summary

The recommended design is a fork-friendly, staged optimization pass:

- Stage 1 stabilizes and cleans the group mainline with minimal `ChatService` intrusion
- Stage 2 cleans rendering and chat UI structure while preserving the native app feel
- Stage 3 performs only small shared cleanup that directly supports the first two stages

This design intentionally prioritizes long-term maintainability of the fork over maximal local refactoring purity.
