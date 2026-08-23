# Tavern Adaptive Render Host Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build one policy-driven Tavern rendering host that gives opening cards, the status HUD, and conversation HTML consistent viewport adaptation, media loading, gestures, motion control, recovery, and diagnostics on Android.

**Architecture:** Add small pure Kotlin contracts for render policy, motion, viewport decisions, session revisions, and recovery. Keep `MarkdownWebView` and `TavernConversationWebView` as the existing concrete containers, but make both consume those contracts and the same injected browser protocol. Expand the status HUD to an adaptive 80% sheet with optional fullscreen while keeping the native composer visible outside fullscreen.

**Tech Stack:** Kotlin, Jetpack Compose, Android WebView, JavaScript, kotlinx.serialization, Preferences DataStore, OkHttp, JUnit, AndroidX instrumentation tests, Gradle, ADB/CDP.

## Global Constraints

- Preserve the native top bar, message tree, and existing native input composer.
- Default expanded HUD height is `0.80f` of available height; clamp persisted values to `0.50f..0.90f`.
- Opening, HUD, and message HTML must use the same policy and browser protocol without forcing the same Compose container.
- Preserve card HTML, CSS, scripts, images, animations, and settings unless a behavior-based repair is required.
- Never use card names, card-specific selectors, or fixed remote URLs in production repair logic.
- Keep `TavernRuntimePermissions` authoritative for scripts, networking, variables, macros, and request headers.
- Do not enable universal file access, arbitrary `content:` access, or mixed-content bypass.
- Remote-image success requires decoded natural dimensions greater than zero on the connected device.
- Do not run unfiltered `connectedDebugAndroidTest`; it can clear the user's installed app data. Run one explicit visible-Activity class at a time.
- Check `opencode.exe` before every write/build/install phase in the shared checkout.
- Preserve all unrelated dirty-worktree changes. Stage and commit only files named by the active task.
- Apply TDD for every behavior change: failing test, observed expected failure, minimal implementation, focused green run, then commit.

---

## File Structure

- Create `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/render/TavernRenderPolicy.kt`: surface, viewport, scroll ownership, and policy resolution.
- Create `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/render/TavernMotionPolicy.kt`: motion environment and `FULL/REDUCED/PAUSED` state resolution.
- Create `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/render/TavernViewportAdapter.kt`: pure viewport repair decisions and shared injected JavaScript.
- Create `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/render/TavernRenderSession.kt`: generation/revision acceptance and recovery state machine.
- Create `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/render/TavernRenderDiagnostics.kt`: bounded, redacted local diagnostic events.
- Create `app/src/main/java/me/rerere/rikkahub/data/model/TavernRenderPreferences.kt`: persisted HUD fraction and presentation defaults.
- Modify `app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt`: serialize render preferences.
- Modify `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/StatusHudBar.kt`: adaptive sheet, fullscreen, compact toolbar, and full-height card region.
- Modify `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt`: consume shared policy, viewport script, gesture ownership, motion, revision, recovery, and diagnostics.
- Modify `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationWebView.kt`: consume the same render protocol and incremental revision rules.
- Modify `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernRemoteMediaLoader.kt`: typed failures, bounded retries, cancellation, and cache outcome metadata.
- Modify `app/src/main/assets/html/tavern-conversation.html`: shared viewport/media/motion event handling and incremental lifecycle hooks.
- Modify `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernOpeningStage.kt`: supply the opening surface policy and motion state.
- Add or extend the JVM and visible Android tests listed in each task.

---

### Task 1: Shared Render Policy Contracts

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/render/TavernRenderPolicy.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/render/TavernRenderPolicyTest.kt`

**Interfaces:**
- Consumes: `surface`, `fullscreen`, `availableHeightDp`, and persisted HUD fraction.
- Produces: `TavernRenderPolicy`, `TavernRenderSurface`, `TavernVerticalScrollOwner`, and `resolveTavernRenderPolicy(...)` for Tasks 2, 4, 6, and 8.

- [ ] **Step 1: Write the failing policy tests**

```kotlin
class TavernRenderPolicyTest {
    @Test
    fun `hud defaults to eighty percent and owns webview gestures`() {
        val policy = resolveTavernRenderPolicy(
            surface = TavernRenderSurface.HUD,
            availableHeightDp = 800,
            persistedHudFraction = null,
            fullscreen = false,
        )

        assertEquals(640, policy.maxHeightDp)
        assertEquals(0.80f, policy.panelFraction)
        assertEquals(TavernVerticalScrollOwner.WEBVIEW, policy.verticalScrollOwner)
        assertTrue(policy.captureHorizontalGestures)
    }

    @Test
    fun `persisted hud fraction is clamped and message keeps parent scroll`() {
        assertEquals(
            0.90f,
            resolveTavernRenderPolicy(TavernRenderSurface.HUD, 1000, 1.4f, false).panelFraction,
        )
        val message = resolveTavernRenderPolicy(
            TavernRenderSurface.MESSAGE,
            availableHeightDp = 1000,
            persistedHudFraction = null,
            fullscreen = false,
        )
        assertEquals(TavernVerticalScrollOwner.PARENT, message.verticalScrollOwner)
        assertFalse(message.captureHorizontalGestures)
    }

    @Test
    fun `fullscreen uses all available height`() {
        val policy = resolveTavernRenderPolicy(
            TavernRenderSurface.HUD,
            availableHeightDp = 812,
            persistedHudFraction = 0.6f,
            fullscreen = true,
        )
        assertEquals(812, policy.maxHeightDp)
        assertTrue(policy.fullscreen)
    }
}
```

- [ ] **Step 2: Run the focused test and verify red**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernRenderPolicyTest" --no-daemon
```

Expected: compilation fails because the render policy types and resolver do not exist.

- [ ] **Step 3: Add the minimal policy implementation**

```kotlin
package me.rerere.rikkahub.ui.pages.chat.tavern.render

internal enum class TavernRenderSurface { OPENING, HUD, MESSAGE }
internal enum class TavernVerticalScrollOwner { WEBVIEW, PARENT }

internal data class TavernRenderPolicy(
    val surface: TavernRenderSurface,
    val panelFraction: Float,
    val maxHeightDp: Int,
    val fullscreen: Boolean,
    val verticalScrollOwner: TavernVerticalScrollOwner,
    val captureHorizontalGestures: Boolean,
)

internal fun resolveTavernRenderPolicy(
    surface: TavernRenderSurface,
    availableHeightDp: Int,
    persistedHudFraction: Float?,
    fullscreen: Boolean,
): TavernRenderPolicy {
    val fraction = when {
        fullscreen -> 1f
        surface == TavernRenderSurface.HUD -> (persistedHudFraction ?: 0.80f).coerceIn(0.50f, 0.90f)
        else -> 1f
    }
    val owner = if (surface == TavernRenderSurface.MESSAGE) {
        TavernVerticalScrollOwner.PARENT
    } else {
        TavernVerticalScrollOwner.WEBVIEW
    }
    return TavernRenderPolicy(
        surface = surface,
        panelFraction = fraction,
        maxHeightDp = (availableHeightDp * fraction).toInt().coerceAtLeast(1),
        fullscreen = fullscreen,
        verticalScrollOwner = owner,
        captureHorizontalGestures = surface != TavernRenderSurface.MESSAGE,
    )
}
```

