# Task 10 Implementation Report

## Scope

Task 10 adds end-to-end prompt-trace integration evidence and a small extraction of the existing `ChatService` conversation persistence wiring so regeneration and fork behavior can be exercised directly with a real in-memory Room database and `PromptTraceRepository`.

## Added coverage

### GenerationHandler integration

- Verifies two provider calls in a tool loop, including the second call's executed tool result.
- Covers cancellation before and after the first chunk, provider failure with sanitized error text, and trace-store failure equivalence.
- Exercises both OpenAI and Google provider fixtures.

### Repository and conversation persistence integration

- Verifies branch selection/history and branch-scoped trace deletion.
- Verifies exact 20-row repository retention with a 21-row fixture.
- `PromptTraceConversationPersistenceTest` now calls production wiring used by `ChatService`:
  - user regeneration builds the truncated conversation, derives removed IDs, persists it, and deletes only matching trace rows;
  - fork construction creates a new conversation ID and new node IDs, persists it, preserves source traces, and starts with no copied trace rows.
- The earlier direct-DAO regeneration/fork assertions were removed; they are superseded by the production-wiring tests.

### Console flow integration

`TavernPromptConsoleFlowTest` uses an in-memory `AppDatabase`, real `PromptTraceRepository`, real `TavernPromptConsoleVM`, and the production `TavernPromptConsoleEntry`/content callbacks in one continuous Compose flow:

1. open the eligible entry for the same conversation ID;
2. default to the selected branch trace and then select history;
3. navigate Overview, Hits, Sent messages, and Preview;
4. copy a real message and complete trace through VM formatters;
5. clear through `PromptTraceRepository` and observe the real empty state;
6. verify the original `Conversation`/`MessageNode` snapshot and conversation row remain unchanged.

## TDD record

- Review RED: the new instrumentation test referenced missing production persistence/build seams; `compileDebugAndroidTestKotlin` failed with unresolved references. Log: `.superpowers/sdd/task-10-review-red.log`.
- Review GREEN: production seams were extracted and called from `ChatService`; the two new persistence tests plus the real console flow passed 3/3. Log: `.superpowers/sdd/task-10-review-green.log`.
- The first GREEN attempt exposed Kotlin bind-argument intersection-type compilation errors; explicit `arrayOf<Any?>` arguments fixed the test fixture before the successful run.

## Verification

Final counts are recorded after the focused and full reruns below.

## Emulator and database record

- Device: `emulator-5554`.
- An ABI-split debug APK was installed and launched; the crash buffer was empty.
- The default non-Tavern assistant showed no prompt-console top-bar entry.
- No provider request was generated, so non-Tavern trace non-creation remains open.
- The streamed debug database contained the `prompt_trace` table with zero rows. This is schema evidence only, not populated-row evidence for sanitization or 20-row retention.
- The live populated-database sanitization/retention gate remains unchecked in the plan; automated fixtures are reported separately.

## Open live-smoke items

The installed data has no configured provider or Tavern/group fixture. All twelve request-dependent live matrix entries remain unchecked. Automated coverage does not relabel the missing emulator observations as passed.

## Review follow-up verification

| Gate | Result |
|---|---|
| RED compilation for missing production seams | Expected failure recorded |
| Targeted production persistence + real console flow | PASS, 3/3 |
| Focused prompt trace/group JVM command | PASS |
| Focused instrumentation command including persistence wiring | PASS, 41/41 |
| Full `:app:testDebugUnitTest` | PASS |
| Full `:app:connectedDebugAndroidTest` | PASS, 54/54 |
| `:app:assembleDebug` | PASS |
| `git diff --check` | PASS |
