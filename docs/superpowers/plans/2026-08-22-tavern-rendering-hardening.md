# Tavern Rendering Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make immersive Tavern openings render full-width, complete long documents, resilient remote portraits/backgrounds, restrained motion, and stable scrolling on Android.

**Architecture:** Keep the single `TavernConversationWebView` and sandboxed raw-HTML iframe. Add a focused native image interceptor backed by the existing OkHttp client and a bounded cache, then stabilize iframe media/height updates without adding trusted capabilities.

**Tech Stack:** Kotlin, Compose, Android WebView, OkHttp, HTML/CSS/JavaScript, JUnit4, AndroidX Test, Gradle, ADB.

## Global Constraints

- Preserve SillyTavern HTML/CSS/JavaScript, status variables, opening swipes, and runtime APIs.
- The outer conversation WebView remains the only vertical gesture owner.
- Existing script/network permission gates and iframe sandboxes remain unchanged.
- No public third-party image proxy and no new dependency.
- Accept only HTTP(S) images up to 15 MiB; use a 48 MiB disk cache.
- Do not replace the native composer, app bar, or navigation.
- Never run `connectedDebugAndroidTest` for final acceptance because its uninstall phase can erase Debug app data.
- Check that `opencode.exe` is not running before every write, build, or install.

## File Map

- Create `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernRemoteMediaLoader.kt`: image request policy, OkHttp fetch/cache, coalescing, and WebView response.
- Create `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernRemoteMediaLoaderTest.kt`: pure policy and fake-fetcher tests.
- Modify `TavernConversationWebView.kt`: lifecycle and secure-client wiring.
- Modify `tavern-conversation.html`: full-width layout, reveal motion, single retry, stable height.
- Modify Tavern unit/instrumentation tests for contracts and visible behavior.

---

### Task 1: Full-Width and Complete Long-Card Baseline

**Files:**
- Modify: `app/src/main/assets/html/tavern-conversation.html`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocumentTest.kt`

**Interfaces:**
- Consumes: `state.openingSwipe`, `.mesAvatarWrapper`, `.mes_block`, and `__rikkahubFrameHeight`.
- Produces: `.mes.opening-swipe` and raw iframe height range `120..20000` CSS px.

- [ ] **Step 1: Add failing semantic tests**

```kotlin
@Test fun `opening uses full row below avatar`() {
    val css = template.substringAfter(".mes.opening-swipe {")
        .substringBefore("@keyframes opening-enter-forward")
    assertTrue(template.contains("mes.classList.add('opening-swipe')"))
    assertTrue(css.contains(".mes.opening-swipe .mes_block"))
    assertTrue(css.contains("width: 100%"))
    assertTrue(css.contains("position: absolute"))
}

@Test fun `raw frame expands to bounded complete document`() {
    val runtime = template.substringAfter("function injectIframeRuntime")
        .substringBefore("function suppressRepeatedRuntime")
    assertTrue(runtime.contains("Math.min(raw,20000)"))
    assertFalse(runtime.contains("window.innerHeight * 4"))
}
```

- [ ] **Step 2: Verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernConversationDocumentTest"
```

Expected: the new assertions fail before the template changes.

- [ ] **Step 3: Implement the layout and height contract**

```css
.mes.opening-swipe { display: block; }
.mes.opening-swipe .mesAvatarWrapper { position: absolute; top: 10px; left: 8px; }
.mes.opening-swipe .mes_block { width: 100%; }
.mes.opening-swipe .name_text { min-height: 50px; margin-left: 60px; }
.mes.opening-swipe .swipe_left,
.mes.opening-swipe .swipe_right { position: absolute; z-index: 3; }
.html-part { overflow: visible; }
```

```javascript
if (isOpeningSwipe) mes.classList.add('opening-swipe');
var height = Math.max(120, Math.min(rawHeight, 20000));
var maxHeight = frame.dataset.autoHeight === 'true' ? 5000 : 20000;
```

