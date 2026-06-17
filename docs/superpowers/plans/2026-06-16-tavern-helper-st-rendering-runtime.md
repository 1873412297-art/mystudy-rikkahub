# Tavern Helper Runtime And SillyTavern Rendering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a controlled compatibility layer for JS-Slash-Runner/Tavern Helper-style scripts and SillyTavern-style chat bubble rendering in the Android app without granting unrestricted native access to arbitrary role-card JavaScript.

**Architecture:** Implement this as a compatibility runtime, not a wholesale browser-port of SillyTavern. Kotlin owns persistent state, permissions, message data, world/lorebook data, and app navigation; WebView owns isolated rendering and script execution; a narrow JSON-RPC bridge connects them. Existing status rendering remains supported and is gradually routed through the same message segment pipeline.

**Tech Stack:** Android/Kotlin, Jetpack Compose, WebView, JavaScript bridge via `addJavascriptInterface`, Kotlin serialization, JUnit, existing Markdown/Status transformers.

## Final Status

Completed on 2026-06-16.

- Stable DOM rendering is enabled and covered by tests.
- Tavern Helper compatibility runtime is wired to the real world/lorebook repository.
- Runtime permissions are persisted and exposed in the native settings UI.
- Emulator smoke for `runtime.ping()` passed on `RikkaHub(AVD) - 15`.

The unchecked boxes below are the original execution trace for this plan and are preserved for reference. They do not indicate remaining blocking work for this branch.

---

## Non-Negotiable Constraints

- Do not expose raw Android APIs, file access, cookies, app settings, provider API keys, or full database access to user/role-card JavaScript.
- Do not implement Tavern Helper as a privileged SillyTavern extension. Implement a compatible subset with explicit permission gates.
- Do not replace existing Markdown rendering in one large diff. Keep the current renderer as fallback until parity tests pass.
- Do not break current support for `<maintext>`, `<Status_block>`, bare JSON Patch, `<UpdateVariable>`, and `<StatusPlaceHolderImpl/>`.
- Do not commit local `app/libs/` workaround until deciding whether the sqlite AAR should be vendored or the dependency should be restored.

## External Behavior To Match

JS-Slash-Runner/Tavern Helper reference behavior:

- The extension executes external JavaScript in an iframe-isolated context.
- It provides script helpers for variables, slash commands, world/lorebook operations, events, chat/message access, and UI rendering.
- It supports complete HTML/CSS/JS interfaces inside chat-like surfaces.

SillyTavern official rendering reference behavior:

- Chat rendering is DOM-oriented: message container, message text, extras/actions, incremental streaming updates, extension hooks.
- Extensions can hook into events and manipulate UI/chat state.
- Message display settings influence loaded messages, streaming FPS, and display behavior.

Local implementation target:

- Provide a safe compatibility surface with the same broad concepts and a stable API shape.
- Keep unsupported helper calls explicit: return `{ ok: false, error: { code: "UNSUPPORTED" } }` rather than silently doing nothing.

## Implementation Strategy In One Page

This work should be treated as three nested layers:

1. **Message segmentation layer**
   - Input: raw assistant text, preset text, greeting text, old saved message text.
   - Output: ordered `RichTextSegment` list.
   - Responsibility: identify Markdown, status blocks, JSON Patch payloads, diagnostics, and HTML apps.
   - Safety level: pure Kotlin, no script execution.

2. **SillyTavern-style display layer**
   - Input: ordered `RichTextSegment` list.
   - Output: stable chat-bubble DOM inside a WebView shell, or native Compose fallback.
   - Responsibility: match the broad DOM shape SillyTavern cards expect: message root, text container, segment nodes, stable classes/data attributes.
   - Safety level: sanitized content for Markdown/status text, no arbitrary helper access unless runtime is enabled.

3. **Tavern Helper compatibility runtime**
   - Input: JavaScript calls from card HTML through `window.TavernHelperCompat`.
   - Output: JSON-RPC responses from Kotlin.
   - Responsibility: provide variables, slash commands, events, world/lorebook access, and message operations as a controlled subset.
   - Safety level: permission-gated. Write operations default denied until explicitly enabled.

The important separation is this:

- Rendering a status block must not require Tavern Helper runtime permissions.
- Running arbitrary role-card JavaScript must not be needed for ordinary Markdown/status display.
- The runtime bridge must not become a backdoor to the database or Android APIs.

## Detailed Data Flow

### Flow A: Normal Assistant Message

```text
AI response text
  -> OutputMessageTransformer.visualTransform()
  -> StatusPlaceholderTransformer extracts UpdateVariable/JSONPatch when present
  -> ChatService persists UIMessage / UIMessagePart
  -> Chat UI calls MarkdownBlock(content)
  -> parseRichTextSegments(content)
  -> MarkdownBlock routes each segment
      -> MARKDOWN: native Compose Markdown renderer
      -> STATUS_BLOCK: mark.html WebView renderer
      -> JSON_PATCH: status/diagnostic WebView renderer
      -> HTML_DOCUMENT: raw HTML WebView renderer
```

### Flow B: Preset/Greeting/Old Conversation Message

```text
Assistant preset or greeting or saved historical message
  -> ChatService load/init path
  -> visualTransforms(outputTransformers)
  -> StatusPlaceholderTransformer applies variable patches and placeholder replacement
  -> UIMessage contains already transformed StatusPlaceholder parts where possible
  -> MarkdownBlock still acts as fallback for raw status/preset content
```

This double coverage is intentional. Transformer coverage handles structured message parts; `MarkdownBlock` coverage protects against raw messages that bypassed transformers or were saved before the feature existed.

### Flow C: HTML App With Tavern Helper Calls

```text
Raw/fenced HTML document segment
  -> MarkdownWebView(isRawHtml = true)
  -> buildSandboxHostHtml()
  -> Inject buildTavernRuntimeScript()
  -> JS calls TavernHelperCompat.variables.get("x")
  -> window.TavernRuntimeBridge.call(JSON.stringify(request), callbackName)
  -> TavernRuntimeBridge.call()
  -> TavernRuntimeController.dispatch()
  -> Kotlin returns TavernRuntimeResponse JSON
  -> WebView evaluateJavascript() invokes callback
  -> JS Promise resolves/rejects
```

The bridge is request/response only. Do not expose methods like `eval`, `readFile`, `queryDatabase`, `openSettings`, or `sendProviderRequest`.

## Compatibility API Surface

The first implementation should support this explicit subset.

### Runtime

| JavaScript API | Kotlin method | Default permission | Expected result |
| --- | --- | --- | --- |
| `TavernHelperCompat.runtime.ping()` | `runtime.ping` | allowed | resolves `"pong"` |

### Variables

| JavaScript API | Kotlin method | Default permission | Expected result |
| --- | --- | --- | --- |
| `variables.get(key, scope)` | `variables.get` | allowed | resolves value or `null` |
| `variables.set(key, value, scope)` | `variables.set` | allowed for chat scope | resolves `true` |
| `variables.list(scope)` | `variables.list` | allowed | resolves object |

Supported scopes for the first pass:

- `chat`: per rendered WebView/controller instance until persisted binding is added.
- `global`: per rendered WebView/controller instance until settings/datastore binding is added.

Persistence upgrade path:

- `chat` should bind to `StatusVariableStore` or a conversation-scoped runtime store.
- `global` should bind to settings/datastore only after adding a permission toggle and migration plan.

### Slash Commands

| JavaScript API | Kotlin method | Default permission | Expected result |
| --- | --- | --- | --- |
| `slash.run("/th help")` | `slash.run` | allowed | returns supported command text |
| `slash.run("/th ping")` | `slash.run` | allowed | returns `"pong"` |
| `slash.run("/th vars")` | `slash.run` | allowed | returns chat variables |
| any unknown command | `slash.run` | allowed | rejects with `UNSUPPORTED_SLASH_COMMAND` |

Do not implement commands that mutate chat history, submit prompts, call model APIs, or alter assistant settings in the first pass.

### Events

| JavaScript API | Kotlin method | Default permission | Expected result |
| --- | --- | --- | --- |
| `events.emit(name, payload)` | `events.emit` | allowed | records event and resolves name |
| `events.on(name, handler)` | browser-side only initially | allowed | listens to DOM event `th:<name>` |

First-pass events are local to the WebView. A later improvement can broadcast app lifecycle events into WebViews:

- `message_rendered`
- `message_updated`
- `stream_started`
- `stream_stopped`
- `variables_changed`

### World/Lorebook

| JavaScript API | Kotlin method | Default permission | Expected result |
| --- | --- | --- | --- |
| `world.getEntries()` | `world.getEntries` | allowed read | resolves array |
| `world.upsertEntry(entry)` | `world.upsertEntry` | denied by default | rejects `PERMISSION_DENIED` unless enabled |
| `world.deleteEntry(id)` | `world.deleteEntry` | denied by default | rejects `PERMISSION_DENIED` unless enabled |

The first implementation may use an in-memory adapter to prove API shape. Binding to the app's real lorebook/prompt-injection model must be a separate commit with tests.

### Messages

| JavaScript API | Kotlin method | Default permission | Expected result |
| --- | --- | --- | --- |
| `messages.getCurrent()` | `messages.getCurrent` | allowed read | returns current message metadata when available |
| `messages.updateCurrent(patch)` | `messages.updateCurrent` | denied by default | rejects `PERMISSION_DENIED` unless enabled |

Message mutation is risky because this app uses `Conversation -> MessageNode -> UIMessage` branching. Do not implement writes until the code has an explicit node id, selected branch index, and transaction boundary.

## Security Model

### What JavaScript Can Do

- Render HTML/CSS/JS inside its own WebView document.
- Ask Kotlin for approved runtime actions through JSON-RPC.
- Open safe external links through existing link whitelist.
- Report height to the Compose container.

### What JavaScript Must Not Do

- Read local files.
- Read provider API keys.
- Read cookies or authenticated browser state.
- Navigate the parent app WebView to arbitrary schemes.
- Call arbitrary Android methods.
- Edit world/lorebook entries without permission.
- Edit messages without permission.
- Trigger model calls or send prompts without a future explicit user-facing permission.

### Bridge Input Limits

Keep these limits in the implementation:

- Request JSON maximum: `256_000` characters.
- Callback name maximum: `128` characters.
- Response JSON should avoid embedding large binary payloads.
- URL opening maximum remains `4096` characters.
- Height reporting range remains `1..200_000`.

### JSON-RPC Error Codes

Use stable error codes because card scripts may branch on them:

