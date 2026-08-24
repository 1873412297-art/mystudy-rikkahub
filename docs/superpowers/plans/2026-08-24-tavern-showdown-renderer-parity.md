# Tavern Showdown Renderer Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make immersive Tavern Markdown messages use SillyTavern-compatible Showdown 2.1 semantics so custom XML wrappers stay structural and mixed `details`/fenced-code openings render correctly on the Huawei device.

**Architecture:** Bundle Showdown 2.1 into the existing local vendor asset set, then prefer one Showdown converter inside `tavern-conversation.html` while retaining MarkdownIt and plain-text fallbacks. Keep the current Kotlin snapshot/macro/regex data flow and DOMPurify boundary unchanged; prove actual DOM behavior in Android WebView before validating the real character card on Huawei MNA-AL00.

**Tech Stack:** Kotlin, JUnit 4, AndroidX Test, Android WebView, JavaScript, Showdown 2.1.0, MarkdownIt fallback, DOMPurify, pnpm, esbuild, Gradle, ADB.

## Global Constraints

- Pin Showdown to exactly `2.1.0`.
- Bundle every renderer asset inside the APK; no CDN or runtime renderer download.
- Preserve existing macro, display-regex, theme, opening-swipe, status-HUD, runtime, and native-action behavior.
- Every Showdown result must pass through the existing DOMPurify deny list.
- Keep MarkdownIt as the compatibility fallback and plain text as the terminal fallback.
- Do not route Markdown openings through script-enabled raw HTML iframes.
- Do not commit the real sensitive card text as a fixture.
- Verify the final arm64 APK on Huawei `XHD0223523008702` using the current real card.
- Preserve unrelated untracked files and do not clean or reset the shared worktree.

---

### Task 1: Bundle Showdown 2.1 in the existing local vendor set

**Files:**
- Modify: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocumentTest.kt`
- Modify: `web-ui/package.json`
- Modify: `web-ui/pnpm-lock.yaml`
- Modify: `web-ui/scripts/vendor-libs.mjs`
- Create: `app/src/main/assets/html/vendor/showdown.min.js`

**Interfaces:**
- Consumes: the existing `web-ui/scripts/vendor-libs.mjs` IIFE build loop and `TavernConversationDocument` stable asset inlining.
- Produces: `window.showdown` with `showdown.Converter` in every built Tavern conversation document.

- [ ] **Step 1: Add a failing vendor contract assertion**

In `bundled vendor set contains required local renderers`, add the exact assertion beside the existing MarkdownIt assertion:

```kotlin
assertTrue("showdown.min.js" in names)
```

- [ ] **Step 2: Run the focused JVM test and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationDocumentTest.bundled vendor set contains required local renderers' --console=plain
```

Expected: FAIL because `showdown.min.js` is absent from `app/src/main/assets/html/vendor`.

- [ ] **Step 3: Add the pinned package and IIFE build entry**

Run from `web-ui`:

```powershell
pnpm add --save-dev --save-exact showdown@2.1.0
```

In `web-ui/scripts/vendor-libs.mjs`, add Showdown before MarkdownIt so the generated global is available before the template initializes converters:

```javascript
const libs = [
  { entry: "showdown", global: "showdown" },
  { entry: "markdown-it", global: "MarkdownIt" },
  { entry: "dompurify", global: "DOMPurify" },
  { entry: "highlight.js", global: "hljs" },
  { entry: "markdown-it-task-lists", global: "MarkdownItTaskLists" },
  { entry: "katex", global: "katex" },
  { entry: "@vscode/markdown-it-katex", global: "vscodeKatex" },
  { entry: "mermaid", global: "mermaid" },
];
```

- [ ] **Step 4: Generate the local vendor file**

Run from `web-ui`:

```powershell
pnpm vendor:libs
```

Expected: output includes `built ...\showdown.min.js`, and `app/src/main/assets/html/vendor/showdown.min.js` is non-empty.