- [ ] **Step 4: Re-run the policy tests**

Run the Step 2 command again.

Expected: all `TavernRenderPolicyTest` cases pass.

- [ ] **Step 5: Commit Task 1**

```powershell
git add -- app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/render/TavernRenderPolicy.kt app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/render/TavernRenderPolicyTest.kt
git commit -m "feat: add shared Tavern render policy"
```

---

### Task 2: Persisted HUD Presentation and Adaptive Sheet

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/model/TavernRenderPreferences.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/StatusHudBar.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/model/TavernRenderPreferencesTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/StatusHudPresentationTest.kt`

**Interfaces:**
- Consumes: `resolveTavernRenderPolicy(...)` from Task 1 and `SettingsStore`.
- Produces: `TavernRenderPreferences`, `normalizeTavernHudFraction(Float)`, and a HUD that passes `maxHeightDp = null` to its primary rich card while the sheet controls total height.

- [ ] **Step 1: Add failing preference and presentation tests**

```kotlin
class TavernRenderPreferencesTest {
    @Test
    fun `hud fraction defaults and clamps`() {
        assertEquals(0.80f, TavernRenderPreferences().hudFraction)
        assertEquals(0.50f, normalizeTavernHudFraction(0.2f))
        assertEquals(0.90f, normalizeTavernHudFraction(1.2f))
    }
}
```

Add this source-contract assertion to `StatusHudPresentationTest`:

```kotlin
@Test
fun `status hud gives the primary card the remaining adaptive panel height`() {
    val source = sourceFile("StatusHudBar.kt")
    assertTrue(source.contains("fillMaxHeight(policy.panelFraction)"))
    assertTrue(source.contains("maxHeightDp = null"))
    assertTrue(source.contains("contentDescription = \"全屏显示状态栏\""))
    assertTrue(source.contains("contentDescription = \"恢复角色卡显示默认设置\""))
    assertFalse(source.contains("maxHeightDp = 360"))
}
```

- [ ] **Step 2: Run the focused tests and verify red**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernRenderPreferencesTest" --tests "*StatusHudPresentationTest" --no-daemon
```

Expected: the preference type is unresolved and the HUD source still contains `maxHeightDp = 360`.

- [ ] **Step 3: Add the serializable preference model and DataStore key**

```kotlin
@Serializable
data class TavernRenderPreferences(
    val hudFraction: Float = 0.80f,
) {
    fun normalized(): TavernRenderPreferences = copy(
        hudFraction = normalizeTavernHudFraction(hudFraction),
    )
}

fun normalizeTavernHudFraction(value: Float): Float = value.coerceIn(0.50f, 0.90f)
```

In `SettingsStore`, add `TAVERN_RENDER_PREFERENCES`, decode it into `Settings.tavernRenderPreferences`, and add:

```kotlin
suspend fun updateTavernRenderPreferences(transform: (TavernRenderPreferences) -> TavernRenderPreferences) {
    dataStore.edit { preferences ->
        val current = preferences[TAVERN_RENDER_PREFERENCES]
            ?.let { runCatching { JsonInstant.decodeFromString<TavernRenderPreferences>(it) }.getOrNull() }
            ?: TavernRenderPreferences()
        preferences[TAVERN_RENDER_PREFERENCES] = JsonInstant.encodeToString(transform(current).normalized())
    }
}
```

- [ ] **Step 4: Replace the fixed HUD body with adaptive/fullscreen layout**

Collect `settingsFlow` in `StatusHudBar`, calculate available height with `BoxWithConstraints`, and resolve policy:

```kotlin
val settingsStore: SettingsStore = koinInject()
val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
var fullscreen by rememberSaveable(conversation.id) { mutableStateOf(false) }

BoxWithConstraints(Modifier.fillMaxSize()) {
    val availableHeightDp = maxHeight.value.toInt()
    val policy = resolveTavernRenderPolicy(
        surface = TavernRenderSurface.HUD,
        availableHeightDp = availableHeightDp,
        persistedHudFraction = settings.tavernRenderPreferences.hudFraction,
        fullscreen = fullscreen,
    )
    StatusHudPanel(
        policy = policy,
        fullscreen = fullscreen,
        onToggleFullscreen = { fullscreen = !fullscreen },
        modifier = Modifier.fillMaxWidth().fillMaxHeight(policy.panelFraction),
        // existing arguments unchanged
    )
}
```

For the primary rich card, remove the inner 360dp cap:

```kotlin
MarkdownWebView(
    content = html,
    isRawHtml = true,
    maxHeightDp = null,
    fixedHeight = true,
    minHeightDp = 240,
    tavernConversationId = conversation.id,
    tavernCurrentMessage = currentMessage,
    ownsSendHookController = false,
    modifier = Modifier.fillMaxWidth().weight(1f),
)
```

Use the existing avatar in the sticky toolbar and add explicit fullscreen/restore icons. Keep sections and option chips below the primary card only when no rich card exists, preventing duplicate display.

The restore action increments a `presentationResetSignal` passed into `MarkdownWebView`. On a new signal, the current Tavern-scoped document executes:

```javascript
try {
  localStorage.clear();
  sessionStorage.clear();
  document.dispatchEvent(new CustomEvent('th:reset_presentation'));
  location.reload();
} catch (error) {
  document.dispatchEvent(new CustomEvent('th:reset_presentation_failed'));
}
```

This clears only the synthetic `rikkahub.local` Tavern presentation origin; it does not mutate conversation messages, variables, permissions, cookies, or application DataStore. Increment the render generation before the reload so callbacks from the old document are ignored.

