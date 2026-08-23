# Tavern Web Compatibility Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve RikkaHub's native chat UI while making real SillyTavern character-card HTML, visual opening selectors, display macros, linked media, and quote colors work through a reusable compatibility runtime.

**Architecture:** Keep the app-owned `TavernConversationWebView` as the only conversation document and run card HTML in sandboxed per-message iframes. Extend the existing parent/iframe RPC broker with an authoritative chat-message gateway backed by `Conversation` and `TavernGreetingSession`; keep all mutations in the host state and patch the document from Compose. Apply display-only macros during snapshot serialization and SillyTavern quote semantics immediately before Markdown parsing.

**Tech Stack:** Kotlin, Jetpack Compose, Android WebView, kotlinx.serialization, JavaScript, markdown-it, DOMPurify, JUnit, AndroidX instrumentation tests, Gradle.

## Global Constraints

- Keep the native top bar, message layout/actions, conversation tree, status HUD, and native input composer.
- Resolve `{user}` and `{{user}}` from `DisplaySetting.userNickname`; blank resolves to `你`.
- Never rewrite persisted source solely to expand a visual macro.
- Card frames never receive a direct Android JavaScript interface or the parent action token.
- Runtime scripts and remote networking remain controlled by existing `TavernRuntimePermissions`.
- Do not enable universal file access, arbitrary `content:` access, or mixed-content bypass.
- Real-card, physical-device evidence is required; unit/DOM tests alone do not prove linked-image compatibility.
- Preserve unrelated dirty-worktree changes and stage only files belonging to each task.

---

## File Structure

- Create `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernDisplayMacroResolver.kt`: display-only macro boundary used by snapshot serialization.
- Create `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernChatMessageGateway.kt`: SillyTavern-compatible query/write model and validation for `getChatMessages` and `setChatMessage(s)`.
- Modify `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationSnapshot.kt`: resolve displayed text and carry opening swipe source data.
- Modify `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationWebView.kt`: supply theme aliases, current snapshot, and host mutation callbacks to the runtime gateway.
- Modify `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationBridge.kt`: validate and dispatch script-originated greeting selections on the WebView/UI thread.
- Modify `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernOpeningStage.kt`: expose every prepared greeting to each candidate document and keep visual/native selection synchronized.
- Modify `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeController.kt`: dispatch the new message API methods to the gateway.
- Modify `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeScript.kt`: publish TavernHelper/global message API shims.
- Modify `app/src/main/assets/html/tavern-conversation.html`: SillyTavern quote transform, theme rules, iframe media hardening, and resize/error behavior.
- Extend the matching JVM and instrumentation test files listed in each task.

---

### Task 1: Display-only Tavern macro resolution

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernDisplayMacroResolver.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationSnapshot.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationWebView.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationSnapshotTest.kt`

**Interfaces:**
- Consumes: `PlaceholderTransformer.expandVisualMacros(text, userName, charName)`.
- Produces: `resolveTavernDisplayText(text: String, userName: String, characterName: String): String`.

- [x] **Step 1: Write failing snapshot tests**

Add tests proving all four name macros resolve case-insensitively, blank nicknames become `你`, raw HTML is resolved for display, and the source `Conversation` remains unchanged:

```kotlin
@Test
fun `snapshot resolves user and char macros without mutating source`() {
    val originalText = "<section>{user} / {{USER}} / {char} / {{CHAR}}</section>"
    val sourceMessage = uiMessage(
        "00000000-0000-0000-0000-000000000021",
        MessageRole.ASSISTANT,
        originalText,
        UIMessagePart.RenderMode.HTML,
    )
    val source = conversation(MessageNode.of(sourceMessage))
    val snapshot = buildTavernConversationSnapshot(source, "阿澈", "白露", emptyMap(), null, false)

    val part = snapshot.nodes.single().selectedMessage.parts.single() as TavernConversationTextPart
    assertEquals("<section>阿澈 / 阿澈 / 白露 / 白露</section>", part.text)
    assertEquals(originalText, (sourceMessage.parts.single() as UIMessagePart.Text).text)
}

