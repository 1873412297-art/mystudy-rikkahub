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
3. **BLOCKED — graceful pause during a successful reply.** A real `hello` turn started `QA Member` generation, but RikkaHub Auto returned HTTP 402 (`You need positive balance to do inference`) before a reply could stream. Therefore reply completion, pending pause during streaming, and suppression of the next successful auto reply were not observable. The idle command path did transition Room state from `RUNNING` to `PAUSED` and the sheet displayed `已暂停` (`39-ui-moderator-paused.xml`), but that is only partial evidence for this row.
4. **PARTIAL — continue one round.** From paused state, `继续一轮` created a three-member round snapshot and attempted generation. The prior skip request was consumed and the selected valid member was `QA B`; the provider then returned HTTP 402. Room/UI restored paused with `oneRoundActive=true`, three remaining members, and `本轮剩余 3 位`/`继续本轮` (`40-one-round-log.txt`, `40-db-one-round-after-failure.txt`, `40-ui-one-round-provider-failure.xml`). At-most-once replies for all members and normal round completion were not observable.
5. **BLOCKED — moderator early STOP.** Moderator mode selection and persistence were verified, but a moderator response containing `STOP` could not be produced because the configured provider returned HTTP 402. Remaining-reply suppression from an accepted STOP was not observable.
6. **PARTIAL — skip-next; PASS — single-member notice.** On the three-member conversation, `SkipNext` persisted `skipNextRequested=true`; `继续一轮` then consumed it and selected/started `QA B` rather than the next `Q AA` (`38-db-round-robin.txt`, `40-one-round-log.txt`). Successful speech was blocked by HTTP 402. On `Solo Group`, tapping skip displayed `暂无其他角色`, captured in `50b-ui-solo-group-skip-notice.png`.
7. **PARTIAL — one-shot nomination.** Tapping the `QA Member` avatar caused exactly one additional `QA Member` generation start (`35-app-log-filtered.txt`), consistent with the passing Compose test. HTTP 402 prevented a successful reply and therefore prevented observing the normal post-reply return to paused.
8. **PASS — conversation-only mode override and manual selector.** Manual, round-robin, and moderator segments all became checked when selected. Room state for the saved conversation recorded `auto_round_robin`, then `auto_moderator`; a new `QA Group` conversation still opened in the assistant default manual mode and exposed the existing member selector (`38-ui-round-robin.xml`, `39-db-moderator-paused.txt`, `45-ui-new-group-manual-selector.xml`, `45b-ui-new-group-manual-sheet.xml`).
9. **PASS — page/process restoration without implicit generation.** The saved conversation was left and reopened; UI restored moderator, paused round, and three remaining members (`42b-ui-page-reopen-state.xml`). After `am force-stop` plus launcher monkey, the app was focused again; opening the saved conversation restored the same moderator/paused/three-remaining state (`44b-ui-sheet-after-process.xml`). Room retained active queued member `QA B`, queue cursor 2, and the three-member remainder. Corrected app-PID logcat check found `IMPLICIT_GENERATION_MATCHES_CORRECTED=0` (`43-force-stop-relaunch.log`).
10. **PASS — non-group visibility guard.** A `QA Member` solo conversation UI dump contained zero matches for the director accessibility label or title (`47-ui-nongroup-no-fab.xml`, `DIRECTOR_MATCH_COUNT=0`).

## Final crash-buffer verification

```powershell
adb -s emulator-5554 logcat -d -b crash
```

- Result: empty crash buffer; `CRASH_MATCHES_APP=0`.
- Final focus remained `me.rerere.rikkahub.debug/me.rerere.rikkahub.RouteActivity`.
- Local raw log: `.superpowers/sdd/task6-evidence/51-final-focus-crash.log`.

## Result summary

- JVM tests and Debug APK: PASS
- Connected instrumentation: PASS
- Migration 26-to-27: PASS
- Director FAB and original visual style: PASS
- Graceful pause: BLOCKED by provider HTTP 402 for the successful-stream portion; idle pause transition PASS
- One-round and moderator STOP: PARTIAL/BLOCKED by provider HTTP 402
- Skip-next and single-member notice: PARTIAL (selection/start verified; speech blocked) / PASS (notice)
- One-shot nomination: PARTIAL (single dispatch verified; successful reply blocked)
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
- No production source, test source, Android resource, build output, or local configuration is staged by this verification task.
- Smoke rows dependent on successful model output are not marked PASS.

## Concerns

1. The configured RikkaHub Auto endpoint returned HTTP 402 for every attempted generation. A provider with usable inference balance is needed to complete rows 3, 4, 5, 6 speech completion, and 7 end to end.
2. The successful-reply-specific pause/round/STOP semantics remain unverified on emulator despite green JVM/Compose coverage.
3. Task 6 and the overall completion gate should remain open until those blocked rows are rerun with successful streamed replies.