- [ ] **Step 5: Run focused tests and compile**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernRenderPreferencesTest" --tests "*StatusHudPresentationTest" :app:compileDebugKotlin --no-daemon
```

Expected: tests and compilation pass; no production `maxHeightDp = 360` remains in `StatusHudBar.kt`.

- [ ] **Step 6: Commit Task 2**

```powershell
git add -- app/src/main/java/me/rerere/rikkahub/data/model/TavernRenderPreferences.kt app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt app/src/main/java/me/rerere/rikkahub/ui/pages/chat/StatusHudBar.kt app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt app/src/test/java/me/rerere/rikkahub/data/model/TavernRenderPreferencesTest.kt app/src/test/java/me/rerere/rikkahub/ui/pages/chat/StatusHudPresentationTest.kt
git commit -m "feat: expand Tavern status into adaptive panel"
```

---

### Task 3: Behavior-Based Viewport and Overlay Adapter

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/render/TavernViewportAdapter.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/render/TavernViewportAdapterTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt`
- Modify: `app/src/main/assets/html/tavern-conversation.html`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebViewHtmlDetectionTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocumentTest.kt`

**Interfaces:**
- Consumes: visible fixed-element metrics and current visual viewport height.
- Produces: `ViewportRepairDecision`, `decideViewportRepair(...)`, and `buildTavernViewportAdapterScript()` used by both WebView containers.

- [ ] **Step 1: Add failing pure decision tests**

```kotlin
class TavernViewportAdapterTest {
    @Test
    fun `repairs only a clipped zero-height panel`() {
        assertEquals(
            ViewportRepairDecision(maxHeightPx = 696, enableVerticalScroll = true),
            decideViewportRepair(
                viewportHeightPx = 720,
                computedMaxHeightPx = 0,
                clientHeightPx = 48,
                scrollHeightPx = 314,
                visible = true,
                fixedOverlay = true,
            ),
        )
    }

    @Test
    fun `preserves a card supplied usable max height`() {
        assertNull(
            decideViewportRepair(720, 540, 314, 314, visible = true, fixedOverlay = true),
        )
    }

    @Test
    fun `ignores hidden and non fixed content`() {
        assertNull(decideViewportRepair(720, 0, 48, 314, visible = false, fixedOverlay = true))
        assertNull(decideViewportRepair(720, 0, 48, 314, visible = true, fixedOverlay = false))
    }
}
```

- [ ] **Step 2: Run the focused test and verify red**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernViewportAdapterTest" --no-daemon
```

Expected: compilation fails because the adapter contracts do not exist.

- [ ] **Step 3: Implement the decision and one shared JavaScript adapter**

```kotlin
internal data class ViewportRepairDecision(
    val maxHeightPx: Int,
    val enableVerticalScroll: Boolean,
)

internal fun decideViewportRepair(
    viewportHeightPx: Int,
    computedMaxHeightPx: Int?,
    clientHeightPx: Int,
    scrollHeightPx: Int,
    visible: Boolean,
    fixedOverlay: Boolean,
): ViewportRepairDecision? {
    if (!visible || !fixedOverlay) return null
    if ((computedMaxHeightPx ?: 0) > 1) return null
    if (scrollHeightPx <= clientHeightPx + 8) return null
    return ViewportRepairDecision(
        maxHeightPx = (viewportHeightPx - 24).coerceAtLeast(216),
        enableVerticalScroll = true,
    )
}
```

`buildTavernViewportAdapterScript()` must export a single scheduled refresh function. Its injected browser code must:

```javascript
const tavernViewportAdapter = (() => {
  let frame = 0;
  let lastViewportHeight = 0;
  function schedule() {
    if (frame) return;
    frame = requestAnimationFrame(refresh);
  }
  function refresh() {
    frame = 0;
    const viewportHeight = Math.max(240, Math.floor(
      (window.visualViewport && window.visualViewport.height) || window.innerHeight || 0
    ));
    if (!document.body) return;
    document.body.querySelectorAll('*').forEach((overlay) => {
      const style = getComputedStyle(overlay);
      if (style.position !== 'fixed' || style.display === 'none' || style.visibility === 'hidden') return;
      Array.from(overlay.children).forEach((panel) => {
        const panelStyle = getComputedStyle(panel);
        const maxHeight = Number.parseFloat(panelStyle.maxHeight);
        const clipped = (!Number.isFinite(maxHeight) || maxHeight <= 1) &&
          panel.scrollHeight > panel.clientHeight + 8;
        if (!clipped) return;
        const target = Math.max(216, viewportHeight - 24) + 'px';
        if (panel.style.maxHeight !== target) panel.style.maxHeight = target;
        if (panel.style.overflowY !== 'auto') panel.style.overflowY = 'auto';
        panel.dataset.rikkahubOverlayRepaired = 'true';
      });
    });
    lastViewportHeight = viewportHeight;
  }
  return { schedule };
})();
```

Mutation and resize observers call `schedule()` only. Attribute writes must be conditional, and the observer must not watch the adapter's own marker attribute.

- [ ] **Step 4: Use the shared adapter in both documents**

Replace the inline `repairInteractiveOverlays()` block in `MarkdownWebView.kt` with `buildTavernViewportAdapterScript()`. Insert the same script into `tavern-conversation.html` before card frames report ready. Preserve existing link interception and height reporting as separate responsibilities.

- [ ] **Step 5: Run viewport and document tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernViewportAdapterTest" --tests "*MarkdownWebViewHtmlDetectionTest" --tests "*TavernConversationDocumentTest" --no-daemon
```

Expected: tests pass and both document builders contain the same adapter marker `rikkahubOverlayRepaired`.

- [ ] **Step 6: Commit Task 3**

```powershell
git add -- app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/render/TavernViewportAdapter.kt app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/render/TavernViewportAdapterTest.kt app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt app/src/main/assets/html/tavern-conversation.html app/src/test/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebViewHtmlDetectionTest.kt app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocumentTest.kt
git commit -m "fix: unify Tavern viewport adaptation"
```

---

### Task 4: Axis-Locked Gesture Arbitration

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/render/TavernRenderPolicy.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationWebView.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebViewSecurityTest.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationWebViewTest.kt`

**Interfaces:**
- Consumes: `TavernRenderPolicy.verticalScrollOwner` and `captureHorizontalGestures`.
- Produces: `TavernGestureDecision`, `resolveTavernGestureDecision(...)`, and identical touch ownership in both WebView containers.

- [ ] **Step 1: Add failing gesture sequence tests**