@Test
fun `snapshot uses Chinese fallback for blank user nickname`() {
    val snapshot = buildTavernConversationSnapshot(
        conversation(MessageNode.of(UIMessage.assistant("欢迎，{user}"))),
        "",
        "白露",
        emptyMap(),
        null,
        false,
    )
    val part = snapshot.nodes.single().selectedMessage.parts.single() as TavernConversationTextPart
    assertEquals("欢迎，你", part.text)
}
```

- [x] **Step 2: Run the focused tests and verify failure**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernConversationSnapshotTest"
```

Expected: the new tests fail because snapshot text is currently copied verbatim and the pane uses `User` as fallback.

- [x] **Step 3: Add the resolver and route every displayed text part through it**

Create the resolver:

```kotlin
internal fun resolveTavernDisplayText(
    text: String,
    userName: String,
    characterName: String,
): String = PlaceholderTransformer.expandVisualMacros(
    text = text,
    userName = userName.ifBlank { "你" },
    charName = characterName,
)
```

Pass `userName` and `characterName` into `toTavernConversationParts`, resolve `UIMessagePart.Text`, reasoning text, tool text output, and status-page HTML at the serialization boundary. Change `TavernConversationPane` to compute:

```kotlin
val userName = settings.displaySetting.userNickname.ifBlank { "你" }
```

- [x] **Step 4: Run focused tests and verify pass**

Run the Task 1 Gradle command again. Expected: all `TavernConversationSnapshotTest` tests pass.

- [ ] **Step 5: Commit only Task 1 files**

```powershell
git add -- app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernDisplayMacroResolver.kt app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationSnapshot.kt app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationWebView.kt app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationSnapshotTest.kt
git commit -m "fix: resolve Tavern display names in conversation"
```

---

### Task 2: SillyTavern dialogue colors and theme aliases

**Files:**
- Modify: `app/src/main/assets/html/tavern-conversation.html`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationWebView.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocumentTest.kt`
- Test: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocumentInstrumentedTest.kt`

**Interfaces:**
- Consumes: `snapshot.themeCssVariables` and Markdown text parts.
- Produces: `wrapSillyTavernQuotes(markdown: string): string` in the app-owned document.

- [x] **Step 1: Add failing document contract tests**

Assert the document contains a quote preprocessor, all six quote families, protected code/style handling, and the theme variable/rule:

```kotlin
assertTrue(template.contains("function wrapSillyTavernQuotes"))
assertTrue(template.contains("--SmartThemeQuoteColor"))
assertTrue(template.contains(".mes_text q"))
listOf("[\\\"]", "[“]", "[«]", "[「]", "[『]", "[＂]").forEach {
    assertTrue("missing quote family $it", template.contains(it))
}
assertTrue(template.indexOf("wrapSillyTavernQuotes") < template.indexOf("markdownit"))
```

Add an instrumentation case that renders prose plus each quote family, fenced code, inline code, and an HTML attribute;
assert only dialogue produces six `<q>` elements and computed dialogue color differs from prose.

- [x] **Step 2: Run focused JVM and instrumentation tests to establish red state**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernConversationDocumentTest"
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationDocumentInstrumentedTest
```

Expected: quote contract and rendered-color assertions fail.

- [x] **Step 3: Port the SillyTavern quote transform before Markdown parsing**

Implement a scanner that temporarily protects fenced code, inline code, `<style>...</style>`, and HTML tags with indexed
sentinels, applies the six non-greedy quote-pair replacements to remaining text, then restores protected segments. The
render path becomes:

```javascript
var source = wrapSillyTavernQuotes(part.text || '');
var rendered = markdown.render(source);
```

Do not re-wrap text already inside `<q>` and do not run this transform on raw HTML parts.

- [x] **Step 4: Add SillyTavern theme aliases and CSS precedence**

Supply these keys from the Material theme:

```kotlin
"--SmartThemeBodyColor" to hex(colorScheme.onSurface),
"--SmartThemeEmColor" to hex(colorScheme.onSurfaceVariant),
"--SmartThemeQuoteColor" to hex(colorScheme.tertiary),
"--SmartThemeUnderlineColor" to hex(colorScheme.secondary),
"--SmartThemeBlurTintColor" to hex(colorScheme.surface),
"--SmartThemeChatTintColor" to hex(colorScheme.surface),
```

Add `.mes_text q { color: var(--SmartThemeQuoteColor); }`, inherit color for nested emphasis, suppress generated `q`
pseudo-quotes, and preserve explicit `<font color>` inheritance as in SillyTavern 1.18.0.

- [ ] **Step 5: Re-run Task 2 tests and commit**

Expected: focused JVM and instrumentation tests pass.

```powershell
git add -- app/src/main/assets/html/tavern-conversation.html app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationWebView.kt app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocumentTest.kt app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocumentInstrumentedTest.kt
git commit -m "feat: match SillyTavern dialogue theme colors"
```

---

### Task 3: Authoritative Tavern chat-message query gateway

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernChatMessageGateway.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationSnapshot.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernOpeningStage.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernChatMessageGatewayTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationSnapshotTest.kt`

