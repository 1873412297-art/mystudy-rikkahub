# Task 4 report: atomic greeting selection and immersive opening stage

## Outcome

- Added one isolated writable runtime journal per `first_mes` / alternate greeting. Candidate message writes, chat/global variables, world mutations, and macro/slash registrations remain local until selection.
- Added mutex-guarded selection/commit semantics. A successful commit applies the selected overlay and discards all other candidates; a failed commit leaves the session unlocked and retryable.
- Added atomic macro/slash registry batches so registration validation cannot leave a half-applied selected candidate.
- Added typed opening metadata helpers (kind, greeting index, content fingerprint, card fingerprint), legacy first-message inference, import tagging, greeting-index navigation, and legacy Base64 reading without a Room migration.
- Added the full-width opening stage on the Task 3 secure host. Every greeting WebView remains composed with no artificial count limit, candidate runtimes do not own the global send-hook controller, and the UI discloses irreversible network effects.
- The existing auto-picker preference is honored: disabled commits `first_mes`; enabled keeps the stage active. Sending from the native input commits the currently selected candidate before writing the first user message.
- After the first user message the stage is destroyed, a top-right opening icon replays the selected opening fullscreen, and changing it creates a separate conversation while preserving the original.

## TDD evidence

Genuine RED was captured before production implementation for:

- candidate/session/metadata/navigation APIs: focused compilation failed on missing `TavernGreetingSession`, candidate overlay, typed metadata, navigation, and new-conversation symbols;
- runtime journaling: focused tests initially failed before `TavernRuntimeController` exposed candidate-local variable/world/message/registration bindings;
- selected-on-first-send boundary: `TavernGreetingSessionTest` failed compilation with unresolved `selectCandidate` and `commitSelected`;
- registration atomicity: `TavernScriptRegistryTest` failed compilation with unresolved `registerBatch` and `SlashCommandRegistration`.

Final focused verification:

```text
.\gradlew.bat :app:testDebugUnitTest \
  --tests "me.rerere.rikkahub.service.tavern.TavernGreetingSessionTest" \
  --tests "me.rerere.rikkahub.data.ai.slash.TavernScriptRegistryTest" --no-daemon
BUILD SUCCESSFUL in 31s
```

Coverage includes candidate isolation, real runtime mutation journaling, commit/discard, failed-commit retryability, current-stage selection on first send, lock after user message, typed metadata, legacy navigation, new-conversation request, retained non-opening presets, and atomic registration batches.

## Final verification

```text
.\gradlew.bat :app:testDebugUnitTest --no-daemon
BUILD SUCCESSFUL in 22s
99 test classes / 718 tests / 0 failures / 0 errors

.\gradlew.bat :app:compileDebugKotlin :app:assembleDebug --no-daemon
BUILD SUCCESSFUL in 27s
```

Generated APKs:

- `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` (84,971,594 bytes)
- `app/build/outputs/apk/debug/app-universal-debug.apk` (95,838,744 bytes)
- `app/build/outputs/apk/debug/app-x86_64-debug.apk` (85,818,856 bytes)

`git diff --check` reported no whitespace errors (only the checkout's expected LF-to-CRLF notices).

## Scope notes

- No Room schema/version change.
- No plan checkbox or shared SDD ledger edits.
- Instrumented/physical-device validation remains assigned to the later reliability task in the plan.