```kotlin
@Test
fun `hud keeps horizontal sequence through action up`() {
    val policy = resolveTavernRenderPolicy(TavernRenderSurface.HUD, 800, null, false)
    assertEquals(
        TavernGestureDecision.CAPTURE_WEBVIEW,
        resolveTavernGestureDecision(policy, deltaX = 80f, deltaY = 8f, atTop = false, atBottom = false),
    )
}

@Test
fun `message donates horizontal sequence to parent`() {
    val policy = resolveTavernRenderPolicy(TavernRenderSurface.MESSAGE, 800, null, false)
    assertEquals(
        TavernGestureDecision.RELEASE_PARENT,
        resolveTavernGestureDecision(policy, deltaX = 80f, deltaY = 8f, atTop = false, atBottom = false),
    )
}

@Test
fun `webview releases outward vertical drag at bottom`() {
    val policy = resolveTavernRenderPolicy(TavernRenderSurface.HUD, 800, null, false)
    assertEquals(
        TavernGestureDecision.RELEASE_PARENT,
        resolveTavernGestureDecision(policy, deltaX = 2f, deltaY = 30f, atTop = false, atBottom = true),
    )
}
```

- [ ] **Step 2: Run the focused tests and verify red**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*MarkdownWebViewSecurityTest" --tests "*TavernConversationWebViewTest" --no-daemon
```

Expected: the shared gesture decision API is unresolved.

- [ ] **Step 3: Implement the shared decision function**

```kotlin
internal enum class TavernGestureDecision { UNDECIDED, CAPTURE_WEBVIEW, RELEASE_PARENT }

internal fun resolveTavernGestureDecision(
    policy: TavernRenderPolicy,
    deltaX: Float,
    deltaY: Float,
    atTop: Boolean,
    atBottom: Boolean,
): TavernGestureDecision {
    val horizontal = kotlin.math.abs(deltaX)
    val vertical = kotlin.math.abs(deltaY)
    val axis = when {
        horizontal < 10f && vertical < 10f -> TavernDragAxis.UNDECIDED
        horizontal >= 24f && horizontal >= vertical * 1.5f -> TavernDragAxis.HORIZONTAL
        else -> TavernDragAxis.VERTICAL
    }
    return when (axis) {
        TavernDragAxis.UNDECIDED -> TavernGestureDecision.UNDECIDED
        TavernDragAxis.HORIZONTAL -> if (policy.captureHorizontalGestures) {
            TavernGestureDecision.CAPTURE_WEBVIEW
        } else TavernGestureDecision.RELEASE_PARENT
        TavernDragAxis.VERTICAL -> {
            val outward = (deltaY < 0f && atTop) || (deltaY > 0f && atBottom)
            if (policy.verticalScrollOwner == TavernVerticalScrollOwner.WEBVIEW && !outward) {
                TavernGestureDecision.CAPTURE_WEBVIEW
            } else TavernGestureDecision.RELEASE_PARENT
        }
    }
}
```

Define `internal enum class TavernDragAxis { UNDECIDED, VERTICAL, HORIZONTAL }` beside the decision enum and replace the old Markdown-only classifier with this shared implementation.

- [ ] **Step 4: Wire one axis lock per touch sequence**

In both WebView factories:

```kotlin
MotionEvent.ACTION_DOWN -> {
    downX = event.x
    downY = event.y
    gestureDecision = TavernGestureDecision.UNDECIDED
    parent.requestDisallowInterceptTouchEvent(policy.verticalScrollOwner == TavernVerticalScrollOwner.WEBVIEW)
}
MotionEvent.ACTION_MOVE -> {
    if (gestureDecision == TavernGestureDecision.UNDECIDED) {
        gestureDecision = resolveTavernGestureDecision(
            policy = policy,
            deltaX = event.x - downX,
            deltaY = downY - event.y,
            atTop = scrollY <= 2,
            atBottom = scrollY + height >= contentHeightPx - 4,
        )
    }
    parent.requestDisallowInterceptTouchEvent(gestureDecision == TavernGestureDecision.CAPTURE_WEBVIEW)
}
MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
    parent.requestDisallowInterceptTouchEvent(false)
    gestureDecision = TavernGestureDecision.UNDECIDED
}
```

Do not change axis after it locks. Return `false` so WebView JavaScript continues receiving the same sequence.

- [ ] **Step 5: Run gesture tests and compile**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*MarkdownWebViewSecurityTest" --tests "*TavernConversationWebViewTest" :app:compileDebugKotlin --no-daemon
```

Expected: tests pass and both containers compile with the shared decision function.

- [ ] **Step 6: Commit Task 4**

```powershell
git add -- app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/render/TavernRenderPolicy.kt app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationWebView.kt app/src/test/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebViewSecurityTest.kt app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationWebViewTest.kt
git commit -m "fix: arbitrate Tavern WebView gestures by surface"
```

---

### Task 5: Resilient Remote Media Pipeline

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernRemoteMediaLoader.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationWebView.kt`
- Modify: `app/src/main/assets/html/tavern-conversation.html`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernRemoteMediaLoaderTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocumentTest.kt`

**Interfaces:**
- Consumes: HTTP(S) image requests allowed by `TavernRuntimePermissions`.
- Produces: `TavernMediaLoadResult`, `TavernMediaFailureReason`, bounded retry, and browser `th:media_state` events.

- [ ] **Step 1: Add failing loader tests**

```kotlin
@Test
fun `transient media failure retries once then succeeds`() {
    var attempts = 0
    val loader = TavernRemoteMediaLoader.forTest {
        attempts++
        if (attempts == 1) throw java.io.IOException("temporary")
        TavernRemoteMediaPayload("image/png", byteArrayOf(1, 2, 3), emptyMap())
    }
    val result = loader.loadResult("https://example.test/a.png", mapOf("Accept" to "image/png"))
    assertTrue(result is TavernMediaLoadResult.Success)
    assertEquals(2, attempts)
}

@Test
fun `invalid content is not retried`() {
    var attempts = 0
    val loader = TavernRemoteMediaLoader.forTest {
        attempts++
        null
    }
    val result = loader.loadResult("https://example.test/a.png", mapOf("Accept" to "image/png"))
    assertEquals(TavernMediaFailureReason.INVALID_RESPONSE, (result as TavernMediaLoadResult.Failure).reason)
    assertEquals(1, attempts)
}

@Test
fun `close cancels an in flight request`() {
    val started = CountDownLatch(1)
    val loader = TavernRemoteMediaLoader.forTest {
        started.countDown()
        Thread.sleep(10_000)
        null
    }
    val future = Executors.newSingleThreadExecutor().submit {
        loader.loadResult("https://example.test/a.png", mapOf("Accept" to "image/png"))
    }
    assertTrue(started.await(2, TimeUnit.SECONDS))
    loader.close()
    assertEquals(TavernMediaFailureReason.CANCELLED, (future.get(2, TimeUnit.SECONDS) as TavernMediaLoadResult.Failure).reason)
}
```

