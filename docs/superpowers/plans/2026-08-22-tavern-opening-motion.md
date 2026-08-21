# Tavern Opening Motion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add restrained ambient motion to Tavern character-card backgrounds and directional transitions to `1 / 5` opening switches while removing the duplicated top-bar opening button.

**Architecture:** Keep the native Compose background and the single Tavern WebView as independent motion layers. A small pure Kotlin policy controls whether image motion is allowed; the WebView infers opening direction from consecutive snapshots, so the Tavern protocol and bridge stay unchanged.

**Tech Stack:** Kotlin, Jetpack Compose animation/graphics layers, Android `ValueAnimator`, HTML/CSS keyframes, vanilla JavaScript, JUnit 4, Gradle, ADB.

## Global Constraints

- Keep the native TopBar, StatusHudBar, and ChatInput stationary.
- Background cycle is 14 seconds with scale held within `1.015..1.045` and translation below 1.2% of the container.
- Opening transition is 220ms with directional translation, opacity `0.35..1`, and scale `0.985..1`.
- Initial load, normal message refresh, streaming, and same-index readiness updates must not animate as opening switches.
- Android disabled animators and CSS `prefers-reduced-motion: reduce` must produce static updates.
- Do not add protocol fields, bridge methods, JavaScript timers, blur filters, particle layers, or image re-decodes.
- Preserve `touch-action: pan-y`, the current horizontal swipe threshold, scroll position, and newest-selection-wins behavior.
- Do not run `connectedDebugAndroidTest`; install with `adb install -r` so device application data is preserved.

---

## File Map

- Create `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/TavernBackgroundMotion.kt`: pure image-motion policy and exact constants.
- Create `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/TavernBackgroundMotionTest.kt`: unit tests for enablement and bounds.
- Modify `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/Background.kt`: apply one lifecycle-owned Compose phase animation to the image layer.
- Modify `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`: enable image motion only for the Tavern presentation and remove the duplicated opening shortcut.
- Modify `app/src/main/assets/html/tavern-conversation.html`: infer opening direction and animate only `.mes_block`.
- Modify `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/BackgroundPerformanceTest.kt`: verify Tavern-only wiring and preserve frozen mesh gradients.
- Modify `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocumentTest.kt`: verify transition behavior and reduced-motion fallback.
- Modify `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernPresentationContractsTest.kt`: prevent the duplicate top-bar opening shortcut from returning.

---