| Code | Meaning |
| --- | --- |
| `BAD_REQUEST` | malformed JSON, missing required params, wrong type |
| `UNSUPPORTED` | method not implemented |
| `UNSUPPORTED_SLASH_COMMAND` | slash command not implemented |
| `PERMISSION_DENIED` | method exists but permission is disabled |
| `BRIDGE_ERROR` | browser-side bridge call failed |
| `CALLBACK_ERROR` | browser-side callback failed |
| `INTERNAL_ERROR` | unexpected Kotlin exception |

Do not leak exception stack traces to JavaScript. Log them in Android logs and return a short message.

## SillyTavern DOM Compatibility Target

The goal is not pixel-perfect SillyTavern UI. The goal is stable DOM affordances that common card renderers can target.

### Required DOM Shape

The WebView HTML shell should create this shape:

```html
<div id="chat">
  <div class="mes assistant" data-message-id="m1" data-rikkahub-role="assistant">
    <div class="mes_block">
      <div class="mes_text">
        <div class="mes_segment" data-kind="MARKDOWN" data-segment-id="segment-0">...</div>
        <div class="mes_segment" data-kind="STATUS_BLOCK" data-segment-id="segment-1">...</div>
      </div>
    </div>
  </div>
</div>
```

Use these classes/data attributes because many ST snippets assume them:

- `.mes`
- `.mes_text`
- `.mes_block`
- `.assistant`
- `.user`
- `data-message-id`
- `data-rikkahub-role`
- `data-kind`
- `data-segment-id`

### Segment Rendering Rules

- `MARKDOWN`
  - Render through markdown-it in the WebView shell when using stable DOM mode.
  - Sanitize result with DOMPurify if bundled in `mark.html`/asset.
  - If DOMPurify is not available in `st-message.html`, escape text and render line breaks until DOMPurify is added.

- `STATUS_BLOCK`
  - Preserve the original content.
  - Render as a bordered collapsible or card-like block.
  - Do not execute scripts inside status text.

- `JSON_PATCH`
  - If it has already been transformed, do not show raw JSON by default.
  - If raw JSON reaches renderer, show compact diagnostics/status card.

- `JSON_PATCH_DIAGNOSTIC`
  - Render visibly as warning text.
  - Include the first safe excerpt only; do not dump huge malformed payloads.

- `HTML_DOCUMENT`
  - Do not mount inside the stable DOM shell.
  - Route to the raw HTML WebView path because it may need its own document, styles, scripts, and measurement.

## Runtime Object Naming

Expose these aliases:

```javascript
window.TavernHelperCompat
window.TavernHelper
window.TH
```

The canonical object is `window.TavernHelperCompat`. The other two are convenience aliases. If a card defines its own `window.TavernHelper`, do not overwrite it:

```javascript
window.TavernHelper = window.TavernHelper || api;
window.TH = window.TH || api;
```

## Persistence Decisions

Implement in this order:

1. In-memory runtime controller stores.
2. Conversation-scoped runtime variable persistence.
3. StatusVariableStore bridge for variables that overlap with JSON Patch status.
4. Real lorebook/world repository binding.
5. Message mutation APIs.

Reasoning:

- In-memory first proves the WebView/Kotlin bridge and JS promise layer.
- Conversation-scoped next prevents surprising cross-chat leakage.
- StatusVariableStore bridge aligns existing `<UpdateVariable>` behavior with Tavern helper variables.
- Lorebook/message writes are highest risk and need settings/permission gates.

## Current Local Context

Existing relevant files:

- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichTextRenderPolicy.kt`
  - Owns rich text normalization, segment detection, status block detection, JSON Patch detection, and HTML document detection.
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt`
  - Owns `MarkdownBlock` Compose rendering and routes segments to native Markdown or WebView rendering.
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt`
  - Owns WebView rendering and JS bridge for height/link callbacks.
- `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/StatusPlaceholderTransformer.kt`
  - Owns SillyTavern-style variable update extraction and status placeholder rendering.
- `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
  - Owns conversation loading, preset/greeting transforms, and status variable store interaction.
- `app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt`
  - Owns persisted conversation/message node structure.
- `ai/src/main/java/me/rerere/ai/ui/Message.kt`
  - Owns `UIMessage` and `UIMessagePart`.

Current tests to keep green:

- `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/MarkdownStatusBlockTest.kt`
- `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebViewHtmlDetectionTest.kt`
- `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/RichTextRenderPolicyTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/ai/transformers/StatusPlaceholderTransformerTest.kt`

Recommended verification command:

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :app:testDebugUnitTest --tests me.rerere.rikkahub.ui.components.richtext.MarkdownStatusBlockTest --tests me.rerere.rikkahub.ui.components.richtext.MarkdownWebViewHtmlDetectionTest --tests me.rerere.rikkahub.ui.components.richtext.RichTextRenderPolicyTest --tests me.rerere.rikkahub.data.ai.transformers.StatusPlaceholderTransformerTest -x :web:buildWebUi --offline
```

Expected result:

```text
BUILD SUCCESSFUL
```

---

## Detailed Architecture Notes

### Rendering Pipeline After This Plan

The final rendering flow should be:

```text
AI/user message text
  -> Output transformers
  -> StatusPlaceholderTransformer
  -> RichTextRenderPolicy.normalizeRichTextContent()
  -> RichTextRenderPolicy.parseRichTextSegments()
  -> RichTextRenderPolicy.chooseRendererMode()
  -> MarkdownBlock
      -> native Compose Markdown for plain Markdown
      -> current WebView segment renderer for raw HTML apps
      -> Stable DOM WebView renderer for mixed SillyTavern/Tavern segments
          -> st-message.html
          -> TavernRuntimeScript injection
          -> TavernRuntimeBridge JSON-RPC
          -> TavernRuntimeController
          -> Kotlin state stores/repositories
```

This keeps responsibilities separated:

- Transformers mutate message content and persistent status variables.
- Rich text policy classifies content only; it must not mutate chat state.
- WebView renders and runs isolated JavaScript only.
- Runtime controller owns all script-visible operations and permission checks.
- Repositories/stores are the only place where runtime calls can persist changes.

### Runtime Trust Boundary

Treat every script from role cards, model output, imported presets, and user-pasted HTML as untrusted.

Allowed data flow:

```text
Untrusted JS -> TavernRuntimeBridge.call(json, callbackName)
  -> decode TavernRuntimeRequest
  -> TavernRuntimeController.dispatch()
  -> permission check
  -> safe store/repository operation
  -> TavernRuntimeResponse JSON
  -> callback in WebView
```

Disallowed data flow:

```text
Untrusted JS -> direct Android API
Untrusted JS -> arbitrary file path
Untrusted JS -> API keys/provider config
Untrusted JS -> unrestricted database query
Untrusted JS -> unrestricted network request through native bridge
Untrusted JS -> silently editing historical messages
```

### Script API Naming

Expose three aliases for compatibility:

```javascript
window.TavernHelperCompat
window.TavernHelper
window.TH
```

All aliases must point to the same object.

The V1 object shape must be:

```javascript
{
  runtime: {
    ping: function(): Promise<string>
  },
  variables: {
    get: function(key: string, scope?: "chat" | "global"): Promise<any>,
    set: function(key: string, value: any, scope?: "chat" | "global"): Promise<boolean>,
    list: function(scope?: "chat" | "global"): Promise<object>
  },
  slash: {
    run: function(command: string, args?: object): Promise<any>
  },
  events: {
    on: function(name: string, handler: function): Promise<boolean>,
    emit: function(name: string, payload?: any): Promise<string>
  },
  world: {
    getEntries: function(): Promise<Array<object>>,
    upsertEntry: function(entry: object): Promise<string>,
    deleteEntry: function(id: string): Promise<boolean>
  },
  messages: {
    getCurrent: function(): Promise<object>,
    updateCurrent: function(patch: Array<object>): Promise<boolean>
  }
}
```

### JSON-RPC Request Contract

Every runtime call from JavaScript to Kotlin must use this JSON shape:

```json
{
  "id": "1",
  "method": "variables.get",
  "params": {
    "scope": "chat",
    "key": "favor"
  }
}
```

Every success response must use:

```json
{
  "id": "1",
  "ok": true,
  "result": "value"
}
```

Every failure response must use:

```json
{
  "id": "1",
  "ok": false,
  "error": {
    "code": "UNSUPPORTED",
    "message": "Runtime method is not available"
  }
}
```

Required error codes:

```text
BAD_REQUEST
UNSUPPORTED
UNSUPPORTED_SLASH_COMMAND
PERMISSION_DENIED
RUNTIME_ERROR
```

### Supported Runtime API V1 Matrix

| Method | Permission | Persistence | Expected Behavior |
| --- | --- | --- | --- |
| `runtime.ping` | none | none | returns `"pong"` |
| `variables.get` | scripts enabled | chat/global variable store | returns value or `null` |
| `variables.set` | scripts enabled | chat/global variable store | stores JSON value, returns `true` |
| `variables.list` | scripts enabled | chat/global variable store | returns object of visible variables |
| `slash.run` | scripts enabled | depends on command | runs built-in `/th` commands only |
| `events.emit` | scripts enabled | event history only | records event and notifies DOM listeners |
| `world.getEntries` | scripts enabled | lore/world repository | returns visible entries |
| `world.upsertEntry` | `allowWorldWrite` | lore/world repository | inserts or updates one entry |
| `world.deleteEntry` | `allowWorldWrite` | lore/world repository | deletes one entry by id |
| `messages.getCurrent` | scripts enabled | current render context | returns current message metadata |
| `messages.updateCurrent` | `allowMessageWrite` | current message only | applies JSON Patch to current message text |

### Permission Defaults

Initial safe defaults:

```kotlin
TavernRuntimePermissionState(
    allowScripts = false,
    allowWorldWrite = false,
    allowMessageWrite = false,
    allowNetwork = false,
)
```

During development-only manual smoke, `allowScripts` can be temporarily enabled in code for the debug build path. Before enabling by default, Task 7.2 must add settings UI and persistence.

### Runtime State Lifetimes

State must use these lifetimes:

```text
chat variables:
  per conversation
  cleared only when conversation is deleted

global variables:
  per assistant profile
  not shared across all assistants unless explicitly designed

event history:
  per WebView/runtime instance
  max 100 events
  non-persistent

world entries:
  from actual lorebook/prompt-injection repository after Task 4.2
  in-memory only before Task 4.2

current message metadata:
  per rendered message
  read-only until allowMessageWrite exists