- [ ] **Step 2: Run loader tests and verify red**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernRemoteMediaLoaderTest" --no-daemon
```

Expected: `loadResult` and the typed result classes are unresolved.

- [ ] **Step 3: Implement typed outcomes and bounded retry**

```kotlin
internal enum class TavernMediaFailureReason { BLOCKED, INVALID_RESPONSE, TIMEOUT, NETWORK, CANCELLED }

internal sealed interface TavernMediaLoadResult {
    data class Success(val payload: TavernRemoteMediaPayload, val fromCache: Boolean) : TavernMediaLoadResult
    data class Failure(val reason: TavernMediaFailureReason, val retryable: Boolean) : TavernMediaLoadResult
}
```

`loadResult` performs at most two network attempts for `IOException`/timeout, never retries blocked URLs or invalid MIME/size, and returns `CANCELLED` after `close()`. Keep the existing 15 MiB body limit, 48 MiB disk cache, and in-flight deduplication.

- [ ] **Step 4: Add stable browser placeholders and single-image retry**

In the shared browser protocol, attach capture listeners once:

```javascript
document.addEventListener('error', (event) => {
  const image = event.target;
  if (!image || image.tagName !== 'IMG' || image.dataset.thFailed === 'true') return;
  image.dataset.thFailed = 'true';
  image.classList.add('th-media-failed');
  document.dispatchEvent(new CustomEvent('th:media_state', {
    detail: { state: 'failed', source: image.currentSrc || image.src }
  }));
  const retry = document.createElement('button');
  retry.type = 'button';
  retry.className = 'th-media-retry';
  retry.textContent = '重试图片';
  retry.addEventListener('click', () => {
    const source = image.currentSrc || image.src;
    image.dataset.thFailed = 'false';
    image.classList.remove('th-media-failed');
    retry.remove();
    document.dispatchEvent(new CustomEvent('th:media_state', {
      detail: { state: 'retrying', source }
    }));
    image.src = source.replace(/([?&])th_retry=\d+/, '$1').replace(/[?&]$/, '') +
      (source.includes('?') ? '&' : '?') + 'th_retry=' + Date.now();
  }, { once: true });
  image.insertAdjacentElement('afterend', retry);
}, true);

document.addEventListener('load', (event) => {
  const image = event.target;
  if (!image || image.tagName !== 'IMG') return;
  document.dispatchEvent(new CustomEvent('th:media_state', {
    detail: {
      state: image.naturalWidth > 0 && image.naturalHeight > 0 ? 'ready' : 'decode_failed',
      source: image.currentSrc || image.src,
      naturalWidth: image.naturalWidth,
      naturalHeight: image.naturalHeight
    }
  }));
}, true);
```

Add neutral CSS for `.th-media-failed` and `.th-media-retry`; card-authored styles retain precedence except for keeping the retry affordance visible.

- [ ] **Step 5: Run media/document tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernRemoteMediaLoaderTest" --tests "*TavernConversationDocumentTest" --tests "*MarkdownWebViewHtmlDetectionTest" --no-daemon
```

Expected: typed outcomes, retry cap, cancellation, and browser placeholder contracts pass.

- [ ] **Step 6: Commit Task 5**

```powershell
git add -- app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernRemoteMediaLoader.kt app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationWebView.kt app/src/main/assets/html/tavern-conversation.html app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernRemoteMediaLoaderTest.kt app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocumentTest.kt
git commit -m "fix: make Tavern remote media recoverable"
```

---

### Task 6: Adaptive Motion Controller

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/render/TavernMotionPolicy.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/render/TavernMotionPolicyTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationWebView.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernOpeningStage.kt`
- Modify: `app/src/main/assets/html/tavern-conversation.html`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernOpeningSelectionMotionTest.kt`

**Interfaces:**
- Consumes: visibility, scrolling, lifecycle, power-save, reduce-motion, and explicit transition state.
- Produces: `TavernMotionLevel` and `resolveTavernMotionLevel(TavernMotionEnvironment)`.

- [ ] **Step 1: Add failing motion state tests**

```kotlin
class TavernMotionPolicyTest {
    @Test
    fun `explicit entry transition is full motion`() {
        assertEquals(
            TavernMotionLevel.FULL,
            resolveTavernMotionLevel(TavernMotionEnvironment(visible = true, transitioning = true)),
        )
    }

    @Test
    fun `scrolling power save background and reduce motion pause decoration`() {
        listOf(
            TavernMotionEnvironment(visible = true, scrolling = true),
            TavernMotionEnvironment(visible = true, powerSave = true),
            TavernMotionEnvironment(visible = false),
            TavernMotionEnvironment(visible = true, reduceMotion = true),
        ).forEach { environment ->
            assertEquals(TavernMotionLevel.PAUSED, resolveTavernMotionLevel(environment))
        }
    }

    @Test
    fun `stable visible card uses reduced continuous motion`() {
        assertEquals(
            TavernMotionLevel.REDUCED,
            resolveTavernMotionLevel(TavernMotionEnvironment(visible = true)),
        )
    }
}
```

- [ ] **Step 2: Run tests and verify red**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernMotionPolicyTest" --no-daemon
```

Expected: the motion contracts do not exist.

- [ ] **Step 3: Implement the pure motion resolver**

```kotlin
internal enum class TavernMotionLevel { FULL, REDUCED, PAUSED }

internal data class TavernMotionEnvironment(
    val visible: Boolean,
    val scrolling: Boolean = false,
    val transitioning: Boolean = false,
    val powerSave: Boolean = false,
    val reduceMotion: Boolean = false,
)

internal fun resolveTavernMotionLevel(environment: TavernMotionEnvironment): TavernMotionLevel = when {
    !environment.visible || environment.scrolling || environment.powerSave || environment.reduceMotion ->
        TavernMotionLevel.PAUSED
    environment.transitioning -> TavernMotionLevel.FULL
    else -> TavernMotionLevel.REDUCED
}
```

- [ ] **Step 4: Publish motion state to both WebView documents**

On state change, evaluate one idempotent script:

```javascript
document.documentElement.dataset.thMotion = 'full'; // or reduced / paused
document.dispatchEvent(new CustomEvent('th:motion_changed', { detail: { level: 'full' } }));
```

Add shared defaults:

```css
html[data-th-motion="paused"] *,
html[data-th-motion="paused"] *::before,
html[data-th-motion="paused"] *::after {
  animation-play-state: paused !important;
}
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { scroll-behavior: auto !important; }
}
```

Use lifecycle visibility, WebView attach state, scroll callbacks, `PowerManager.isPowerSaveMode`, and the system animator duration scale to construct the environment. Debounce scroll-end restoration by 160 ms and cancel the prior job on renewed movement.

- [ ] **Step 5: Run motion/opening tests and compile**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernMotionPolicyTest" --tests "*TavernOpeningSelectionMotionTest" :app:compileDebugKotlin --no-daemon
```

