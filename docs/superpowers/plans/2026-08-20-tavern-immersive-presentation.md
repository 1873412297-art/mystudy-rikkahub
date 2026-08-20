# Android Tavern Immersive Presentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Android 单人酒馆纯文本/HTML 会话中提供单一 ST WebView 消息区、沉浸式多开场、悬浮 HUD 和统一运行时，并在混合消息出现时无损回退 Compose。

**Architecture:** 先建立纯 Kotlin 的视图选择、opening metadata、会话快照/patch 与权限迁移契约；再实现单一 `TavernConversationWebView` 和窄 JS bridge；最后接入 ChatPage、开场舞台、HUD、编辑预览和实机可靠性。所有业务写操作继续由 ChatService/现有仓库负责，WebView 只表达 UI 意图。

**Tech Stack:** Kotlin, Jetpack Compose, Android WebView, kotlinx.serialization, Room-backed conversation repository, JUnit, AndroidX instrumentation.

## Global Constraints

- 仅 Android；web-ui、群聊和普通非酒馆助手不改变视觉路径。
- ST 模式只用于 SOLO、存在 `tavernCardJson`、当前分支消息全部仅含 `UIMessagePart.Text` 的会话。
- 任意图片、音视频、文档、推理、工具或状态占位部件使整页回退 Compose，消息数据不转换。
- 开场候选无数量上限且全部保活；每个候选在独立覆盖层执行，选中时原子提交。
- 新装和升级均默认开启最大兼容权限；`allowRequestHeaders` 必须保持 false。
- 文件访问、content URI、危险协议和 WebView 顶层导航始终禁止。
- 所有生产行为严格遵循 RED → GREEN → REFACTOR；每个任务单独提交并经任务审查。

---

### Task 1: Presentation contracts, opening metadata, and permission policy

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernPresentationContracts.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/data/model/TavernOpeningMetadata.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/model/TavernRuntimePermissions.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernPresentationContractsTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/model/TavernOpeningMetadataTest.kt`

**Interfaces:**

```kotlin
enum class TavernPresentationMode { ST_WEB, COMPOSE }
data class TavernPresentationDecision(val mode: TavernPresentationMode, val fallbackReason: String? = null)
fun resolveTavernPresentation(assistant: Assistant?, conversation: Conversation): TavernPresentationDecision

data class TavernOpeningRef(
    val greetingIndex: Int,
    val contentFingerprint: String,
    val cardFingerprint: String,
)
fun UIMessagePart.Text.withTavernOpening(ref: TavernOpeningRef): UIMessagePart.Text
fun UIMessagePart.Text.tavernOpeningRef(): TavernOpeningRef?
fun inferLegacyOpening(message: UIMessage, card: TavernCharacterCard): TavernOpeningRef?
```

- [x] Write resolver tests for eligible SOLO text/HTML, missing card, group assistant, and every unsupported part family; run the two focused test classes and confirm missing-symbol failures.
- [x] Implement the minimal resolver and rerun until green.
- [x] Write metadata round-trip, malformed metadata, stable SHA-256 fingerprint, and legacy `first_mes` recognition tests; verify RED.
- [x] Implement typed metadata helpers without adding Room columns; rerun focused tests.
- [x] Change runtime permission defaults to true for scripts/world/message/network/variables/events/macro and false for request headers; add default/preset assertions and rerun focused plus existing runtime tests.
- [x] Commit the complete Task 1 diff.

### Task 2: Snapshot, patch diff, and ST conversation document

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationSnapshot.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocument.kt`
- Create: `app/src/main/assets/html/tavern-conversation.html`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationSnapshotTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationDocumentTest.kt`

**Interfaces:**

```kotlin
@Serializable data class TavernConversationSnapshot(/* conversation, nodes, selected messages, theme, css, streaming */)
@Serializable sealed interface TavernConversationPatch
fun buildTavernConversationSnapshot(/* current app state */): TavernConversationSnapshot
fun diffTavernSnapshots(previous: TavernConversationSnapshot?, current: TavernConversationSnapshot): List<TavernConversationPatch>
fun buildTavernConversationDocument(context: Context, initial: TavernConversationSnapshot): String
```

- [x] Write RED tests for full replace, message upsert/remove, branch select, streaming flag changes, stable ordering, escaped fallback, ST selectors, local vendors, and no external CDN.
- [x] Implement serializable snapshot and deterministic patch diff; make focused tests green.
- [x] Implement cached document builder using `tavern-conversation.html` and existing bundled vendor assets; make contract tests green.
- [x] Add raw HTML iframe/fullscreen markers, `.mes/.mes_block/.name_text/.mes_text/.ch_name`, card CSS scope, and message action data attributes; rerun tests.
- [x] Commit Task 2.

### Task 3: Single WebView host, native action bridge, and Compose fallback

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationWebView.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationBridge.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernConversationBridgeTest.kt`

