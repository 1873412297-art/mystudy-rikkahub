# Task 5 Report: Floating HUD and bottom panel

## Status

DONE

## Implementation

- Added `StatusHudPresentation` and `buildStatusHudPresentation` as a pure conversation-to-HUD projection.
  It selects the newest assistant status, preserves the source message, structured sections, story options,
  full placeholder HTML and all character pages, and emits a content-derived update identity plus updating state.
- Replaced the old in-layout expandable card with a compact floating summary overlay in `ChatPage`.
- Moved full HUD content into a modal bottom sheet whose content is capped at 90% height. The sheet retains
  collapsible sections, secure HTML rendering, multi-character paging and story options.
- Changed story-option behavior to call `ChatInputState.setMessageText` and dismiss the sheet. There is no send
  callback in the selection helper and no HUD path to `ChatVM.handleMessageSend`.
- Added secondary-viewer ownership control to `MarkdownWebView` and `MultiCharacterStatusView`. HUD WebViews use
  an isolated script registry and a disabled `TavernSendHookControllerBinding`, so opening the sheet cannot replace
  the main conversation WebView's send-hook owner.
- No web-ui files were changed.

## TDD evidence

### RED

Command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests me.rerere.rikkahub.ui.pages.chat.StatusHudPresentationTest --no-daemon
```

Observed result: `:app:compileDebugUnitTestKotlin FAILED` because `buildStatusHudPresentation` and
`selectStatusHudOption` did not exist. The four intended behaviors therefore failed before production code was added.

### GREEN focused tests

Command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests me.rerere.rikkahub.ui.pages.chat.StatusHudPresentationTest --tests me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationBridgeTest --no-daemon
```

Result: `BUILD SUCCESSFUL` (focused HUD behavior and the reused send-hook ownership binding).

Covered behaviors:

- newest status/header wins;
- same-message streaming content advances update identity and finish state;
- complete placeholder HTML and every character page survive projection;
- option selection invokes prefill then dismiss, with no send callback.

## Final verification

Command:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug --no-daemon
```

Result: `BUILD SUCCESSFUL in 18s`; 242 actionable tasks (13 executed, 229 up-to-date).

JUnit XML summary: 101 test classes, 742 tests, 0 failures, 0 errors, 0 skipped.

APK outputs included:

- `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` (84,638,295 bytes)
- `app/build/outputs/apk/debug/app-universal-debug.apk` (95,505,445 bytes)
- `app/build/outputs/apk/debug/app-x86_64-debug.apk` (85,485,557 bytes)

`git diff --check` reported no whitespace errors (only Windows line-ending conversion notices).

## Self-review

- Confirmed `StatusHudBar` and its option callback contain no call to `handleMessageSend`.
- Confirmed the HUD is a sibling overlay aligned to the top of the message host, not a child consuming the
  conversation column's height.
- Confirmed all HUD HTML WebViews pass `ownsSendHookController = false` and retain file/network/runtime safety from
  the shared `MarkdownWebView` host.
- Confirmed no plan/ledger or web-ui files were modified.

## Concerns

None.