Expected: motion tests pass and opening transitions still compile and preserve their existing direction rules.

- [ ] **Step 6: Commit Task 6**

```powershell
git add -- app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/render/TavernMotionPolicy.kt app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/render/TavernMotionPolicyTest.kt app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationWebView.kt app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernOpeningStage.kt app/src/main/assets/html/tavern-conversation.html app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernOpeningSelectionMotionTest.kt
git commit -m "feat: adapt Tavern motion to interaction state"
```

---

### Task 7: Revisioned Render Session, Recovery, and Diagnostics

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/render/TavernRenderSession.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/render/TavernRenderDiagnostics.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/render/TavernRenderSessionTest.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/render/TavernRenderDiagnosticsTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationWebView.kt`

**Interfaces:**
- Consumes: document generation, incremental revision, ready/error/media/viewport callbacks.
- Produces: `TavernRenderSessionState`, `TavernRecoveryStage`, `TavernRenderSessionState.accepts(...)`, and a bounded `TavernRenderDiagnostics` buffer.

- [ ] **Step 1: Add failing session and redaction tests**

```kotlin
@Test
fun `stale generation and revision callbacks are ignored`() {
    val current = TavernRenderSessionState(generation = 3, revision = 9)
    assertFalse(current.accepts(generation = 2, revision = 10))
    assertFalse(current.accepts(generation = 3, revision = 8))
    assertTrue(current.accepts(generation = 3, revision = 9))
}

@Test
fun `recovery advances without discarding last successful frame`() {
    val ready = TavernRenderSessionState(generation = 1, revision = 4, hasSuccessfulFrame = true)
    val failed = ready.onFailure("script timeout")
    assertEquals(TavernRecoveryStage.RETRY_COMPONENT, failed.recoveryStage)
    assertTrue(failed.keepLastSuccessfulFrame)
    assertEquals(TavernRecoveryStage.STATIC_HTML, failed.advanceRecovery().advanceRecovery().recoveryStage)
}

@Test
fun `diagnostics redact content and cap buffer`() {
    val diagnostics = TavernRenderDiagnostics(capacity = 2)
    diagnostics.record(TavernRenderEvent.ScriptFailure("secret character text", "token=abc"))
    diagnostics.record(TavernRenderEvent.MediaFailure("https://example.test/private/a.png", "timeout"))
    diagnostics.record(TavernRenderEvent.ViewportRepair(0, 696))
    val snapshot = diagnostics.snapshot()
    assertEquals(2, snapshot.size)
    assertFalse(snapshot.joinToString().contains("secret character text"))
    assertFalse(snapshot.joinToString().contains("token=abc"))
    assertFalse(snapshot.joinToString().contains("/private/a.png"))
}
```

- [ ] **Step 2: Run tests and verify red**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernRenderSessionTest" --tests "*TavernRenderDiagnosticsTest" --no-daemon
```

Expected: session, recovery, and diagnostic contracts are unresolved.

- [ ] **Step 3: Implement the recovery state machine**

```kotlin
internal enum class TavernRecoveryStage { NONE, RETRY_COMPONENT, RESTART_RUNTIME, STATIC_HTML, RAW_CONTENT }

internal data class TavernRenderSessionState(
    val generation: Int,
    val revision: Long,
    val hasSuccessfulFrame: Boolean = false,
    val recoveryStage: TavernRecoveryStage = TavernRecoveryStage.NONE,
    val keepLastSuccessfulFrame: Boolean = false,
) {
    fun accepts(generation: Int, revision: Long): Boolean =
        generation == this.generation && revision >= this.revision

    fun onFailure(reason: String): TavernRenderSessionState = copy(
        recoveryStage = TavernRecoveryStage.RETRY_COMPONENT,
        keepLastSuccessfulFrame = hasSuccessfulFrame,
    )

    fun advanceRecovery(): TavernRenderSessionState = copy(
        recoveryStage = when (recoveryStage) {
            TavernRecoveryStage.NONE -> TavernRecoveryStage.RETRY_COMPONENT
            TavernRecoveryStage.RETRY_COMPONENT -> TavernRecoveryStage.RESTART_RUNTIME
            TavernRecoveryStage.RESTART_RUNTIME -> TavernRecoveryStage.STATIC_HTML
            TavernRecoveryStage.STATIC_HTML, TavernRecoveryStage.RAW_CONTENT -> TavernRecoveryStage.RAW_CONTENT
        },
    )
}
```

Keep `reason` out of the state exposed to card HTML; pass only a redacted category to diagnostics.

- [ ] **Step 4: Implement bounded redacted diagnostics**

Use an `ArrayDeque<TavernRenderEventRecord>` guarded by synchronization. Store only event type, timestamp, generation/revision, HTTP host plus hashed path, and numeric metrics. Script error text is reduced to a stable category (`timeout`, `syntax`, `bridge`, `unknown`) before storage.

```kotlin
internal sealed interface TavernRenderEvent {
    data class ScriptFailure(val rawMessage: String, val rawDetail: String?) : TavernRenderEvent
    data class MediaFailure(val rawUrl: String, val category: String) : TavernRenderEvent
    data class ViewportRepair(val previousMaxHeightPx: Int, val appliedMaxHeightPx: Int) : TavernRenderEvent
}

internal data class TavernRenderEventRecord(
    val type: String,
    val category: String,
    val target: String? = null,
    val firstMetric: Int? = null,
    val secondMetric: Int? = null,
)

internal class TavernRenderDiagnostics(private val capacity: Int = 128) {
    private val records = ArrayDeque<TavernRenderEventRecord>()

    @Synchronized
    fun record(event: TavernRenderEvent) {
        val record = when (event) {
            is TavernRenderEvent.ScriptFailure -> TavernRenderEventRecord(
                type = "script_failure",
                category = classifyScriptFailure(event.rawMessage, event.rawDetail),
            )
            is TavernRenderEvent.MediaFailure -> TavernRenderEventRecord(
                type = "media_failure",
                category = event.category,
                target = redactMediaTarget(event.rawUrl),
            )
            is TavernRenderEvent.ViewportRepair -> TavernRenderEventRecord(
                type = "viewport_repair",
                category = "clipped_overlay",
                firstMetric = event.previousMaxHeightPx,
                secondMetric = event.appliedMaxHeightPx,
            )
        }
        while (records.size >= capacity) records.removeFirst()
        records.addLast(record)
    }

    @Synchronized
    fun snapshot(): List<TavernRenderEventRecord> = records.toList()
}

private fun classifyScriptFailure(message: String, detail: String?): String {
    val value = "$message ${detail.orEmpty()}".lowercase()
    return when {
        "timeout" in value || "timed out" in value -> "timeout"
        "syntax" in value || "unexpected token" in value -> "syntax"
        "bridge" in value || "rpc" in value -> "bridge"
        else -> "unknown"
    }
}

private fun redactMediaTarget(rawUrl: String): String? = runCatching {
    val uri = java.net.URI(rawUrl)
    val host = uri.host ?: return@runCatching null
    val pathHash = uri.path.orEmpty().hashCode().toUInt().toString(16)
    "$host/#$pathHash"
}.getOrNull()
```