- [ ] **Step 5: Run the vendor contract and offline checks and verify GREEN**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationDocumentTest.bundled vendor set contains required local renderers' --console=plain
rg -n -i 'unpkg\.com|cdn\.jsdelivr|esm\.sh|cdnjs' app\src\main\assets\html\vendor\showdown.min.js
```

Expected: the Gradle test passes; `rg` prints no matches.

- [ ] **Step 6: Commit the vendor unit**

```powershell
git add -- web-ui/package.json web-ui/pnpm-lock.yaml web-ui/scripts/vendor-libs.mjs app/src/main/assets/html/vendor/showdown.min.js app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocumentTest.kt
git diff --cached --check
git commit -m "build: bundle Showdown for Tavern rendering"
```

Expected: one commit containing only the pinned dependency, generated vendor, build entry, and vendor contract.

---

### Task 2: Prove the current WebView failure, then migrate the immersive Markdown path

**Files:**
- Modify: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocumentTest.kt`
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernImmersiveRuntimeInstrumentedTest.kt`
- Modify: `app/src/main/assets/html/tavern-conversation.html`

**Interfaces:**
- Consumes: `window.showdown.Converter`, `window.MarkdownIt`, `window.DOMPurify`, `wrapSillyTavernQuotes(source: string): string`, and `renderMarkdownPart(part): HTMLElement`.
- Produces: `configureShowdown(): showdown.Converter|null`; `renderMarkdownPart` prefers `showdownConverter.makeHtml`, then `markdown.render`, then the existing text node.

- [ ] **Step 1: Add failing JVM pipeline contracts**

Add this test to `TavernConversationDocumentTest.kt`:

```kotlin
@Test
fun `immersive markdown prefers Showdown and preserves sanitized fallbacks`() {
    val configure = template.substringAfter("function configureShowdown()")
        .substringBefore("function configureMarkdown()")
    val renderer = template.substringAfter("function renderMarkdownPart(part)")
        .substringBefore("function protectQuotedMarkup")
    val enhancements = template.substringAfter("function runMarkdownEnhancements(scope)")
        .substringBefore("function applyDocumentStyle")

    assertTrue(configure.contains("new window.showdown.Converter"))
    assertTrue(configure.contains("literalMidWordUnderscores: true"))
    assertTrue(configure.contains("simpleLineBreaks: true"))
    assertTrue(renderer.contains("showdownConverter.makeHtml"))
    assertTrue(renderer.contains("markdown.render"))
    assertTrue(renderer.contains("window.DOMPurify.sanitize"))
    assertTrue(renderer.contains("FORBID_TAGS"))
    assertTrue(renderer.contains("FORBID_ATTR"))
    assertTrue(enhancements.contains("querySelectorAll('pre code')"))
    assertTrue(enhancements.contains("window.hljs.highlightElement(code)"))
    assertTrue(enhancements.contains("language-mermaid"))
    assertTrue(template.contains("showdownConverter = configureShowdown()"))
}
```

- [ ] **Step 2: Add a behavioral Android WebView regression test**

Add this import to `TavernImmersiveRuntimeInstrumentedTest.kt`:

```kotlin
import org.json.JSONTokener
```

Then add this test, reusing the file's existing `snapshot(...)` helper:

````kotlin
@Test
fun showdownRendersCustomTagOpeningAsStructuredSanitizedMarkdown() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val document = buildTavernConversationDocument(
        context = instrumentation.targetContext,
        initial = snapshot(
            conversationId = Uuid.parse("00000000-0000-0000-0000-000000000201"),
            nodeId = Uuid.parse("00000000-0000-0000-0000-000000000202"),
            messageId = Uuid.parse("00000000-0000-0000-0000-000000000203"),
            text = """
                <customize_HCI>
                <now_plot>
                <main_plot>
                # Opening
                </main_plot>
                <details><summary>Status</summary>

                ```body1
                hp: 10
                ```
                </details>
                ```javascript
                const hp = 10;
                ```
                ```mermaid
                graph TD; A-->B
                ```
                <script>window.__forbiddenOpeningScript=true</script>
                </now_plot>
                </customize_HCI>
            """.trimIndent(),
            renderMode = UIMessagePart.RenderMode.MARKDOWN,
        ),
    )
    val result = AtomicReference<JSONObject>()
    val completed = CountDownLatch(1)

    ActivityScenario.launch<TavernRuntimeSmokeActivity>(
        Intent(instrumentation.targetContext, TavernRuntimeSmokeActivity::class.java),
    ).use { scenario ->
        scenario.onActivity { activity ->
            val webView = WebView(activity).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript(
                            """
                            (function(){
                              var scope=document.querySelector('.mes_text');
                              return JSON.stringify({
                                text:scope ? scope.innerText : '',
                                headings:scope ? scope.querySelectorAll('h1').length : 0,
                                details:scope ? scope.querySelectorAll('details > summary').length : 0,
                                code:scope ? scope.querySelectorAll('pre code').length : 0,
                                highlighted:scope ? scope.querySelectorAll('pre code.hljs').length : 0,
                                mermaid:scope ? scope.querySelectorAll('.mermaid').length : 0,
                                forbidden:scope ? scope.querySelectorAll('script,iframe,object,embed,form').length : -1,
                                executed:window.__forbiddenOpeningScript === true
                              });
                            })();
                            """.trimIndent(),
                        ) { encoded ->
                            val decoded = JSONTokener(encoded).nextValue() as String
                            result.set(JSONObject(decoded))
                            completed.countDown()
                        }
                    }
                }
            }
            activity.setContentView(webView)
            webView.loadDataWithBaseURL(
                TAVERN_CONVERSATION_BASE_URL,
                document,
                "text/html",
                "UTF-8",
                null,
            )
        }

        assertTrue("structured Markdown probe timed out", completed.await(45, TimeUnit.SECONDS))
        val rendered = requireNotNull(result.get())
        assertFalse(rendered.getString("text").contains("<customize_HCI>"))
        assertFalse(rendered.getString("text").contains("<now_plot>"))
        assertFalse(rendered.getString("text").contains("<main_plot>"))
        assertEquals(1, rendered.getInt("headings"))
        assertEquals(1, rendered.getInt("details"))
        assertEquals(2, rendered.getInt("code"))
        assertEquals(1, rendered.getInt("highlighted"))
        assertEquals(1, rendered.getInt("mermaid"))
        assertEquals(0, rendered.getInt("forbidden"))
        assertFalse(rendered.getBoolean("executed"))
    }
}
````

- [ ] **Step 3: Run both tests and verify RED for the intended reasons**

Run the JVM contract:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationDocumentTest.immersive markdown prefers Showdown and preserves sanitized fallbacks' --console=plain
```

