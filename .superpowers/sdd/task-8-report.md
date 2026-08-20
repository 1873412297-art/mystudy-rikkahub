# Task 8 Report — Integration, instrumentation, and Huawei acceptance

## Scope

Completed final acceptance from base `8ba9231e`. Added visible-Activity device coverage for the immersive conversation document and its recovery UI, ran the required Android and web-ui verification, installed the arm64 build on the named Huawei, exercised the available real cards plus a controlled 12-opening fixture, and recorded all observed limits. No production runtime behavior or Room schema was changed.

## Delivered coverage

- `TavernImmersiveRuntimeInstrumentedTest` loads the real conversation document in a visible `TavernRuntimeSmokeActivity` and executes raw iframe HTML/JavaScript through the shipped runtime.
- The same test proves variables increment from 1 to 2 across a whole-document reload, registers a macro only in the first document and executes that retained host macro after reload without re-registering it, and receives the new context/current-message values.
- The authenticated native bridge is exercised for message long-press, branch selection, raw-HTML viewer, and Compose fallback actions. File/content WebView access remains disabled.
- A debug-only visible `TavernConversationRecoveryActivity` hosts the actual `TavernConversationWebView`. A real main-frame error from a tiny WebView probe is delivered to its real client, proving FAILED state, exact static-source preservation, retry into a different WebView instance, a second failure, and Compose fallback callback.
- UIAutomator dismisses only the Huawei notification-permission prompt by its permission-controller resource ID and verifies the fixture regains the foreground. This prevents a system dialog from being misreported as a missing Compose root.
- Added the existing version-catalog UIAutomator dependency to `androidTest`; no runtime dependency was added.

## Files

- `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernImmersiveRuntimeInstrumentedTest.kt`
- `app/src/debug/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationRecoveryActivity.kt`
- `app/src/debug/AndroidManifest.xml`
- `app/build.gradle.kts`
- `docs/superpowers/plans/2026-08-20-tavern-immersive-presentation.md`
- `.superpowers/sdd/task-8-report.md`

## TDD evidence

### RED

The initial instrumentation implementation attempted to instantiate Android's platform-owned `WebResourceError`, so Android-test compilation failed before a device test could run. The recovery test was rewritten to create a genuine main-frame error by loading a denied missing file in a probe WebView and forwarding the framework objects to the real target client.

The first visible recovery run then failed three times while waiting for Compose roots. Read-only Activity and hierarchy inspection showed `com.android.permissioncontroller` in front of the debug fixture. After adding targeted dismissal and a foreground assertion, the exact recovery flow became green. Task 8 did not uncover a production behavior defect; its code changes remain device coverage and debug-only fixture plumbing.

### GREEN

```text
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationDocumentInstrumentedTest,me.rerere.rikkahub.ui.components.richtext.MarkdownWebViewReloadInstrumentedTest,me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeSmokeTest,me.rerere.rikkahub.ui.pages.chat.tavern.TavernImmersiveRuntimeInstrumentedTest" --no-daemon
7 tests / 0 skipped / 0 failed / BUILD SUCCESSFUL in 1m 21s

.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.pages.chat.tavern.TavernImmersiveRuntimeInstrumentedTest" --no-daemon
2 tests / 0 skipped / 0 failed / BUILD SUCCESSFUL in 1m 18s after review fix
```

An unquoted PowerShell attempt parsed the Gradle property as an unknown task and stopped before test execution. Quoting the property as shown above corrected the invocation.

## Complete automated verification

```text
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug --no-daemon
BUILD SUCCESSFUL in 25s
108 JVM test classes / 776 tests / 0 failures / 0 errors / 0 skipped

cd web-ui
pnpm test
3 files / 41 tests passed

pnpm typecheck
passed

pnpm lint
passed with 0 errors and 7 pre-existing warnings

pnpm build
passed; source-map and chunk-size warnings only
```

The Android test compiler still emits the repository's existing unresolved `ExperimentalNavigation3Api` opt-in warning; it is non-fatal.

## Device and install evidence