```

### WebView Safety Settings To Keep

Do not loosen these settings in `MarkdownWebView.kt`:

```kotlin
mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
allowFileAccess = false
allowContentAccess = false
@Suppress("DEPRECATION")
allowFileAccessFromFileURLs = false
@Suppress("DEPRECATION")
allowUniversalAccessFromFileURLs = false
```

Do not add these capabilities in V1:

```kotlin
settings.allowFileAccess = true
settings.allowContentAccess = true
settings.javaScriptCanOpenWindowsAutomatically = true
```

### Callback Name Sanitization

The bridge callback name must be sanitized before calling `evaluateJavascript`.

Allowed callback characters:

```text
A-Z
a-z
0-9
_
.
$
```

If sanitization changes the callback name to blank, return without evaluating JavaScript.

Expected Kotlin guard:

```kotlin
val escapedCallback = callbackName.replace(Regex("[^A-Za-z0-9_.$]"), "")
if (escapedCallback.isBlank()) return@TavernRuntimeBridge
```

If the exact receiver scope makes `return@TavernRuntimeBridge` invalid, use a local helper function:

```kotlin
private fun safeCallbackName(callbackName: String): String? {
    val safe = callbackName.replace(Regex("[^A-Za-z0-9_.$]"), "")
    return safe.takeIf { it.isNotBlank() }
}
```

### Message DOM Structure Target

The stable DOM renderer should emit this conceptual structure:

```html
<div class="mes assistant" data-message-id="m1" data-role="assistant">
  <div class="mes_header">
    <span class="ch_name">Assistant</span>
  </div>
  <div class="mes_text">
    <div class="mes_segment" data-kind="MARKDOWN" data-segment-id="segment-0"></div>
    <div class="mes_segment" data-kind="STATUS_BLOCK" data-segment-id="segment-1"></div>
  </div>
  <div class="mes_actions"></div>
</div>
```

V1 does not need to recreate every SillyTavern CSS class. It must provide stable class names and data attributes so role cards/scripts can target predictable nodes.

### Streaming Update Requirement

Do not implement fine-grained streaming DOM patching first. V1 may reload the WebView per message update if the current code path already does so.

After V1 is stable, add a V2 method:

```javascript
window.__RIKKAHUB_ST_UPDATE_MESSAGE__(messageJson)
```

Expected future behavior:

```text
Same message id:
  update existing segment nodes
  preserve scroll where possible

Different message id:
  replace full DOM
```

This is a future optimization, not a blocker for V1.

### Compatibility Claim Rules

After this plan, describe support as:

```text
Rikkahub provides a safe Tavern Helper compatibility runtime for common role-card render scripts.
```

Do not describe support as:

```text
Full SillyTavern runtime
Full JS-Slash-Runner port
Complete Tavern Helper API parity
```

Those claims require full API parity tests against upstream behavior.

---

## File Structure To Add

Create runtime package:

- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeModels.kt`
  - JSON-RPC request/response models, permission models, runtime events.
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeBridge.kt`
  - Android `@JavascriptInterface` bridge receiving JSON-RPC calls from WebView.
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeController.kt`
  - Kotlin dispatcher for runtime methods: variable, event, slash, world, message, render.
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeScript.kt`
  - Injected JavaScript that exposes `window.TavernHelperCompat` and safe aliases.
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimePermissionStore.kt`
  - Per-assistant/per-conversation permission decisions.
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeEventBus.kt`
  - In-process event bus for WebView/runtime/message lifecycle events.
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeWorldStore.kt`
  - Safe read/write adapter for lorebook/world info when local data models are identified.

Create official-style rendering package:

- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/st/StableMessageHtmlRenderer.kt`
  - Produces a SillyTavern-like HTML shell for mixed chat segments.
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/st/StableMessageDomModels.kt`
  - Message DOM model: bubble, segment, role, streaming state, actions.
- `app/src/main/assets/html/st-message.html`
  - Browser-side renderer shell: DOMPurify, markdown-it, message container, segment mounting, runtime script loading.

Create tests:

- `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeModelsTest.kt`
- `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeControllerTest.kt`
- `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeScriptTest.kt`
- `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/st/StableMessageHtmlRendererTest.kt`
- `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/st/StableMessageDomModelsTest.kt`

---

## Phase 0: Create Safety Baseline

### Task 0.0: Start-Of-Day Workspace And Build Check

**Files:**

- Read-only: repository state

- [ ] **Step 1: Confirm branch and dirty state**

Run:

```powershell
& 'C:\Program Files\Git\cmd\git.exe' branch --show-current
& 'C:\Program Files\Git\cmd\git.exe' status --short
```

Expected:

```text
Branch is private-main or an explicitly created feature branch.
Existing uncommitted rendering changes are visible.
No unrelated user edits are reverted.
```

- [ ] **Step 2: Create a feature branch if current branch should stay stable**

Run only if the user wants isolation:

```powershell
& 'C:\Program Files\Git\cmd\git.exe' checkout -b feature/tavern-helper-runtime
```

Expected:

```text
Switched to a new branch 'feature/tavern-helper-runtime'
```

- [ ] **Step 3: Confirm Java and Android SDK**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
java -version
Get-Content local.properties
```

Expected:

```text
Java version is 17.x.
local.properties contains sdk.dir=C:/Users/18734/AppData/Local/Android/Sdk
```

- [ ] **Step 4: Run current targeted tests before new work**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests me.rerere.rikkahub.ui.components.richtext.MarkdownStatusBlockTest --tests me.rerere.rikkahub.ui.components.richtext.MarkdownWebViewHtmlDetectionTest --tests me.rerere.rikkahub.ui.components.richtext.RichTextRenderPolicyTest --tests me.rerere.rikkahub.data.ai.transformers.StatusPlaceholderTransformerTest -x :web:buildWebUi --offline
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: If the build fails because sqlite dependency is missing**

Check:

```powershell
Get-ChildItem app\libs
Select-String -Path app\build.gradle.kts -Pattern "sqlite"
```

Expected known local workaround:

```text
app/libs contains sqlite-android--SNAPSHOT.aar
app/build.gradle.kts uses fileTree libs and does not require online JitPack for sqlite
```

If this differs, stop and decide whether to vendor the AAR or restore dependency resolution before runtime work.

### Task 0.1: Snapshot Current Rendering Behavior

**Files:**

- Modify: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/RichTextRenderPolicyTest.kt`

- [ ] **Step 1: Add regression test for mixed story/status/update message**

Add this test:

```kotlin
@Test
fun `mixed story updatevariable placeholder and status block keep visible story segments`() {
    val content = """
        <maintext>
        正文第一段
        </maintext>
        <UpdateVariable>
        <JSONPatch>
        [{ "op": "replace", "path": "/世界/当前时间", "value": "子时" }]
        </JSONPatch>
        </UpdateVariable>
        <StatusPlaceHolderImpl/>
        <Status_block>状态文本</Status_block>
        正文第二段
    """.trimIndent()

    val segments = parseRichTextSegments(content)

    assertEquals(
        listOf(
            RichTextSegment.Kind.MARKDOWN,
            RichTextSegment.Kind.JSON_PATCH,
            RichTextSegment.Kind.MARKDOWN,
            RichTextSegment.Kind.STATUS_BLOCK,
            RichTextSegment.Kind.MARKDOWN,
        ),
        segments.map { it.kind }
    )
    assertTrue(segments[0].raw.contains("正文第一段"))
    assertTrue(segments[2].raw.contains("StatusPlaceHolderImpl"))
    assertTrue(segments[4].raw.contains("正文第二段"))
}
```

- [ ] **Step 2: Run test and record result**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests me.rerere.rikkahub.ui.components.richtext.RichTextRenderPolicyTest -x :web:buildWebUi --offline
```

Expected before changes:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 3: Commit baseline test**

```powershell
& 'C:\Program Files\Git\cmd\git.exe' add app/src/test/java/me/rerere/rikkahub/ui/components/richtext/RichTextRenderPolicyTest.kt
& 'C:\Program Files\Git\cmd\git.exe' commit -m "test: snapshot mixed tavern rendering segments"
```

### Task 0.2: Add API Compatibility Fixture Files

**Files:**

- Create: `app/src/test/resources/tavern-fixtures/runtime-ping.html`
- Create: `app/src/test/resources/tavern-fixtures/runtime-variables.html`
- Create: `app/src/test/resources/tavern-fixtures/runtime-world-denied.html`

- [ ] **Step 1: Create runtime ping fixture**

Use `apply_patch` to create:

```html
<!DOCTYPE html>
<html>
<body>
<div id="out">loading</div>
<script>
TavernHelperCompat.runtime.ping().then(function(value) {
  document.getElementById('out').textContent = value;
}).catch(function(error) {
  document.getElementById('out').textContent = error.code || 'ERROR';
});
</script>
</body>
</html>
```

- [ ] **Step 2: Create runtime variables fixture**

Use `apply_patch` to create:

```html
<!DOCTYPE html>
<html>
<body>
<div id="out">loading</div>
<script>
TavernHelperCompat.variables.set('favor', '12').then(function() {
  return TavernHelperCompat.variables.get('favor');
}).then(function(value) {
  document.getElementById('out').textContent = value;
}).catch(function(error) {
  document.getElementById('out').textContent = error.code || 'ERROR';
});
</script>
</body>
</html>
```

- [ ] **Step 3: Create runtime world denied fixture**

Use `apply_patch` to create:

```html
<!DOCTYPE html>
<html>
<body>
<div id="out">loading</div>
<script>
TavernHelperCompat.world.upsertEntry({
  id: 'entry-1',
  key: '顾雪鸢',
  content: '云山宗宗主'
}).then(function(value) {
  document.getElementById('out').textContent = 'unexpected:' + value;
}).catch(function(error) {
  document.getElementById('out').textContent = error.code || 'ERROR';
});
</script>
</body>
</html>
```

- [ ] **Step 4: Commit fixtures**

```powershell
& 'C:\Program Files\Git\cmd\git.exe' add app/src/test/resources/tavern-fixtures
& 'C:\Program Files\Git\cmd\git.exe' commit -m "test: add tavern runtime html fixtures"
```

---

## Phase 1: JSON-RPC Runtime Bridge

### Task 1.1: Define Runtime JSON Models

**Files:**

- Create: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeModels.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeModelsTest.kt`

- [ ] **Step 1: Write failing model serialization tests**

Create the test file:

```kotlin
package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernRuntimeModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `request decodes method params and id`() {
        val request = json.decodeFromString<TavernRuntimeRequest>(
            """{"id":"1","method":"variables.get","params":{"scope":"chat","key":"x"}}"""
        )

        assertEquals("1", request.id)
        assertEquals("variables.get", request.method)
        assertEquals("chat", request.params["scope"]?.jsonPrimitive?.content)
        assertEquals("x", request.params["key"]?.jsonPrimitive?.content)
    }

    @Test
    fun `success response encodes ok true result and id`() {
        val encoded = json.encodeToString(
            TavernRuntimeResponse.serializer(),
            TavernRuntimeResponse.success("7", JsonPrimitive("done"))
        )

        assertTrue(encoded.contains(""""ok":true"""))
        assertTrue(encoded.contains(""""id":"7""""))
        assertTrue(encoded.contains(""""result":"done""""))
    }

    @Test
    fun `error response encodes code and message`() {
        val encoded = json.encodeToString(
            TavernRuntimeResponse.serializer(),
            TavernRuntimeResponse.error("8", "UNSUPPORTED", "Method is not available")
        )

        assertFalse(encoded.contains(""""ok":true"""))
        assertTrue(encoded.contains(""""ok":false"""))
        assertTrue(encoded.contains(""""code":"UNSUPPORTED""""))
        assertTrue(encoded.contains(""""message":"Method is not available""""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeModelsTest -x :web:buildWebUi --offline
```