- [ ] **Step 5: Wire generation/revision gates into both containers**

Every `documentReady`, height, media, and runtime callback carries generation and revision. Reject stale callbacks before mutating Compose state. On component failure preserve the current WebView if it has a successful frame; only swap to `MarkdownStaticFallback` at `STATIC_HTML` or `RAW_CONTENT`.

- [ ] **Step 6: Run session tests and existing render-state regressions**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernRenderSessionTest" --tests "*TavernRenderDiagnosticsTest" --tests "*MarkdownWebViewSecurityTest" --tests "*TavernConversationBridgeTest" --no-daemon
```

Expected: all tests pass, including existing stale-render callback cases.

- [ ] **Step 7: Commit Task 7**

```powershell
git add -- app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/render/TavernRenderSession.kt app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/render/TavernRenderDiagnostics.kt app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/render/TavernRenderSessionTest.kt app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/render/TavernRenderDiagnosticsTest.kt app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationWebView.kt
git commit -m "feat: recover and diagnose Tavern render sessions"
```

---

### Task 8: Unify Opening, HUD, and Conversation Entry Points

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernOpeningStage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/StatusHudBar.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationWebView.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernRenderEntryPointTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/StatusHudPresentationTest.kt`

**Interfaces:**
- Consumes: Tasks 1–7 contracts.
- Produces: every Tavern entry point supplies an explicit `TavernRenderSurface` and shares viewport, media, motion, revision, recovery, and diagnostics behavior.

- [ ] **Step 1: Add failing entry-point contract tests**

```kotlin
class TavernRenderEntryPointTest {
    @Test
    fun `opening hud and message declare explicit render surfaces`() {
        assertTrue(projectFile("ui/pages/chat/tavern/TavernOpeningStage.kt").contains("TavernRenderSurface.OPENING"))
        assertTrue(projectFile("ui/pages/chat/StatusHudBar.kt").contains("TavernRenderSurface.HUD"))
        assertTrue(projectFile("ui/components/message/ChatMessage.kt").contains("TavernRenderSurface.MESSAGE"))
    }

    @Test
    fun `both webview containers install the shared browser protocol`() {
        assertTrue(projectFile("ui/components/richtext/MarkdownWebView.kt").contains("buildTavernViewportAdapterScript"))
        assertTrue(projectFile("ui/pages/chat/tavern/TavernConversationWebView.kt").contains("TavernRenderSessionState"))
        assertTrue(assetFile("tavern-conversation.html").contains("th:motion_changed"))
        assertTrue(assetFile("tavern-conversation.html").contains("th-media-retry"))
    }

    private fun projectFile(relative: String): String = listOf(
        File("src/main/java/me/rerere/rikkahub/$relative"),
        File("app/src/main/java/me/rerere/rikkahub/$relative"),
    ).firstOrNull(File::exists)?.readText() ?: error("$relative not found")

    private fun assetFile(name: String): String = listOf(
        File("src/main/assets/html/$name"),
        File("app/src/main/assets/html/$name"),
    ).firstOrNull(File::exists)?.readText() ?: error("$name not found")
}
```

- [ ] **Step 2: Run entry-point tests and verify red**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TavernRenderEntryPointTest" --tests "*StatusHudPresentationTest" --no-daemon
```

Expected: at least one entry point lacks an explicit surface or shared protocol marker.

- [ ] **Step 3: Add `renderSurface` to the rich-text parameter chain**

Extend `Markdown`, `MarkdownBlock`, `MultiCharacterStatusView`, and `MarkdownWebView` with:

```kotlin
renderSurface: TavernRenderSurface = TavernRenderSurface.MESSAGE
```

Pass `HUD` from `StatusHudBar`, `OPENING` from `TavernOpeningStage`, and retain `MESSAGE` in ordinary chat. Derive the concrete `TavernRenderPolicy` at the WebView boundary using current available height.

- [ ] **Step 4: Remove duplicate surface-specific patches**

Delete the old inline viewport repair from `MarkdownWebView`, any separate motion pause code duplicated in opening, and any media retry implementation that bypasses `TavernRemoteMediaLoader`. Preserve surface-specific Compose presentation only.

- [ ] **Step 5: Run the Tavern-focused JVM suite**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*Tavern*Test" --tests "*MarkdownWebView*Test" --tests "*StatusHud*Test" --no-daemon
```

Expected: all focused tests pass with no duplicate inline viewport implementation.

- [ ] **Step 6: Commit Task 8**

```powershell
git add -- app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernOpeningStage.kt app/src/main/java/me/rerere/rikkahub/ui/pages/chat/StatusHudBar.kt app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationWebView.kt app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernRenderEntryPointTest.kt app/src/test/java/me/rerere/rikkahub/ui/pages/chat/StatusHudPresentationTest.kt
git commit -m "refactor: unify Tavern render entry points"
```

---

### Task 9: Visible Android and Real-Device Acceptance

**Files:**
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernImmersiveRuntimeInstrumentedTest.kt`
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocumentInstrumentedTest.kt`
- Modify: `app/src/debug/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationRecoveryActivity.kt`
- Create: `verification-screenshots/adaptive-render-host/VERIFICATION.md`

**Interfaces:**
- Consumes: completed Tasks 1–8 and connected device `XHD0223523008702`.
- Produces: filtered visible-WebView results, Debug APK, installed package evidence, CDP image/layout metrics, screenshots, and logcat/gfx evidence.

- [ ] **Step 1: Add visible instrumentation assertions**