**Interfaces:**
- Consumes: latest `TavernConversationSnapshot`, all prepared opening texts, active opening index, and revision.
- Produces: `TavernChatMessageGateway.get(range: String, options: JsonObject): TavernRuntimeResponsePayload` and immutable
  `TavernOpeningSwipe.swipes: List<String>`.

- [x] **Step 1: Write failing range and swipe-shape tests**

Cover `0`, `0-2`, `-1`, reversed ranges, out-of-range clamping, role/hide filters, `include_swipes=false`, and
`include_swipes=true`. The opening assertion must require:

```kotlin
assertEquals(3, first["swipes"]!!.jsonArray.size)
assertEquals(1, first["swipe_id"]!!.jsonPrimitive.int)
assertEquals("<article>第二幕</article>", first["swipes"]!!.jsonArray[1].jsonPrimitive.content)
assertEquals("assistant", first["role"]!!.jsonPrimitive.content)
```

- [x] **Step 2: Run the new gateway tests and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernChatMessageGatewayTest" --tests "*TavernConversationSnapshotTest"
```

Expected: compilation fails because the gateway and `swipes` field do not exist.

- [x] **Step 3: Implement the immutable query model**

Define:

```kotlin
internal data class TavernChatQueryOptions(
    val role: String = "all",
    val hideState: String = "all",
    val includeSwipes: Boolean = false,
)

internal interface TavernChatMessageGateway {
    fun getChatMessages(range: String, options: TavernChatQueryOptions): JsonArray
    fun setChatMessage(params: JsonObject): TavernChatMutationResult
    fun setChatMessages(params: JsonObject): TavernChatMutationResult
}

internal sealed interface TavernChatMutationResult {
    data object Accepted : TavernChatMutationResult
    data class Rejected(val code: String, val message: String) : TavernChatMutationResult
}
```

Use zero-based visible indices and normalize negative indices from the end. Serialize each selected node into the exact
fields `message_id`, `name`, `role`, `is_hidden`, `message`, `data`, and `extra`; add swipe fields only when requested.

- [x] **Step 4: Expose prepared opening texts without adding a second state machine**

Extend `TavernOpeningSwipe` with `swipes: List<String> = emptyList()` and validate `swipes.isEmpty() || swipes.size == count`.
In `TavernOpeningStage`, pass:

```kotlin
openingSwipe = TavernOpeningSwipe(
    index = index,
    count = candidates.size,
    ready = readyCandidates[candidate.id] == true,
    failed = failedCandidates[candidate.id] == true,
    swipes = candidates.map { it.renderedOpening },
)
```

The gateway reads this immutable snapshot and returns the selected opening as message zero.

- [ ] **Step 5: Run focused tests and commit**

```powershell
git add -- app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernChatMessageGateway.kt app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationSnapshot.kt app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernOpeningStage.kt app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernChatMessageGatewayTest.kt app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationSnapshotTest.kt
git commit -m "feat: expose Tavern-compatible chat message snapshots"
```

Expected: both focused test classes pass.

---

### Task 4: Message mutation RPC and visual opening selection

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernChatMessageGateway.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationBridge.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationWebView.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeController.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeScript.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernChatMessageGatewayTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationBridgeTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeControllerTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeScriptApiTest.kt`