- [ ] **Step 4: Verify GREEN and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernConversationDocumentTest"
git add app/src/main/assets/html/tavern-conversation.html app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocumentTest.kt
git commit -m "fix: use full Tavern opening render area"
```

---

### Task 2: Permission-Aware Remote Image Loader

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernRemoteMediaLoader.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernRemoteMediaLoaderTest.kt`

**Interfaces:**
- Produces `intercept(rawUrl: String, requestHeaders: Map<String, String>): WebResourceResponse?` and `close()`.
- Produces `isLikelyTavernImageRequest(rawUrl: String, accept: String?): Boolean`.
- Produces `validateTavernImageMetadata(contentType: String?, contentLength: Long): String?`.

- [ ] **Step 1: Write failing policy tests**

```kotlin
@Test fun `only remote images are classified`() {
    assertTrue(isLikelyTavernImageRequest("https://host/portrait", "image/webp,*/*"))
    assertTrue(isLikelyTavernImageRequest("https://host/card.png", null))
    assertFalse(isLikelyTavernImageRequest("https://host/app.js", "*/*"))
    assertFalse(isLikelyTavernImageRequest("file:///sdcard/card.png", "image/*"))
}

@Test fun `metadata rejects html and oversized images`() {
    assertEquals("image/webp", validateTavernImageMetadata("image/webp", 1024))
    assertNull(validateTavernImageMetadata("text/html", 1024))
    assertNull(validateTavernImageMetadata("image/png", 15L * 1024 * 1024 + 1))
}
```

- [ ] **Step 2: Verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernRemoteMediaLoaderTest"
```

Expected: compilation fails because the loader does not exist.

- [ ] **Step 3: Add exact policy and payload types**

```kotlin
internal const val TAVERN_MEDIA_MAX_BYTES = 15L * 1024 * 1024
internal const val TAVERN_MEDIA_CACHE_BYTES = 48L * 1024 * 1024

internal data class TavernRemoteMediaPayload(
    val mimeType: String,
    val bytes: ByteArray,
    val responseHeaders: Map<String, String>,
)

