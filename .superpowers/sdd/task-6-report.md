# Task 6 Report: Permission migration, presets, and real-session greeting preview

## Scope

- Added a one-shot DataStore migration for fresh installs and upgrades.
- Added exact maximum-compatible and conservative permission presets and settings actions.
- Added source/live-preview switching for `first_mes` and every alternate greeting.
- Added an explicit real-conversation target gate and routed full-runtime side effects to that target.
- Kept Room schema/version untouched.

## TDD evidence

### RED 1: permission migration and presets

Command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernPermissionMigrationTest" --tests "*TavernGreetingPreviewTargetTest" --no-daemon
```

Observed compiler failures included:

```text
Unresolved reference 'TavernPermissionCompatibilityMigration'.
Unresolved reference 'maximumCompatible'.
Unresolved reference 'conservative'.
Unresolved reference 'TavernGreetingPreviewTargetSelection'.
```

### GREEN 1

The focused tests passed after implementing the one-shot migration, exact presets, and explicit target selection/routing.

### RED 2: persisted current-message write

Observed compiler failure: `Unresolved reference 'applyTavernPreviewMessagePatch'.`

### GREEN 2

The helper now updates only the selected branch's current persisted message while preserving message identity.

### RED 3: target initialization race

Observed compiler failure: `Unresolved reference 'markReady'.` Writes were possible after selection but before the
real conversation finished loading.

### GREEN 3

Selection now resets readiness, only the selected conversation can become ready, the WebView is withheld until initialization succeeds, and writes are rejected until then.

### RED/GREEN 4: independent-review hardening

The independent review found that preview writes could race ordinary conversation saves, chat-variable writes were
not durable, stale callbacks could be redirected after target switching, and multiple preview WebViews could compete
for sendHook ownership. Tests were added first. The observed RED diagnostics included:

```text
Unresolved reference 'ConversationPersistenceGate'.
Unresolved reference 'PersistingTavernRuntimeVariableGateway'.
Unresolved reference 'TavernPreviewConversationLease'.
Unresolved reference 'TavernPreviewSideEffectQueue'.
```

The GREEN implementation serializes persistence and bridge side effects, persists full chat-variable snapshots,
rejects callbacks whose captured conversation ID is stale, keeps exactly one greeting runtime active, and acquires and
releases a single conversation lease. Focused regression tests passed after these changes.

## Implementation notes

- `TavernPermissionCompatibilityMigration` overwrites legacy/fresh values with maximum compatibility once and records `tavern_permission_compat_migrated_v1`; later user changes are preserved.
- Maximum compatibility enables scripts, network, message/variable/world writes, event subscription, and macro registration. Request headers remain disabled.
- Conservative mode disables all script capabilities and request-header access; individual switches remain available.
- The editor never auto-selects a conversation. The selected target is visible in the editor top bar and message
  section with its title, short unique ID, and update timestamp, and can be changed explicitly.
- Target conversation references are acquired/released by the editor ViewModel. Initialization readiness prevents scripts from running against a stub session.
- The existing secure single-WebView runtime is reused. Variables, world writes, events, macro/slash registration, network, and sendHook ownership use the real conversation ID. `messages.updateCurrent` is persisted through `ChatService` to the selected conversation.
- Only one opening field owns a live WebView at a time. WebView/controller disposal detaches active sendHook ownership;
  target changes and ViewModel clearing release conversation references.
- Preview writes are serialized in callback order. Conversation repository writes share a per-conversation persistence
  gate, publish preview mutations to the live session before persistence, and roll back only when no newer live state
  has replaced them.
- Message-write callbacks receive the conversation ID captured by their owning runtime controller. They do not infer it
  from the latest Compose callback, so an old WebView cannot be redirected to a newly selected target during disposal.

## Verification

Focused tests + compile:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernPermissionMigrationTest" --tests "*TavernGreetingPreviewTargetTest" :app:compileDebugKotlin --no-daemon
```

Result: `BUILD SUCCESSFUL` (permission, target gate, stale callback, ownership, variable persistence, conversation
persistence, lease, and FIFO side-effect tests).

Full JVM suite:

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

Result: `BUILD SUCCESSFUL` — 105 test classes / 755 tests / 0 failures / 0 errors / 0 skipped.

