# Runtime message API report

## Scope

Implemented persistent Tavern message RPCs: `list`, `get`, `getCurrent`, `create`, `update`,
`updateCurrent`, and `delete`.

## RED to GREEN evidence

1. `TavernRuntimeMessageGatewayTest` initially failed to compile because `TavernRuntimeMessage` and
   `InMemoryTavernRuntimeMessageGateway` did not exist. The test now verifies selected-order reads and the
   full controller create/update/delete path.
2. The controller message-RPC test then failed because `TavernRuntimeController` had no `messageGateway`
   constructor parameter. It is green with permission denial/no mutation, structured missing data, no active
   conversation, and injected-current precedence coverage.
3. The JavaScript contract test failed because the runtime did not expose the new message functions. It is green
   after adding all functions to the shared `TavernHelper`/`TH` API object.
4. `ChatServiceRuntimeMessageTest` initially failed to compile because `replaceRuntimeMessageText` did not exist.
   It is green and proves exact text replacement preserves ID, role, annotation, attachment object, and metadata.
5. Existing runtime regressions initially caught the loss of context/injected `getCurrent` fallback. The controller
   was corrected and the regression suite is green.

## Implementation

- Added `TavernRuntimeMessageGateway`, in-memory test/preview implementation, and
  `ChatServiceTavernRuntimeMessageGateway`.
- `MarkdownWebView` injects the production gateway backed directly by the singleton `ChatService`.
- `ChatService` now appends message nodes, performs exact in-place text replacement rather than creating a swipe,
  uses normal deletion/persistence cleanup, refreshes live session state through `saveConversation`, and emits
  message lifecycle events.
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

## Third review concurrency pass

- Host-injected `UIMessage.serializer()` payloads are normalized to the runtime `messageId`, `role`, `text`, and
  `isCurrent` shape. `messages.updateCurrent` detects a real UUID plus active conversation, persists through the
  gateway, and replaces the injected cache with the committed shape; only synthetic iframe-only payloads remain
  memory-only.
- `ChatService` now provides an atomic suspend runtime snapshot: it retains the existing session, takes the shared
  mutation mutex, verifies readiness and session identity, then reads selected messages. The production gateway
  calls it through `runBlocking` for list/get/current reads, returning `CONVERSATION_NOT_READY` without creating an
  empty live session after eviction.
- Streaming chunk merging and completion now use the established `groupDirectorMutex -> conversationMutationMutex`
  order. Generation-start live updates follow that order too. A deterministic streaming-chunk/runtime-mutation
  regression proves the runtime write cannot read a pre-chunk snapshot.
- `selectMessageNode`, greetings, interrupted-tool cleanup, invalid-message cleanup, translation final saves, and
  the non-group generation completion path now read and save inside `withConversationMutation`; raw saves avoid
  locking the non-reentrant mutex twice.

## Fourth review mutation audit

- Audited every ChatService read followed by a live update, repository save, or raw save. Tool approval,
  user/assistant regeneration, title and suggestion completion, translation chunk/clear updates, and public
  `updateConversationState` now derive from the latest value under `withConversationMutation`.
- Group command reduction, selected-speaker resolution, group cancellation, failure handoff, success handoff, and
  group cleanup follow one lock order: `groupDirectorMutex -> conversationMutationMutex`. Once that lock is held,
  they call `saveConversationUnlocked` rather than re-entering public `saveConversation`.
- `normalizeCancelledGroupGeneration` takes the conversation mutation lock while it derives its cancellation
  state, so all of its persistence callbacks receive a current snapshot. Translation network work remains outside
  the lock; each final live apply re-reads the current session while locked.
- `mutateConversation(session)` is the shared production helper used by ChatService. Its deterministic regression
  pauses a runtime mutation before commit, starts the tool-approval-shaped production mutation path, and proves it
  cannot read the stale snapshot; the final session retains both changes.

## Fifth review field and long-running mutation pass

- ChatVM and web conversation routes no longer save UI/repository snapshots over live messages. They delegate title,
  pin, assistant, injection, metadata, and selected-node changes to explicit ChatService field methods, each of
  which reads the latest session state under the shared mutation lock before persisting.
- Compression records its source message-node IDs before provider work. Its final lock verifies that baseline remains
  the live prefix, replaces only that prefix, and retains nodes appended by runtime or normal sends while the remote
  summary was pending.
- `messages.updateCurrent` now calls one atomic `gateway.updateLatest` operation. The production store chooses the
  latest selected message, updates, persists, and emits in one mutex scope; controller no longer performs a separate
  snapshot read. Store tests cover both direct latest update and a controlled updateLatest/create interleave.
