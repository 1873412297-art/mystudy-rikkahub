# Director Task 6 Verification Report

- Date: 2026-07-16 (Asia/Shanghai)
- Workspace: `C:\Users\18734\Desktop\HTML\rikkahub-port-2.4.1`
- Branch before evidence commit: `codex/port-private-to-2.4.1`
- Feature commit under test: `d715eb34bd8b139ce202d212962dba2f405c9996 feat: add group director controls`
- Production code/resources changed by Task 6: none
- App data policy: used `adb install -r`; app data was not cleared

## Required command results

### Full JVM regression and Debug APK

```powershell
.\gradlew.bat test :app:assembleDebug --console=plain
```

- Result: `BUILD SUCCESSFUL` (first run: 7 s, 288 actionable tasks, 12 executed/276 up-to-date).
- Because the existing APK timestamp preceded the last feature commit by 59 seconds even though Gradle reported it up-to-date, only that generated APK file was removed and the exact command was repeated. The second run was `BUILD SUCCESSFUL` (3 s, 288 actionable tasks, 9 executed/279 up-to-date); `:app:packageDebug` and `:app:assembleDebug` executed.
- APK: `C:\Users\18734\Desktop\HTML\rikkahub-port-2.4.1\app\build\outputs\apk\debug\app-universal-debug.apk`
- APK size: `91,210,298` bytes
- APK last write: `2026-07-16T04:57:06.9124708+08:00`
- APK SHA-256: `94B0EA6FF1DE70E4CD6FD5C6C0F695104975A64EA75620A9DD797A01CF64C802`
- Feature commit time: `2026-07-16T04:48:26+08:00`; rebuilt APK is newer.
- Local raw log: `.superpowers/sdd/task6-evidence/01-full-jvm-assemble.log`

### Connected instrumentation

```powershell
adb -s emulator-5554 wait-for-device
.\gradlew.bat connectedDebugAndroidTest --console=plain
```

- Result: `BUILD SUCCESSFUL` in 58 s; 550 actionable tasks, 18 executed/532 up-to-date.
- App instrumentation XML: 13 tests, 0 failures, 0 errors, 0 skipped.
- `Migration_26_27_Test.migrate26To27_addsRuntimeStateWithEmptyObjectDefault`: PASS.
- `GroupDirectorControlsTest`: 3/3 PASS:
  - `fabUsesDirectorAccessibilityLabel`
  - `memberNominationDispatchesExactlyOneOneShotCommand`
  - `pausedRoundShowsContinueCurrentRoundAndDispatchesCommand`
- Local raw logs: `.superpowers/sdd/task6-evidence/03-connected-debug-android-test.log` and `04-instrumentation-results.log`.

### Device, install, launch, and focus

```powershell
adb devices -l
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-universal-debug.apk
adb -s emulator-5554 shell monkey -p me.rerere.rikkahub.debug -c android.intent.category.LAUNCHER 1
adb -s emulator-5554 shell dumpsys window | Select-String 'mCurrentFocus|mFocusedApp'
```

- Device: `emulator-5554`, model `sdk_gphone64_x86_64`, Android 15, API 35.
- Install: `Success`; version name `2.4.1`, version code `169`.
- Launch: monkey injected one launcher event and exited 0.
- Focus: `me.rerere.rikkahub.debug/me.rerere.rikkahub.RouteActivity` for both `mCurrentFocus` and `mFocusedApp`.
- Local raw logs: `.superpowers/sdd/task6-evidence/02-device-apk.log` and `05-install-launch-focus.log`.

## Emulator smoke matrix

The installed app initially had no conversations and no three-member group. Without clearing data, the UI was used to create three solo assistants (`QA Member`, `Q AA`, `QA B`) and the three-enabled-member group `QA Group`. A separate one-member `Solo Group` was created for the no-alternative check. UI interactions used bounds from step-specific `uiautomator` dumps; screenshots were captured with `adb exec-out screencap -p`.

