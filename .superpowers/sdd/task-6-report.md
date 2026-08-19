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

Expected failure: unresolved `TavernPermissionCompatibilityMigration`, preset factories, migration marker, and preview target gate.

### GREEN 1

The focused tests passed after implementing the one-shot migration, exact presets, and explicit target selection/routing.

### RED 2: persisted current-message write

Expected failure: unresolved `applyTavernPreviewMessagePatch`.

### GREEN 2

The helper now updates only the selected branch's current persisted message while preserving message identity.

### RED 3: target initialization race

Expected failure: unresolved `markReady`; writes were possible after selection but before the real conversation finished loading.

### GREEN 3

Selection now resets readiness, only the selected conversation can become ready, the WebView is withheld until initialization succeeds, and writes are rejected until then.

## Implementation notes

- `TavernPermissionCompatibilityMigration` overwrites legacy/fresh values with maximum compatibility once and records `tavern_permission_compat_migrated_v1`; later user changes are preserved.
- Maximum compatibility enables scripts, network, message/variable/world writes, event subscription, and macro registration. Request headers remain disabled.
- Conservative mode disables all script capabilities and request-header access; individual switches remain available.
- The editor never auto-selects a conversation. The selected target is visible in the editor top bar and message section and can be changed explicitly.
- Target conversation references are acquired/released by the editor ViewModel. Initialization readiness prevents scripts from running against a stub session.
- The existing secure single-WebView runtime is reused. Variables, world writes, events, macro/slash registration, network, and sendHook ownership use the real conversation ID. `messages.updateCurrent` is persisted through `ChatService` to the selected conversation.
- WebView/controller disposal detaches active sendHook ownership; target changes and ViewModel clearing release conversation references.

## Verification

Focused tests + compile:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernPermissionMigrationTest" --tests "*TavernGreetingPreviewTargetTest" :app:compileDebugKotlin --no-daemon
```

Result: `BUILD SUCCESSFUL`.

Full JVM suite:

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

Result: `BUILD SUCCESSFUL`.

Compile + APK:

```powershell
.\gradlew.bat :app:compileDebugKotlin :app:assembleDebug --no-daemon
```

Result: `BUILD SUCCESSFUL`.

`git diff --check` reported no whitespace errors. No `opencode.exe` process was running. No Room entity, migration, schema, or database version file changed.

## Self-review

- Fixed the target-loading race before verification by adding the readiness gate.
- Confirmed preset actions preserve per-permission controls and never enable request-header reads.
- Confirmed target switching/destroy paths detach active sendHook controllers and release conversation references.
- No plan or ledger files were modified.
