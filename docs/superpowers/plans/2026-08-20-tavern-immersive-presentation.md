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

- [ ] Write RED bridge validation tests for UUID/index parsing, invalid payload rejection, protocol-whitelisted links, and action dispatch.
- [ ] Implement the narrow bridge and make tests green.
- [ ] Add one lifecycle-owned WebView, snapshot patch delivery, runtime context repush on every ready event, file/content access denial, navigation interception, and renderer crash/unresponsive callbacks.
- [ ] Integrate the resolver into ChatPage: ST WebView for eligible conversations, existing ChatList otherwise, visible fallback reason, and manual retry/Compose switch.
- [ ] Route long press and branch actions to existing ChatVM/ChatService operations; keep native top bar/input and verify compilation.
- [ ] Commit Task 3.

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

- [ ] Write RED service tests for independent candidate state, atomic selected-state commit, discarded candidates, locked-after-user behavior, legacy greeting navigation, and new-conversation switching.
- [ ] Implement overlay/journal interfaces around conversation variables/messages/world mutations and make service tests green.
- [ ] Add typed opening metadata during import/initialization and greeting-index navigation while retaining legacy Base64 reading.
- [ ] Implement the opening stage with all candidates retained, full runtime, index/page controls, auto-picker setting, warning for irreversible network effects, and bottom input preserved.
- [ ] After first user message, replace the stage with a top-right opening icon; implement full-screen current-opening replay and force later greeting changes into a new conversation.
- [ ] Commit Task 4.

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

- [ ] Write RED tests for latest header selection, update identity, multi-character pages, and option prefill text.
- [ ] Extract the pure presentation builder and make tests green.
- [ ] Replace the in-layout expanded card with a floating one-line summary and a 90%-height modal bottom sheet using the shared runtime host.
- [ ] Make option taps fill ChatInputState and close the sheet without sending; verify Compose compilation and focused tests.
- [ ] Commit Task 5.

### Task 6: Editor preview and runtime permission migration UI

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/tavern/TavernCardEditorPage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesRuntimePage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/datastore/TavernPermissionMigrationTest.kt`

- [ ] Write RED tests proving legacy stored permissions migrate once to maximum compatibility while request headers remain false, and preset factories return exact values.
- [ ] Implement the preference migration and maximum/conservative preset helpers; make tests green.
- [ ] Add both preset controls to runtime settings while preserving per-permission switches.
- [ ] Add source/live preview for first and alternate greetings; require a manually selected real conversation, display the target persistently, and run preview writes against that conversation.
- [ ] Run focused tests and compile; commit Task 6.

### Task 7: Localize remaining assets and harden reload/failure behavior

**Files:**
- Modify: `app/src/main/assets/html/mark.html`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebViewSecurityTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/MarkTemplateContractTest.kt`

- [ ] Write RED contract tests that forbid CDN script/style dependencies and require local vendor placeholders, context-ready handshake, static fallback, retry, and protocol/file restrictions.
- [ ] Localize mark.html using existing vendor bundles and make template tests green.
- [ ] Ensure every document-ready event resets delivery hashes and republishes runtime context/current message; implement timeout/crash fallback and retry without losing raw content.
- [ ] Run focused security/runtime tests and compile; commit Task 7.

### Task 8: Integration, instrumentation, and real-device acceptance

**Files:**
- Create/Modify: focused tests under `app/src/androidTest/java/me/rerere/rikkahub/ui/components/richtext/runtime/`
- Modify: `docs/superpowers/plans/2026-08-20-tavern-immersive-presentation.md` with a final verification record

- [ ] Add a visible-Activity instrumentation fixture covering full HTML/JS, macros, variables, context after reload, message actions, fallback, and renderer recovery; verify it fails before completing missing behavior.
- [ ] Make instrumentation tests green without test-only production hooks.
- [ ] Run `:app:testDebugUnitTest`, `:app:compileDebugKotlin`, `:app:assembleDebug`, web-ui tests/typecheck/build, and filtered connected instrumentation tests.
- [ ] Install the enumerated arm64 Debug APK on Huawei MNA-AL00 and verify with the real cards in `/sdcard/Download/角色卡/` and `/sdcard/Pictures/角色卡/`.
- [ ] Exercise at least 12 simultaneous greeting candidates, first-message collapse/icon replay, ST/Compose fallback, HUD option prefill, light/dark, rotation/back, and inspect logcat for FATAL/ANR.
- [ ] Record exact commands/results and commit the verification record.
