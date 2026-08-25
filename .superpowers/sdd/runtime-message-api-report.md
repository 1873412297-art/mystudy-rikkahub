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

## Follow-up correctness pass

- `ChatService.cleanup()` clears `TavernRuntimeConversationReadiness` before clearing sessions. Session eviction
  also clears the same readiness owner; a cleared/recreated conversation therefore rejects runtime writes without
  replacing loaded history.
- `ConversationSession` now has one conversation-mutation mutex. The runtime-specific name is a compatibility
  alias to that mutex. Normal send, public `saveConversation`, ordinary edit, and ordinary delete all use it;
  runtime persistence uses the unlocked internal save function while already holding it, avoiding non-reentrant
  `Mutex` deadlock.
- The normal-save/runtime-create race regression pauses a production `TavernRuntimeMessageMutationStore` create at
  persistence, starts a full normal save, and proves the normal save cannot read the old snapshot before runtime
  commits. The final session has both messages.
- `saveConversationUnlocked` persists through the repository before publishing `StateFlow`; the failure regression
  proves a repository exception leaves live state unchanged.
- `getCurrent` returns `NOT_FOUND` for a ready, empty gateway. Changing the bound conversation clears injected
  current-message state. Create responses and the production gateway re-read committed selection for `isCurrent`.
- `ChatServiceTavernRuntimeMessageGateway` is tested directly through the real `TavernRuntimeMessageService`
  contract implemented by `ChatService`, including structured role/ID/current mapping.

## Verification

- Focused runtime JVM suite: PASS, including controller/gateway, mutation-store, readiness, persistence-order,
  and conversation-lock competition tests.
- `:app:compileDebugKotlin --no-configuration-cache`: PASS.
- `git diff --check`: PASS.

## Self-review

- Confirmed no changes were made to `verification-screenshots/`.
- Existing message iframe injection and context fallback behavior remain covered by prior tests.
- The production gateway does not maintain a second conversation cache; all mutations use `ChatService`.

## Concerns

- Runtime calls return `CONVERSATION_NOT_READY` until `initializeConversation` installs live state. Cleanup and
  eviction synchronously revoke this readiness before a later recreated session can be mutated.
- The focused suite uses deterministic JVM persistence fixtures rather than Room instrumentation. The production
  gateway itself is exercised directly and `ChatService` is its concrete service implementation; its adapter still
  delegates to the real repository save/live-state path.

## Commit

Implementation commit: `81456f1e feat: add persistent Tavern runtime message API`.

Refactor/test commit: `1b65be8a refactor: extract Tavern runtime message mutations`.

Follow-up correctness commit: current `fix: serialize Tavern runtime conversation mutations`.