- AUTO_MODERATOR captures its decision snapshot under the established group-then-conversation order, performs the
  provider call with the conversation mutation lock released, then re-enters both locks and applies only when message,
  queue, active-member, and runtime director state still match the snapshot.

## Verification

## Tenth review initialization rendering and assistant deletion gate pass

- Initialization now renders stored/preset status instructions against a temporary `StatusVariableStore` seeded from
  the persisted variables. `UpdateVariable` patches therefore contribute to candidate status HTML and the candidate
  `Conversation.statusVariables`, but no losing loader can write the global store. Only the accepted `INSTALL` branch
  publishes the candidate once; `MARK_READY` keeps the live variable state.
- Every `withConversationMutation` admission retains its captured session, confirms it is still the mapped instance
  inside the shared lock, and retries a replacement/closed session once before failing. This prevents an old session
  from writing a newer session after eviction.
- Assistant batch deletion now unions repository IDs with live-only sessions for that assistant, processes stable
  IDs through `deleteConversationAtomic`, and installs a scoped deletion gate. Initializers and persistence reject
  gated assistants, while `finally` always removes the gate. Per-conversation exceptions are collected and later
  conversations are still attempted; AssistantVM only removes the assistant after a fully successful batch.
- Focused JVM suite: PASS (`ChatServiceTest`, mutation store, runtime gateway, and status transformer tests).

## Eleventh review assistant ownership and initialization publication pass

- `deleteConversationAtomic` now accepts an optional expected assistant ID and returns `DELETED`, `NOT_FOUND`, or
  `MOVED`. Assistant batch deletion passes the owner it collected from; active sessions verify the expected owner
  while holding the conversation mutation lock, and the Room delete is conditional on `id` plus `assistant_id`.
  A conversation moved to another assistant after collection is left intact, keeps its live session, and is a safe
  batch skip. FTS/files/status cleanup runs only after that conditional database delete succeeds.
- Moving a conversation out of a deleting assistant remains allowed; assignment into a deleting assistant is rejected
  by the scoped deletion gate. Routes translate only a non-`DELETED` ordinary delete to 404.
- Initialization now persists the candidate and publishes its conversation live state before it initializes global
  status variables, then marks runtime readiness. A persistence failure leaves the existing global variables intact
  and does not mark the session ready.
- Assistant batch deletion now rethrows `CancellationException` immediately, records only ordinary `Exception`s,
  never absorbs `Error`, and clears its deletion gate in `finally`.
- RED to GREEN: added deterministic JVM regressions for collection-then-move ownership protection, deletion-gate
  direction, cancellation stopping later deletes, `MOVED` batch success, and failed initialization persistence
  retaining global status variables.
- Focused JVM suite: PASS (`ChatServiceTest`, `TavernRuntimeMessageMutationStoreTest`,
  `TavernRuntimeMessageGatewayTest`, and `StatusPlaceholderTransformerTest`).
- `:app:compileDebugKotlin :web:compileDebugKotlin --no-configuration-cache`: PASS.

## Final assistant-initialization gate pass

- The assistant-deletion gate is rechecked while holding the gate mutex through the final conversation install, so an
  initializer that started before deletion cannot publish a live/ready session after deletion admission begins.
- An initialized, ready, live-only conversation with no Room row is closed as a successful deletion; an unready
  placeholder still reports `NOT_FOUND`.
- Focused runtime JVM suite remains green (83 tests, 0 failures), with app/web compilation covered by the preceding
  verification pass.
- Assistant batch deletion now always lets Room's conditional `id + assistantId` delete decide persisted ownership;
  placeholder session metadata is never trusted. Only a ready live-only session with no Room row is treated as a
  successful close, while an unready placeholder remains `NOT_FOUND`.
- Final compatibility pass distinguishes an authoritative persistent empty snapshot (`NOT_FOUND`) from a legacy
  controller without a persistent message source, which may still read its current entry from the context snapshot.
- Full `:app:testDebugUnitTest :app:assembleDebug` verification: PASS (113 test classes, 786 tests, 0 failures;
  versionName 2.4.10, versionCode 177).
- `git diff --check`: PASS.

## Twelfth review deletion-finalization and current-message pass

- Assistant deletion now keeps one scoped gate from ordered conversation deletion through the caller-supplied
  settings, memory, and file finalization. `AssistantVM` performs that finalizer through
  `ChatService.deleteAssistantAtomically`; finalizer failure is returned/logged and the gate is released in
  `finally`. Target-assistant moves hold the same gate mutex from deletion-gate check through the active or DAO
  commit, so a conversation cannot move into an assistant after its deletion has begun.
- `HistoryVM` restores only through `ChatService.restoreConversationAtomic`, which verifies that the owning assistant
  still exists, is not deleting, and the conversation row is absent before it inserts.
