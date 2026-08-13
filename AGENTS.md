# Repository Guidelines

本文档面向贡献者，概述本仓库的模块结构、开发流程，便于快速上手并保持一致的协作质量。

## Build, Test, and Development Commands

使用 Android Studio 或命令行 Gradle：

```bash
./gradlew assembleDebug          # 构建 Debug APK
./gradlew test                   # 运行所有模块的 JVM 单元测试
./gradlew connectedDebugAndroidTest  # 运行设备/模拟器上的仪器测试
./gradlew lint                   # 运行 Android Lint
```

构建应用需要在 `app/` 下提供 `google-services.json`（用于 Firebase）。
`web` 模块会在 `preBuild` 阶段构建 `web-ui/` 并复制静态资源，需要本地可用 `pnpm`。

## Coding Style & Naming Conventions

本仓库使用 `.editorconfig` 统一格式：

- Kotlin/Gradle 脚本：4 空格缩进，最大行长 120。
- XML/JSON：2 空格缩进。
- Markdown/YAML：2 空格缩进，允许尾随空格（用于对齐）。

命名习惯：模块名为小写目录（如 `ai/`、`speech/`），Kotlin 类遵循 PascalCase，测试类以 `*Test` 结尾。

## Testing Guidelines

测试框架以 JUnit/AndroidX Test 为主。未设定强制覆盖率门槛，但新逻辑应配套新增/更新测试。测试文件命名建议：

- 单元测试：`FooTest.kt`
- 仪器测试：`FooInstrumentedTest.kt` 或 `*Test.kt`

## Module Structure

- **app**: Main application module with UI, ViewModels, and core logic
- **ai**: AI SDK abstraction layer for different providers (OpenAI, Google, Anthropic)
- **common**: Common utilities and extensions
- **document**: Document parsing module for handling PDF, DOCX, PPTX, and EPUB files
- **highlight**: Code syntax highlighting implementation
- **material3**: Material color utility extensions used by the app UI
- **search**: Search functionality SDK for multiple providers (Exa, Tavily, Zhipu, Bing, Brave, SearXNG, and others)
- **speech**: Speech module for TTS and ASR implementations
- **web**: Embedded web server module that provides Ktor server startup function and hosts static frontend build files (
  built from web-ui/ React project)
- **workspace**: Sandboxed per-workspace file system and shell execution environment exposed to the AI as tools.

## Concepts

- **Assistant**: An assistant configuration with system prompts, model parameters, and conversation isolation. Each
  assistant maintains its own settings including temperature, context size, custom headers, tools, memory options, regex
  transformations, and prompt injections (mode/lorebook). Assistants provide isolated chat environments with specific
  behaviors and capabilities. (app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt)

