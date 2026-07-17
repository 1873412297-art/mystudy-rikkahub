# Task 10 Implementation Report

## Scope

Task 10 adds final integration evidence for prompt tracing without changing production chat generation. The implementation changes are limited to Android instrumentation tests, the execution plan, and this report.

## Added coverage

### GenerationHandler integration

- Strengthened the two-provider-call tool loop assertion:
  - trace order is provider step `[1, 0]` in repository newest-first order;
  - both rows finish `COMPLETED`;
  - the second call's provider-bound trace contains the executed `tool result` output.
- Added deterministic cancellation fixtures using `CompletableDeferred`:
  - cancellation before the first chunk preserves the request anchor and leaves response binding null;
  - cancellation after the first chunk preserves the bound response ID;
  - both collection jobs stay cancelled rather than becoming chat error output.
- Strengthened provider failure coverage with `authorization=secret`:
  - prepared provider-bound messages remain readable;
  - status is `FAILED`;
  - the error contains `[redacted]` and excludes the secret.
- Added streaming trace-store failure equivalence against tracing disabled, comparing every emitted message's role, parts, model, and usage while excluding generated response IDs and timestamps.
- Added a Google provider integration alongside the existing OpenAI fixture to prove provider-agnostic capture and provider identity.

### Repository and cleanup integration

- Two response alternatives map to distinct trace IDs.
- Default branch selection changes without deleting history.
- Removing one response alternative deletes only its bound trace.
- Tail regeneration deletes bound response traces and unbound attempts anchored in the removed tail while retaining earlier traces.
- Unbound attempts survive unrelated removals and follow their request anchor.
- A fork conversation begins without inherited trace rows.
- The 21st insert removes the exact oldest trace and retains the newest 20.

### Console flow integration

`TavernPromptConsoleFlowTest` now exercises one continuous Compose flow:

1. render an eligibility-driven `Cards02` entry;
2. open the same conversation ID;
3. verify selected-branch default trace selection;
4. navigate Overview -> Hits -> Sent messages -> Preview;
5. copy one message and the complete trace;
6. select a historical trace;
7. confirm clear;
8. prove the original `MessageNode.messages` and `selectIndex` remain unchanged.

## TDD record

- Initial GenerationHandler run: RED, 1/13 failed. The first streaming equivalence assertion included a response ID generated separately for each independent run.
- Test correction: compare emitted semantic content (role, parts, model, usage) while excluding per-run IDs/timestamps.
- GenerationHandler GREEN: 14/14 passed.
- Repository/UI characterization additions: 10/10 passed against existing behavior.
- No production source modification was required.

Logs are retained locally under `.superpowers/sdd/task-10-*.log` and excluded from Git.

## Verification

| Gate | Result |
|---|---|
| Focused prompt trace/group JVM command from brief | PASS |
| Group modes + sanitization matrix | PASS, 57 tests across 6 classes |
| Focused instrumentation command from brief | PASS, 41 tests |
| Full `:app:testDebugUnitTest` | PASS |
| Full `:app:connectedDebugAndroidTest` | PASS, 54 tests |
| `:app:assembleDebug` | PASS |
| `:app:lintDebug` | Completed with repository baseline failure: 101 errors, 289 warnings, 3 hints |
| Task 10 files in lint findings | 0 |
| `git diff --check` | PASS |

The first lint error is the existing `local.properties` `PropertyEscape` finding. Other fatal findings are in pre-existing production files/resources; none references the three Task 10 test files.

## Emulator and database record

- Device: `emulator-5554`.
- The build emits ABI-split APKs; `app-x86_64-debug.apk` installed successfully.
- Package `me.rerere.rikkahub.debug` launched and kept a live process; the crash buffer was empty.
- The fresh installed data contained the default non-Tavern assistant. UI-tree inspection showed no prompt-console top-bar entry.
- The streamed debug Room database contained the `prompt_trace` table with 0 rows.
- Local SQLite inspection reported:
  - base64-body matches: 0;
  - query-credential matches: 0;
  - credential-metadata matches: 0;
  - opaque-reasoning-signature matches: 0;
  - maximum trace rows per conversation: 0.
- Populated sanitization and 20-row retention fixtures pass in automated tests.

## Open live-smoke items

The installed data set has no configured provider or Tavern/group fixture. Eleven request-dependent live matrix entries therefore remain unchecked in the plan. Their deterministic logic is covered by the focused/full suites, including solo/group seed behavior, layered group context, manual/round-robin/moderator director behavior, multi-provider capture, injection provenance, cancellation/failure, branch cleanup, retention, and Preview state.