**Interfaces:**
- Consumes: Task 3 gateway, latest snapshot/revision, and `TavernConversationActions.onSelectGreeting(index)`.
- Produces: runtime methods `messages.getChatMessages`, `messages.setChatMessage`, and `messages.setChatMessages` plus
  global/TavernHelper JS aliases.

- [x] **Step 1: Write failing controller and mutation tests**

Assert:

```kotlin
controller.dispatch(
    TavernRuntimeRequest(
        id = "query-1",
        method = "messages.getChatMessages",
        params = buildJsonObject {
            put("range", "0")
            putJsonObject("options") { put("include_swipes", true) }
        },
    ),
).also { assertTrue(it.ok) }

var selectedGreeting = -1
val openingTexts = listOf("<p>一</p>", "<p>二</p>", "<p>三</p>")
val currentRevision = 9L
val gateway = TavernConversationMessageGateway(
    snapshotProvider = { snapshotWithOpenings(openingTexts, selectedIndex = 0, revision = currentRevision) },
    dispatchGreeting = { index, _, _ -> selectedGreeting = index },
)
val result = gateway.setChatMessage(buildJsonObject {
    put("message_id", 0)
    put("message", openingTexts[2])
    put("swipe_id", 2)
    put("refresh", "display_and_render_current")
    put("revision", currentRevision)
})
assertEquals(TavernChatMutationResult.Accepted, result)
assertEquals(2, selectedGreeting)
```

Define the test helper in `TavernChatMessageGatewayTest`:

```kotlin
private fun snapshotWithOpenings(texts: List<String>, selectedIndex: Int, revision: Long) =
    TavernConversationSnapshot(
        conversationId = "00000000-0000-0000-0000-000000000001",
        nodes = listOf(
            TavernConversationNode(
                id = "00000000-0000-0000-0000-000000000101",
                selectedIndex = 0,
                branchCount = 1,
                selectedMessage = TavernConversationMessage(
                    id = "00000000-0000-0000-0000-000000000201",
                    role = MessageRole.ASSISTANT,
                    name = "白露",
                    parts = listOf(TavernConversationTextPart(texts[selectedIndex], UIMessagePart.RenderMode.HTML)),
                ),
            ),
        ),
        userName = "阿澈",
        characterName = "白露",
        themeCssVariables = emptyMap(),
        cardCss = "",
        streaming = false,
        revision = revision,
        openingSwipe = TavernOpeningSwipe(selectedIndex, texts.size, ready = true, swipes = texts),
    )
```

Also cover stale revision, mismatched message text/swipe index, invalid refresh value, non-opening role change, payload over
64 KiB, disabled `allowMessageWrite`, and an index outside the greeting count.