Extend the visible recovery activity and filtered tests to expose deterministic probes:

```kotlin
assertEquals("hud", probe.renderSurface)
assertEquals("reduced", probe.motionLevel)
assertTrue(probe.viewportHeightPx >= 600)
assertEquals(0, probe.documentReloadsAfterVariablePatch)
assertTrue(probe.settingsPanelClientHeight >= probe.settingsPanelScrollHeight)
assertEquals(4, probe.loadedCharacterImages)
assertTrue(probe.loadedImageNaturalWidths.all { it > 0 })
```

Add a failure fixture whose first image request fails and whose retry succeeds; assert the retry button disappears and natural width becomes nonzero.

- [ ] **Step 2: Build the app and the filtered test APK**

Before running Gradle, verify no `opencode.exe` owns the checkout.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
```

Expected: `BUILD SUCCESSFUL`; all JVM XML suites report zero failures/errors.

- [ ] **Step 3: Install without clearing data**

```powershell
adb -s XHD0223523008702 install -r app\build\outputs\apk\debug\app-arm64-v8a-debug.apk
adb -s XHD0223523008702 install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
```

Expected: both commands return `Success`. Do not run `pm clear`.

- [ ] **Step 4: Run only the two explicit visible test classes**

```powershell
adb -s XHD0223523008702 shell am instrument -w -r -e class me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationDocumentInstrumentedTest me.rerere.rikkahub.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s XHD0223523008702 shell am instrument -w -r -e class me.rerere.rikkahub.ui.pages.chat.tavern.TavernImmersiveRuntimeInstrumentedTest me.rerere.rikkahub.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: each reports `OK`; neither command runs unrelated classes.

- [ ] **Step 5: Perform physical-device interaction acceptance**

Open the real `慈脂佛母` conversation and record each result in `VERIFICATION.md`:

1. Cold-start and expand the status HUD; measure that the card occupies 75–85% of available height.
2. Tap settings, switch list/detail, drag scale, close, reopen, and verify persistence.
3. Swipe `1/4 → 2/4 → 3/4 → 4/4 → 1/4`; verify each theme, name, state, and image changes.
4. Scroll long content vertically to its boundary and continue dragging; verify outer panel receives the handoff.
5. Open every character image preview and close it by touch.
6. Toggle fullscreen and restore; verify the native composer is present in normal expanded mode.
7. Background for 10 seconds, return, rotate once, and verify context, variables, images, and settings remain.
8. Disable networking, trigger one image failure, re-enable networking, tap the single-image retry, and verify decoded natural dimensions become nonzero.

ADB gestures may support measurement, but acceptance requires the same interaction to be reproduced by real finger input because prior ADB swipes did not model all reported jank.

- [ ] **Step 6: Collect CDP and system performance evidence**

Capture two idle CDP samples four seconds apart and record:

- `LayoutCount`, `RecalcStyleCount`, and document reload counter.
- All visible remote images: `complete`, `naturalWidth`, `naturalHeight`.
- Settings panel `clientHeight`, `scrollHeight`, computed `maxHeight`, and repair marker.

Then collect:

```powershell
adb -s XHD0223523008702 shell dumpsys gfxinfo me.rerere.rikkahub.debug reset
# perform the defined finger scroll and role-switch sequence
adb -s XHD0223523008702 shell dumpsys gfxinfo me.rerere.rikkahub.debug
adb -s XHD0223523008702 logcat -d -v brief | Select-String -Pattern 'FATAL EXCEPTION|ANR in me\.rerere\.rikkahub|Process: me\.rerere\.rikkahub\.debug'
```

Expected: no FATAL/ANR; no idle document reload; no host-observer-driven continuous layout growth; all six real-card remote images have nonzero natural dimensions.

- [ ] **Step 7: Save visual evidence and verification report**

Save at minimum:

- `hud-expanded-80-percent.png`
- `hud-fullscreen.png`
- `settings-complete.png`
- `character-1.png` through `character-4.png`
- `image-retry-recovered.png`
- `background-resume.png`

In `VERIFICATION.md`, include APK path, package/version/ABI, exact test commands, test totals, device serial/model, CDP metrics, logcat result, and any remaining gap. Do not claim untested card behaviors.

- [ ] **Step 8: Commit Task 9**

```powershell
git add -- app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernImmersiveRuntimeInstrumentedTest.kt app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocumentInstrumentedTest.kt app/src/debug/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationRecoveryActivity.kt verification-screenshots/adaptive-render-host/VERIFICATION.md verification-screenshots/adaptive-render-host/*.png
git commit -m "test: verify adaptive Tavern render host on device"
```

---

### Task 10: Final Regression, Diff Audit, and Handoff

**Files:**
- Modify: `docs/superpowers/plans/2026-08-23-tavern-adaptive-render-host.md`

**Interfaces:**
- Consumes: all Task 1–9 commits.
- Produces: checked plan boxes, fresh full-suite results, installed final APK, and an explicit remaining-risk statement.

- [ ] **Step 1: Run the final full verification**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL`; XML aggregation reports zero failures and zero errors.

- [ ] **Step 2: Audit formatting and task-scoped changes**

```powershell
git diff --check HEAD~10..HEAD
git status --short
git log -10 --oneline --decorate
```

Expected: no whitespace errors other than repository-accepted Markdown hard breaks; unrelated pre-existing dirty files remain untouched and unstaged.

- [ ] **Step 3: Reinstall and launch the final APK**

```powershell
adb -s XHD0223523008702 install -r app\build\outputs\apk\debug\app-arm64-v8a-debug.apk
adb -s XHD0223523008702 shell monkey -p me.rerere.rikkahub.debug -c android.intent.category.LAUNCHER 1
adb -s XHD0223523008702 shell dumpsys package me.rerere.rikkahub.debug | Select-String -Pattern 'versionCode=|versionName=|primaryCpuAbi='
```

Expected: install succeeds, `RouteActivity` is foreground, and package metadata matches the built arm64 APK.

- [ ] **Step 4: Update this plan with final evidence**

Append a dated `Final Verification` section containing:

- exact unit-test count, failures, errors, and suite count;
- final APK absolute path and byte size;
- installed version/ABI;
- filtered instrumentation results;
- real-finger cases passed;
- linked-image natural dimensions;
- FATAL/ANR and idle-layout results;
- any remaining compatibility limitation stated without claiming completion for untested cards.

- [ ] **Step 5: Commit final plan evidence**

```powershell
git add -- docs/superpowers/plans/2026-08-23-tavern-adaptive-render-host.md
git commit -m "docs: record adaptive Tavern render verification"
```
