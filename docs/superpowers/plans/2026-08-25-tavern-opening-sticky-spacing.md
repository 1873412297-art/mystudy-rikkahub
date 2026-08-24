# Tavern Opening Sticky Spacing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the 12 CSS px gap above the sticky Tavern opening-greeting switcher without changing ordinary chat spacing.

**Architecture:** Keep the existing WebView document structure and global `#chat` padding. Add one opening-only sticky offset in `tavern-conversation.html`, protected by the existing document-level JVM test, then verify the rendered geometry and screenshot on the connected Huawei device.

**Tech Stack:** Android/Kotlin, JUnit, HTML/CSS inside Android WebView, Gradle, ADB, Chrome DevTools Protocol.

## Global Constraints

- Only the opening-greeting sticky toolbar may change; ordinary immersive chat spacing must remain unchanged.
- Keep the switch buttons, counter, toolbar height, background, message spacing, and sticky behavior unchanged.
- Device acceptance target is Huawei MNA-AL00 (`XHD0223523008702`) running the arm64 Debug APK.
- Final sticky toolbar top must measure `0px` in the WebView scroll viewport and the screenshot must show no text leaking above it.

---

### Task 1: Make the opening switcher flush with the scroll viewport

**Files:**
- Modify: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocumentTest.kt:345`
- Modify: `app/src/main/assets/html/tavern-conversation.html:81`

**Interfaces:**
- Consumes: `#chat` with `padding: 12px 8px 24px` and `.mes.opening-swipe .opening-swipe-nav` with sticky positioning.
- Produces: opening-only CSS whose sticky top is offset by `-12px`; no Kotlin API changes.

- [ ] **Step 1: Write the failing regression assertion**

Extend `opening navigation uses a sticky toolbar without covering the message` with:

```kotlin
assertTrue(openingCss.contains("top: -12px"))
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationDocumentTest.opening navigation uses a sticky toolbar without covering the message"
```

Expected: `FAILED`; the new assertion is false because production CSS still contains `top: 0`.

- [ ] **Step 3: Apply the minimal opening-only CSS fix**

Change the opening toolbar rule to:

```css
.mes.opening-swipe .opening-swipe-nav { position: sticky; top: -12px; z-index: 4; display: flex; align-items: center; justify-content: center; gap: 10px; min-height: 48px; padding: 2px 0 8px; background: var(--rikkahub-surface); }
```

- [ ] **Step 4: Run focused and class-level tests and verify GREEN**

Run:

```powershell
./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationDocumentTest.opening navigation uses a sticky toolbar without covering the message"
./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationDocumentTest"
```

Expected: both commands finish with `BUILD SUCCESSFUL` and zero failed tests.

- [ ] **Step 5: Build, install, launch, and reproduce**

Run:

```powershell
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb shell monkey -p me.rerere.rikkahub.debug -c android.intent.category.LAUNCHER 1
```

Expected: Gradle `BUILD SUCCESSFUL`, ADB `Success`, and `RouteActivity` in the foreground. Return to the existing opening-greeting conversation and scroll until the switcher sticks.

- [ ] **Step 6: Verify geometry, screenshot, controls, and runtime health**

Use CDP `Runtime.evaluate` to read `getBoundingClientRect().top` for `.opening-swipe-nav`; expected value is `0`. Capture `verification-screenshots/greeting-spacing-after.png`; the toolbar must touch the scroll viewport top with no message text visible above it. Tap a greeting arrow once and confirm the counter changes, then check filtered logcat for `FATAL EXCEPTION` and ANR entries.

- [ ] **Step 7: Commit the focused fix**

```powershell
git add app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocumentTest.kt app/src/main/assets/html/tavern-conversation.html
git commit -m "fix: remove Tavern opening sticky gap"
```