**Interfaces:**

```kotlin
interface TavernConversationActions {
    fun onMessageLongPress(messageId: Uuid)
    fun onSelectBranch(nodeId: Uuid, index: Int)
    fun onOpenHtml(messageId: Uuid)
    fun onFallbackRequested()
}
```

- [x] Write RED bridge validation tests for UUID/index parsing, invalid payload rejection, protocol-whitelisted links, and action dispatch.
- [x] Implement the narrow bridge and make tests green.
- [x] Add one lifecycle-owned WebView, snapshot patch delivery, runtime context repush on every ready event, file/content access denial, navigation interception, and renderer crash/unresponsive callbacks.
- [x] Integrate the resolver into ChatPage: ST WebView for eligible conversations, existing ChatList otherwise, visible fallback reason, and manual retry/Compose switch.
- [x] Route long press and branch actions to existing ChatVM/ChatService operations; keep native top bar/input and verify compilation.
- [x] Commit Task 3.

### Task 4: Atomic greeting selection and immersive opening stage

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/service/tavern/TavernGreetingSession.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/tavern/TavernOpeningStage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/RouteActivity.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/service/tavern/TavernGreetingSessionTest.kt`

**Interfaces:**

```kotlin
data class TavernGreetingCandidate(/* index, rendered opening, overlay state */)
suspend fun ChatService.selectInitialGreeting(conversationId: Uuid, greetingIndex: Int)
suspend fun ChatService.commitGreetingCandidate(conversationId: Uuid, candidateId: Uuid)
suspend fun ChatService.createConversationFromGreeting(assistantId: Uuid, greetingIndex: Int): Uuid
```

- [x] Write RED service tests for independent candidate state, atomic selected-state commit, discarded candidates, locked-after-user behavior, legacy greeting navigation, and new-conversation switching.
- [x] Implement overlay/journal interfaces around conversation variables/messages/world mutations and make service tests green.
- [x] Add typed opening metadata during import/initialization and greeting-index navigation while retaining legacy Base64 reading.
- [x] Implement the opening stage with all candidates retained, full runtime, index/page controls, auto-picker setting, warning for irreversible network effects, and bottom input preserved.
- [x] After first user message, replace the stage with a top-right opening icon; implement full-screen current-opening replay and force later greeting changes into a new conversation.
- [x] Commit Task 4.

### Task 5: Floating HUD and bottom panel

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/StatusHudBar.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/StatusHudPresentationTest.kt`

**Interfaces:**

```kotlin
data class StatusHudPresentation(/* header, sections, pages, options, source message */)
fun buildStatusHudPresentation(conversation: Conversation): StatusHudPresentation?
```

- [x] Write RED tests for latest header selection, update identity, multi-character pages, and option prefill text.
- [x] Extract the pure presentation builder and make tests green.
- [x] Replace the in-layout expanded card with a floating one-line summary and a 90%-height modal bottom sheet using the shared runtime host.
- [x] Make option taps fill ChatInputState and close the sheet without sending; verify Compose compilation and focused tests.
- [x] Commit Task 5.

### Task 6: Editor preview and runtime permission migration UI

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/tavern/TavernCardEditorPage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesRuntimePage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/datastore/TavernPermissionMigrationTest.kt`

- [x] Write RED tests proving legacy stored permissions migrate once to maximum compatibility while request headers remain false, and preset factories return exact values.
- [x] Implement the preference migration and maximum/conservative preset helpers; make tests green.
- [x] Add both preset controls to runtime settings while preserving per-permission switches.
- [x] Add source/live preview for first and alternate greetings; require a manually selected real conversation, display the target persistently, and run preview writes against that conversation.
- [x] Run focused tests and compile; commit Task 6.

### Task 7: Localize remaining assets and harden reload/failure behavior

**Files:**
- Modify: `app/src/main/assets/html/mark.html`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebViewSecurityTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/MarkTemplateContractTest.kt`