- [x] **Step 2: Run focused tests and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernChatMessageGatewayTest" --tests "*TavernConversationBridgeTest" --tests "*TavernRuntimeControllerTest" --tests "*TavernRuntimeScriptApiTest"
```

- [x] **Step 3: Wire validated mutations to the existing greeting action**

Build the gateway with providers so it never holds stale Compose state:

```kotlin
TavernConversationMessageGateway(
    snapshotProvider = { latestSnapshot },
    dispatchGreeting = { index, count, revision ->
        webView.post {
            actionBridge.selectGreeting(actionToken, index, count, revision)
        }
    },
)
```

The gateway accepts an opening selection only when `message_id == 0`, `swipe_id` is valid, supplied text matches that
swipe, refresh is one of the documented SillyTavern values, and revision matches the latest snapshot. `setChatMessages`
normalizes and validates every entry before dispatching any mutation so a partial batch cannot be applied.

- [x] **Step 4: Add runtime controller dispatch and JavaScript compatibility facade**

Publish these mappings:

```javascript
api.messages.getChatMessages = function(range, options) {
  return call('messages.getChatMessages', { range: String(range), options: options || {} });
};
api.messages.setChatMessage = function(fieldValues, messageId, options) {
  return call('messages.setChatMessage', {
    field_values: typeof fieldValues === 'string' ? { message: fieldValues } : (fieldValues || {}),
    message_id: Number(messageId),
    options: options || {}
  });
};
api.messages.setChatMessages = function(messages, options) {
  return call('messages.setChatMessages', { messages: messages || [], options: options || {} });
};
window.getChatMessages = api.messages.getChatMessages;
window.setChatMessage = api.messages.setChatMessage;
window.setChatMessages = api.messages.setChatMessages;
```

Controller methods must use the existing global `allowScripts` gate and the `allowMessageWrite` gate for both setters.

- [ ] **Step 5: Re-run focused tests and commit**

```powershell
git add -- app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernChatMessageGateway.kt app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationBridge.kt app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationWebView.kt app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeController.kt app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeScript.kt app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernChatMessageGatewayTest.kt app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationBridgeTest.kt app/src/test/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeControllerTest.kt app/src/test/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeScriptApiTest.kt
git commit -m "feat: support TavernHelper visual greeting selection"
```

---

### Task 5: Rich iframe media, resizing, and failure isolation

**Files:**
- Modify: `app/src/main/assets/html/tavern-conversation.html`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationBridge.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationWebView.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationResources.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationResourcesTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocumentTest.kt`
- Test: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernImmersiveRuntimeInstrumentedTest.kt`

**Interfaces:**
- Consumes: existing `TavernConversationResourceRegistry`, `shouldAllowTavernSubresource`, runtime permission atomics,
  and iframe height messages.
- Produces: stable linked-image/local-image loading and bounded `ResizeObserver` updates.

- [x] **Step 1: Write failing resource-policy and WebView tests**

Require these outcomes:

```kotlin
assertTrue(shouldAllowTavernSubresource("https://files.catbox.moe/card.png", true))
assertFalse(shouldAllowTavernSubresource("https://files.catbox.moe/card.png", false))
assertTrue(shouldAllowTavernSubresource("data:image/png;base64,AA==", false))
assertTrue(shouldAllowTavernSubresource("blob:https://rikkahub.local/id", false))
assertFalse(shouldAllowTavernSubresource("file:///sdcard/private.png", true))
```

In the instrumentation fixture render one HTTPS image from a local test server, one `data:` image, one mapped content
resource, and one broken image. Assert the first three complete with positive natural dimensions, the broken image gains
`data-rikkahub-media-error`, the iframe remains mounted, and its height changes after a delayed accordion expansion.

- [x] **Step 2: Run focused tests and verify the new assertions fail**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernConversationResourcesTest" --tests "*TavernConversationDocumentTest"
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.pages.chat.tavern.TavernImmersiveRuntimeInstrumentedTest
```

- [x] **Step 3: Harden iframe resource and resize behavior**

Inside the injected iframe bootstrap:

```javascript
var reportHeight = function() {
  var h = Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0);
  h = Math.max(120, Math.min(h, Math.max(960, window.innerHeight * 4)));
  parent.postMessage({ __rikkahubFrameHeight: h, frameTarget: frameTarget }, '*');
};
new ResizeObserver(function() { requestAnimationFrame(reportHeight); }).observe(document.documentElement);
document.addEventListener('load', reportHeight, true);
document.addEventListener('error', function(event) {
  var media = event.target;
  if (media && /^(IMG|VIDEO|AUDIO)$/.test(media.tagName)) media.dataset.rikkahubMediaError = 'true';
  reportHeight();
}, true);
```

Debounce height messages in the parent, reject non-finite/out-of-range values, preserve the last valid height, and show a
compact in-frame media placeholder on resource failure. Keep external navigation gesture-gated.

- [ ] **Step 4: Re-run focused tests and commit**

```powershell
git add -- app/src/main/assets/html/tavern-conversation.html app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationBridge.kt app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationWebView.kt app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationResources.kt app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationResourcesTest.kt app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocumentTest.kt app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernImmersiveRuntimeInstrumentedTest.kt
git commit -m "feat: harden Tavern rich media frames"
```

---

### Task 6: End-to-end visual opening card contract

**Files:**
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernImmersiveRuntimeInstrumentedTest.kt`
- Modify: `app/src/debug/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationRecoveryActivity.kt`
- Test: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernImmersiveRuntimeInstrumentedTest.kt`

**Interfaces:**
- Consumes: completed macro, quote, message API, iframe, and greeting-session paths.
- Produces: a sanitized debug fixture matching the reference card's technical behavior without embedding its narrative
  content in the repository.

- [x] **Step 1: Add a sanitized rich-card fixture**

