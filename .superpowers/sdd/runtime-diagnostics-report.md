# Browser script runtime diagnostics report

## Outcome

- Added a process-local, per-script diagnostics store with a 500-entry ring buffer, status flow, copy-safe redaction, and a compact serializable entry model.
- Browser sessions now forward console debug/info/warn/error and lifecycle/runtime errors through `RikkahubScriptBridge` without suppressing the browser console.
- Session selection keeps global → character → assistant/preset repository order, retains the 32-session cap, and labels enabled overflow scripts `OVER_LIMIT` instead of silently losing their state.
- Added a native full-screen script log route with back, copy, clear, reload, and explicit empty state. Script rows and folder children display Chinese runtime status and open their own logs.

## RED evidence

1. Before production changes, ran:

   ```powershell
   .\gradlew.bat :app:testDebugUnitTest --tests 'me.rerere.rikkahub.ui.components.richtext.runtime.TavernBrowserSessionHtmlTest'
   ```

   Result: expected failure at `TavernBrowserSessionHtmlTest.kt:44`, because generated session HTML did not contain the diagnostics bridge / console interception.

2. After the first minimal implementation, added the explicit unload-status expectation and ran the same focused test.

   Result: expected failure at `TavernBrowserSessionHtmlTest.kt:48`, because unload did not explicitly call `lifecycle('paused')`.

## GREEN evidence

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'me.rerere.rikkahub.ui.components.richtext.runtime.TavernBrowserSessionHtmlTest' --tests 'me.rerere.rikkahub.ui.components.richtext.runtime.TavernScriptDiagnosticsTest' --tests 'me.rerere.rikkahub.ui.components.richtext.runtime.TavernBrowserScriptSelectionTest'
# BUILD SUCCESSFUL (7 focused JVM tests)

.\gradlew.bat :app:compileDebugKotlin
# BUILD SUCCESSFUL

git diff --check
# exit 0 (only repository line-ending warnings)
```

## Files

- `TavernScriptDiagnostics.kt`: model, statuses, bounded per-script store, redaction, Chinese labels.
- `TavernBrowserSessionHtml.kt` and `TavernBrowserScriptBridge.kt`: console/lifecycle bridge injection and capture.
- `MarkdownWebView.kt` and `TavernBrowserRuntimeHost.kt`: main-frame load-failure diagnostics, isolated reload, ordered/capped selection.
- `TavernHelperPage.kt`, `TavernScriptLogPage.kt`, and `RouteActivity.kt`: status presentation and native log route.
- Added diagnostics and selection JVM tests; expanded session HTML test.

## Commit

This report is included in the focused diagnostics commit at the final `HEAD` of `codex/port-private-to-2.4.10`.

## Self-review

- Each script owns its own log deque and clear operation; no failure or clear path mutates another script's entries.
- All stored and copied message/error text passes through redaction, including JSON-shaped Authorization/Bearer values.
- `verification-screenshots/` remains untracked and untouched.

## Concerns

- Diagnostics are intentionally process-local, so history is cleared on process death.
- Existing project-wide Compose/Kotlin deprecation and opt-in warnings remain; this task introduces no build errors.