1. **PASS — group FAB placement and style.** `30-ui-group-new-chat.xml/png` shows the themed director FAB with accessibility label `打开群聊导演台`. FAB bounds `[891,1743][1038,1890]` do not overlap send bounds `[925,2180][1051,2306]` or the input/member controls.
2. **PASS — director sheet visual contract.** `31-ui-director-sheet.xml` and `39-ui-moderator-paused.png` show the Material 3 drag handle, `导演台` title, playback status, pause/continue/skip actions, segmented manual/round-robin/moderator modes, and all three member avatars in the current light theme.
3. **PASS — graceful pause during a successful reply.** The review-fix rerun used the deterministic fixture with four SSE chunks delayed by five seconds each. In a saved three-member round-robin conversation, a normal auto reply was started, `当前角色说完后暂停` was applied while the first reply was still streaming, and `mock-unblock-reviewfix/21-pending-status.xml/png` captured the complete pending label `本条回复结束后暂停` before stream completion. After completion, `22-complete-paused.xml/png` captured `已暂停`; `22-request-count.txt` recorded `REQUEST_COUNT=1`, `STREAM_DONE_COUNT=1`, and `ZERO_EXTRA_REQUEST=True`; and `23-room-final.json` recorded `playbackState=PAUSED`, `oneRoundActive=false`, and no pending one-shot.
4. **PASS — continue one round.** From paused round-robin state, `继续一轮` produced exactly three successful member streams in snapshot order: `QA B`, `QA Member`, then `Q AA`. Each snapshot member appeared once, no duplicate request followed, and Room ended with `playbackState=PAUSED`, `oneRoundActive=false`, and an empty remainder (`mock-unblock/16-one-round-requests.jsonl`, `16-ui-one-round-final-summary.txt`, `16-db-after-one-round.json`).
5. **PASS — moderator early STOP.** The deterministic moderator first returned the UUID for `QA Member`; that member completed one streamed reply; the second moderator call returned `STOP`. No remaining member request followed and the sheet/Room state was paused (`mock-unblock/17-moderator-stop-requests.jsonl`, `17-ui-moderator-stop-final-summary.txt`, `17-db-after-moderator-stop.json`).
6. **PASS — skip-next and single-member notice.** In a persisted paused three-member conversation, Room first recorded `skipNextRequested=true` with no active member and an empty persisted queue, making the normalized first candidate `QA Member`. `继续一轮` then consumed the skip and the following member `Q AA` completed exactly one successful stream before the requested pause. Final Room state recorded active `Q AA`, queue index 1, `skipNextRequested=false`, and paused playback (`mock-unblock/21-skip-pending-db.json`, `21-skip-verified-requests.jsonl`, `21-skip-verified-final.xml`, `21-skip-final-db.json`). On `Solo Group`, tapping skip displayed `暂无其他角色`, captured in `50b-ui-solo-group-skip-notice.png`.
7. **PASS — one-shot nomination.** While paused, tapping the `QA B` avatar produced exactly one successful `QA B` stream and returned the conversation to `已暂停`; persisted director state had no pending one-shot and remained paused (`mock-unblock/19-one-shot-requests.jsonl`, `19-ui-one-shot-final.xml`, `19-db-after-one-shot-correct.json`).
8. **PASS — conversation-only mode override and manual selector.** Manual, round-robin, and moderator segments all became checked when selected. Room state for the saved conversation recorded `auto_round_robin`, then `auto_moderator`; a new `QA Group` conversation still opened in the assistant default manual mode and exposed the existing member selector (`38-ui-round-robin.xml`, `39-db-moderator-paused.txt`, `45-ui-new-group-manual-selector.xml`, `45b-ui-new-group-manual-sheet.xml`).
9. **PASS — page/process restoration without implicit generation.** The saved conversation was left and reopened; UI restored moderator, paused round, and three remaining members (`42b-ui-page-reopen-state.xml`). After `am force-stop` plus launcher monkey, the app was focused again; opening the saved conversation restored the same moderator/paused/three-remaining state (`44b-ui-sheet-after-process.xml`). Room retained active queued member `QA B`, queue cursor 2, and the three-member remainder. Corrected app-PID logcat check found `IMPLICIT_GENERATION_MATCHES_CORRECTED=0` (`43-force-stop-relaunch.log`).
10. **PASS — non-group visibility guard.** A `QA Member` solo conversation UI dump contained zero matches for the director accessibility label or title (`47-ui-nongroup-no-fab.xml`, `DIRECTOR_MATCH_COUNT=0`).

## Successful-output unblock setup

The five initially provider-blocked rows were rerun against a deterministic local OpenAI-compatible fixture without changing production source or clearing app data:

- Host server: `.superpowers/sdd/task6_mock_provider.py`, port `18080`; Android base URL `http://10.0.2.2:18080/v1`.
- Supported test surface: model discovery plus streamed/non-streamed `/v1/chat/completions`, including deterministic moderator UUID then `STOP` behavior.
- Request/chunk audit: `.superpowers/sdd/task6-evidence/mock-unblock/requests.jsonl`.
- Configuration method: a reversible debug-app DataStore snapshot replacement after `adb shell input text` proved unreliable for the URL. The original file was also retained on-device as `settings.preferences_pb.task6-pre-mock`.
- Restore verification: original and restored SHA-256 both `044858f075bf06306e299b516620230ef0f4f0cb45edd2ed1d9a80652c79c58a`, `match=True` (`mock-unblock/22-final-restore.txt`).
- Cleanup: the pre-mock DataStore was restored, the mock server was stopped, and the debug app was relaunched successfully.

### Review-fix graceful-pause evidence

