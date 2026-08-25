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

## Production mutation-store refactor

- Added `TavernRuntimeMessageMutationStore` with a persistence adapter boundary. It owns the runtime
  read-modify-save sequence and emits lifecycle events only after persistence returns successfully.
- `ChatService` now provides the real adapter and delegates its runtime create/update/delete APIs to that store.
  The adapter holds a `ConversationSession` reference while it owns the per-session mutation lock, then verifies
  that the same session remains registered and ready inside the lock. This closes the readiness/eviction TOCTOU
  before a live-state read can overwrite persisted history.
- `TavernRuntimeMessageMutationStoreTest` was written first and observed RED three times: first for the absent
  store/adapter, then for absent update/delete operations, and finally for an absent removal-persistence path.
  Its seven green cases cover not-ready and eviction, concurrent creates plus update/delete, reload persistence,
  live `StateFlow`, success/failure event ordering, selected-branch update preservation, and no swipe creation.
- The runtime gateway test now verifies that only the last selected-branch message has `isCurrent=true`.

## Verification

- `:app:testDebugUnitTest` focused runtime/controller/bridge/message tests: PASS (67 tests).
- `:app:compileDebugKotlin --no-configuration-cache`: PASS.
- `git diff --check`: PASS.
- Focused runtime JVM suite: PASS, including the seven mutation-store cases.
- `:app:compileDebugKotlin --no-configuration-cache`: PASS after the refactor.

## Self-review

- Confirmed no changes were made to `verification-screenshots/`.
- Existing message iframe injection and context fallback behavior remain covered by prior tests.
- The production gateway does not maintain a second conversation cache; all mutations use `ChatService`.

## Concerns

- Runtime calls now return `CONVERSATION_NOT_READY` until `initializeConversation` completes; mutations are serialized by the per-session runtime-message mutex and only emit events after persistence succeeds.
- The new JVM persistence fixture deliberately models the adapter contract rather than Room itself; the real
  adapter delegates to `ChatService.saveConversation`, which continues to use the repository source of truth.

## Commit

Implementation commit: `81456f1e feat: add persistent Tavern runtime message API`.

Refactor/test commit: current `refactor: extract Tavern runtime message mutations` commit.
