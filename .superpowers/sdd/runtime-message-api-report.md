# Runtime message API report

## Scope

Implemented persistent Tavern message RPCs: `list`, `get`, `getCurrent`, `create`, `update`, `updateCurrent`, and `delete`.

## RED to GREEN evidence

1. `TavernRuntimeMessageGatewayTest` initially failed to compile because `TavernRuntimeMessage` and `InMemoryTavernRuntimeMessageGateway` did not exist. The test now verifies selected-order reads and the full controller create/update/delete path.
2. The controller message-RPC test then failed because `TavernRuntimeController` had no `messageGateway` constructor parameter. It is green with permission denial/no mutation, structured missing data, no active conversation, and injected-current precedence coverage.
3. The JavaScript contract test failed because the runtime did not expose the new message functions. It is green after adding all functions to the shared `TavernHelper`/`TH` API object.
4. `ChatServiceRuntimeMessageTest` initially failed to compile because `replaceRuntimeMessageText` did not exist. It is green and proves exact text replacement preserves ID, role, annotation, attachment object, and part metadata.
5. Existing runtime regressions initially caught the loss of context/injected `getCurrent` fallback. The controller was corrected and the regression suite is green.

## Implementation

- Added `TavernRuntimeMessageGateway`, in-memory test/preview implementation, and `ChatServiceTavernRuntimeMessageGateway`.
- `MarkdownWebView` injects the production gateway backed directly by the singleton `ChatService`.
- `ChatService` now appends message nodes, performs exact in-place text replacement rather than creating a swipe, uses normal deletion/persistence cleanup, refreshes live session state through `saveConversation`, and emits message lifecycle events.
- Controller validates roles/IDs/text, enforces `allowScripts` plus `allowMessageWrite`, and returns structured errors.

## Verification

- `:app:testDebugUnitTest` focused runtime/controller/bridge/message tests: PASS (67 tests).
- `:app:compileDebugKotlin --no-configuration-cache`: PASS.
- `git diff --check`: PASS.

## Self-review

- Confirmed no changes were made to `verification-screenshots/`.
- Existing message iframe injection and context fallback behavior remain covered by prior tests.
- The production gateway does not maintain a second conversation cache; all mutations use `ChatService`.

## Concerns

- The existing JVM test infrastructure has no lightweight constructible `ChatService`/Room fixture, so persistence and host-event dispatch are asserted through the production call chain and focused pure/controller tests rather than a new end-to-end Room test. Device/instrumented verification remains advisable.

## Commit

Implementation commit: `81456f1e feat: add persistent Tavern runtime message API`.