- Conversation deletion is split into a Room commit and non-cancellable best-effort FTS/file/status cleanup. Once
  Room commits, active readiness/state is revoked and the session is closed/removed before external cleanup runs.
  Cleanup errors are collected and logged; session identity replacement retries twice before using the inactive
  conditional-delete path.
- Bound injected current messages now validate their `messageId` against the production gateway. A successful
  matching delete clears that cache; an authoritative ready-but-empty snapshot returns `NOT_FOUND` without falling
  back to stale context chat data.
- RED to GREEN: added focused regressions for injected-current delete, gate lifetime through finalization, finalizer
  failure release, restore admission, and FTS failure continuing file/status cleanup.
- Focused JVM suite: PASS (`ChatServiceTest`, `TavernRuntimeMessageMutationStoreTest`,
  `TavernRuntimeMessageGatewayTest`, and `StatusPlaceholderTransformerTest`).
- `:app:compileDebugKotlin :web:compileDebugKotlin --no-configuration-cache`: PASS.
- `git diff --check`: PASS.

## Ninth review initialization variables, assistant deletion, and injected-current pass

- Persisted status variables are now initialized only in the winning `INSTALL` branch while holding the session
  mutation lock. A superseded loader and a same-generation loader invalidated by a live mutation take `SKIP` or
  `MARK_READY` without changing variables. Initial-history rendering explicitly disables status-variable writes, so
  old persisted message tags cannot mutate the live store before the installation token is accepted.
- `AssistantVM` delegates assistant-owned conversation removal to
  `ChatService.deleteConversationsOfAssistantAtomic`. The service reads the IDs once, orders them stably, attempts
  every deletion, and routes each through `deleteConversationAtomic` before settings/memory/file cleanup can remove
  the assistant. This preserves the closed-session/readiness barrier for active conversations.
- Bound injected `messages.getCurrent` now validates the production gateway readiness before returning its cache;
  a deleted or evicted conversation returns `CONVERSATION_NOT_READY`. Pure isolated iframe injection remains
  available when no conversation is bound.
- A normal save cannot insert a missing non-ready conversation. Explicit fork creation and ready newly initialized
  conversations remain allowed to create persistence rows.
- New JVM regressions cover superseded and mutation-invalidated initialization variables, deterministic batch
  deletion order/attempts, deleted-save admission, bound injected-current readiness, and isolated injection.
- Focused JVM suite: PASS (`ChatServiceTest` 39, `TavernRuntimeMessageMutationStoreTest` 9,
  `TavernRuntimeMessageGatewayTest` 16; 0 failures).
- `:app:compileDebugKotlin :web:compileDebugKotlin --no-configuration-cache`: PASS.
- `git diff --check`: PASS.

## Eighth review initialization ownership and deletion pass

- Initialization action now distinguishes token generation from mutation version: a later initializer makes the older
  loader skip completely, while only a mutation that occurred during the latest loader marks the existing live state
  ready. The superseded-initializer regression covers this distinction.
- Runtime mutation admission no longer calls `getOrCreateSession`; it requires the already-bound session, readiness,
  and an existing repository row inside the mutation lock. Eviction, cleanup, and deletion therefore cannot recreate
  an empty runtime session or insert a deleted conversation.
- Conversation deletion is centralized in `ChatService.deleteConversationAtomic`. An active session first revokes
  readiness, closes under the shared mutation lock, and is removed before repository deletion. Routes, ChatVM, and
  HistoryVM delegate to it. History bulk delete delegates each conversation as well.
- Existing-field updates retry once after an evicted/replaced active session; if it is no longer active they use the
  DAO affected-row update, avoiding a transient false 404. Folder updates return an affected-row Boolean and the web
  endpoint maps a missing row to 404.

- Focused JVM suite: PASS (`ChatServiceTest` 34, previous runtime gateway/store/scheduler focused suites remain green).
- `:app:compileDebugKotlin :web:compileDebugKotlin --no-configuration-cache`: PASS.
- `git diff --check`: PASS.

## Seventh review lifecycle and exact-message pass

- A stale initialization token from the same still-mapped session no longer leaves Tavern runtime permanently not
  ready. The loader keeps the newer live state and marks it ready; a replaced session or already-ready session is
  still ignored. The regression covers the live mutation version advance directly.
- `messages.updateCurrent` now uses `gateway.update(conversationId, injectedMessageId, text)` whenever the injected
  serializer payload contains a real persisted ID. The old-message regression has a distinct last message and proves
  only the injected ID changes. Uninjected runtime calls still use atomic `updateLatest`.