- Focused evidence directory: `.superpowers/sdd/task6-evidence/mock-unblock-reviewfix/`.
- `21-pending-status.xml/png` and `21-pending-status-summary.txt`: in-flight sheet with the exact status `本条回复结束后暂停`.
- `21-pending-requests-at-capture.jsonl`: request/chunk audit copied while the pending status was visible.
- `22-complete-paused.xml/png` and `22-complete-paused-summary.txt`: completed sheet with `已暂停`.
- `22-final-requests.jsonl` and `22-request-count.txt`: exactly one member request, one stream completion, and zero extra request.
- `23-room-final.json`: persisted `auto_round_robin` mode with `playbackState=PAUSED` and `oneRoundActive=false`.
- `24-server-stop.txt`: no listener remained on port 18080.
- `24-settings-restore-before-launch.txt`: the original DataStore was restored byte-for-byte before relaunch.
- `26-final-settings-restore.txt`: because the installed 2.4.1 build normally rewrote the older 2.3.0 settings payload during startup, the original bytes were reapplied after launch and verified with matching SHA-256 `A2A85EAA912E43305C8B7E2316F24ADEC6C445792D90749B51DA46C3E7D049C3`.
- `28-final-settings-recheck.txt`: the same byte-for-byte DataStore hash still matched after the focused Gradle compile completed.
- `26-final-focus.txt` and `26-final-crash.txt`: `RouteActivity` remained focused and the crash buffer was empty.

## Final crash-buffer verification

```powershell
adb -s emulator-5554 logcat -d -b crash
```

- Result: empty crash buffer; `CRASH_MATCHES_APP=0`.
- Final focus remained `me.rerere.rikkahub.debug/me.rerere.rikkahub.RouteActivity`.
- Local raw logs: `.superpowers/sdd/task6-evidence/51-final-focus-crash.log`, `mock-unblock/22-final-focus.txt`, and `mock-unblock/22-final-crash.txt`.

## Result summary

- JVM tests and Debug APK: PASS
- Connected instrumentation: PASS
- Migration 26-to-27: PASS
- Director FAB and original visual style: PASS
- Graceful pause: PASS
- One-round and moderator STOP: PASS
- Skip-next and single-member notice: PASS
- One-shot nomination: PASS
- Conversation-only mode override: PASS
- Page/process restoration: PASS
- Non-group visibility guard: PASS
- Crash buffer: PASS

## Self-review

- Required Gradle commands were run exactly and completed green.
- Universal APK existence, freshness, size, and digest were recorded.
- Instrumentation results were checked in generated XML, not inferred only from Gradle exit status.
- APK was installed with `-r`; app data was never cleared.
- All taps used UI-tree bounds. Each claim above points to a UI dump, screenshot, Room snapshot, instrumentation XML, or filtered log.
- No production or test logic, build output, or local configuration is staged by this verification task; the review fix stages only the requested two-space indentation for the existing `group_director_*` resource lines plus documentation updates.
- Successful-output rows were rerun against an auditable deterministic fixture and are supported by request/chunk logs plus UI/Room evidence.
- The graceful-pause review rerun now includes the previously missing full pending-status UI tree before completion.
- Temporary mock configuration was restored byte-for-byte and the local server was stopped.

## Concerns

1. The user's configured RikkaHub Auto endpoint returned HTTP 402 during the first pass; the deterministic local provider removed that external dependency for the five successful-output smoke rows.
2. The mock implements only the OpenAI-compatible surface required by this verification and was not added to production sources.
3. No blocking Task 6 concern remains.

## Review-fix validation

- All 40 previously unchecked step boxes in Tasks 1-5 are now checked; together with Task 6, the plan records all 47 implementation steps complete.
- Only the newly added `group_director_*` resource lines were changed from four-space to the repository's two-space XML indentation.
- `.\gradlew.bat :app:compileDebugKotlin -x :web:buildWebUi --console=plain`: `BUILD SUCCESSFUL` in 4m 38s; 96 actionable tasks, 5 executed and 91 up-to-date (`mock-unblock-reviewfix/27-compile-debug-kotlin.log`).
- `git diff --check`: clean.

## Final branch-review hardening

- Manual mode is now a graceful barrier: active replies finish under `PAUSE_AFTER_CURRENT`, ordinary auto chains stop, active one-round state pauses with its remainder retained, and the completion-window continuation is rejected before another automatic speaker starts.
- `resolveNextSpeaker` no longer treats `MANUAL` as permission to choose the active/first member. A stale manual `RUNNING` state is normalized to `PAUSED` when automatic selection stops.
- All group-generation cancellation paths use one `NonCancellable` persistence handoff. The persisted director becomes inactive/paused, an unfinished round keeps its remainder, transient nomination/skip state is cleared, the session job/reply phase is released, and callers rethrow `CancellationException`.
- The migration test now inserts a valid legacy row into schema 26 before running `Migration_26_27`, then asserts the migrated row has the `{}` runtime-state default.
- TDD red evidence:
  - Engine tests failed to compile while `afterCancellation` was absent.
  - ChatService tests failed to compile while `normalizeCancelledGroupGeneration` was absent.
  - Manual stale-state coverage failed to compile before `afterNoCandidate` accepted the effective strategy.
- Final validation:
  - `ChatServiceTest` plus all `service.group.*`: 90 tests, 0 failures/errors.
  - Focused `Migration_26_27_Test` on `emulator-5554`: 1 test, PASS.
  - `:app:compileDebugKotlin -x :web:buildWebUi`: PASS.
  - `git diff --check`: clean.