Compile + APK:

```powershell
.\gradlew.bat :app:compileDebugKotlin :app:assembleDebug --no-daemon
```

Result: `BUILD SUCCESSFUL`.

`git diff --check` reported no whitespace errors. No `opencode.exe` process was running. No Room entity, migration, schema, or database version file changed.

## Self-review

- Fixed the target-loading race before verification by adding the readiness gate.
- Fixed every Critical/Important item from the first independent review and added focused regressions for the
  persistence gate, durable variables, stale callbacks, single runtime ownership, unique target labels, lease cleanup,
  and FIFO bridge writes.
- A post-commit self-audit found and closed the remaining Compose callback handoff window by moving target-ID capture
  into the runtime controller boundary.
- Confirmed preset actions preserve per-permission controls and never enable request-header reads.
- Confirmed target switching/destroy paths detach active sendHook controllers and release conversation references.
- No plan or ledger files were modified.

## Final review hardening: drain-safe effects and stale-save rebasing

The last review identified three remaining concurrency gaps and each was reproduced by a test before production code
changed:

- closing the editor could cancel queued persistence and release the target's last conversation reference before the
  write completed;
- an old WebView could mutate chat or global variables before its later persistence callback noticed that the preview
  target had changed;
- the persistence mutex serialized writes but did not repair a whole `Conversation` object captured before a preview
  mutation.

RED command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernGreetingPreviewTargetTest" --tests "*TavernPersistingVariableGatewayTest" --tests "*TavernPreviewMutationRebaserTest" --no-daemon
```

The expected failures were missing queue lease/drain parameters, missing `validateTarget`, and missing
`TavernPreviewMutationRebaser`.

The queue now owns a drainable supervisor scope and acquires a separate conversation reference synchronously for each
accepted callback. `close()` rejects new work but lets accepted work finish; every operation releases its reference in
`finally`, and persistence failures are surfaced through `ChatService.addError`. The selected-target lease may therefore
be cleared when the editor closes without invalidating an already accepted write.

The persistent variable gateway validates the controller's captured conversation ID before both `set` and `delete`,
including global scope, so stale controllers cannot mutate a delegate first. The validator is carried through the
shared conversation WebView and resolves against the editor's current explicit target/readiness gate.

`TavernPreviewMutationRebaser` records successfully persisted message-text and per-key chat-variable effects. Every
later whole-conversation save under `ConversationPersistenceGate` rebases a stale matching value to the preview value;
an explicit later change to the same message/key retires that journal entry. This preserves unrelated concurrent
message additions and variable changes without adding a Room column or schema migration.

Fresh verification after these fixes:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernPermissionMigrationTest" --tests "*TavernGreetingPreviewTargetTest" --tests "*TavernPersistingVariableGatewayTest" --tests "*ConversationPersistenceGateTest" --tests "*TavernPreviewMutationRebaserTest" --no-daemon
```

Result: `BUILD SUCCESSFUL`.

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

Result: `BUILD SUCCESSFUL` — 106 test classes / 759 tests / 0 failures / 0 errors / 0 skipped.

```powershell
.\gradlew.bat :app:compileDebugKotlin :app:assembleDebug --no-daemon
```

Result: `BUILD SUCCESSFUL` (227 actionable tasks, 11 executed / 216 up-to-date). `git diff --check` reported no
whitespace errors.

## Final independent review: transactional journal retirement

The next read-only review confirmed every earlier finding closed, then identified one persistence-failure window:
`rebase` retired explicit-edit journal entries while preparing a save. If a later message in the same preparation
raised `StaleTavernPreviewSnapshotException`, or if repository persistence failed afterward, those retirements survived
even though the save did not.

A two-message regression was written first. The first mutation was eligible for retirement and the second was missing
from the stale snapshot; before the fix the rejected preparation lost the first mutation (`8 tests / 1 failed`). A
second contract test was then written before the new API and failed compilation on the missing `prepareRebase` symbol.

Rebasing is now two-phase. `prepareRebase` computes the rebased conversation and pending retirements without mutating
the journal. `ChatService` commits those retirements only after conversation repository persistence returns
successfully. Rejected preparation and repository failure therefore keep every replay entry. The commit is idempotent,
removes only the exact mutations from the same journal instance, and cannot erase a replacement journal created after
service cleanup.

