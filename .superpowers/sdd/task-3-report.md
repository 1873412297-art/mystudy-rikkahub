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

## Second review fix — iframe runtime RPC broker

- Raw-HTML frames now install a broker transport before the shared Tavern runtime, and the bootstrap script is inserted as the first node of a structurally parsed `<head>`. Inline scripts at the beginning of a supplied document therefore see `TavernHelperCompat`, `SillyTavern`, and related APIs immediately.
- Each iframe call receives a high-entropy `requestId`. The trusted parent accepts requests only when `event.source` is the current `contentWindow` of a retained conversation iframe, creates a separate high-entropy native callback, and posts the result only to that originating source and request ID. Released/replaced frames cannot receive a late response.
- The native conversation runtime bridge now requires the same parent-only per-generation token as the action bridge. Raw frames cannot bypass the broker by directly invoking the globally visible JavaScript interface or collide with predictable callbacks in the main document. Valid calls still delegate to the existing permission-gated `TavernRuntimeController`.
- Added visible-Activity instrumentation using the actual sandbox iframe, shared runtime script, native controller, and Java bridge. Its first inline user script checks API availability and awaits `runtime.ping()` through the complete child → parent broker → native → parent → originating child path.

### Second review RED

JVM contract command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationDocumentTest"
```

Observed: 13 tests, 3 expected failures for the missing source-correlated broker, request/response protocol, and structural head-first bootstrap.

Physical-device instrumentation command:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationDocumentInstrumentedTest#rawHtmlEarlyScriptSeesRuntimeAndIframeRpcReturnsToOriginatingFrame"
```

Observed on Huawei MNA-AL00 (`XHD0223523008702`): expected failure, `runtime APIs must exist before the first user script`.

Authenticated-native-channel RED command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationBridgeTest" --tests "me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationDocumentTest"
```

Observed: `Unresolved reference 'TavernConversationRuntimeBridge'`, proving the raw-frame native bypass was not yet closed.

### Second review GREEN

Focused JVM command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationBridgeTest" --tests "me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationDocumentTest" --tests "me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeScriptTest" --tests "me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeScriptApiTest"
```

Result: `BUILD SUCCESSFUL`.

Physical-device visible-Activity command:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationDocumentInstrumentedTest"
```

Result on Huawei MNA-AL00: `BUILD SUCCESSFUL`; all 3 instrumented document tests passed, including early inline API visibility and real iframe `runtime.ping()` response resolution.

Full JVM/build command:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug
```

Result: `BUILD SUCCESSFUL`; 98 JVM test classes / 706 tests / 0 failures / 0 errors / 0 skipped. Debug APK assembly succeeded.

## Adaptive viewport adapter addendum (2026-08-23)

### Scope

- Added a pure `ViewportRepairDecision` policy which repairs only visible fixed-overlay children with an invalid or
  effectively zero computed `max-height` and more than 8 px of actual clipping. Card-provided usable max heights,
  hidden overlays, non-fixed content, and non-clipped panels are preserved.
- Added one generated JavaScript viewport adapter. Its mutation/resize callbacks only schedule a coalesced animation
  frame; style and marker writes are conditional, and the observer attribute filter excludes the adapter marker.
- `MarkdownWebView` embeds the generator output directly. The static `tavern-conversation.html` asset exposes a
  `{{VIEWPORT_ADAPTER}}` build placeholder which `TavernConversationDocument` replaces with the same generator output
  before the parent document renders frames or reports bridge readiness.
- Existing height reporting, document-ready delivery, and link interception remain separate from viewport repair.

### RED

Command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernViewportAdapterTest" --no-daemon
```

Observed expected compilation failure for the absent `ViewportRepairDecision`, `decideViewportRepair(...)`, and
`buildTavernViewportAdapterScript()` contracts.

### Focused GREEN

Command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernViewportAdapterTest" --tests "*MarkdownWebViewHtmlDetectionTest" --tests "*TavernConversationDocumentTest" --no-daemon
```

Result: `BUILD SUCCESSFUL`.

### Commit and staging boundary

- Commit: `57a4f241` (`fix: unify Tavern viewport adaptation`).
- The two new adapter files were staged as complete files. `TavernConversationDocument.kt` was clean before the task,
  so its focused placeholder-replacement change was staged directly. This report is intentionally left uncommitted.
- `MarkdownWebView.kt`, `tavern-conversation.html`, `MarkdownWebViewHtmlDetectionTest.kt`, and
  `TavernConversationDocumentTest.kt` already contained user WIP. Their Task 3 changes are patch-staged against `HEAD`;
  existing remote-media, touch, opening-animation, bridge, permission, and unrelated test hunks remain unstaged.

### Concerns

- Verification is JVM document/contract coverage only. This task does not claim physical-device visual acceptance of a
  third-party card overlay.