### Task 1: Remove the duplicated top-bar opening shortcut

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernPresentationContractsTest.kt`

**Interfaces:**
- Consumes: the existing in-message `openingSwipe` controls and `TavernConversationBridge.selectGreeting(...)`.
- Produces: `TopBar(...)` without an `onOpenOpening` callback or `BookOpen01` action.

- [ ] **Step 1: Write the failing source-contract test**

Add this test to `TavernPresentationContractsTest`:

```kotlin
@Test
fun `native top bar does not duplicate the inline opening selector`() {
    val chatPage = listOf(
        File("src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt"),
        File("app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt"),
    ).first { it.exists() }.readText()
    val topBarFunction = chatPage.substringAfter("private fun TopBar(")

    assertFalse(topBarFunction.contains("onOpenOpening"))
    assertFalse(topBarFunction.contains("HugeIcons.BookOpen01"))
    assertFalse(topBarFunction.contains("查看开场"))
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.chat.tavern.TavernPresentationContractsTest.native top bar does not duplicate the inline opening selector"
```

Expected: FAIL at the first assertion because `TopBar` still exposes `onOpenOpening`.

- [ ] **Step 3: Remove only the duplicate action**

In `ChatPage.kt`:

1. Remove `import me.rerere.hugeicons.stroke.BookOpen01`.
2. Remove the `onOpenOpening = ...` argument from the `TopBar` call.
3. Remove `onOpenOpening: (() -> Unit)?` from `TopBar`.
4. Remove this action block:

```kotlin
if (onOpenOpening != null) {
    IconButton(onClick = onOpenOpening) {
        Icon(HugeIcons.BookOpen01, contentDescription = "查看开场")
    }
}
```

Do not remove `currentOpeningMessage`, the fullscreen message viewer, or the in-message swipe controls.

- [ ] **Step 4: Run the test and verify GREEN**

Run the Step 2 command again.

Expected: BUILD SUCCESSFUL; one test passes.

- [ ] **Step 5: Commit the focused cleanup**

```powershell
git add app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernPresentationContractsTest.kt
git commit -m "fix: remove duplicate Tavern opening shortcut"
```

---

### Task 2: Add a reduced-motion-aware background policy

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/TavernBackgroundMotion.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/TavernBackgroundMotionTest.kt`

**Interfaces:**
- Consumes: `animateImage`, background presence, and `ValueAnimator.areAnimatorsEnabled()`.
- Produces: `resolveTavernBackgroundMotion(Boolean, Boolean, Boolean, Boolean): TavernBackgroundMotion`.

- [ ] **Step 1: Write failing policy tests**

Create `TavernBackgroundMotionTest.kt`:

```kotlin
package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernBackgroundMotionTest {
    @Test
    fun `motion is enabled only for an animated Tavern image`() {
        assertTrue(resolveTavernBackgroundMotion(true, true, true, true).enabled)
        assertFalse(resolveTavernBackgroundMotion(false, true, true, true).enabled)
        assertFalse(resolveTavernBackgroundMotion(true, false, true, true).enabled)
    }

    @Test
    fun `disabled system animators produce a static policy`() {
        assertEquals(TavernBackgroundMotion.Static, resolveTavernBackgroundMotion(true, true, false, true))
        assertEquals(TavernBackgroundMotion.Static, resolveTavernBackgroundMotion(true, true, true, false))
    }

    @Test
    fun `ambient motion stays inside restrained visual bounds`() {
        val motion = resolveTavernBackgroundMotion(true, true, true, true)

        assertTrue(motion.minScale >= 1.015f)
        assertTrue(motion.maxScale <= 1.045f)
        assertTrue(motion.translationFraction <= 0.012f)
        assertEquals(14_000, motion.durationMillis)
    }
}
```

- [ ] **Step 2: Run the tests and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.chat.TavernBackgroundMotionTest"
```

Expected: compilation fails because `TavernBackgroundMotion` and `resolveTavernBackgroundMotion` do not exist.

- [ ] **Step 3: Implement the pure policy**

Create `TavernBackgroundMotion.kt`:

```kotlin
package me.rerere.rikkahub.ui.pages.chat

internal data class TavernBackgroundMotion(
    val enabled: Boolean,
    val minScale: Float,
    val maxScale: Float,
    val translationFraction: Float,
    val durationMillis: Int,
) {
    companion object {
        val Static = TavernBackgroundMotion(
            enabled = false,
            minScale = 1f,
            maxScale = 1f,
            translationFraction = 0f,
            durationMillis = 0,
        )
    }
}

internal fun resolveTavernBackgroundMotion(
    animateImage: Boolean,
    hasBackground: Boolean,
    animatorsEnabled: Boolean,
    pageVisible: Boolean,
): TavernBackgroundMotion = if (animateImage && hasBackground && animatorsEnabled && pageVisible) {
    TavernBackgroundMotion(
        enabled = true,
        minScale = 1.015f,
        maxScale = 1.045f,
        translationFraction = 0.006f,
        durationMillis = 14_000,
    )
} else {
    TavernBackgroundMotion.Static
}
```

- [ ] **Step 4: Run the tests and verify GREEN**

Run the Step 2 command again.

Expected: BUILD SUCCESSFUL; three tests pass.

- [ ] **Step 5: Commit the policy**

```powershell
git add app/src/main/java/me/rerere/rikkahub/ui/pages/chat/TavernBackgroundMotion.kt app/src/test/java/me/rerere/rikkahub/ui/pages/chat/TavernBackgroundMotionTest.kt
git commit -m "feat: define restrained Tavern background motion"
```

---

### Task 3: Animate only the native character image layer

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/Background.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/BackgroundPerformanceTest.kt`

**Interfaces:**
- Consumes: `resolveTavernBackgroundMotion(...)` from Task 2.
- Produces: `AssistantBackground(..., animateImage: Boolean = false)`.

- [ ] **Step 1: Extend the source-contract test and verify RED**

Add these assertions to `immersive Tavern freezes the animated background behind its WebView`:

```kotlin
assertTrue(chatPage.contains("animateImage = useTavernWeb"))
assertTrue(background.contains("animateImage: Boolean = false"))
assertTrue(background.contains("ValueAnimator.areAnimatorsEnabled()"))
assertTrue(background.contains("Lifecycle.State.RESUMED"))
assertTrue(background.contains("resolveTavernBackgroundMotion("))
assertTrue(background.contains("graphicsLayer"))
```

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.chat.BackgroundPerformanceTest"
```

Expected: FAIL because the new image-motion wiring is absent; the existing mesh-freeze assertions remain green.

- [ ] **Step 2: Add the Tavern-only call-site flag**

Change the existing call in `ChatPage.kt` to:

```kotlin
AssistantBackground(
    setting = setting,
    modifier = Modifier.hazeSource(hazeState),
    animateGradient = !useTavernWeb,
    animateImage = useTavernWeb,
)
```

- [ ] **Step 3: Add one phase animation to `Background.kt`**

Add `animateImage: Boolean = false` to `AssistantBackground`. In the image-background branch, resolve the policy with:

```kotlin
val motion = resolveTavernBackgroundMotion(
    animateImage = animateImage,
    hasBackground = true,
    animatorsEnabled = ValueAnimator.areAnimatorsEnabled(),
    pageVisible = lifecycleState.isAtLeast(Lifecycle.State.RESUMED),
)
val phase = if (motion.enabled) {
    val transition = rememberInfiniteTransition(label = "tavern_background")
    val animatedPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2.0).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(motion.durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "tavern_background_phase",
    )
    animatedPhase
} else {
    0f
}
```

Apply it only to `AsyncImage`:

```kotlin
.graphicsLayer {
    if (motion.enabled) {
        val wave = ((sin(phase) + 1f) / 2f)
        val scale = motion.minScale + (motion.maxScale - motion.minScale) * wave
        scaleX = scale
        scaleY = scale
        translationX = size.width * motion.translationFraction * cos(phase)
        translationY = size.height * motion.translationFraction * 0.65f * sin(phase)
    }
}
```

Obtain `lifecycleState` from `LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()` before resolving the policy. Import `android.animation.ValueAnimator`, Compose animation-core functions, `graphicsLayer`, `getValue`, `Lifecycle`, `LocalLifecycleOwner`, `collectAsStateWithLifecycle`, `sin`, and `cos`. Leave the overlay `Box`, opacity, crop mode, and `MeshGradientBackground(animated = animateGradient)` unchanged.

- [ ] **Step 4: Run policy and performance tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.chat.TavernBackgroundMotionTest" --tests "me.rerere.rikkahub.ui.pages.chat.BackgroundPerformanceTest"
```