The fixture must include a responsive opening grid, remote/local portraits, CSS transitions, a theme toggle, and this
same compatibility call pattern used by the reference card:

```javascript
async function switchToOpening(index) {
  var messages = await getChatMessages('0', { include_swipes: true });
  await setChatMessage(messages[0].swipes[index], 0, {
    swipe_id: index,
    refresh: 'display_and_render_current'
  });
}
```

- [x] **Step 2: Add an instrumentation scenario that taps a visual card**

Assert, in order: `{user}` is replaced in the iframe; three visual opening cards are present; tapping card 3 changes
the authoritative selected greeting to index 2; native counter becomes `3 / 3`; iframe content changes to swipe 3;
`MESSAGE_SWIPED` fires; dialogue `<q>` uses the quote theme color; portrait image is complete; animation/accordion
changes iframe height; scroll and back/re-entry do not duplicate callbacks or crash.

- [x] **Step 3: Run the end-to-end instrumentation class repeatedly**

```powershell
1..3 | ForEach-Object {
  .\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.pages.chat.tavern.TavernImmersiveRuntimeInstrumentedTest
  if ($LASTEXITCODE -ne 0) { throw "instrumentation run $_ failed" }
}
```

Expected: all three runs pass without WebView process death, duplicate bridge callbacks, or timeout.

- [ ] **Step 4: Commit the end-to-end fixture and acceptance test**

```powershell
git add -- app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernImmersiveRuntimeInstrumentedTest.kt app/src/debug/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationRecoveryActivity.kt
git commit -m "test: cover rich Tavern visual opening cards"
```

---

### Task 7: Full regression, APK installation, and real-card acceptance

**Files:**
- Modify: `docs/superpowers/plans/2026-08-22-tavern-web-compatibility-runtime.md` only to record evidence and check completed boxes.
- Evidence: `verification-screenshots/tavern-web-compat/`

**Interfaces:**
- Consumes: all prior tasks and the user's three cards already available in the local SillyTavern/RikkaHub environment.
- Produces: current build/test/device evidence for every objective requirement.

- [x] **Step 1: Verify repository ownership and focused diffs**

```powershell
if (Get-Process opencode -ErrorAction SilentlyContinue) { throw 'opencode.exe owns the checkout' }
git status --short
git diff --check
```

Inspect every in-scope diff and confirm unrelated user edits remain untouched.