- Session cleanup waits for an admitted runtime mutation on the shared mutation mutex, then closes that session so a
  late mutation fails before persistence. `ChatService.cleanup` is suspend and closes each retained session before
  removing it; session eviction schedules the same close operation without main-thread `runBlocking`.
- History pin operations now delegate to `ChatService.updatePinnedStatus`. Folder moves use the existing field-mutation
  path for active sessions and an affected-row DAO update for inactive sessions; web folder move returns 404 when no
  row exists.

- Focused JVM suite: PASS (`ChatServiceTest` 33, mutation store 9, runtime gateway 14, group scheduler 14).
- `:app:compileDebugKotlin :web:compileDebugKotlin --no-configuration-cache`: PASS.
- `git diff --check`: PASS.

## Sixth review initialization and field-update pass

- `initializeConversation` now reads the repository and renders outside the session mutation mutex, then installs only
  while the original session is still mapped, runtime readiness is still false, and its initialization token still
  matches the latest live mutation version. Repeated ready calls are no-ops; an evicted/recreated session or a newer
  live mutation rejects the old loaded snapshot.
- The branch selector passes only `nodeId` and `selectIndex` from `ChatMessageBranch` through `ChatList`, `ChatPage`,
  and `ChatVM` to `ChatService.selectMessageNode`; swiping cannot replace messages or annotations from an old node.
- Chat drawer pin/move operations now call `ChatService`. Active conversations mutate the latest live state under the
  shared lock; inactive conversations use new single-row Room updates for title, pin, and assistant/folder fields,
  preserving message nodes. These service methods return false for missing ids and never create a session or insert.
- Web pin/title/move endpoints translate a false field update into `404 Not Found`. The DAO writes return affected-row
  counts, so a concurrent delete is also reported as missing instead of creating a replacement conversation.
- AUTO_MODERATOR snapshots and validation use the same normalized persisted queue order. This prevents a harmless
  assistant-configuration order difference from discarding a valid moderator decision.

- `ChatServiceTest`: PASS (31 tests), including stale initialization-token and differing configuration/queue-order
  moderator regressions.
- `GroupTurnSchedulerTest`: PASS (14 tests).
- `:app:compileDebugKotlin :web:compileDebugKotlin --no-configuration-cache`: PASS.

- Focused runtime JVM suite: PASS, including controller/gateway, mutation-store, readiness, persistence-order,
  serializer-injection, atomic-read, streaming/runtime competition, and conversation-lock competition tests.
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

Third review commit: `b881268a fix: harden Tavern runtime message synchronization`.

Fourth review commit: `fix: serialize ChatService conversation mutations` (this commit).

Sixth review commit: `dd5dd24d fix: guard Tavern conversation initialization`.

Seventh review commit: `fix: close Tavern runtime mutations during cleanup`.

Ninth review commit: `fix: harden Tavern initialization and assistant deletion`.

Tenth review commit: `85cb8517 fix: preserve Tavern initialization candidates`.

Eleventh review commit: `fix: guard Tavern assistant ownership deletion` (current HEAD).

Twelfth review commit: `fix: finalize Tavern assistant deletion atomically` (current HEAD).

## Thirteenth review cancellation and runtime-cleanup pass

- Assistant batch deletion checks `currentCoroutineContext().ensureActive()` immediately before every conversation.
  A cancellation that arrives while the current deletion performs its NonCancellable database commit therefore lets
  that commit finish but prevents the next ID from starting.
- Both the production assistant-deletion gate and its focused helper release their gate with
  `withContext(NonCancellable)` before taking the gate mutex. A cancelled finalizer cannot strand an assistant in the
  deleting set, including when another coroutine temporarily holds that mutex.
- Browser-runtime mutations now enter a production lifecycle admission gate. Cleanup atomically stops new admissions,
  waits for admitted writes to leave their shared session lock, then clears readiness and closes/removes sessions.
  An admitted write retains the ready state through persistence, live-state publication, and host-event emission; a
  later call receives `CONVERSATION_NOT_READY` without recreating a session.
- The runtime persistence adapter now returns a Boolean. `create`, `update`, `updateLatest`, and `delete` reject a
  false result as `CONVERSATION_NOT_READY` before emitting an event.
- New deterministic JVM regressions cover declined persistence without events, cancellation after a first
  NonCancellable commit, cancellation-safe gate release while its mutex is occupied, and admitted-versus-late runtime
  cleanup ordering.
- Focused JVM suite: PASS (`ChatServiceTest` 55, `TavernRuntimeMessageMutationStoreTest` 10,
  `TavernRuntimeConversationReadinessTest` 1, and `TavernRuntimeMessageGatewayTest` 17; 0 failures).
- `:app:compileDebugKotlin :web:compileDebugKotlin --no-configuration-cache`: PASS.