Expected: BUILD SUCCESSFUL; all selected tests pass.

- [ ] **Step 5: Commit the native background motion**

```powershell
git add app/src/main/java/me/rerere/rikkahub/ui/pages/chat/Background.kt app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt app/src/test/java/me/rerere/rikkahub/ui/pages/chat/BackgroundPerformanceTest.kt
git commit -m "feat: animate Tavern character backgrounds"
```

---

### Task 4: Add directional opening transitions inside the WebView

**Files:**
- Modify: `app/src/main/assets/html/tavern-conversation.html`
- Modify: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocumentTest.kt`

**Interfaces:**
- Consumes: consecutive `state.openingSwipe.index` values already present in snapshot protocol v2.
- Produces: `resolveOpeningTransition(previousSnapshot, nextSnapshot): -1 | 0 | 1` and one-shot CSS classes on `.mes_block`.

- [ ] **Step 1: Write failing document tests**

Add these tests to `TavernConversationDocumentTest`:

```kotlin
@Test
fun `opening switch uses restrained directional motion`() {
    assertTrue(template.contains("@keyframes opening-enter-forward"))
    assertTrue(template.contains("@keyframes opening-enter-backward"))
    assertTrue(template.contains("220ms cubic-bezier(.2,.8,.2,1)"))
    assertTrue(template.contains("translateX(18px) scale(.985)"))
    assertTrue(template.contains("translateX(-18px) scale(.985)"))
    assertTrue(template.contains("opacity: .35"))
}