Expected: FAIL because `function configureShowdown()` and `showdownConverter` do not exist.

Run the WebView behavior test on the Huawei device:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.pages.chat.tavern.TavernImmersiveRuntimeInstrumentedTest#showdownRendersCustomTagOpeningAsStructuredSanitizedMarkdown --console=plain
```

Expected: FAIL because the accessible text contains `<customize_HCI>` or because the fenced code inside the custom wrapper does not produce `pre code`.

- [ ] **Step 4: Add the Showdown converter while retaining both fallbacks**

In `tavern-conversation.html`, declare both converter variables:

```javascript
var showdownConverter = null;
var markdown = null;
```

Insert this function immediately before the existing `configureMarkdown()`:

```javascript
function configureShowdown() {
  if (!window.showdown || typeof window.showdown.Converter !== 'function' || !window.DOMPurify) return null;
  try {
    return new window.showdown.Converter({
      emoji: true,
      literalMidWordUnderscores: true,
      parseImgDimensions: true,
      tables: true,
      underline: true,
      simpleLineBreaks: true,
      strikethrough: true,
      disableForced4SpacesIndentedSublists: true
    });
  } catch (_) {
    return null;
  }
}
```

Replace the beginning of `renderMarkdownPart(part)` through the `rendered` assignment with:

```javascript
function renderMarkdownPart(part) {
  var fallback = document.createElement('div');
  fallback.dataset.renderMode = 'markdown';
  fallback.textContent = part.text;
  if (!window.DOMPurify) return fallback;
  var source = wrapSillyTavernQuotes(part.text);
  var rendered = null;
  if (showdownConverter) {
    try { rendered = showdownConverter.makeHtml(source); } catch (_) {}
  }
  if (rendered === null && markdown) {
    try { rendered = markdown.render(source); } catch (_) {}
  }
  if (rendered === null) return fallback;
```

Keep the existing `DOMPurify.sanitize(rendered, { ... })` block unchanged after this replacement.

Replace `runMarkdownEnhancements(scope)` so Showdown fences retain the existing highlight and Mermaid behavior:

```javascript
function runMarkdownEnhancements(scope) {
  if (!scope) return;
  var codeNodes = Array.prototype.slice.call(scope.querySelectorAll('pre code'));
  codeNodes.forEach(function (code) {
    var classes = String(code.className || '');
    if (/(?:^|\s)(?:language-)?mermaid(?:\s|$)/.test(classes)) {
      var pre = code.closest('pre');
      if (!pre) return;
      var diagram = document.createElement('div');
      diagram.className = 'mermaid';
      diagram.textContent = code.textContent || '';
      pre.replaceWith(diagram);
      return;
    }
    if (!window.hljs || code.classList.contains('hljs')) return;
    var languageMatch = classes.match(/(?:^|\s)language-([^\s]+)/);
    if (languageMatch && !window.hljs.getLanguage(languageMatch[1])) return;
    try { window.hljs.highlightElement(code); } catch (_) {}
  });

  if (!window.mermaid) return;
  var mermaidNodes = scope.querySelectorAll('.mermaid:not([data-processed])');
  if (mermaidNodes.length === 0) return;
  try {
    var pending = window.mermaid.run({ nodes: mermaidNodes });
    if (pending && typeof pending.catch === 'function') pending.catch(function () {});
  } catch (_) {}
}
```

Initialize in preference order at the bottom of the template:

```javascript
showdownConverter = configureShowdown();
markdown = configureMarkdown();
initializeMermaid();
renderAll();
```

- [ ] **Step 5: Run focused JVM and WebView tests and verify GREEN**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationDocumentTest' --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.pages.chat.tavern.TavernImmersiveRuntimeInstrumentedTest#showdownRendersCustomTagOpeningAsStructuredSanitizedMarkdown --console=plain
```

Expected: all `TavernConversationDocumentTest` tests pass and the selected Android test reports `BUILD SUCCESSFUL` with zero failures.

- [ ] **Step 6: Run existing immersive regressions**

Run the full instrumentation class:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.pages.chat.tavern.TavernImmersiveRuntimeInstrumentedTest --console=plain
```

Expected: every existing immersive runtime, recovery, opening selection, action bridge, and new Showdown test passes.

- [ ] **Step 7: Commit the parser migration**

```powershell
git add -- app/src/main/assets/html/tavern-conversation.html app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocumentTest.kt app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernImmersiveRuntimeInstrumentedTest.kt
git diff --cached --check
git commit -m "fix: align Tavern Markdown with Showdown"
```

Expected: one focused commit containing the WebView behavior test, contracts, and template migration.

---

### Task 3: Build, install, and verify the real card on Huawei

**Files:**
- Verify: `app/build/outputs/apk/debug/output-metadata.json`
- Verify: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
- Create evidence: `verification-screenshots/opening-render-20260824/rikkahub-opening-showdown-2.png`
- Create evidence: `verification-screenshots/opening-render-20260824/rikkahub-opening-showdown-3.png`
- Create evidence: `verification-screenshots/opening-render-20260824/rikkahub-opening-showdown-dom.json`

**Interfaces:**
- Consumes: the local Showdown vendor, migrated template, current installed debug package, and current real “1.赛博机娘同化” card.
- Produces: a fresh arm64 APK installed on Huawei plus screenshot, DOM, version, and crash-log evidence.

- [ ] **Step 1: Run the integrated JVM suite and Debug build**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationDocumentTest' --tests 'me.rerere.rikkahub.service.tavern.TavernGreetingSessionTest' :app:assembleDebug --console=plain
```

Expected: `BUILD SUCCESSFUL`, no failed tests, and a newly timestamped `app-arm64-v8a-debug.apk`.

- [ ] **Step 2: Verify artifact/device identity and install only the arm64 split**

Run:

```powershell
adb devices -l
Get-Content -Raw app\build\outputs\apk\debug\output-metadata.json
Get-Item app\build\outputs\apk\debug\app-arm64-v8a-debug.apk | Select-Object FullName,Length,LastWriteTime
adb -s XHD0223523008702 install -r app\build\outputs\apk\debug\app-arm64-v8a-debug.apk
adb -s XHD0223523008702 shell monkey -p me.rerere.rikkahub.debug -c android.intent.category.LAUNCHER 1
```

Expected: the only selected target is Huawei `MNA_AL00`; metadata identifies `arm64-v8a`; install returns `Success`; `RouteActivity` launches.

- [ ] **Step 3: Reopen the current real greeting page and capture the complex openings**

Use the already-open app state. Select opening 2/4 and capture:

```powershell
adb -s XHD0223523008702 shell screencap -p /sdcard/Download/rikkahub-opening-showdown-2.png
adb -s XHD0223523008702 pull /sdcard/Download/rikkahub-opening-showdown-2.png verification-screenshots\opening-render-20260824\rikkahub-opening-showdown-2.png
```

Select opening 3/4 and capture:

```powershell
adb -s XHD0223523008702 shell screencap -p /sdcard/Download/rikkahub-opening-showdown-3.png
adb -s XHD0223523008702 pull /sdcard/Download/rikkahub-opening-showdown-3.png verification-screenshots\opening-render-20260824\rikkahub-opening-showdown-3.png
```

Expected: neither screenshot shows `<customize_HCI>`, `<now_plot>`, or `<main_plot>`; headings, paragraphs, collapsed status summaries, page pill, navigation, avatar fallback, and HUD remain visually usable.

- [ ] **Step 4: Read the live WebView DOM through DevTools**

Forward the active WebView socket:

```powershell
$appProcessId=(adb -s XHD0223523008702 shell pidof me.rerere.rikkahub.debug).Trim()
adb -s XHD0223523008702 forward tcp:9224 "localabstract:webview_devtools_remote_$appProcessId"
Invoke-RestMethod http://127.0.0.1:9224/json | ConvertTo-Json -Depth 6
```

Evaluate the visible opening target and store a compact JSON record containing only structural evidence:

```javascript
JSON.stringify({
  text: document.querySelector('.mes_text')?.innerText.slice(0, 200),
  leaked: /<(?:customize_HCI|now_plot|main_plot)>/.test(document.querySelector('.mes_text')?.innerText || ''),
  details: document.querySelectorAll('.mes_text details').length,
  code: document.querySelectorAll('.mes_text pre code').length,
  headings: document.querySelectorAll('.mes_text h1,h2,h3').length,
  forbidden: document.querySelectorAll('.mes_text script,.mes_text iframe,.mes_text object,.mes_text embed,.mes_text form').length,
  opening: window.__RIKKAHUB_TAVERN_CONVERSATION__?.openingSwipe
})
```

Save the returned compact structure as `rikkahub-opening-showdown-dom.json` using `apply_patch`; do not store the full real card source.

Expected for opening 2 or 3: `leaked=false`, `details=3`, `code=4`, `headings>=1`, `forbidden=0`, and the opening index matches the screenshot.

- [ ] **Step 5: Verify installed version, foreground activity, and crash-free process**

Run:

```powershell
adb -s XHD0223523008702 shell dumpsys package me.rerere.rikkahub.debug | Select-String 'versionName|versionCode|lastUpdateTime'
adb -s XHD0223523008702 shell dumpsys activity activities | Select-String 'mResumedActivity|topResumedActivity'
$appProcessId=(adb -s XHD0223523008702 shell pidof me.rerere.rikkahub.debug).Trim()
adb -s XHD0223523008702 logcat -d --pid=$appProcessId | Select-String 'FATAL|AndroidRuntime'
```

Expected: installed version matches `output-metadata.json`; `me.rerere.rikkahub.debug/me.rerere.rikkahub.RouteActivity` is resumed; crash filter prints no lines.

- [ ] **Step 6: Run final hygiene and requirement audit**

Run:

```powershell
git diff --check HEAD~2..HEAD
git status --short --branch
rg -n -i 'unpkg\.com|cdn\.jsdelivr|esm\.sh|cdnjs' app\src\main\assets\html\tavern-conversation.html app\src\main\assets\html\vendor\showdown.min.js
```

Audit every design completion criterion against fresh evidence: JVM test output, full immersive instrumentation output, APK build output, installed package metadata, both screenshots, compact DevTools DOM, and crash log. Do not mark the goal complete if any item is missing or indirect.

- [ ] **Step 7: Commit the compact non-sensitive verification record**

Screenshots and pulled card exports remain untracked evidence. If the compact DOM JSON contains no card prose, commit only that record:

```powershell
git add -- verification-screenshots/opening-render-20260824/rikkahub-opening-showdown-dom.json
git diff --cached --check
git commit -m "test: record Huawei Tavern renderer verification"
```

Expected: no real card text, image, or full DOM is added to Git.
