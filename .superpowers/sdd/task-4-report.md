# Task 4 report: atomic greeting selection and immersive opening stage

## Outcome

- Added one isolated writable runtime journal per `first_mes` / alternate greeting. Candidate message writes, chat/global variables, world mutations, and macro/slash registrations remain local until selection.
- Added mutex-guarded selection/commit semantics. A successful commit applies the selected overlay and discards all other candidates; a failed commit leaves the session unlocked and retryable.
- Added atomic macro/slash registry batches so registration validation cannot leave a half-applied selected candidate.
- Hardened the commit boundary after review: registration, send-hook, settings, variable, and conversation changes are
  serialized and cancellation/failure rollback runs in `NonCancellable`; macro/slash/send-hook registrations are owned by
  conversation so candidates, replay viewers, and other conversations cannot replace one another.
- Added typed opening metadata helpers (kind, greeting index, content fingerprint, card fingerprint), legacy first-message inference, import tagging, greeting-index navigation, and legacy Base64 reading without a Room migration.
- Added the full-width opening stage on the Task 3 secure host. Every greeting WebView remains composed with no artificial count limit, candidate runtimes do not own the global send-hook controller, and the UI discloses irreversible network effects.
- The existing auto-picker preference is honored: disabled commits `first_mes`; enabled keeps the stage active. Sending from the native input commits the currently selected candidate before writing the first user message.
- After the first user message the stage is destroyed, a top-right opening icon replays the selected opening fullscreen, and changing it creates a separate conversation while preserving the original.
- Candidate snapshots now update reactively while their scripts run. Once selected, opening metadata records that runtime
  execution has completed, so normal chat and fullscreen replay render the opener without running its scripts again.

## TDD evidence

Genuine RED was captured before production implementation for:

- candidate/session/metadata/navigation APIs: focused compilation failed on missing `TavernGreetingSession`, candidate overlay, typed metadata, navigation, and new-conversation symbols;
- runtime journaling: focused tests initially failed before `TavernRuntimeController` exposed candidate-local variable/world/message/registration bindings;
- selected-on-first-send boundary: `TavernGreetingSessionTest` failed compilation with unresolved `selectCandidate` and `commitSelected`;
- registration atomicity: `TavernScriptRegistryTest` failed compilation with unresolved `registerBatch` and `SlashCommandRegistration`.
- review hardening: new tests first failed compilation on missing owner-scoped registrations, candidate `overlayFlow`,
  send-hook journaling/selection, committed-runtime metadata, and replay script-suppression fields.
- the production transaction coordinator test first failed on missing `withTavernGreetingAtomicCommit`, then covered both
  shared-mutex serialization and cancellation rollback before the coordinator was wired into `ChatService`.

Final focused verification:

```text
.\gradlew.bat :app:testDebugUnitTest \
  --tests "me.rerere.rikkahub.service.tavern.TavernGreetingSessionTest" \
  --tests "me.rerere.rikkahub.service.tavern.TavernGreetingAtomicCommitTest" \
  --tests "me.rerere.rikkahub.data.ai.slash.TavernScriptRegistryTest" \
  --tests "me.rerere.rikkahub.ui.components.richtext.runtime.TavernSendHookStoreTest" \
  --tests "me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationSnapshotTest" \
  --tests "me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationDocumentTest" --no-daemon
BUILD SUCCESSFUL in 18s
```

Coverage includes candidate isolation, reactive runtime mutation journaling, commit/discard, failed-commit retryability,
current-stage selection on first send, lock after user message, typed metadata, legacy navigation, new-conversation request,
retained non-opening presets, owner-scoped registration batches, per-conversation send-hook selection, committed fallback,
and suppression of repeated opening scripts.

## Final verification

```text
.\gradlew.bat :app:testDebugUnitTest --no-daemon
BUILD SUCCESSFUL in 13s
100 test classes / 727 tests / 0 failures / 0 errors

.\gradlew.bat :app:compileDebugKotlin :app:assembleDebug --no-daemon
BUILD SUCCESSFUL in 16s
```

Generated APKs:

- `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` (85,757,019 bytes)
- `app/build/outputs/apk/debug/app-universal-debug.apk` (96,624,169 bytes)
- `app/build/outputs/apk/debug/app-x86_64-debug.apk` (86,604,281 bytes)

`git diff --check` reported no whitespace errors (only the checkout's expected LF-to-CRLF notices).

## Scope notes

- No Room schema/version change.
- No plan checkbox or shared SDD ledger edits.
- Instrumented/physical-device validation remains assigned to the later reliability task in the plan.

## Second review hardening

The first re-review found remaining stale-snapshot, selection-race, process-restoration, replay, permission, navigation,
cleanup, and test-evidence gaps. The follow-up implementation:

- records global-variable and world-book operations as mutation journals and rebases them onto state read at commit;
- freezes the selected runtime at the commit boundary, reopens it on failure, and disables selection until its WebView is
  ready;
- persists macro/slash/send-hook runtime state inside typed opening message metadata, restores it during conversation
  initialization and before sending with current permissions, and cleans conversation-owned runtime state on deletion;
- renders committed replay with a no-script sandbox, restrictive offline CSP, nested active-element removal, and URL-bearing
  attribute stripping;
- consumes greeting navigation once and skips it for already locked conversations;
- removes the obsolete unscoped send-hook path and expands tests for journal rebasing, freeze/retry, metadata round-trip,
  cleanup, and static replay.

Final verification after this wave:

```text
.\gradlew.bat :app:testDebugUnitTest --no-daemon
BUILD SUCCESSFUL in 12s
100 test classes / 732 tests / 0 failures / 0 errors

.\gradlew.bat :app:compileDebugKotlin :app:assembleDebug --no-daemon
BUILD SUCCESSFUL in 17s
```

The next re-review tightened the remaining boundaries: native send now shares the same runtime-readiness gate as the
selection button; auto-picker-off commits intentionally remain unexecuted so the normal pane can initialize scripts;
route consumption uses saveable state; settings/world apply and rollback rebase only touched journal keys onto the latest
settings; failed deletes are not journaled; and static replay retains CSP-permitted embedded `data:`/`blob:` media.

Final verification for this wave: full JVM `100 classes / 734 tests / 0 failures / 0 errors` (`BUILD SUCCESSFUL in 13s`),
then `:app:compileDebugKotlin :app:assembleDebug` (`BUILD SUCCESSFUL in 17s`).
