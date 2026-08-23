# Tavern Opening Visual Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Polish the Tavern greeting picker so failed avatars degrade cleanly, the floating HUD shows useful candidate state, and greeting navigation remains visible for long openings.

**Architecture:** Keep candidate state ownership in `TavernOpeningStage`. Derive the compact HUD label as a pure Kotlin presentation function, while keeping WebView-only avatar and navigation behavior in `tavern-conversation.html`. Preserve character-card CSS and the expanded status renderer.

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx.serialization JSON, embedded HTML/CSS/JavaScript, JUnit 4, Gradle, ADB on Huawei MNA-AL00.

## Global Constraints

- Preserve the character card's rich status panel and injected CSS.
- Do not modify card content, image assets, worldbook data, candidate commit semantics, variable isolation, or script permissions.
- Failed avatar images must never expose the browser broken-image icon or alt text.
- Greeting position and navigation must remain visible regardless of opening length.
- Verify on Huawei device `XHD0223523008702`, not an emulator.
- Preserve unrelated dirty-worktree changes.
- Do not commit implementation files in this pass: the HTML and both target test files already contain overlapping user changes; report the exact diff instead.

---

### Task 1: Candidate-aware compact HUD summary

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/StatusHudPresentation.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/StatusHudPresentationTest.kt`

**Interfaces:**
- Consumes: `Conversation.statusVariables: JsonObject`, extracted status-block header.
- Produces: `resolveStatusHudHeaderLine(statusVariables: JsonObject, extractedHeader: String?): String`.

- [ ] **Step 1: Write failing presentation tests**

Add tests proving direct and `stat_data`-wrapped variables resolve to a useful summary and missing variables preserve the extracted header:

```kotlin
@Test
fun `compact hud prefers current time and location from candidate variables`() {
    val variables = buildJsonObject {
        put("世界", buildJsonObject {
            put("当前时间", "申时")
            put("当前地点", "顾家镇·潘寡妇宅")
        })
    }
    assertEquals("申时 · 顾家镇·潘寡妇宅", resolveStatusHudHeaderLine(variables, "状态栏"))
}

@Test
fun `compact hud reads SillyTavern stat data wrapper and falls back safely`() {
    val wrapped = buildJsonObject {
        put("stat_data", buildJsonObject {
            put("世界", buildJsonObject { put("当前时间", "辰时") })
        })
    }
    assertEquals("辰时", resolveStatusHudHeaderLine(wrapped, null))
    assertEquals("『最新状态』", resolveStatusHudHeaderLine(buildJsonObject {}, "『最新状态』"))
}
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'me.rerere.rikkahub.ui.pages.chat.StatusHudPresentationTest' --console=plain
```

Expected: compilation fails because `resolveStatusHudHeaderLine` does not exist.

- [ ] **Step 3: Implement the pure summary resolver and wire it into the presentation**

Add a resolver that accepts both direct variables and the SillyTavern `stat_data` wrapper, reads string primitives only, joins nonblank time and location with ` · `, and falls back without throwing:

```kotlin
internal fun resolveStatusHudHeaderLine(
    statusVariables: JsonObject,
    extractedHeader: String?,
): String {
    val root = statusVariables["stat_data"]?.jsonObjectOrNull ?: statusVariables
    val world = root["世界"]?.jsonObjectOrNull
    val time = world?.get("当前时间")?.jsonPrimitiveOrNull?.contentOrNull?.trim().orEmpty()
    val location = world?.get("当前地点")?.jsonPrimitiveOrNull?.contentOrNull?.trim().orEmpty()
    return listOf(time, location).filter(String::isNotBlank).joinToString(" · ")
        .ifBlank { extractedHeader?.takeIf(String::isNotBlank) ?: "状态栏" }
}
```

Set `StatusHudPresentation.headerLine` with:

```kotlin
headerLine = resolveStatusHudHeaderLine(conversation.statusVariables, extraction.headerLine)
```

- [ ] **Step 4: Run the presentation tests and verify GREEN**

Run the Step 2 command. Expected: all `StatusHudPresentationTest` tests pass.

### Task 2: Avatar fallback and stable greeting navigation

**Files:**
- Modify: `app/src/main/assets/html/tavern-conversation.html`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocumentTest.kt`

**Interfaces:**
- Consumes: message name, optional avatar emoji/URL, `state.openingSwipe`.
- Produces: `createAvatarFallback(messageName, avatarEmoji): HTMLSpanElement` and stable opening CSS selectors.

- [ ] **Step 1: Write failing document-contract tests**

Add tests for the image failure path and fixed navigation contract:

```kotlin
@Test
fun `failed avatar is replaced with a one character fallback`() {
    assertTrue(template.contains("function createAvatarFallback(messageName, avatarEmoji)"))
    assertTrue(template.contains("avatar.alt = ''"))
    assertTrue(template.contains("avatar.addEventListener('error'"))
    assertTrue(template.contains("avatar.replaceWith(createAvatarFallback(message.name, avatarEmoji))"))
}

@Test
fun `opening navigation stays pinned inside the visible viewport`() {
    val openingCss = template.substringAfter(".mes.opening-swipe {")
        .substringBefore("@keyframes opening-enter-forward")
    assertTrue(openingCss.contains(".mes.opening-swipe .swipes-counter"))
    assertTrue(openingCss.contains("position: fixed"))
    assertTrue(openingCss.contains("bottom: 12px"))
    assertTrue(openingCss.contains("top: 50%"))
    assertTrue(openingCss.contains("min-width: 44px"))
}
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationDocumentTest' --console=plain
```