Expected:

```text
Unresolved reference 'TavernRuntimeRequest'
```

- [ ] **Step 3: Implement models**

Create:

```kotlin
package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
internal data class TavernRuntimeRequest(
    val id: String,
    val method: String,
    val params: JsonObject = JsonObject(emptyMap()),
)

@Serializable
internal data class TavernRuntimeError(
    val code: String,
    val message: String,
)

@Serializable
internal data class TavernRuntimeResponse(
    val id: String,
    val ok: Boolean,
    val result: JsonElement? = null,
    val error: TavernRuntimeError? = null,
) {
    companion object {
        fun success(id: String, result: JsonElement): TavernRuntimeResponse {
            return TavernRuntimeResponse(id = id, ok = true, result = result)
        }

        fun error(id: String, code: String, message: String): TavernRuntimeResponse {
            return TavernRuntimeResponse(
                id = id,
                ok = false,
                error = TavernRuntimeError(code = code, message = message),
            )
        }
    }
}

@Serializable
internal data class TavernRuntimePermissionState(
    val allowScripts: Boolean = false,
    val allowWorldWrite: Boolean = false,
    val allowMessageWrite: Boolean = false,
    val allowNetwork: Boolean = false,
)

internal fun JsonObject.getString(name: String): String? {
    return (this[name] as? JsonPrimitive)?.content
}

internal fun emptyJsonObject(): JsonObject = buildJsonObject {}
```

- [ ] **Step 4: Run test to verify it passes**

Run the same test command.

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: Commit**

```powershell
& 'C:\Program Files\Git\cmd\git.exe' add app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeModels.kt app/src/test/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeModelsTest.kt
& 'C:\Program Files\Git\cmd\git.exe' commit -m "feat: add tavern runtime rpc models"
```

### Task 1.2: Add Runtime Controller With Unsupported Defaults

**Files:**