- [x] Write RED contract tests that forbid CDN script/style dependencies and require local vendor placeholders, context-ready handshake, static fallback, retry, and protocol/file restrictions.
- [x] Localize mark.html using existing vendor bundles and make template tests green.
- [x] Ensure every document-ready event resets delivery hashes and republishes runtime context/current message; implement timeout/crash fallback and retry without losing raw content.
- [x] Run focused security/runtime tests and compile; commit Task 7.

### Task 8: Integration, instrumentation, and real-device acceptance

**Files:**
- Create/Modify: focused tests under `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/tavern/`
- Create: visible recovery fixture under `app/src/debug/java/me/rerere/rikkahub/ui/pages/chat/tavern/`
- Modify: `docs/superpowers/plans/2026-08-20-tavern-immersive-presentation.md` with a final verification record

- [x] Add a visible-Activity instrumentation fixture covering full HTML/JS, macros, variables, context after reload, message actions, fallback, and renderer recovery; capture RED while constructing the previously absent device coverage.
- [x] Make instrumentation tests green without test-only production hooks.
- [x] Run `:app:testDebugUnitTest`, `:app:compileDebugKotlin`, `:app:assembleDebug`, web-ui tests/typecheck/lint/build, and filtered connected instrumentation tests.
- [x] Install the enumerated arm64 Debug APK on Huawei MNA-AL00 and verify with the real cards in `/sdcard/Download/角色卡/` and `/sdcard/Pictures/角色卡/`.
- [x] Exercise at least 12 simultaneous greeting candidates, first-message collapse/icon replay, ST/Compose fallback, HUD option prefill, light/dark, rotation/back, and inspect logcat for FATAL/ANR/OOM/renderer failure.
- [x] Record exact commands/results and commit the verification record.

#### Task 8 final verification record — 2026-08-21

**Instrumentation and TDD.** `TavernImmersiveRuntimeInstrumentedTest` runs against visible Activities and real WebViews. The full-document case loads a raw-HTML iframe with JavaScript, writes a runtime variable twice across a whole-document reload, registers a macro only in the first document and executes that host macro after reload without re-registering it, receives fresh context/current-message values after reload, and invokes the native long-press, branch, HTML-viewer, and Compose-fallback actions. The recovery case drives the actual `TavernConversationWebView`, forwards a genuine main-frame `WebResourceError` produced by a blocked missing-file probe, verifies preserved static source, retries into a new WebView generation, fails the replacement, and selects Compose fallback. File and content access are asserted disabled. No `main` source-set test hook was added.

The first RED could not compile because the initial coverage attempted to construct the platform-owned `WebResourceError`; the test was corrected to obtain a real main-frame error from a probe WebView. The first recovery UI run then failed with no Compose roots. A hierarchy dump proved the Huawei notification-permission dialog, not the fixture, owned the foreground; the test now denies that dialog by its permission-controller resource ID and asserts the debug fixture regains focus. No missing production behavior was discovered in Task 8, so the changes are instrumentation/debug-fixture coverage rather than a production workaround.

**Automated verification.** The following commands were run from this worktree:

```text
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug --no-daemon
BUILD SUCCESSFUL in 25s
108 JVM test classes / 776 tests / 0 failures / 0 errors / 0 skipped

.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.pages.chat.tavern.TavernConversationDocumentInstrumentedTest,me.rerere.rikkahub.ui.components.richtext.MarkdownWebViewReloadInstrumentedTest,me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeSmokeTest,me.rerere.rikkahub.ui.pages.chat.tavern.TavernImmersiveRuntimeInstrumentedTest" --no-daemon
7 tests on MNA-AL00 / 0 skipped / 0 failed / BUILD SUCCESSFUL in 1m 21s

.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.pages.chat.tavern.TavernImmersiveRuntimeInstrumentedTest" --no-daemon
2 tests on MNA-AL00 / 0 skipped / 0 failed / BUILD SUCCESSFUL in 1m 18s after review fix

cd web-ui
pnpm test       # 3 files / 41 tests passed
pnpm typecheck  # passed
pnpm lint       # 0 errors; 7 existing warnings
pnpm build      # passed; only source-map and chunk-size warnings
```

