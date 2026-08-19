# Task 3 Report — Single WebView host, native action bridge, and Compose fallback

## Scope delivered

- Added a narrow `TavernConversationBridge` with typed UUID parsing, bounded branch indexes, protocol-whitelisted external links, repeated document-ready delivery, and renderer failure/retry state.
- Added a lifecycle-owned conversation `WebView` host which:
  - loads one cached ST conversation document and applies ordered snapshot patches;
  - injects the existing Tavern runtime and republishes context/current message on every parent or iframe ready event;
  - forwards runtime host events into the parent and sandboxed raw-HTML frames;
  - denies file/content access, blocks top-level navigation, gates HTTP(S) subresources by `allowNetwork`, and only opens http/https/mailto/tel externally;
  - handles first-render timeout, main-frame errors, renderer unresponsiveness, and renderer process exit with a raw-text static fallback, retry, and Compose-switch action;
  - destroys/release-cleans every WebView and both JS interfaces.
- Extended `tavern-conversation.html` with native action delegation, branch controls, long press, safe link handling, full iframe runtime bootstrap/context delivery, bounded iframe height, and repeated ready handshake.
- Integrated resolver-driven presentation in `ChatPage`: eligible SOLO Tavern text/HTML conversations use the ST host while unsupported parts, preview mode, ordinary assistants, and groups retain the existing Compose list.
- Reused the native message operation sheet for copy/select, edit, delete, share, fork, favorite, regenerate, and full-screen preview; branch selection routes through `ChatService.selectMessageNode` via `ChatVM`.
- Full-screen HTML/markdown viewing reuses the same hardened Tavern host instead of the generic WebView page.

## TDD evidence

### RED 1 — bridge contract absent

Command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationBridgeTest" --tests "me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationDocumentTest" --no-daemon
```

Expected failure observed: `Unresolved reference 'TavernConversationBridge'` and `Unresolved reference 'TavernConversationActions'`.

### RED 2 — renderer failure/retry state absent

Command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationBridgeTest" --no-daemon
```

Expected failure observed: `Unresolved reference 'TavernConversationRenderState'` and `Unresolved reference 'TavernConversationRenderStatus'`.

### GREEN — focused bridge/document/snapshot/resolver contracts

Command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationBridgeTest" --tests "me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationDocumentTest" --tests "me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationSnapshotTest" --tests "me.rerere.rikkahub.ui.pages.chat.tavern.TavernPresentationContractsTest" --no-daemon
```

Result: `BUILD SUCCESSFUL`.

## Final verification

Command:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug --no-daemon
```

Result: `BUILD SUCCESSFUL`; 98 JVM test classes / 693 tests / 0 failures / 0 errors / 0 skipped. Debug APK assembly succeeded.

`git diff --check` reported no whitespace errors (only the checkout's existing Git CRLF conversion notices).

## Notes for later tasks

- Task 8 should exercise this host in a visible Activity to validate real WebView ready/reload behavior, raw-frame runtime APIs, action callbacks, and renderer recovery on device.
- Task 5 will replace the existing in-layout HUD invocation; this task intentionally preserved it.

## Review fix pass

The Task 3 review findings were addressed without changing presentation eligibility or the native top/input surfaces:

- Runtime injection now uses the explicit trusted `{{RUNTIME_LIB}}` template marker. A regression builds the document with the actual bundled DOMPurify and Mermaid sources, both of which contain literal `</head>` text, and proves the runtime remains after those vendors and before the structural closing head.
- Renderer ready/failure transitions now require the captured generation; ready is accepted only by the matching `LOADING` state. Late crash/error/timeout callbacks from a released generation cannot fail a retry.
- The full-screen viewer explicitly opts out of `TavernSendHookStore` ownership. A lifecycle binding test proves viewer attach/detach neither replaces nor clears the conversation controller.
- Native actions use a random per-generation token held by the trusted parent document. Every action bridge method authenticates it, parent clicks/context menus additionally require `event.isTrusted`, and external navigation requires a WebView or DOM user gesture. The opaque raw-HTML iframe receives the permission-gated `TavernRuntimeBridge` runtime but no action token or trusted action helper.
- The document base is non-network `about:blank`; interception rejects every HTTP(S) request while `allowNetwork=false`, including the former synthetic host and redirect targets, and always rejects file/content URLs.
- Regeneration from the ST native action sheet now shows the same destructive confirmation for user messages as the Compose message action path.

### Review RED

Command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationBridgeTest" --tests "me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationDocumentTest"
```

Expected failure observed in `:app:compileDebugUnitTestKotlin`: missing authenticated bridge parameters, generation-aware failure API, trusted runtime/action placeholders, navigation/network policy helpers, send-hook lifecycle binding, and regenerate-confirmation policy. This was the pre-fix production surface the tests were written to require.

### Review focused GREEN

Command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationBridgeTest" --tests "me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationDocumentTest"
```

Result: `BUILD SUCCESSFUL`.

### Review full verification

Command:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug
```

Result: `BUILD SUCCESSFUL`; 98 JVM test classes / 703 tests / 0 failures / 0 errors / 0 skipped. Debug APK assembly succeeded. `git diff --check` reported no whitespace errors (only Git's CRLF conversion notices).