Expected: the new avatar fallback and pinned-navigation assertions fail.

- [ ] **Step 3: Implement avatar fallback without duplicate DOM logic**

Add one JavaScript helper near `renderNode`:

```javascript
function createAvatarFallback(messageName, avatarEmoji) {
  var fallback = document.createElement('span');
  fallback.className = 'avatar';
  fallback.textContent = avatarEmoji || String(messageName || '?').trim().slice(0, 1).toUpperCase() || '?';
  fallback.setAttribute('aria-hidden', 'true');
  return fallback;
}
```

Use it for both missing URLs and image errors:

```javascript
if (avatarUrl) {
  var avatar = document.createElement('img');
  avatar.className = 'avatar';
  avatar.alt = '';
  avatar.addEventListener('error', function () {
    avatar.replaceWith(createAvatarFallback(message.name, avatarEmoji));
  }, { once: true });
  avatar.src = avatarUrl;
  avatarWrapper.appendChild(avatar);
} else {
  avatarWrapper.appendChild(createAvatarFallback(message.name, avatarEmoji));
}
```

- [ ] **Step 4: Pin the opening controls and refine opening-only rhythm**

Change only `.mes.opening-swipe` rules:

```css
.mes.opening-swipe { display: block; padding-top: 8px; padding-bottom: 54px; }
.mes.opening-swipe .name_text { min-height: 46px; margin-left: 60px; margin-bottom: 6px; }
.mes.opening-swipe .mes_text { line-height: 1.62; }
.mes.opening-swipe .swipe_left,
.mes.opening-swipe .swipe_right {
  position: fixed;
  top: 50%;
  z-index: 4;
  min-width: 44px;
  width: 44px;
  height: 52px;
  margin: 0;
  border: 1px solid var(--rikkahub-border);
  background: color-mix(in srgb, var(--rikkahub-surface) 92%, transparent);
  opacity: .78;
  transform: translateY(-50%);
}
.mes.opening-swipe .swipes-counter {
  position: fixed;
  bottom: 12px;
  left: 50%;
  z-index: 4;
  min-height: 24px;
  border: 1px solid var(--rikkahub-border);
  border-radius: 999px;
  padding: 4px 12px;
  background: color-mix(in srgb, var(--rikkahub-surface) 94%, transparent);
  transform: translateX(-50%);
}
```

Keep the existing focus, active, disabled, reduced-motion, forward, and backward rules.

- [ ] **Step 5: Run the document tests and verify GREEN**

Run the Step 2 command. Expected: all `TavernConversationDocumentTest` tests pass.

### Task 3: Integrated build and Huawei visual verification

**Files:**
- Verify: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
- Create evidence: `verification-screenshots/huawei-opening-visual-1.png`
- Create evidence: `verification-screenshots/huawei-opening-visual-3.png`
- Create evidence: `verification-screenshots/huawei-opening-visual-3-status.png`
- Create evidence: `verification-screenshots/huawei-opening-visual-5.png`

**Interfaces:**
- Consumes: Tasks 1 and 2 behavior.
- Produces: built APK, installed app, screenshot and log evidence.

- [ ] **Step 1: Run related regression tests and build the APK**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest `
  --tests 'me.rerere.rikkahub.ui.pages.chat.StatusHudPresentationTest' `
  --tests 'me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationDocumentTest' `
  --tests 'me.rerere.rikkahub.service.tavern.TavernGreetingSessionTest' `
  :app:assembleDebug --console=plain
```

Expected: `BUILD SUCCESSFUL` and zero failed tests.

- [ ] **Step 2: Confirm the Huawei target and install**

Run:

```powershell
adb devices -l
adb -s XHD0223523008702 install -r app\build\outputs\apk\debug\app-arm64-v8a-debug.apk
adb -s XHD0223523008702 shell monkey -p me.rerere.rikkahub.debug -c android.intent.category.LAUNCHER 1
```

Expected: only the Huawei `MNA_AL00` target is used and installation returns `Success`.

- [ ] **Step 3: Capture and inspect the full greeting flow**

Capture 1/5, switch to 3/5, expand the HUD, switch to 5/5, then return to 1/5. Inspect every image for:

- one-character avatar fallback with no broken-image icon or long alt text;
- useful compact summary such as `申时 · 顾家镇·潘寡妇宅`;
- visible bottom page pill on long content;
- stable side controls and correct candidate content;
- rich status panel still rendering card CSS and live variables.

- [ ] **Step 4: Inspect the app process log**

Run:

```powershell
$appProcessId = (adb -s XHD0223523008702 shell pidof me.rerere.rikkahub.debug).Trim()
adb -s XHD0223523008702 logcat -d --pid=$appProcessId | Select-String 'FATAL|AndroidRuntime'
```

Expected: no matching crash lines.

- [ ] **Step 5: Run diff hygiene checks**

Run:

```powershell
git diff --check -- `
  app/src/main/java/me/rerere/rikkahub/ui/pages/chat/StatusHudPresentation.kt `
  app/src/main/assets/html/tavern-conversation.html `
  app/src/test/java/me/rerere/rikkahub/ui/pages/chat/StatusHudPresentationTest.kt `
  app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocumentTest.kt
```

Expected: no whitespace errors.