One attempted rerun omitted quotes around the PowerShell Gradle property and Gradle rejected the split text as an unknown task before any test ran. The quoted command above is the corrected and passing command.

**Device, APK, and cards.** Before building/installing, `opencode.exe` was absent, Git remained at Task 8 base `8ba9231ebbf9`, and only the intended Task 8 files were dirty. The attached target was serial `XHD0223523008702`, Huawei `MNA-AL00`, Android 12, `arm64-v8a`. `output-metadata.json` enumerated universal, x86_64, and arm64 outputs at version code 172 / version name 2.4.5. The selected artifact was `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` (85,788,781 bytes). `adb -s XHD0223523008702 install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` returned `Success`; the final installed package reports `primaryCpuAbi=arm64-v8a`, version code 172, version name 2.4.5, and `RouteActivity` launched. The instrumentation task uninstalled its target package after its last rerun, so the same verified arm64 artifact was installed once more after testing to restore this final device state.

The four user card files found under the two requested directories were inspected read-only. Their V3 greeting counts were 9, 9 (the same card in both locations), 5, and 4; none contained HTML or scripts. The 9-greeting real card displayed all nine candidates as simultaneously live WebView targets, committed an opening, supplied the live HUD, and was used for the HUD option test. User originals were never modified.

Because no real device card met the required 12-candidate/HTML condition, a controlled safe V3 PNG fixture with 12 local HTML/JS greetings was generated outside the repository, hashed as `b30bc5d93a549f55ca7613a2ee52b79c1bcc6f04e9e54c9d855b7d3468d837c0`, and temporarily pushed to `/sdcard/Download/task8-acceptance-12.png`. Import showed 12 simultaneous DevTools WebView targets; 11 consecutive next-opening selections kept all 12 alive. A point-in-time RSS sample was approximately 699,132 KiB for the app plus 236,104 KiB for its WebView sandbox; this is a baseline sample, not a leak proof. Committing the selected opening reduced the live candidate set from 12 to one. After the first user message, the stage collapsed, the top opening action appeared, and it opened the full-screen replay plus the new-conversation-from-opening chooser. The chooser was cancelled without changing that conversation. The temporary fixture's device hash was rechecked and the exact temporary file was removed; the local temporary copy remains outside the repository.

**Manual behavior.** On the production chat path, the authenticated in-document fallback action changed ST view to the Compose compatibility view, showing the compatibility notice and retry control; retry restored the ST document. On the real-card HUD, the floating summary showed its header and update state, the bottom sheet opened with sections and four story options, and selecting one only prefilled the composer. The draft remained present after three seconds and no message was sent. The visible ST document changed from light (`prefers-color-scheme: dark=false`, `--rikkahub-surface=#F6FAFF`) to dark (`true`, `#0D1419`) and back while the DOM and HUD remained available. Locking the device to landscape preserved the WebView/HUD; Back closed the HUD sheet without leaving `RouteActivity`; portrait and the original auto-rotation settings were restored.

The focused manual log window and crash buffer had zero matches for app FATAL exceptions, ANR, `OutOfMemoryError`, `RenderProcessGone`, process-death crash records, or crash-buffer entries. Limitations are explicit: no supplied real card had 12 greetings or HTML/scripts, so real-card behavior and the controlled 12-HTML case are separate evidence; external-network script side effects and a live model response were not exercised.

**Independent review.** The first read-only review found two Important issues: the reloaded document re-registered the macro instead of proving host persistence, and the required report lived under the shared `.superpowers` exclude. It also noted cleanup was not failure-safe. The test now registers only in the first document, reports no registration in the second, executes the retained owner-scoped host macro after reload and asserts its output, and releases the controller/WebView/interfaces/Activity in `finally`. The report was force-added to the Git index. The same reviewer re-read the changes and returned `Approved` with no remaining Blocker or Important finding.