- **Conversation**: A persistent conversation thread between the user and an assistant. Each conversation maintains a
  list of MessageNodes in a tree structure to support message branching, along with metadata like title, creation time,
  update time, pin status, chat suggestions, optional conversation-level system prompt, and prompt injection bindings. (
  app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **UIMessage**: A platform-agnostic message abstraction that encapsulates chat messages with different types of content
  parts (text, images, documents, reasoning, tool calls/results, etc.). Each message has a role (USER, ASSISTANT,
  SYSTEM, TOOL), creation timestamp, model ID, token usage information, and optional annotations. UIMessages support
  streaming updates through chunk merging. (ai/src/main/java/me/rerere/ai/ui/Message.kt)

- **MessageNode**: A container holding one or more UIMessages to implement message branching functionality. Each node
  maintains a list of alternative messages and tracks which message is currently selected (selectIndex). This enables
  users to regenerate responses and switch between different conversation branches, creating a tree-like conversation
  structure. (app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **Message Transformer**: A pipeline mechanism for transforming messages before sending to AI providers (
  InputMessageTransformer) or after receiving responses (OutputMessageTransformer). Transformers can modify message
  content, add metadata, apply templates, handle special tags, convert formats, and perform OCR. Common transformers
  include:
  - TemplateTransformer: Apply Pebble templates to user messages with variables like time/date
  - ThinkTagTransformer: Extract `<think>` tags and convert to reasoning parts
  - RegexOutputTransformer: Apply regex replacements to assistant responses
  - DocumentAsPromptTransformer: Convert document attachments to text prompts
  - Base64ImageToLocalFileTransformer: Convert base64 images to local file references
  - OcrTransformer: Perform OCR on images to extract text

  Output transformers support `visualTransform()` for UI display during streaming and `onGenerationFinish()` for final
  processing after generation completes.
  (app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt)

## Internationalization

- String resources are usually located in `app/src/main/res/values*/strings.xml`; feature modules such as `search`
  may also maintain their own `values*/strings.xml`
- Use `stringResource(R.string.key_name)` in Compose
- Page-specific strings should use page prefix (e.g., `setting_page_`)
- If the user does not explicitly request localization, prioritize implementing functionality without considering
  localization. (e.g `Text("Hello world")`)
- For `locale-tui` operations, use the `locale-tui-localization` skill.

## Current Status

**2026-08-13：Android 渲染链路提升（子项目 B1）。**

- st-message.html 重建为 ST 默认形状（.mes_text > p 分段、.name_text/.ch_name、mes_buttons 占位、CSS 变量默认主题）；
  前端库（markdown-it/DOMPurify/hljs/katex/mermaid）经 esbuild IIFE 打包到 assets/html/vendor/ 并在构建期内联（无 CDN/file:// 依赖）
- 主题通道：Material 色值 → CSS 变量（--rikkahub-*）；角色卡 CSS 经 TavernCardStyleResolver + CssSanitizer 注入消息气泡
  （StatusRenderer 复用 CssSanitizer）；renderKey 含卡样式版本键
- 流式增量：RikkahubDomBridge.applySegmentPatch + StableSegmentSnapshot 段 diff；streaming 经 ChatMessage→Markdown→MarkdownWebView 传递
- 生命周期：onDispose destroy + removeJavascriptInterface；ChatList contentType；viewHeight 初始 0 + minHeight 占位
- 性能修复（Task 5 审查 Important 并入）：MarkdownBlock STABLE_DOM 分支 buildStableMessageHtml（读 assets 模板 +
  内联 ~5MB vendor）改为 remember 缓存，键含 normalizedContent/tavernCardStyle/streaming/cssVariables/roleName/stableRole
  —— 无关 recompose 不再重建；主题切换时 cssVariables 键失效同步重建（与 MarkdownWebView baseKey 颜色失效一致）
- 验证：`:app:testDebugUnitTest`/`:app:compileDebugKotlin`/`:app:assembleDebug` 全绿（84 类 / 592 测试 0 失败）；
  `pnpm typecheck`/`test`（41）/`build` 全绿
- 模拟器冒烟 ✅（2026-08-14，emulator-5554 + DB 注入法）：
  - 消息气泡渲染：普通 markdown/状态占位符（多角色 tabs + WebView 状态页）/STABLE_DOM（st-message DOM：.ch_name
    「Yes, My Liege」+ 叙事文本经 WebView 虚拟节点可见）均正常
  - HUD：bar 出现/展开（HP 100/100、MP 50/50、随身物品、选项 chips）；HTML section 经 WebView 渲染
  - 暗/亮切换：`cmd uimode night` 像素采样验证（背景 89→28、STABLE_DOM 气泡 250→18）；切换后内容完整无崩溃
  - 滚动/后退复进：多次上下滚动、跨会话往返 3 轮无 FATAL；WebViews 计数稳定（6→6，无泄漏）
  - 待验证：流式增量 patch（需真实模型/mock 服务器，本环境无）
- 待办：子项目 B2（脚本 API 兼容性：SillyTavern.getContext/event_types/MacroHelper/SlashCommandParser）
- 计划/设计：`docs/superpowers/specs/2026-08-13-android-renderer-upgrade-design.md`、
  `docs/superpowers/plans/2026-08-13-android-renderer-upgrade.md`

**2026-08-13：web-ui 酒馆渲染栈（子项目 A）。**

- 后端：`GET /api/assistant/{id}/tavern-render` 端点（`TavernCardCssExtractor` 共享抽取自 StatusPlaceholderTransformer）；
  `ConversationDto.statusVariables`（snapshot/node_update 自动携带）；对话 stream 新增 `status_variables` SSE 事件
  （`ChatService.getStatusVariablesFlow` 订阅 StatusVariableStore StateFlow；注：coroutines 1.11.0 中
  `StateFlow.distinctUntilChanged()` 为 ERROR 级 deprecation，勿加）
- web-ui：TS 移植 StatusTags/StatusBlockExtractor/StatusFallbackHtml（vitest 覆盖，与 Kotlin 测试样例对齐）；
  sandboxed iframe 统一渲染（`HtmlFrame` 展示模式 allow-same-origin 无脚本 + `RenderStatusFrame` 重渲染模式
  allow-scripts opaque origin + done 守卫 + key 重挂载）；`StatusPlaceholderView`（多角色 tabs + 实时重渲染）、
  TextPart renderMode/状态标签剥离、`StatusHudBar` + 选项 chips 点击发送；`useTavernStore`（变量树/角色卡）+ SSE 接线
- 验证：`:app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug` ✅（80 类 / 575 测试 0 失败）；
  `pnpm test`（41）/`typecheck`/`lint`/`build` 全绿
- 已知 Minor（final review 待裁决）：Task 10 无 seq 水位（快照旧值可能短暂覆盖新变量，自愈）；角色卡加载失败永久缓存无重试；
  JS 整数键序 vs Kotlin LinkedHashMap（fallback HTML 行序）
- Task 14 浏览器冒烟 ✅（2026-08-13，模拟器 + DB 注入法）：
  - 后端：tavern-render 端点三态（null 字段/空卡/404）；snapshot 携带 statusVariables；SSE stream 初始即发 `status_variables` 事件（seq 递增）
  - web-ui（agent-browser 验证）：状态块文本剥离（气泡无标签泄漏）；HUD 出现/展开（headerLine + HTML section 经无脚本 iframe 渲染
    「HP 100/100」）；选项 chips 点击 → USER 消息进入对话树；多角色 tabs 切换（艾莉娅/守卫队长分页正确）；无 renderStatus JS 时
    直接展示服务端 htmlContent（降级路径）
  - 未覆盖：renderStatus JS 实时重渲染（现有角色卡均无 statusRenderJs）；生成期间变量更新驱动（需真实模型输出 `<UpdateVariable>`）
- 待办：子项目 B（Android 渲染器提升：样式/性能/主题/脚本 API）
- 计划/设计：`docs/superpowers/specs/2026-08-13-web-ui-tavern-rendering-design.md`、
  `docs/superpowers/plans/2026-08-13-web-ui-tavern-rendering.md`

**2026-08-08：酒馆功能整体优化 + 整词匹配（TDD）。**

- 工作区路径注意拼写：`C:\Users\18734\Desktop\HTML\rikkahub-source`（`rikkahub`，1 个 h）。
- 整体优化（去重 + 健壮性）：
  - `StatusTags` 单一事实来源：状态块标签族正则去重（StatusBlockExtractor / RichTextRenderPolicy / Markdown 三处共用）
  - `StatusFallbackHtml` 共享构建器：StatusRenderer 与 StatusPlaceholderTransformer 两份 HTML 构建合并 + 修复 `>` 转义
  - `StatusPlaceholderTransformer` 移除每 chunk 调试日志/全量扫描（行为不变）
  - `StatusVariableStore` 会话删除时清理接线（`ConversationRepository.deleteConversation` → `remove(id)`）
- UI 一致性：统一 `EmptyState` 组件（5 个酒馆/扩展页面复用）；`TavernCardEditorPage` TopBar 补 `CustomColors.topBarColors`
- 新功能（TDD）：世界书关键词「整词匹配」`matchWholeWords`（ST Match Whole Words 对齐）：
  模型 + 匹配逻辑（ASCII 词边界，CJK 子串语义，正则不叠加）+ 世界书编辑器开关 +
  TavernRuntimeWorldBinding / ExportSerializer（ST 两种拼写）/ PromptInjectionMatch trace 同步
- 验证：`:app:testDebugUnitTest` **74 类 / 556 测试 0 失败** ✅、`:app:compileDebugKotlin` ✅、`:app:assembleDebug` ✅
- 计划文档：`docs/superpowers/plans/2026-08-08-tavern-optimization-pass.md`
- 说明：历史若干回合误报"工作区不可访问"，实为路径拼写错误（rikkahhub vs rikkahub）；本轮全部改动已验证通过。


**2026-08-07：私有 fork 已全量移植到官方最新版 2.4.5（versionCode 172）。**

- 合并提交：`62787dce`（`merge: integrate 2.4.5 port (all private features) into private-main`）
- 移植分支：`codex/port-private-to-2.4.5`（已推送 origin；worktree `C:\Users\18734\Desktop\HTML\rikkahub-source-2.4.5`）
- 移植内容：群聊/酒馆/状态渲染/Slash/群组上下文玩法（受控 merge `codex/port-private-to-2.4.1`）
  + 提示词追踪控制台 + 状态 HUD + 酒馆功能包（世界书/ST 正则/QuickReply/作者注释/脚本 API）
- 验证（在 `private-main` 集成后复跑）：`:app:compileDebugKotlin` ✅、
  `:app:testDebugUnitTest` **70 类 / 518 测试 0 失败** ✅、`:app:assembleDebug` ✅、
  模拟器冒烟（`emulator-5554` 安装 + 启动 + 前台运行无 FATAL）✅
- DB schema：`private-main` 现为 v27（上游 v24 folder_id → v25 群组/状态 → v27 group_runtime_state）
- 详情见 `docs/superpowers/plans/2026-08-07-port-to-2.4.5-status.md`
- 备份：private-main WIP 快照分支 `codex/private-main-wip-snapshot-2026-08-07`（`794ca7d9`）；
  旧 private-main 历史已推送 `origin/private-main`（`0df5fc8b`）
- 待办：模拟器深度功能冒烟（群聊四场景/酒馆/状态 HUD）参考既有 Manual Test Results 清单

---
（以下为历史状态块，保留作参考）

Primary source of truth:

- `docs/superpowers/plans/2026-06-17-group-context-gameplay-plan.md`
- Keep `docs/superpowers/plans/2026-06-16-tavern-helper-st-rendering-runtime.md` intact as the prior Tavern Helper baseline.

Completed on 2026-06-17:

1. Group message transport rewrite is extracted and covered by tests.
2. Persistent `groupRuntimeState` is added to `Conversation` with serialization coverage.
3. Layered group context builder, speaker scorer, runtime state updater, group context options, and runtime debug sheet are implemented.
4. Relevant JVM tests and `assembleDebug` passed during this implementation pass.
5. Debug APK was installed on `emulator-5554` and the group chat UI was manually opened.

Still unfinished / pending:

1. Task 8 manual smoke remains partially blocked by unreliable `adb` interaction with the Compose chat input/send UI on the running emulator.
2. Specifically, `adb shell input text` corrupts non-ASCII or spaced prompts, and coordinate taps on the visible send affordance do not reliably dispatch the in-app send action.
3. Manual, round-robin, moderator, and runtime-state end-to-end verification should continue from the current plan document's `Manual Test Results Status` block rather than restarting from scratch.

Keep the existing Tavern Helper rendering/runtime changes and the new group-context changes intact. Do not revert unrelated user edits. When continuing this task, prefer small focused changes with tests and update the plan checkboxes/status block as work progresses.

## Upstream Sync Status (2026-06-17)

Upstream sync work was completed in a separate git worktree to avoid disturbing the dirty `private-main` workspace:

- Sync worktree: `C:\Users\18734\Desktop\HTML\rikkahub-source-sync`
- Sync branch: `codex/sync-upstream-2026-06-17`
- Latest pushed sync commit: `98d9a339` (`fix: support windows web-ui build in synced branch`)

What is already done on the sync branch:

1. Merged `upstream/master` into the fork baseline.
2. Resolved merge conflicts around chat UI/message rendering and localized strings.
3. Fixed sync regressions required for local compilation:
   - `StatusPlaceholderTransformer.findBareJsonPatch` visibility
   - `ChatMessageAvatar` optional `isRealUserMessage` parameter
4. Fixed `web/build.gradle.kts` so `:web:buildWebUi` works on Windows (`pnpm.cmd`) while preserving the existing Unix path (`zsh`).
5. Verified on the sync branch:
   - `./gradlew :app:compileDebugKotlin -x :web:buildWebUi`
   - `./gradlew :web:buildWebUi`
   - `./gradlew :app:assembleDebug`

Current dirty workspace policy:

- Treat `C:\Users\18734\Desktop\HTML\rikkahub-source` as the active feature workspace.
- Do not hard reset, clean, or overwrite it.
- Do not merge the sync branch directly into a dirty tree.

Recommended reintegration workflow for the dirty workspace:

1. In the dirty `private-main` workspace, create a safety snapshot before any integration step.
2. Prefer committing the current local work-in-progress onto a temporary branch rather than relying only on stash.
3. Update or create a clean integration branch from `origin/codex/sync-upstream-2026-06-17`.
4. Replay the local feature commits onto that clean branch with `cherry-pick` or a controlled merge.
5. Resolve conflicts in the known hot files first:
   - `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`
   - `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt`
   - `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageAvatar.kt`
   - `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/StatusPlaceholderTransformer.kt`
   - `app/src/main/java/me/rerere/rikkahub/service/group/*`
   - `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/*`
   - `app/src/main/res/values*/strings.xml`
6. Re-run focused verification after replay:
   - group chat/manual modes
   - Tavern Helper/ST rendering
   - `./gradlew :app:assembleDebug`
7. Only after the replayed branch is green should it replace or merge back into `private-main`.

If a later session continues the sync/reintegration work, start from this status block instead of re-merging upstream from scratch.

## Status HUD Integration Status (2026-07-21)

The status-HUD feature set (expanded status tag family + bare `<details>` fallback + StatusHudBar) has been fully replayed onto the sync codebase and verified green.

- Integration branch: `codex/integrate-status-hud-20260720`
- Worktree: `C:\Users\18734\Desktop\HTML\rikkahub-source\.integration-wt` (untracked in main repo; keep it)
- Base: `origin/codex/sync-upstream-2026-06-17` @ `59325fa9` (remote has advanced past `98d9a339` recorded above)
- Safety tag on dirty workspace: `backup/private-main-20260720`

Commit stack on the integration branch (5 commits):

```
10acec99 docs: record integration branch verification results
05144b7c feat: wire status HUD into chat UI on sync branch
15ecd95f docs: add status HUD integration note and wiring patches
97461172 feat: add status HUD bar for status block display
361a210c feat: expand status block tag family and add bare details fallback
```

The same 3-commit feature/docs stack also exists on dirty `private-main` (`5bf9d53e`, `fc970e7f`, `0df5fc8b`).

Verification already done on the integration branch:

1. 44/44 status/richtext unit tests pass.
2. `./gradlew :app:assembleDebug` BUILD SUCCESSFUL.
3. Emulator smoke (DB-injection method): 6/6 tag variants (`status_block`, `statusblock`/`<StatusBlock>`, `statusbar`/`<STATUSBAR>`, `<status!>`, `<状态栏>`) + bare `<details>` fallback all render the HUD correctly; evidence in `verification-screenshots/integration/` + `VERIFICATION-REPORT.md`.

Accepted adaptations/caveats on the integration branch (full detail in `docs/superpowers/plans/2026-07-20-status-hud-integration-note.md` section 7):

- `RichTextRenderPolicy.kt` kept as dormant infra (modify/delete conflict; stable-DOM renderer absent on sync base).
- `StatusHudBar` tavern args dropped (option b): HUD HTML sections lose chat-scope tavern variables.
- `!isManualGroup` guard omitted (group-chat WIP not on sync base).
- Room 29→25 downgrade crash when downgrade-installing over private-main data is expected; `pm clear` + re-inject.

Remaining last step (NOT done): after `private-main`'s WIP (author-note, tavern runtime, group chat) is committed, merge/rebase `codex/integrate-status-hud-20260720` back into `private-main`, resolving the known hot files listed above. Do not merge into the dirty tree.