- Device: `XHD0223523008702`, Huawei `MNA-AL00`, Android 12, `arm64-v8a`.
- `opencode.exe` was absent at the preflight and final mutation boundaries.
- APK metadata: version code 172, version name 2.4.5.
- Enumerated outputs:
  - `app-universal-debug.apk` — 96,655,931 bytes.
  - `app-x86_64-debug.apk` — 86,636,043 bytes.
  - `app-arm64-v8a-debug.apk` — 85,788,781 bytes.
- Selected/install command:

```text
adb -s XHD0223523008702 install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
Performing Streamed Install
Success
```

- Final package state: `me.rerere.rikkahub.debug`, version code 172, version name 2.4.5, `primaryCpuAbi=arm64-v8a`; `RouteActivity` launched successfully.
- The last connected-test rerun uninstalled its test target. The same verified arm64 artifact was therefore installed again afterward so the final device state is installed, not merely tested.

## Card inspection and acceptance matrix

The original files under `/sdcard/Download/角色卡/` and `/sdcard/Pictures/角色卡/` were inspected read-only. There were four paths representing three cards: one duplicated V3 card with 9 greetings and two cards with 5 and 4 greetings. None had HTML or scripts. No user file was written, renamed, or removed.

| Scenario | Evidence | Result |
| --- | --- | --- |
| Real multi-opening stage | 9-greeting V3 real card showed 9 simultaneous WebView targets and the previous/use/next controls | Pass within available real-card limit |
| At least 12 candidates | Controlled safe V3 PNG showed 12 simultaneous targets and retained 12 through 11 next selections | Pass, controlled fixture |
| Candidate commit/memory | Commit changed target count 12 to 1; point sample was app ~699,132 KiB plus WebView sandbox ~236,104 KiB | Pass; baseline only, not leak proof |
| First-message collapse/replay | First user message collapsed the stage; top opening action opened full-screen replay and the new-conversation chooser | Pass |
| ST/Compose fallback | Authenticated document action showed compatibility view and retry; retry restored ST | Pass |
| HUD behavior | Real card showed floating header/update state and four story choices; selecting one closed the sheet and prefilled the composer | Pass |
| No auto-send | Draft remained unchanged after three seconds; no send action was observed | Pass |
| Theme | Visible target reported light `#F6FAFF`, dark `#0D1419`, then light again; DOM/HUD remained available | Pass |
| Rotation/back | Landscape retained WebView/HUD; Back dismissed the sheet and kept `RouteActivity`; portrait/auto-rotate restored | Pass |
| Crash audit | Manual log window and crash buffer: 0 FATAL/ANR/OOM/`RenderProcessGone`/process-death matches | Pass |

The controlled fixture was created outside the repository with local-only HTML/JavaScript. Its SHA-256 was `b30bc5d93a549f55ca7613a2ee52b79c1bcc6f04e9e54c9d855b7d3468d837c0`. The device copy at `/sdcard/Download/task8-acceptance-12.png` was hash-verified and then removed exactly; the temporary source remains outside the repository. The imported debug-app data was subsequently cleared by the connected-test uninstall, while user card files remained untouched.

## Explicit limitations

- No supplied real card had 12 greetings; the largest had 9. The 12-candidate evidence therefore comes from the controlled fixture and is not described as real-card evidence.
- No supplied real card contained HTML or scripts. Full HTML/JavaScript, macro, variable, reload, context, and action behavior is covered by the visible instrumentation test and controlled HTML fixture, not attributed to those real cards.
- External-network side effects were deliberately not generated by the controlled fixture, and no real card supplied a script that could test them.
- No live model completion was required for these presentation scenarios and none was exercised.

## Independent review

The first read-only review reported two Important findings and one cleanup issue:

1. The first version registered the same macro in both documents, so it did not prove host persistence.
2. `.superpowers/sdd/task-8-report.md` is covered by the shared Git exclude and needed an explicit force-add.
3. Assertion failure could leave the manually launched Activity/WebView alive.

The test now registers only in the first document, records `registeredThisDocument=false` after reload, directly executes the retained owner-scoped host macro and asserts `macro:after-reload`, and cleans the controller, JavaScript interfaces, WebView, and Activity in `finally`. The report is present in the Git index despite the shared exclude. The same reviewer re-read the full Task 8 package and returned `Approved` with no remaining Blocker or Important finding.