- Create: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeController.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeControllerTest.kt`

- [ ] **Step 1: Write failing controller tests**

Create:

```kotlin
package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernRuntimeControllerTest {
    private val controller = TavernRuntimeController()

    @Test
    fun `ping returns pong`() = runTest {
        val response = controller.dispatch(
            TavernRuntimeRequest(id = "1", method = "runtime.ping")
        )

        assertTrue(response.ok)
        assertEquals("pong", response.result!!.jsonPrimitive.content)
    }

    @Test
    fun `unknown method returns unsupported error`() = runTest {
        val response = controller.dispatch(
            TavernRuntimeRequest(id = "2", method = "unknown.method")
        )

        assertFalse(response.ok)
        assertEquals("UNSUPPORTED", response.error!!.code)
    }

    @Test
    fun `variables set then get returns value`() = runTest {
        val setResponse = controller.dispatch(
            TavernRuntimeRequest(
                id = "3",
                method = "variables.set",
                params = JsonObject(
                    mapOf(
                        "scope" to JsonPrimitive("chat"),
                        "key" to JsonPrimitive("favor"),
                        "value" to JsonPrimitive("1"),
                    )
                ),
            )
        )
        val getResponse = controller.dispatch(
            TavernRuntimeRequest(
                id = "4",
                method = "variables.get",
                params = JsonObject(
                    mapOf(
                        "scope" to JsonPrimitive("chat"),
                        "key" to JsonPrimitive("favor"),
                    )
                ),
            )
        )

        assertTrue(setResponse.ok)
        assertEquals("1", getResponse.result!!.jsonPrimitive.content)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeControllerTest -x :web:buildWebUi --offline
```

Expected:

```text
Unresolved reference 'TavernRuntimeController'
```

- [ ] **Step 3: Implement minimal controller**

Create:

```kotlin
package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class TavernRuntimeController {
    private val chatVariables = linkedMapOf<String, JsonElement>()
    private val globalVariables = linkedMapOf<String, JsonElement>()

    suspend fun dispatch(request: TavernRuntimeRequest): TavernRuntimeResponse {
        return when (request.method) {
            "runtime.ping" -> TavernRuntimeResponse.success(request.id, JsonPrimitive("pong"))
            "variables.get" -> getVariable(request)
            "variables.set" -> setVariable(request)
            "variables.list" -> listVariables(request)
            else -> TavernRuntimeResponse.error(
                id = request.id,
                code = "UNSUPPORTED",
                message = "Runtime method '${request.method}' is not available in this compatibility layer",
            )
        }
    }

    private fun variablesFor(scope: String): MutableMap<String, JsonElement> {
        return if (scope == "global") globalVariables else chatVariables
    }

    private fun getVariable(request: TavernRuntimeRequest): TavernRuntimeResponse {
        val scope = request.params.getString("scope") ?: "chat"
        val key = request.params.getString("key") ?: return TavernRuntimeResponse.error(
            request.id,
            "BAD_REQUEST",
            "variables.get requires params.key",
        )
        return TavernRuntimeResponse.success(request.id, variablesFor(scope)[key] ?: JsonNull)
    }

    private fun setVariable(request: TavernRuntimeRequest): TavernRuntimeResponse {
        val scope = request.params.getString("scope") ?: "chat"
        val key = request.params.getString("key") ?: return TavernRuntimeResponse.error(
            request.id,
            "BAD_REQUEST",
            "variables.set requires params.key",
        )
        val value = request.params["value"] ?: JsonNull
        variablesFor(scope)[key] = value
        return TavernRuntimeResponse.success(request.id, JsonPrimitive(true))
    }

    private fun listVariables(request: TavernRuntimeRequest): TavernRuntimeResponse {
        val scope = request.params.getString("scope") ?: "chat"
        val result = JsonObject(variablesFor(scope).toMap())
        return TavernRuntimeResponse.success(request.id, result)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run the same controller test command.

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: Commit**

```powershell
& 'C:\Program Files\Git\cmd\git.exe' add app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeController.kt app/src/test/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeControllerTest.kt
& 'C:\Program Files\Git\cmd\git.exe' commit -m "feat: add tavern runtime controller"
```

### Task 1.3: Add WebView JavaScript Bridge

**Files:**

- Create: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeBridge.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt`

- [ ] **Step 1: Add bridge class**

Create:

```kotlin
package me.rerere.rikkahub.ui.components.richtext.runtime

import android.webkit.JavascriptInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

internal class TavernRuntimeBridge(
    private val controller: TavernRuntimeController,
    private val scope: CoroutineScope,
    private val emitResult: (callbackName: String, payloadJson: String) -> Unit,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @JavascriptInterface
    fun call(requestJson: String, callbackName: String) {
        if (requestJson.length > 256_000 || callbackName.length > 128) return
        scope.launch(Dispatchers.Main) {
            val response = try {
                val request = json.decodeFromString<TavernRuntimeRequest>(requestJson)
                controller.dispatch(request)
            } catch (e: Exception) {
                TavernRuntimeResponse.error(
                    id = "unknown",
                    code = "BAD_REQUEST",
                    message = e.message ?: "Invalid runtime request",
                )
            }
            emitResult(callbackName, json.encodeToString(TavernRuntimeResponse.serializer(), response))
        }
    }
}
```

- [ ] **Step 2: Wire bridge into `MarkdownWebView`**

In `MarkdownWebView`, add:

```kotlin
val runtimeController = remember { TavernRuntimeController() }
val runtimeScope = rememberCoroutineScope()
```

Inside `WebView(ctx).apply { ... }`, after `addJavascriptInterface(bridge, "RikkahubBridge")`, add:

```kotlin
val tavernBridge = TavernRuntimeBridge(
    controller = runtimeController,
    scope = runtimeScope,
    emitResult = { callbackName, payloadJson ->
        val escapedPayload = org.json.JSONObject.quote(payloadJson)
        val escapedCallback = callbackName.replace(Regex("[^A-Za-z0-9_.$]"), "")
        post {
            evaluateJavascript(
                "window.$escapedCallback && window.$escapedCallback(JSON.parse($escapedPayload));",
                null
            )
        }
    },
)
addJavascriptInterface(tavernBridge, "TavernRuntimeBridge")
```

Add imports:

```kotlin
import androidx.compose.runtime.rememberCoroutineScope
import me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeBridge
import me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeController
```

- [ ] **Step 3: Run compile**

Run:

```powershell
.\gradlew.bat :app:compileDebugKotlin -x :web:buildWebUi --offline
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 4: Commit**

```powershell
& 'C:\Program Files\Git\cmd\git.exe' add app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeBridge.kt app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt
& 'C:\Program Files\Git\cmd\git.exe' commit -m "feat: expose tavern runtime bridge to webview"
```

---

## Phase 2: Tavern Helper Compatible JavaScript API

### Task 2.1: Generate Injected Runtime Script

**Files:**

- Create: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeScript.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeScriptTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt`

- [ ] **Step 1: Write failing script test**

Create:

```kotlin
package me.rerere.rikkahub.ui.components.richtext.runtime

import org.junit.Assert.assertTrue
import org.junit.Test

class TavernRuntimeScriptTest {
    @Test
    fun `script exposes compat namespace and helper methods`() {
        val script = buildTavernRuntimeScript()

        assertTrue(script.contains("window.TavernHelperCompat"))
        assertTrue(script.contains("variables.get"))
        assertTrue(script.contains("variables.set"))
        assertTrue(script.contains("slash.run"))
        assertTrue(script.contains("events.on"))
        assertTrue(script.contains("world.getEntries"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeScriptTest -x :web:buildWebUi --offline
```

Expected:

```text
Unresolved reference 'buildTavernRuntimeScript'
```

- [ ] **Step 3: Implement runtime script**

Create:

```kotlin
package me.rerere.rikkahub.ui.components.richtext.runtime

internal fun buildTavernRuntimeScript(): String = """
(function(){
  if (window.TavernHelperCompat) return;
  var seq = 0;
  var pending = {};
  function callbackName(id){ return "__rikkahubTavernRuntimeCallback_" + id; }
  function call(method, params) {
    return new Promise(function(resolve, reject){
      var id = String(++seq);
      var cb = callbackName(id);
      pending[id] = { resolve: resolve, reject: reject };
      window[cb] = function(response){
        try {
          delete window[cb];
          delete pending[id];
          if (response && response.ok) resolve(response.result);
          else reject(response && response.error ? response.error : { code: "UNKNOWN", message: "Runtime call failed" });
        } catch (e) {
          reject({ code: "CALLBACK_ERROR", message: String(e && e.message || e) });
        }
      };
      try {
        window.TavernRuntimeBridge.call(JSON.stringify({ id: id, method: method, params: params || {} }), cb);
      } catch (e) {
        delete window[cb];
        delete pending[id];
        reject({ code: "BRIDGE_ERROR", message: String(e && e.message || e) });
      }
    });
  }
  var api = {
    runtime: {
      ping: function(){ return call("runtime.ping", {}); }
    },
    variables: {
      get: function(key, scope){ return call("variables.get", { key: key, scope: scope || "chat" }); },
      set: function(key, value, scope){ return call("variables.set", { key: key, value: value, scope: scope || "chat" }); },
      list: function(scope){ return call("variables.list", { scope: scope || "chat" }); }
    },
    slash: {
      run: function(command, args){ return call("slash.run", { command: command, args: args || {} }); }
    },
    events: {
      on: function(name, handler){ document.addEventListener("th:" + name, function(ev){ handler(ev.detail); }); return Promise.resolve(true); },
      emit: function(name, payload){ return call("events.emit", { name: name, payload: payload || null }); }
    },
    world: {
      getEntries: function(){ return call("world.getEntries", {}); },
      upsertEntry: function(entry){ return call("world.upsertEntry", { entry: entry }); },
      deleteEntry: function(id){ return call("world.deleteEntry", { id: id }); }
    },
    messages: {
      getCurrent: function(){ return call("messages.getCurrent", {}); },
      updateCurrent: function(patch){ return call("messages.updateCurrent", { patch: patch }); }
    }
  };
  window.TavernHelperCompat = api;
  window.TavernHelper = window.TavernHelper || api;
  window.TH = window.TH || api;
})();
""".trimIndent()
```

- [ ] **Step 4: Inject into raw HTML host**

In `MarkdownWebView.kt`, inside `buildSandboxHostHtml`, change:

```kotlin
val injectTag = "<script>${buildIframeInjectScript()}</script>"
```

to:

```kotlin
val injectTag = "<script>${buildTavernRuntimeScript()}\n${buildIframeInjectScript()}</script>"
```

Add import:

```kotlin
import me.rerere.rikkahub.ui.components.richtext.runtime.buildTavernRuntimeScript
```

- [ ] **Step 5: Run test and compile**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeScriptTest -x :web:buildWebUi --offline
.\gradlew.bat :app:compileDebugKotlin -x :web:buildWebUi --offline
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 6: Commit**

```powershell
& 'C:\Program Files\Git\cmd\git.exe' add app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeScript.kt app/src/test/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeScriptTest.kt app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt
& 'C:\Program Files\Git\cmd\git.exe' commit -m "feat: inject tavern helper compat script"
```

### Task 2.2: Add Slash Command Registry

**Files:**

- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeController.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeControllerTest.kt`

- [ ] **Step 1: Add failing slash command tests**

Append:

```kotlin
@Test
fun `slash help lists supported commands`() = runTest {
    val response = controller.dispatch(
        TavernRuntimeRequest(id = "5", method = "slash.run", params = JsonObject(mapOf("command" to JsonPrimitive("/th help"))))
    )

    assertTrue(response.ok)
    assertTrue(response.result!!.jsonPrimitive.content.contains("/th help"))
}

@Test
fun `unknown slash command returns unsupported`() = runTest {
    val response = controller.dispatch(
        TavernRuntimeRequest(id = "6", method = "slash.run", params = JsonObject(mapOf("command" to JsonPrimitive("/unknown"))))
    )

    assertFalse(response.ok)
    assertEquals("UNSUPPORTED_SLASH_COMMAND", response.error!!.code)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run the controller test command.

Expected:

```text
expected true but was false
```

- [ ] **Step 3: Implement slash dispatch**

In controller `when`, add:

```kotlin
"slash.run" -> runSlash(request)
```

Add:

```kotlin
private fun runSlash(request: TavernRuntimeRequest): TavernRuntimeResponse {
    val command = request.params.getString("command")?.trim().orEmpty()
    return when {
        command == "/th help" || command == "th help" -> TavernRuntimeResponse.success(
            request.id,
            JsonPrimitive("/th help\n/th vars\n/th ping")
        )
        command == "/th ping" || command == "th ping" -> TavernRuntimeResponse.success(
            request.id,
            JsonPrimitive("pong")
        )
        command == "/th vars" || command == "th vars" -> listVariables(
            request.copy(params = JsonObject(mapOf("scope" to JsonPrimitive("chat"))))
        )
        else -> TavernRuntimeResponse.error(
            request.id,
            "UNSUPPORTED_SLASH_COMMAND",
            "Slash command '$command' is not supported by Rikkahub Tavern compatibility runtime",
        )
    }
}
```

- [ ] **Step 4: Run controller tests**

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: Commit**

```powershell
& 'C:\Program Files\Git\cmd\git.exe' add app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeController.kt app/src/test/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeControllerTest.kt
& 'C:\Program Files\Git\cmd\git.exe' commit -m "feat: add tavern compat slash commands"
```

---

## Phase 3: Event System

### Task 3.1: Add Runtime Event Bus

**Files:**

- Create: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeEventBus.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeController.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeControllerTest.kt`

- [ ] **Step 1: Add event emit test**

Append:

```kotlin
@Test
fun `events emit records event payload`() = runTest {
    val response = controller.dispatch(
        TavernRuntimeRequest(
            id = "7",
            method = "events.emit",
            params = JsonObject(
                mapOf(
                    "name" to JsonPrimitive("message_rendered"),
                    "payload" to JsonPrimitive("ok"),
                )
            ),
        )
    )

    assertTrue(response.ok)
    assertEquals("message_rendered", response.result!!.jsonPrimitive.content)
}
```

- [ ] **Step 2: Implement event bus**

Create:

```kotlin
package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.JsonElement

internal class TavernRuntimeEventBus {
    private val history = ArrayDeque<Pair<String, JsonElement?>>()

    fun emit(name: String, payload: JsonElement?) {
        history += name to payload
        while (history.size > 100) history.removeFirst()
    }

    fun recent(): List<Pair<String, JsonElement?>> = history.toList()
}
```

Modify `TavernRuntimeController` constructor:

```kotlin
internal class TavernRuntimeController(
    private val eventBus: TavernRuntimeEventBus = TavernRuntimeEventBus(),
) {
```

Add to `when`:

```kotlin
"events.emit" -> emitEvent(request)
```

Add:

```kotlin
private fun emitEvent(request: TavernRuntimeRequest): TavernRuntimeResponse {
    val name = request.params.getString("name") ?: return TavernRuntimeResponse.error(
        request.id,
        "BAD_REQUEST",
        "events.emit requires params.name",
    )
    eventBus.emit(name, request.params["payload"])
    return TavernRuntimeResponse.success(request.id, JsonPrimitive(name))
}
```

- [ ] **Step 3: Run controller tests**

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 4: Commit**

```powershell
& 'C:\Program Files\Git\cmd\git.exe' add app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeEventBus.kt app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeController.kt app/src/test/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeControllerTest.kt
& 'C:\Program Files\Git\cmd\git.exe' commit -m "feat: add tavern runtime events"
```

---

## Phase 4: World/Lorebook API Compatibility

### Task 4.1: Add In-Memory World Store Adapter First

**Files:**

- Create: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeWorldStore.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeController.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeControllerTest.kt`

- [ ] **Step 1: Add world entry tests**

Append:

```kotlin
@Test
fun `world upsert then get entries returns entry`() = runTest {
    val entry = JsonObject(
        mapOf(
            "id" to JsonPrimitive("entry-1"),
            "key" to JsonPrimitive("顾雪鸢"),
            "content" to JsonPrimitive("云山宗宗主"),
        )
    )
    controller.dispatch(
        TavernRuntimeRequest(
            id = "8",
            method = "world.upsertEntry",
            params = JsonObject(mapOf("entry" to entry)),
        )
    )

    val response = controller.dispatch(TavernRuntimeRequest(id = "9", method = "world.getEntries"))

    assertTrue(response.ok)
    assertTrue(response.result.toString().contains("顾雪鸢"))
}
```

- [ ] **Step 2: Create world store**

Create:

```kotlin
package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.JsonObject

internal class TavernRuntimeWorldStore {
    private val entries = linkedMapOf<String, JsonObject>()

    fun upsert(entry: JsonObject): String {
        val id = entry.getString("id") ?: "entry-${entries.size + 1}"
        entries[id] = JsonObject(entry + ("id" to kotlinx.serialization.json.JsonPrimitive(id)))
        return id
    }

    fun delete(id: String): Boolean = entries.remove(id) != null

    fun list(): List<JsonObject> = entries.values.toList()
}
```

Modify controller constructor:

```kotlin
internal class TavernRuntimeController(
    private val eventBus: TavernRuntimeEventBus = TavernRuntimeEventBus(),
    private val worldStore: TavernRuntimeWorldStore = TavernRuntimeWorldStore(),
) {
```

Add to `when`:

```kotlin
"world.getEntries" -> getWorldEntries(request)
"world.upsertEntry" -> upsertWorldEntry(request)
"world.deleteEntry" -> deleteWorldEntry(request)
```

Add:

```kotlin
private fun getWorldEntries(request: TavernRuntimeRequest): TavernRuntimeResponse {
    return TavernRuntimeResponse.success(
        request.id,
        kotlinx.serialization.json.JsonArray(worldStore.list())
    )
}

private fun upsertWorldEntry(request: TavernRuntimeRequest): TavernRuntimeResponse {
    val entry = request.params["entry"] as? JsonObject ?: return TavernRuntimeResponse.error(
        request.id,
        "BAD_REQUEST",
        "world.upsertEntry requires params.entry object",
    )
    val id = worldStore.upsert(entry)
    return TavernRuntimeResponse.success(request.id, JsonPrimitive(id))
}

private fun deleteWorldEntry(request: TavernRuntimeRequest): TavernRuntimeResponse {
    val id = request.params.getString("id") ?: return TavernRuntimeResponse.error(
        request.id,
        "BAD_REQUEST",
        "world.deleteEntry requires params.id",
    )
    return TavernRuntimeResponse.success(request.id, JsonPrimitive(worldStore.delete(id)))
}
```

- [ ] **Step 3: Run controller tests**

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 4: Commit**

```powershell
& 'C:\Program Files\Git\cmd\git.exe' add app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeWorldStore.kt app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeController.kt app/src/test/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeControllerTest.kt
& 'C:\Program Files\Git\cmd\git.exe' commit -m "feat: add tavern world api adapter"
```

### Task 4.2: Replace In-Memory Store With Real Lorebook Binding

**Files to inspect before editing:**

- `app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt`
- `app/src/main/java/me/rerere/rikkahub/data/model/PromptInjection.kt`
- `app/src/main/java/me/rerere/rikkahub/data/repository/`
- `app/src/main/java/me/rerere/rikkahub/data/datastore/`

- [ ] **Step 1: Locate actual lorebook/world model**

Run:

```powershell
rg "Lore|World|PromptInjection|lorebook|world" app/src/main/java
```

Expected:

```text
At least one model/repository path for prompt injection or lorebook-like data.
```

- [ ] **Step 2: Write adapter test against the real repository boundary**

Create a fake repository interface if the real repository is Android/database-bound:

```kotlin
internal interface TavernWorldRepository {
    suspend fun listEntries(conversationId: String?): List<JsonObject>
    suspend fun upsertEntry(conversationId: String?, entry: JsonObject): String
    suspend fun deleteEntry(conversationId: String?, id: String): Boolean
}
```

Then update `TavernRuntimeWorldStore` to depend on this interface.

- [ ] **Step 3: Run tests**

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 4: Commit real binding**

```powershell
& 'C:\Program Files\Git\cmd\git.exe' add app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime app/src/test/java/me/rerere/rikkahub/ui/components/richtext/runtime
& 'C:\Program Files\Git\cmd\git.exe' commit -m "feat: bind tavern world api to local lore data"
```

---

## Phase 5: SillyTavern-Style Message DOM Renderer

### Task 5.1: Define Stable Message DOM Models

**Files:**

- Create: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/st/StableMessageDomModels.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/st/StableMessageDomModelsTest.kt`

- [ ] **Step 1: Write model tests**

Create:

```kotlin
package me.rerere.rikkahub.ui.components.richtext.st

import me.rerere.rikkahub.ui.components.richtext.RichTextSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class StableMessageDomModelsTest {
    @Test
    fun `dom message stores role and ordered segments`() {
        val message = StableDomMessage(
            id = "m1",
            role = StableDomRole.ASSISTANT,
            segments = listOf(
                StableDomSegment("s1", RichTextSegment.Kind.MARKDOWN, "hello"),
                StableDomSegment("s2", RichTextSegment.Kind.STATUS_BLOCK, "<Status_block>x</Status_block>"),
            ),
            streaming = false,
        )

        assertEquals("m1", message.id)
        assertEquals(StableDomRole.ASSISTANT, message.role)
        assertEquals(listOf("s1", "s2"), message.segments.map { it.id })
    }
}
```

- [ ] **Step 2: Implement models**

Create:

```kotlin
package me.rerere.rikkahub.ui.components.richtext.st

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.ui.components.richtext.RichTextSegment

@Serializable
internal enum class StableDomRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL,
}

@Serializable
internal data class StableDomSegment(
    val id: String,
    val kind: RichTextSegment.Kind,
    val raw: String,
)

@Serializable
internal data class StableDomMessage(
    val id: String,
    val role: StableDomRole,
    val segments: List<StableDomSegment>,
    val streaming: Boolean,
)
```

- [ ] **Step 3: Run tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests me.rerere.rikkahub.ui.components.richtext.st.StableMessageDomModelsTest -x :web:buildWebUi --offline
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 4: Commit**

```powershell
& 'C:\Program Files\Git\cmd\git.exe' add app/src/main/java/me/rerere/rikkahub/ui/components/richtext/st/StableMessageDomModels.kt app/src/test/java/me/rerere/rikkahub/ui/components/richtext/st/StableMessageDomModelsTest.kt
& 'C:\Program Files\Git\cmd\git.exe' commit -m "feat: add sillytavern style message dom models"
```

### Task 5.2: Build HTML Shell Renderer

**Files:**

- Create: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/st/StableMessageHtmlRenderer.kt`
- Create: `app/src/main/assets/html/st-message.html`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/st/StableMessageHtmlRendererTest.kt`

- [ ] **Step 1: Write renderer test**

Create:

```kotlin
package me.rerere.rikkahub.ui.components.richtext.st

import me.rerere.rikkahub.ui.components.richtext.RichTextSegment
import org.junit.Assert.assertTrue
import org.junit.Test

class StableMessageHtmlRendererTest {
    @Test
    fun `renderer embeds message json and segment root`() {
        val html = buildStableMessageHtml(
            StableDomMessage(
                id = "m1",
                role = StableDomRole.ASSISTANT,
                segments = listOf(StableDomSegment("s1", RichTextSegment.Kind.MARKDOWN, "hello")),
                streaming = false,
            )
        )

        assertTrue(html.contains("data-rikkahub-st-message"))
        assertTrue(html.contains("window.__RIKKAHUB_ST_MESSAGE__"))
        assertTrue(html.contains("hello"))
    }
}
```

- [ ] **Step 2: Create asset shell**

Create `app/src/main/assets/html/st-message.html`:

```html
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width,initial-scale=1.0">
  <style>
    html, body { margin: 0; padding: 0; background: transparent; color: inherit; font-family: sans-serif; }
    .mes { border-radius: 12px; padding: 10px 12px; line-height: 1.55; word-break: break-word; }
    .mes.assistant { background: rgba(127,127,127,.08); }
    .mes.user { background: rgba(80,120,255,.10); }
    .mes_text { display: flex; flex-direction: column; gap: 8px; }
    .mes_segment[data-kind="STATUS_BLOCK"], .mes_segment[data-kind="JSON_PATCH"] {
      border: 1px solid rgba(127,127,127,.25);
      border-radius: 10px;
      padding: 8px;
      overflow: auto;
    }
    pre { white-space: pre-wrap; margin: 0; }
  </style>
</head>
<body>
  <div id="root" data-rikkahub-st-message></div>
  <script>
    (function(){
      var message = window.__RIKKAHUB_ST_MESSAGE__;
      var root = document.getElementById('root');
      function esc(text){
        return String(text).replace(/[&<>"']/g, function(ch){
          return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'})[ch];
        });
      }
      function renderSegment(segment){
        var div = document.createElement('div');
        div.className = 'mes_segment';
        div.dataset.kind = segment.kind;
        if (segment.kind === 'MARKDOWN') {
          div.innerHTML = esc(segment.raw).replace(/\n/g, '<br>');
        } else {
          div.innerHTML = '<pre>' + esc(segment.raw) + '</pre>';
        }
        return div;
      }
      var mes = document.createElement('div');
      mes.className = 'mes ' + String(message.role || 'assistant').toLowerCase();
      var text = document.createElement('div');
      text.className = 'mes_text';
      (message.segments || []).forEach(function(segment){ text.appendChild(renderSegment(segment)); });
      mes.appendChild(text);
      root.appendChild(mes);
      function report(){
        try {
          var h = Math.max(document.body.scrollHeight, document.documentElement.scrollHeight);
          window.RikkahubBridge && window.RikkahubBridge.reportHeight(Math.ceil(h * (window.devicePixelRatio || 1)));
        } catch(e) {}
      }
      report();
      window.addEventListener('load', report);
      setTimeout(report, 100);
      setTimeout(report, 400);
    })();
  </script>
</body>
</html>
```

- [ ] **Step 3: Implement renderer**

Create:

```kotlin
package me.rerere.rikkahub.ui.components.richtext.st

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json {
    encodeDefaults = true
}

internal fun buildStableMessageHtml(message: StableDomMessage): String {
    val messageJson = json.encodeToString(message)
        .replace("</script>", "<\\/script>")
    return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="UTF-8">
          <meta name="viewport" content="width=device-width,initial-scale=1.0">
        </head>
        <body>
          <script>window.__RIKKAHUB_ST_MESSAGE__ = $messageJson;</script>
          <div data-rikkahub-st-message></div>
          <script>
            document.querySelector('[data-rikkahub-st-message]').textContent =
              JSON.stringify(window.__RIKKAHUB_ST_MESSAGE__);
          </script>
        </body>
        </html>
    """.trimIndent()
}
```

The minimal renderer above passes tests. After it is green, replace its body by loading `st-message.html` and injecting the JSON before `</head>`:

```kotlin
internal fun buildStableMessageHtml(message: StableDomMessage, template: String): String {
    val messageJson = json.encodeToString(message).replace("</script>", "<\\/script>")
    return template.replace(
        "<body>",
        "<body><script>window.__RIKKAHUB_ST_MESSAGE__ = $messageJson;</script>"
    )
}
```

- [ ] **Step 4: Run renderer test**

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: Commit**

```powershell
& 'C:\Program Files\Git\cmd\git.exe' add app/src/main/java/me/rerere/rikkahub/ui/components/richtext/st/StableMessageHtmlRenderer.kt app/src/main/assets/html/st-message.html app/src/test/java/me/rerere/rikkahub/ui/components/richtext/st/StableMessageHtmlRendererTest.kt
& 'C:\Program Files\Git\cmd\git.exe' commit -m "feat: add sillytavern style message html renderer"
```

---

## Phase 6: Compose Integration And Runtime Toggle

### Task 6.1: Add Rendering Mode Switch

**Files:**

- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichTextRenderPolicy.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/RichTextRenderPolicyTest.kt`

- [ ] **Step 1: Add policy enum**

In `RichTextRenderPolicy.kt`, add:

```kotlin
internal enum class RichTextRendererMode {
    NATIVE_MARKDOWN,
    WEBVIEW_SEGMENTS,
    STABLE_DOM,
}
```

Add:

```kotlin
internal fun chooseRendererMode(content: String): RichTextRendererMode {
    val segments = parseRichTextSegments(content)
    return when {
        segments.any { it.kind == RichTextSegment.Kind.HTML_DOCUMENT } -> RichTextRendererMode.WEBVIEW_SEGMENTS
        segments.any { it.kind != RichTextSegment.Kind.MARKDOWN } -> RichTextRendererMode.STABLE_DOM
        else -> RichTextRendererMode.NATIVE_MARKDOWN
    }
}
```

- [ ] **Step 2: Add tests**

```kotlin
@Test
fun `status content prefers stable dom renderer`() {
    assertEquals(
        RichTextRendererMode.STABLE_DOM,
        chooseRendererMode("hello\n<Status_block>x</Status_block>")
    )
}

@Test
fun `html app keeps webview segment renderer`() {
    assertEquals(
        RichTextRendererMode.WEBVIEW_SEGMENTS,
        chooseRendererMode("<!DOCTYPE html><html><body>x</body></html>")
    )
}
```

- [ ] **Step 3: Run policy tests**

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 4: Integrate behind local constant**

In `Markdown.kt`, add a private constant near top-level:

```kotlin
private const val ENABLE_STABLE_DOM_RENDERER = false
```

In `MarkdownBlock`, before current segment branch:

```kotlin
val rendererMode = remember(normalizedContent) { chooseRendererMode(normalizedContent) }
if (ENABLE_STABLE_DOM_RENDERER && rendererMode == RichTextRendererMode.STABLE_DOM) {
    MarkdownWebView(
        content = buildStableMessageHtml(
            StableDomMessage(
                id = normalizedContent.hashCode().toString(),
                role = StableDomRole.ASSISTANT,
                segments = segments.mapIndexed { index, segment ->
                    StableDomSegment(
                        id = "segment-$index",
                        kind = segment.kind,
                        raw = segment.raw,
                    )
                },
                streaming = false,
            )
        ),
        modifier = modifier,
        isRawHtml = true,
    )
    return
}
```

Add imports:

```kotlin
import me.rerere.rikkahub.ui.components.richtext.st.StableDomMessage
import me.rerere.rikkahub.ui.components.richtext.st.StableDomRole
import me.rerere.rikkahub.ui.components.richtext.st.StableDomSegment
import me.rerere.rikkahub.ui.components.richtext.st.buildStableMessageHtml
```

- [ ] **Step 5: Compile**

Run:

```powershell
.\gradlew.bat :app:compileDebugKotlin -x :web:buildWebUi --offline
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 6: Commit disabled integration**

```powershell
& 'C:\Program Files\Git\cmd\git.exe' add app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichTextRenderPolicy.kt app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt app/src/test/java/me/rerere/rikkahub/ui/components/richtext/RichTextRenderPolicyTest.kt
& 'C:\Program Files\Git\cmd\git.exe' commit -m "feat: add gated stable dom renderer mode"
```

### Task 6.2: Enable Stable DOM Renderer After Manual Parity Test

**Files:**

- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt`

- [ ] **Step 1: Change flag**

Change:

```kotlin
private const val ENABLE_STABLE_DOM_RENDERER = false
```

to:

```kotlin
private const val ENABLE_STABLE_DOM_RENDERER = true
```

- [ ] **Step 2: Build APK**

Run:

```powershell
.\gradlew.bat :app:assembleDebug -x :web:buildWebUi --offline
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 3: Install APK**

Run:

```powershell
& 'C:\Users\18734\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s emulator-5554 install -r '.\app\build\outputs\apk\debug\app-x86_64-debug.apk'
```

Expected:

```text
Success
```

- [x] **Step 4: Manual smoke cases**

Paste or load messages containing:

```text
<maintext>
正文
</maintext>
<Status_block>
状态
</Status_block>
```

Expected:

```text
正文 visible.
状态 visible in status segment.
No raw maintext tag visible.
No crash.
```

Paste or load:

```text
```html
<style>.card{color:red}</style>
<body><div class="card">HTML App</div><script>document.body.dataset.ok='1'</script></body>
```
```

Expected:

```text
HTML App renders inside WebView.
No AndroidRuntime/FATAL EXCEPTION in logcat.
```

- [ ] **Step 5: Commit enablement**

```powershell
& 'C:\Program Files\Git\cmd\git.exe' add app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt
& 'C:\Program Files\Git\cmd\git.exe' commit -m "feat: enable stable dom renderer for tavern segments"
```

---

## Phase 7: Permission UI And Security Hardening

### Task 7.1: Add Runtime Permission Store

**Files:**

- Create: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimePermissionStore.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeController.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeControllerTest.kt`

- [ ] **Step 1: Add permission denial test**

Append:

```kotlin
@Test
fun `world write denied when permission disallows it`() = runTest {
    val deniedController = TavernRuntimeController(
        permissionStore = TavernRuntimePermissionStore(
            initial = TavernRuntimePermissionState(allowScripts = true, allowWorldWrite = false)
        )
    )
    val response = deniedController.dispatch(
        TavernRuntimeRequest(
            id = "10",
            method = "world.upsertEntry",
            params = JsonObject(mapOf("entry" to JsonObject(mapOf("id" to JsonPrimitive("x"))))),
        )
    )

    assertFalse(response.ok)
    assertEquals("PERMISSION_DENIED", response.error!!.code)
}
```

- [ ] **Step 2: Implement permission store**

Create:

```kotlin
package me.rerere.rikkahub.ui.components.richtext.runtime

internal class TavernRuntimePermissionStore(
    initial: TavernRuntimePermissionState = TavernRuntimePermissionState(),
) {
    private var state = initial

    fun current(): TavernRuntimePermissionState = state

    fun update(newState: TavernRuntimePermissionState) {
        state = newState
    }
}
```

Modify controller constructor:

```kotlin
internal class TavernRuntimeController(
    private val eventBus: TavernRuntimeEventBus = TavernRuntimeEventBus(),
    private val worldStore: TavernRuntimeWorldStore = TavernRuntimeWorldStore(),
    private val permissionStore: TavernRuntimePermissionStore = TavernRuntimePermissionStore(
        TavernRuntimePermissionState(allowScripts = true)
    ),
) {
```

At start of `upsertWorldEntry` and `deleteWorldEntry`, add:

```kotlin
if (!permissionStore.current().allowWorldWrite) {
    return TavernRuntimeResponse.error(
        request.id,
        "PERMISSION_DENIED",
        "World write access is disabled for this script",
    )
}
```

- [ ] **Step 3: Run controller tests**

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 4: Commit**

```powershell
& 'C:\Program Files\Git\cmd\git.exe' add app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimePermissionStore.kt app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeController.kt app/src/test/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeControllerTest.kt
& 'C:\Program Files\Git\cmd\git.exe' commit -m "feat: gate tavern runtime world writes"
```

### Task 7.2: Add Settings UI With Safe Defaults

**Files to inspect first:**

- `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/`
- `app/src/main/java/me/rerere/rikkahub/data/datastore/Settings.kt`

- [ ] **Step 1: Locate settings patterns**

Run:

```powershell
rg "Switch|Checkbox|Setting" app/src/main/java/me/rerere/rikkahub/ui/pages/setting app/src/main/java/me/rerere/rikkahub/data/datastore
```

- [ ] **Step 2: Add settings keys**

Add fields to settings model:

```kotlin
val tavernRuntimeAllowScripts: Boolean = false
val tavernRuntimeAllowWorldWrite: Boolean = false
val tavernRuntimeAllowMessageWrite: Boolean = false
```

- [ ] **Step 3: Add UI toggles using existing setting component style**

Labels:

```text
Enable Tavern Helper compatibility runtime
Allow Tavern scripts to write world/lorebook entries
Allow Tavern scripts to edit current message
```

- [ ] **Step 4: Wire settings into `TavernRuntimePermissionStore`**

Create the store from settings where `MarkdownWebView` creates the controller.

- [ ] **Step 5: Run compile and manual settings smoke**

Expected:

```text
Settings screen opens.
Toggles persist after app restart.
Runtime remains disabled by default.
```

---

## Phase 8: Full Verification

### Task 8.1: Run Unit Test Suite For New Runtime

- [ ] **Step 1: Run targeted test suite**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeModelsTest --tests me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeControllerTest --tests me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeScriptTest --tests me.rerere.rikkahub.ui.components.richtext.st.StableMessageDomModelsTest --tests me.rerere.rikkahub.ui.components.richtext.st.StableMessageHtmlRendererTest --tests me.rerere.rikkahub.ui.components.richtext.RichTextRenderPolicyTest --tests me.rerere.rikkahub.ui.components.richtext.MarkdownWebViewHtmlDetectionTest --tests me.rerere.rikkahub.ui.components.richtext.MarkdownStatusBlockTest --tests me.rerere.rikkahub.data.ai.transformers.StatusPlaceholderTransformerTest -x :web:buildWebUi --offline
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 2: Run APK build**

```powershell
.\gradlew.bat :app:assembleDebug -x :web:buildWebUi --offline
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 3: Install to emulator**

```powershell
& 'C:\Users\18734\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s emulator-5554 install -r '.\app\build\outputs\apk\debug\app-x86_64-debug.apk'
```

Expected:

```text
Success
```

Smoke results recorded on 2026-06-16:

- `adb shell am instrument -w -r -e class me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeSmokeTest me.rerere.rikkahub.debug.test/androidx.test.runner.AndroidJUnitRunner`
- Result: `OK (1 test)` on `RikkaHub(AVD) - 15`
- `adb shell am instrument -w -r me.rerere.rikkahub.debug.test/androidx.test.runner.AndroidJUnitRunner` also passed after the smoke Activity was moved into the app debug process.

- [ ] **Step 4: Start app**

```powershell
& 'C:\Users\18734\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s emulator-5554 shell am start -n me.rerere.rikkahub.debug/me.rerere.rikkahub.RouteActivity
```

Expected:

```text
Starting: Intent
```

- [ ] **Step 5: Check foreground and crash logs**

```powershell
& 'C:\Users\18734\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s emulator-5554 shell dumpsys window | Select-String -Pattern "mCurrentFocus|mFocusedApp"
& 'C:\Users\18734\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s emulator-5554 logcat -d -t 300 | Select-String -Pattern "FATAL EXCEPTION|AndroidRuntime|me.rerere.rikkahub"
```

Expected:

```text
mCurrentFocus includes me.rerere.rikkahub.debug/me.rerere.rikkahub.RouteActivity
No FATAL EXCEPTION for me.rerere.rikkahub.debug
```

### Task 8.2: Manual Compatibility Matrix

Run these content cases in the app:

- [ ] **Case 1: Plain Markdown**

```markdown
# 标题
正文
```

Expected:

```text
Native Markdown display remains normal.
```

- [ ] **Case 2: Status Block**

```text
<Status_block>
『📅 日期：秦武阳十五年三月 春 | ⏰ 时间：下午』
</Status_block>
```

Expected:

```text
Status content visible and styled.
```

- [ ] **Case 3: UpdateVariable + Placeholder**

```text
<UpdateVariable>
<JSONPatch>
[{ "op": "replace", "path": "/世界/当前时间", "value": "子时" }]
</JSONPatch>
</UpdateVariable>
<StatusPlaceHolderImpl/>
```

Expected:

```text
Patch applies.
Placeholder renders status HTML.
Raw UpdateVariable tag not visible after transformer.
```

- [ ] **Case 4: Tavern Helper Compat Script**

```html
<!DOCTYPE html>
<html>
<body>
<div id="out">loading</div>
<script>
TavernHelperCompat.runtime.ping().then(function(v){
  document.getElementById('out').textContent = v;
});
</script>
</body>
</html>
```

Expected:

```text
Output becomes "pong".
No crash.
```

- [ ] **Case 5: Variables API**

```html
<!DOCTYPE html>
<html>
<body>
<div id="out">loading</div>
<script>
TavernHelperCompat.variables.set('x', '1').then(function(){
  return TavernHelperCompat.variables.get('x');
}).then(function(v){
  document.getElementById('out').textContent = v;
});
</script>
</body>
</html>
```

Expected:

```text
Output becomes "1".
```

- [ ] **Case 6: Denied World Write**

```html
<!DOCTYPE html>
<html>
<body>
<div id="out">loading</div>
<script>
TavernHelperCompat.world.upsertEntry({id:'x'}).catch(function(e){
  document.getElementById('out').textContent = e.code;
});
</script>
</body>
</html>
```

Expected when permission disabled:

```text
Output becomes "PERMISSION_DENIED".
```

---

## Execution Order And Dependency Map

Use this dependency order. Do not skip forward unless the prior phase has tests passing.

| Order | Phase | Why it must happen here |
| --- | --- | --- |
| 1 | Phase 0 baseline | Locks current behavior before runtime work touches render paths |
| 2 | Phase 1 JSON-RPC models/controller/bridge | Establishes Kotlin-owned API boundary before JS aliases exist |
| 3 | Phase 2 injected JS API | Adds card-facing helper object after bridge is testable |
| 4 | Phase 3 events | Events depend on bridge/controller but not world/message writes |
| 5 | Phase 4 world API | Needs permission decisions and repository discovery |
| 6 | Phase 5 ST DOM renderer | Independent rendering shell, initially not enabled globally |
| 7 | Phase 6 gated integration | Turns DOM renderer on only after it exists and tests pass |
| 8 | Phase 7 permissions UI | Makes risky write capabilities user-controlled |
| 9 | Phase 8 verification | Confirms combined runtime/rendering behavior |

Recommended commit rhythm:

```text
One commit per task.
Never mix runtime bridge changes and DOM renderer changes in the same commit.
Never mix permission UI and permission enforcement in the same commit.
```

## Debugging Guide

### Symptom: WebView Shows Blank Area

Check in order:

1. Confirm `MarkdownWebView` loaded content:

```powershell
& 'C:\Users\18734\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s emulator-5554 logcat -d -t 300 | Select-String -Pattern "buildSandboxHostHtml|RWKV|chromium"
```

Expected:

```text
buildSandboxHostHtml logs show non-zero userHtml.length and finalHtml.length.
```

2. Confirm height bridge is working:

```text
If content exists but height remains around 60-100px, inspect RikkahubBridge.reportHeight calls.
```

3. Confirm raw HTML path did not receive Markdown-only content:

```kotlin
assertFalse(looksLikeHtmlDocument("<maintext>story</maintext>"))
```

4. Confirm fenced HTML app detection:

```kotlin
assertTrue(looksLikeHtmlDocument("```html\n<body><script>1</script></body>\n```"))
```

### Symptom: `TavernHelperCompat` Is Undefined

Check in order:

1. Confirm `buildTavernRuntimeScript()` is injected into `buildSandboxHostHtml()`.
2. Confirm the content is routed as raw HTML:

```kotlin
assertEquals(RichTextSegment.Kind.HTML_DOCUMENT, parseRichTextSegments(html).single().kind)
```

3. Confirm the script executes after body exists. If card script runs in `<head>` before injection, move injected script earlier:

```html
<head>
  <script>/* runtime script */</script>
</head>
```

4. Confirm Android WebView JS is enabled:

```kotlin
settings.javaScriptEnabled = true
```

### Symptom: JS Promise Never Resolves

Check in order:

1. Confirm `TavernRuntimeBridge.call()` is registered:

```kotlin
addJavascriptInterface(tavernBridge, "TavernRuntimeBridge")
```

2. Confirm callback name sanitization does not erase dots or underscores needed by the callback:

```kotlin
callbackName.replace(Regex("[^A-Za-z0-9_.$]"), "")
```

3. Confirm response callback JavaScript is valid:

```javascript
window.__rikkahubTavernRuntimeCallback_1 &&
window.__rikkahubTavernRuntimeCallback_1(JSON.parse("{...}"));
```

4. If payload quoting breaks, use Android `JSONObject.quote(payloadJson)` and pass the quoted string to `JSON.parse(...)`.

### Symptom: `PERMISSION_DENIED` Appears For Reads

Reads should be allowed for:

- `variables.get`
- `variables.list`
- `world.getEntries`
- `messages.getCurrent`

If reads are denied, check that permission gates are only placed in:

- `world.upsertEntry`
- `world.deleteEntry`
- `messages.updateCurrent`
- future model/prompt submission commands

### Symptom: Raw `<UpdateVariable>` Still Visible

Check these paths:

1. New conversation preset path in `ChatService`.
2. Initial greeting path in `ChatVM.applyInitialGreeting`.
3. Existing conversation repair path in `ChatService`.
4. UI fallback in `MarkdownBlock`.

The expected design is:

```text
Transformer removes tags when it has conversation context.
MarkdownBlock fallback keeps visible text acceptable when transformer was bypassed.
```

### Symptom: Status Block Renders As Plain Code

Check:

```kotlin
containsStatusBlockTag("<Status_block>x</Status_block>")
parseRichTextSegments("<Status_block>x</Status_block>")
```

Expected:

```text
containsStatusBlockTag returns true.
parseRichTextSegments returns one STATUS_BLOCK segment.
```

If this fails, the regex in `RichTextRenderPolicy.kt` and `Markdown.kt` may have drifted. Keep one source of truth in `RichTextRenderPolicy.kt` and make `Markdown.kt` call that.

## Manual Test Script Snippets

Use these exact snippets during emulator smoke testing.

### Runtime Ping HTML

```html
<!DOCTYPE html>
<html>
<body>
<div id="out">loading</div>
<script>
TavernHelperCompat.runtime.ping()
  .then(function(v){ document.getElementById('out').textContent = v; })
  .catch(function(e){ document.getElementById('out').textContent = e.code || 'error'; });
</script>
</body>
</html>
```

Expected visible text:

```text
pong
```

### Variables HTML

```html
<!DOCTYPE html>
<html>
<body>
<div id="out">loading</div>
<script>
TavernHelperCompat.variables.set('favor', '12')
  .then(function(){ return TavernHelperCompat.variables.get('favor'); })
  .then(function(v){ document.getElementById('out').textContent = 'favor=' + v; })
  .catch(function(e){ document.getElementById('out').textContent = e.code || 'error'; });
</script>
</body>
</html>
```

Expected visible text:

```text
favor=12
```

### Slash HTML

```html
<!DOCTYPE html>
<html>
<body>
<pre id="out">loading</pre>
<script>
TavernHelperCompat.slash.run('/th help')
  .then(function(v){ document.getElementById('out').textContent = v; })
  .catch(function(e){ document.getElementById('out').textContent = e.code || 'error'; });
</script>
</body>
</html>
```

Expected visible text includes:

```text
/th help
```

### Permission Denial HTML

```html
<!DOCTYPE html>
<html>
<body>
<div id="out">loading</div>
<script>
TavernHelperCompat.world.upsertEntry({ id: 'x', key: 'test', content: 'blocked' })
  .then(function(){ document.getElementById('out').textContent = 'unexpected success'; })
  .catch(function(e){ document.getElementById('out').textContent = e.code; });
</script>
</body>
</html>
```

Expected visible text with default safe settings:

```text
PERMISSION_DENIED
```

### Mixed Story And Status Text

```text
<maintext>
一夜无话。
</maintext>
<Status_block>
『📅 日期：秦武阳十五年三月 春 | ⏰ 时间：下午』
</Status_block>
正文继续。
```

Expected:

```text
"一夜无话。" visible.
Status block visible.
"正文继续。" visible.
Raw <maintext> tag not visible.
```

## Do Not Implement In This Pass

These are deliberately outside this plan's first working version:

- Arbitrary network fetch proxy for card scripts.
- Direct model/provider API calls from JS.
- Importing complete SillyTavern frontend bundle.
- Injecting jQuery/lodash by default.
- Giving JS access to all conversations.
- Editing arbitrary historical messages.
- Running slash commands that submit prompts or change assistant settings.
- Letting role-card JS read app settings or API keys.

If a role card needs one of these, add a separate plan with:

- Exact card sample.
- Required API.
- Permission model.
- Persistence model.
- Tests that prove denial by default and success when enabled.

## Review Checklist Before Enabling Runtime By Default

- [ ] `TavernRuntimeBridge` has no methods except `call(...)`.
- [ ] `TavernRuntimeController` returns explicit errors for unknown methods.
- [ ] World writes are denied by default.
- [ ] Message writes are denied by default.
- [ ] Runtime script aliases do not overwrite existing `window.TavernHelper`.
- [ ] Raw HTML route still blocks file/content access in WebView settings.
- [ ] Link opening still uses protocol whitelist.
- [ ] Existing status block tests pass.
- [ ] Existing JSON Patch tests pass.
- [ ] APK installs and launches on emulator.
- [ ] Manual `runtime.ping()` snippet resolves to `pong`.

---

## Rollback Plan

If the runtime causes regressions:

1. Set `ENABLE_STABLE_DOM_RENDERER = false`.
2. Remove `buildTavernRuntimeScript()` injection from `buildSandboxHostHtml`.
3. Keep model/controller tests and runtime files in the branch for a follow-up runtime branch, but do not route production rendering through them.
4. Re-run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests me.rerere.rikkahub.ui.components.richtext.MarkdownStatusBlockTest --tests me.rerere.rikkahub.ui.components.richtext.MarkdownWebViewHtmlDetectionTest --tests me.rerere.rikkahub.ui.components.richtext.RichTextRenderPolicyTest --tests me.rerere.rikkahub.data.ai.transformers.StatusPlaceholderTransformerTest -x :web:buildWebUi --offline
.\gradlew.bat :app:assembleDebug -x :web:buildWebUi --offline
```

Expected:

```text
BUILD SUCCESSFUL
```

---

## Completion Criteria

- All current rendering tests remain green.
- New runtime model/controller/script/ST DOM renderer tests are green.
- Debug APK builds and installs.
- App starts on emulator with no `FATAL EXCEPTION`.
- Manual compatibility matrix passes.
- Runtime permissions default to safe/denied for write operations.
- Unsupported Tavern Helper methods return explicit JSON-RPC errors.
- Work is committed in small commits matching the phases above.

## Known Deferred Work

- Full SillyTavern extension ecosystem parity is not included.
- Network APIs for scripts are not included.
- Direct editing of arbitrary historical messages is not included until a permission UI and repository transaction boundary are implemented.
- Full theme parity with SillyTavern CSS is not included; this plan adds DOM structure and safe segment rendering first.
- Full JS-Slash-Runner libraries such as lodash/jquery injection are not included. Add them only if a real role card requires them and after size/security review.