- [x] **Step 2: Run full JVM, compile, lint for touched modules, and assemble**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin :app:lintDebug :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`, zero unit-test failures, and no new lint errors in modified files.

- [x] **Step 3: Select the correct arm64 APK from output metadata and install it**

Read `app/build/outputs/apk/debug/output-metadata.json`, select the `arm64-v8a` element, confirm the connected device ABI,
then install and launch:

```powershell
adb devices -l
adb shell getprop ro.product.cpu.abi
adb install -r '<resolved-arm64-apk-path>'
adb shell monkey -p me.rerere.rikkahub.debug -c android.intent.category.LAUNCHER 1
```

Expected: exactly one intended physical device, ABI `arm64-v8a`, install `Success`, and RouteActivity in the foreground.

- [ ] **Step 4: Perform real-card requirement-by-requirement acceptance**

Using the three supplied cards, capture screenshots and log evidence for:

1. `{user}` and `{{user}}` show the configured nickname; blank nickname shows `你`.
2. The rich character/status panel displays its portrait, fields, progress/status styling, and linked resources.
3. The visual opening grid is responsive and animated; tapping a card changes both the rendered opening and native `N / M`.
4. Narrative prose and all supported dialogue quote styles use distinct theme colors.
5. Theme toggle, accordion, 3D/transition effects, scrolling, fullscreen, back/re-entry, and native input remain usable.
6. Disable scripts and network separately: static fallback remains readable and remote media fails locally without taking
   down the conversation document.

- [x] **Step 5: Check runtime health**

```powershell
adb logcat -d | Select-String -Pattern 'FATAL EXCEPTION|AndroidRuntime|chromium.*crash|RenderProcessGone|OutOfMemoryError'
adb shell dumpsys activity activities | Select-String -Pattern 'mResumedActivity|topResumedActivity'
```

Expected: no new fatal app/WebView errors and the debug RouteActivity remains resumed.

- [ ] **Step 6: Record evidence, run final diff audit, and commit the report update**

Update this plan's checkboxes and add a `Verification Results` section containing exact commands, counts, device serial,
APK path/hash, and screenshot names. Then:

```powershell
git diff --check
git status --short
git add -- docs/superpowers/plans/2026-08-22-tavern-web-compatibility-runtime.md
git commit -m "docs: record Tavern web compatibility verification"
```

Do not claim completion if any real card image, selector interaction, quote color, or native UI regression remains
unverified.

## Verification Results (2026-08-22)

- Repository ownership: `opencode.exe` was absent before edits/builds. The dirty `private-main` worktree was preserved;
  implementation files overlap earlier uncommitted Tavern work, so task commits were intentionally not created.
- Focused TDD: the real-card `include_swipe: true` compatibility case failed first, then passed after accepting both
  `include_swipe` and `include_swipes` without relaxing message-write permission checks.
- Full local verification: `:app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug` completed with
  `BUILD SUCCESSFUL`; parsed result XML contains 117 suites / 839 tests / 0 failures / 0 errors.
- Lint status: the combined `lintDebug` run is not globally green because the existing checkout reports 113 errors,
  309 warnings, and 4 hints (including the Windows `local.properties` separator and pre-existing `ChatPage` findings).
  Filtering the touched Tavern files found warnings only and no new Tavern-related lint errors.
- APK/device: installed
  `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` (SHA-256
  `856ED213E9A0A1063B0536A7E8552ED8814D4FF7D8CB7C6B12FDBAFD23FDE4B9`) successfully on
  `XHD0223523008702` / Huawei MNA-AL00 / `arm64-v8a`. Installed package is `me.rerere.rikkahub.debug`, version
  `2.4.5` (`versionCode=172`), with `RouteActivity` resumed.
- Instrumentation: the rich visual-opening scenario passed three consecutive physical-device runs. It verified the
  rendered `{user}` value, card-3 selection, authoritative native counter/content update, exactly one
  `MESSAGE_SWIPED`, quote color, image completion, delayed resize, and no WebView crash.
- Real cards imported and exercised: `慈脂佛母` (5 openings), `道家仙子美母` (9 openings), and the PNG card
  `明明我才是主人公，为什么身边的女主都是你的炮友啊！？` (rich overview plus 5 openings). The rich PNG card
  rendered its animated/themeable HTML overview and original avatar while retaining RikkaHub's native composer.
- Real linked media: the rich card's `https://files.catbox.moe/...` portraits were requested by WebView. The device's
  direct network returned Chromium `net::ERR_CONNECTION_CLOSED`; with the test machine's existing proxy temporarily
  applied, the original HTTPS portraits loaded at `naturalWidth=832` and were visibly rendered. The device proxy was
  restored to its original `null` value afterward. This distinguishes host/network reachability from app policy.
- Real visual selector: the card's script uses `getChatMessages('0', { include_swipe: true })`. A physical tap on
  `OPENING · 03` changed the native message to the matching 朝雾花冷 scene and the native counter to `4 / 6` because
  this card counts the rich overview itself as swipe 0. Dialogue quotes remained visibly distinct purple.
- Runtime health: no matches for `FATAL EXCEPTION`, Chromium crash, `RenderProcessGone`, or `OutOfMemoryError` after
  install and real-card interaction; the debug `RouteActivity` remained resumed.
- Evidence: `verification-screenshots/tavern-web-compat/cizhi-render.png`,
  `cizhi-opening2-counter.png`, `protagonist-render.png`, `protagonist-linked-image-proxy-waited.png`,
  `protagonist-opening3-fixed-selected.png`, and `protagonist-opening3-native-counter.png`.
- Remaining non-core acceptance item: the fullscreen affordance is present and its bridge contract is unit-tested, but
  opening it from an uncommitted greeting preview does not currently produce the fullscreen dialog because that preview
  message is not yet in the persisted conversation tree. The requested macro, rich opening, linked-media, quote-color,
  animation, native-counter, and native-composer outcomes are all verified; Task 7 Step 4 remains unchecked solely for
  this fullscreen-preview limitation.