internal fun interface TavernRemoteMediaFetcher {
    fun fetch(rawUrl: String, requestHeaders: Map<String, String>): TavernRemoteMediaPayload?
}
```

Classification accepts an HTTP(S) URL when `Accept` contains `image/` or its extension is png/jpg/jpeg/gif/webp/avif/svg. Metadata accepts only `image/*` and lengths from 0 through `TAVERN_MEDIA_MAX_BYTES`.

- [ ] **Step 4: Implement bounded OkHttp cache/fetch and coalescing**

```kotlin
val mediaClient = baseClient.newBuilder()
    .cache(Cache(File(cacheDir, "tavern_remote_media"), TAVERN_MEDIA_CACHE_BYTES))
    .connectTimeout(12, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .build()
```

The fetcher must validate the final URL scheme, successful status, MIME, declared length, and actual bytes read with a `TAVERN_MEDIA_MAX_BYTES + 1` sentinel. OkHttp's built-in redirect ceiling rejects excessive redirect chains. If the network call fails, retry once with `CacheControl.FORCE_CACHE`; never substitute one URL's cached body for another URL. Track only calls created by this loader and cancel those calls from `close()` without cancelling the shared application's OkHttp dispatcher. A `ConcurrentHashMap<String, FutureTask<TavernRemoteMediaPayload?>>` coalesces identical concurrent requests; each caller receives its own `ByteArrayInputStream`.

- [ ] **Step 5: Add a fake-fetcher coalescing test**

```kotlin
val calls = AtomicInteger()
val release = CountDownLatch(1)
val loader = TavernRemoteMediaLoader.forTest { _, _ ->
    calls.incrementAndGet()
    release.await(2, TimeUnit.SECONDS)
    TavernRemoteMediaPayload("image/png", byteArrayOf(1), emptyMap())
}
```

Start two executor requests for the same URL, release the latch, require two non-null responses and `calls.get() == 1`.

- [ ] **Step 6: Verify and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernRemoteMediaLoaderTest" --tests "*TavernConversationResourcesTest"
git add app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernRemoteMediaLoader.kt app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernRemoteMediaLoaderTest.kt
git commit -m "feat: add resilient Tavern remote media loader"
```

---

### Task 3: Secure WebView Integration

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationWebView.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationBridgeTest.kt`

**Interfaces:**
- Consumes: loader from Task 2 and existing local resource/network gates.
- Produces order: local resource, permission rejection, remote image loader, ordinary WebView.

- [ ] **Step 1: Add a failing route-decision test**

```kotlin
internal enum class TavernSubresourceRoute { LOCAL, BLOCKED, REMOTE_MEDIA, WEBVIEW }
```

Test local URLs with network off, remote images with network off/on, unsafe `file:` URLs, and allowed scripts. Require `LOCAL`, `BLOCKED`, `REMOTE_MEDIA`, and `WEBVIEW` respectively.

- [ ] **Step 2: Verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernConversationBridgeTest"
```

- [ ] **Step 3: Create one loader per immersive WebView**

```kotlin
val httpClient: OkHttpClient = koinInject()
val mediaLoader = remember(context.applicationContext, httpClient) {
    TavernRemoteMediaLoader(context.applicationContext.cacheDir, httpClient)
}
DisposableEffect(mediaLoader) { onDispose(mediaLoader::close) }
```

Change `secureClient` interception to:

```kotlin
val rawUrl = uri.toString()
resourceRegistry?.intercept(rawUrl)?.let { return it }
if (!shouldAllowTavernSubresource(rawUrl, networkAllowed.get())) return blockedResponse()
return mediaLoader.intercept(rawUrl, request.requestHeaders)
    ?: super.shouldInterceptRequest(view, request)
```

- [ ] **Step 4: Verify and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernConversationBridgeTest" --tests "*TavernRemoteMediaLoaderTest" :app:compileDebugKotlin
git add app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationWebView.kt app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationBridgeTest.kt
git commit -m "feat: route Tavern images through secure media cache"
```

---

### Task 4: Reveal Motion and Stable Height Updates

**Files:**
- Modify: `app/src/main/assets/html/tavern-conversation.html`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocumentTest.kt`
- Test: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocumentInstrumentedTest.kt`

**Interfaces:**
- Produces `data-rikkahub-media-ready`, `.rikkahub-frame-ready`, and a 2 px height threshold.

- [ ] **Step 1: Write failing motion/height contracts**

```kotlin
assertTrue(template.contains("data-rikkahub-media-ready"))
assertTrue(template.contains(".html-part.rikkahub-frame-ready iframe"))
assertTrue(template.contains(".mes.opening-swipe .swipe_left:focus-visible"))
assertTrue(template.contains("Math.abs(nextHeight - previousHeight) < 2"))
```

Also require the reduced-motion block to disable the new transition/animation.

- [ ] **Step 2: Verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernConversationDocumentTest"
```

- [ ] **Step 3: Implement restrained reveal styles**

```css
.html-part iframe { opacity: 0; transition: opacity 180ms ease; }
.html-part.rikkahub-frame-ready iframe { opacity: 1; }
.mes.opening-swipe .swipe_left,
.mes.opening-swipe .swipe_right { opacity: .42; transition: opacity 160ms ease; }
.mes.opening-swipe .swipe_left:focus-visible,
.mes.opening-swipe .swipe_right:focus-visible,
.mes.opening-swipe .swipe_left:active,
.mes.opening-swipe .swipe_right:active { opacity: 1; }
[data-rikkahub-media-ready="true"] { animation: rikkahub-media-reveal 180ms ease both; }
```

On iframe load add `rikkahub-frame-ready`. On media load set the ready attribute, remove one adjacent fallback, and report size. Error handling must retain original URL/alt, hide the native broken glyph, and create one keyboard-activatable Chinese retry control.

- [ ] **Step 4: Filter height churn**

```javascript
var nextHeight = Math.max(120, Math.min(requestedHeight, maxHeight));
var previousHeight = Number(frame.__rikkahubPendingHeight || frame.__rikkahubLastValidHeight || 0);
if (previousHeight > 0 && Math.abs(nextHeight - previousHeight) < 2) return;
frame.__rikkahubPendingHeight = nextHeight;
```

Keep debounced application and update `__rikkahubLastValidHeight` only after writing the iframe style.

- [ ] **Step 5: Add a data-URI instrumentation assertion**

Render a delayed data-URI image and evaluate:

```javascript
JSON.stringify({
  ready: document.querySelector('.html-part').classList.contains('rikkahub-frame-ready'),
  frameHeight: document.querySelector('iframe').getBoundingClientRect().height,
  blockWidth: document.querySelector('.mes_block').getBoundingClientRect().width,
  messageWidth: document.querySelector('.mes').getBoundingClientRect().width
})
```

Require `ready`, `frameHeight > 120`, and `blockWidth >= messageWidth - 24`.

- [ ] **Step 6: Verify and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug :app:assembleDebugAndroidTest
git add app/src/main/assets/html/tavern-conversation.html app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocumentTest.kt app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocumentInstrumentedTest.kt
git commit -m "perf: stabilize Tavern rich card rendering"
```

- [ ] **Step 7: Verify the existing adjacent-candidate residency policy**

`TavernOpeningStage` must continue mounting at most two not-yet-ready candidates and unmount ready candidates outside the selected/adjacent window. Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernOpeningSelectionMotionTest" --tests "*TavernGreetingSessionTest"
```

Expected: candidate selection and direction tests pass; do not expand the number of simultaneously executing long-card WebViews.

---

### Task 5: Safe Huawei Acceptance

**Files:** Verify only.

**Interfaces:** Consumes the arm64 main/test APKs and device `XHD0223523008702`; produces screenshots, geometry, media, crash, proxy, and data-preservation evidence.

- [ ] **Step 1: Record device state**

```powershell
adb devices -l
adb shell pm path me.rerere.rikkahub.debug
adb shell run-as me.rerere.rikkahub.debug ls -l databases/rikka_hub files/datastore/settings.preferences_pb
adb shell settings get global http_proxy
```

- [ ] **Step 2: Install only with replacement semantics**

```powershell
Get-Content app/build/outputs/apk/debug/output-metadata.json
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb shell monkey -p me.rerere.rikkahub.debug -c android.intent.category.LAUNCHER 1
```

Expected: `Success`, versionCode 172, and unchanged non-zero data files.

- [ ] **Step 3: Run instrumentation without Gradle uninstall cleanup**

```powershell
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationDocumentInstrumentedTest me.rerere.rikkahub.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK`; re-check the database/settings files immediately.

- [ ] **Step 4: Verify real-card geometry/media**

DevTools requirements:

```text
mes_block.width >= message.width - 24
html-part.maxHeight == none
html-part.overflow == visible
iframe height matches its reported document height within 2 px
outer #chat owns vertical overflow
```

On reachable networking, require all remote portraits and CSS backgrounds to be visible. On unreachable networking, require one retry fallback per failed media and preserved source URLs. If a proxy is temporarily used, restore the exact recorded original value.

- [ ] **Step 5: Capture and final safety check**

```powershell
adb exec-out screencap -p > verification-screenshots/tavern-rendering-hardening.png
adb shell dumpsys activity activities | Select-String 'mResumedActivity'
adb logcat -d -t 500 | Select-String 'FATAL EXCEPTION|AndroidRuntime.*FATAL|RenderProcessGone'
adb shell settings get global http_proxy
adb shell run-as me.rerere.rikkahub.debug ls -l databases/rikka_hub files/datastore/settings.preferences_pb
git diff --check
```

Expected: RikkaHub foreground, no fatal/render-process crash, original proxy restored, and data files present.