Fresh final verification after the transactional fix:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernPermissionMigrationTest" --tests "*TavernGreetingPreviewTargetTest" --tests "*TavernPersistingVariableGatewayTest" --tests "*ConversationPersistenceGateTest" --tests "*TavernPreviewMutationRebaserTest" --no-daemon
```

Result: `BUILD SUCCESSFUL`.

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

Result: `BUILD SUCCESSFUL` — 106 test classes / 766 tests / 0 failures / 0 errors / 0 skipped.

```powershell
.\gradlew.bat :app:compileDebugKotlin :app:assembleDebug --no-daemon
```

Result: `BUILD SUCCESSFUL` (227 actionable tasks, 11 executed / 216 up-to-date). `git diff --check` reported no
whitespace errors.

## Independent review and revision-aware rebase fix

The independent read-only review rejected the value-only rebaser because an intentional later edit back to a field's
original value was indistinguishable from an old snapshot. It also noted that composed preview mutations returning to
their original value left a no-op journal entry.

Two regressions were written first: a same-text message reversion and deletion of a variable that did not exist before
preview. A third test requires session publication to advance beyond both the current and incoming revisions. RED failed
on missing `Conversation.stateRevision`, `previewRevision`, and `advanceConversationRevision` symbols.

The fix adds an in-memory-only `@Transient stateRevision` to `Conversation`; it is excluded from serialization and does
not add a Room field or migration. Every ChatService session publication advances the revision. Preview journals record
the revision at which their effect was published. Saves from an older revision reapply matching preview effects, while a
save at or beyond the preview revision retires a journal entry whenever its field differs from the preview value — even
when that value equals the original state. Coalesced message/variable mutations that return to their starting value are
removed immediately.

Fresh post-review verification:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernPermissionMigrationTest" --tests "*TavernGreetingPreviewTargetTest" --tests "*TavernPersistingVariableGatewayTest" --tests "*ConversationPersistenceGateTest" --tests "*TavernPreviewMutationRebaserTest" --no-daemon
```

Result: `BUILD SUCCESSFUL`.

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

Result: `BUILD SUCCESSFUL` — 106 test classes / 761 tests / 0 failures / 0 errors / 0 skipped.

```powershell
.\gradlew.bat :app:compileDebugKotlin :app:assembleDebug --no-daemon
```

Result: `BUILD SUCCESSFUL` (227 actionable tasks, 12 executed / 215 up-to-date). The only compiler warnings were
pre-existing unresolved/deprecation warnings outside the Task 6 diff. `git diff --check` remained clean.

## Second independent review: older divergent snapshots

The re-review closed the earlier revision/no-op findings but identified one stricter stale-save case: an older snapshot
could contain a value that differed from both the state immediately before preview and the preview result. Treating that
value as a later edit retired the journal and allowed the old whole object to overwrite the newer preview write.

Message and variable regressions were added first. Both failed before production code changed (`2 failed / 6 tests`),
showing the older divergent values persisted instead of the preview values. The rebaser now uses revision order as the
authority: every snapshot older than the preview publication replays the recorded preview result regardless of its old
field value. A snapshot at or beyond the preview revision still represents a possible explicit later edit and therefore
retires the journal when its value differs from the preview result. An older snapshot that no longer contains the
preview-mutated message is rejected rather than silently saved because that structural conflict cannot be safely
rebased.

Fresh final verification:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernPermissionMigrationTest" --tests "*TavernGreetingPreviewTargetTest" --tests "*TavernPersistingVariableGatewayTest" --tests "*ConversationPersistenceGateTest" --tests "*TavernPreviewMutationRebaserTest" --no-daemon
```

Result: `BUILD SUCCESSFUL`.

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

Result: `BUILD SUCCESSFUL` — 106 test classes / 764 tests / 0 failures / 0 errors / 0 skipped.

```powershell
.\gradlew.bat :app:compileDebugKotlin :app:assembleDebug --no-daemon
```

Result: `BUILD SUCCESSFUL` (227 actionable tasks, 11 executed / 216 up-to-date). `git diff --check` reported no
whitespace errors.