@Test
fun `opening motion is inferred only from changed opening indices`() {
    val resolver = template.substringAfter("function resolveOpeningTransition")
        .substringBefore("function renderMarkdownPart")

    assertTrue(resolver.contains("previousSnapshot.openingSwipe.index"))
    assertTrue(resolver.contains("nextSnapshot.openingSwipe.index"))
    assertTrue(resolver.contains("prefers-reduced-motion: reduce"))
    assertTrue(template.contains("pendingOpeningDirection = resolveOpeningTransition(state, patch.snapshot)"))
    assertTrue(template.contains("pendingOpeningDirection = 0"))
}
```

- [ ] **Step 2: Run the tests and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationDocumentTest.opening switch uses restrained directional motion" --tests "me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationDocumentTest.opening motion is inferred only from changed opening indices"
```

Expected: both tests fail because the keyframes and resolver are absent.

- [ ] **Step 3: Add scoped CSS keyframes**

Add:

```css
.mes.opening-forward .mes_block { animation: opening-enter-forward 220ms cubic-bezier(.2,.8,.2,1) both; }
.mes.opening-backward .mes_block { animation: opening-enter-backward 220ms cubic-bezier(.2,.8,.2,1) both; }
@keyframes opening-enter-forward {
  from { opacity: .35; transform: translateX(18px) scale(.985); }
  to { opacity: 1; transform: translateX(0) scale(1); }
}
@keyframes opening-enter-backward {
  from { opacity: .35; transform: translateX(-18px) scale(.985); }
  to { opacity: 1; transform: translateX(0) scale(1); }
}
@media (prefers-reduced-motion: reduce) {
  .mes { transition: none; }
  .mes.opening-forward .mes_block,
  .mes.opening-backward .mes_block { animation: none; }
}
```

The selector targets only `.mes_block`, leaving the left/right buttons fixed.

- [ ] **Step 4: Infer direction without changing the bridge**

Declare `var pendingOpeningDirection = 0;` next to `state`. Add before `renderMarkdownPart`:

```javascript
function resolveOpeningTransition(previousSnapshot, nextSnapshot) {
  if (!previousSnapshot || !nextSnapshot || !previousSnapshot.openingSwipe || !nextSnapshot.openingSwipe) return 0;
  if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) return 0;
  var previousIndex = Number(previousSnapshot.openingSwipe.index);
  var nextIndex = Number(nextSnapshot.openingSwipe.index);
  if (!Number.isFinite(previousIndex) || !Number.isFinite(nextIndex) || previousIndex === nextIndex) return 0;
  return nextIndex > previousIndex ? 1 : -1;
}
```

In `renderNode`, after calculating `isOpeningSwipe`, add the appropriate class to `mes` only for the active opening:

```javascript
if (isOpeningSwipe && pendingOpeningDirection !== 0) {
  mes.classList.add(pendingOpeningDirection > 0 ? 'opening-forward' : 'opening-backward');
}
```

Reset after `renderAll` finishes:

```javascript
pendingOpeningDirection = 0;
```

Update only the `replace_all` branch:

```javascript
if (patch.type === 'replace_all') {
  pendingOpeningDirection = resolveOpeningTransition(state, patch.snapshot);
  state = patch.snapshot;
  renderAll();
  return;
}
```

This ensures initial document load, same-index ready changes, streaming patches, and ordinary message patches remain static. A later replacement removes an earlier animated DOM node, so rapid switching keeps only the newest target.

- [ ] **Step 5: Run the complete document test class**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationDocumentTest"
```

Expected: BUILD SUCCESSFUL; all document tests pass.

- [ ] **Step 6: Commit the WebView transition**

```powershell
git add app/src/main/assets/html/tavern-conversation.html app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocumentTest.kt
git commit -m "feat: animate Tavern opening switches"
```

---

### Task 5: Full verification, APK installation, and real-device acceptance

**Files:**
- Verify: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
- Create verification evidence under: `verification-screenshots/tavern-opening-motion/`

**Interfaces:**
- Consumes: Tasks 1–4.
- Produces: a tested arm64 Debug APK installed on `XHD0223523008702` without clearing application data.

- [ ] **Step 1: Run focused regression tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.chat.TavernBackgroundMotionTest" --tests "me.rerere.rikkahub.ui.pages.chat.BackgroundPerformanceTest" --tests "me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationDocumentTest" --tests "me.rerere.rikkahub.ui.pages.chat.tavern.TavernPresentationContractsTest"
```

Expected: BUILD SUCCESSFUL with zero failed tests.

- [ ] **Step 2: Run the complete JVM suite and build the APK**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Expected: BUILD SUCCESSFUL and `app-arm64-v8a-debug.apk` exists.

- [ ] **Step 3: Preserve device data and install**

First verify the device and package:

```powershell
adb -s XHD0223523008702 get-state
adb -s XHD0223523008702 shell pm path me.rerere.rikkahub.debug
```

Then install without uninstalling:

```powershell
adb -s XHD0223523008702 install -r app\build\outputs\apk\debug\app-arm64-v8a-debug.apk
adb -s XHD0223523008702 shell monkey -p me.rerere.rikkahub.debug -c android.intent.category.LAUNCHER 1
```

Expected: device state `device`, package path present, install `Success`, and RouteActivity launches.

- [ ] **Step 4: Verify visible behavior on the existing Tavern card**

On the restored `慈脂佛母` conversation:

1. Confirm the book icon is absent from the native top bar.
2. Observe the background for at least five seconds; confirm slow drift without edge exposure or flicker.
3. Tap next: confirm `1 / 5 → 2 / 5` enters from the right in about 220ms.
4. Tap previous: confirm `2 / 5 → 1 / 5` enters from the left.
5. Swipe vertically through the opening, then swipe horizontally; confirm vertical scrolling remains smooth and the horizontal gesture switches once.
6. Tap next rapidly three times; confirm only the final selected opening remains visible.
7. Confirm TopBar, StatusHudBar, and ChatInput do not move during either animation.

- [ ] **Step 5: Capture evidence and check crashes**

```powershell
New-Item -ItemType Directory -Force verification-screenshots\tavern-opening-motion | Out-Null
adb -s XHD0223523008702 exec-out screencap -p > verification-screenshots\tavern-opening-motion\final.png
adb -s XHD0223523008702 logcat -d -t 1500 | Select-String -Pattern 'FATAL EXCEPTION'
git diff --check
```

Expected: screenshot shows the cleaned top bar and native input; FATAL search returns no application crash; `git diff --check` reports no whitespace errors.

- [ ] **Step 6: Record verification without committing unrelated work**

```powershell
git status --short
git log -5 --oneline
```

Confirm that only the focused task commits and pre-existing user changes are present. Do not stage `.tmp-inspect`, generated APKs, unrelated reports, or existing dirty files.
