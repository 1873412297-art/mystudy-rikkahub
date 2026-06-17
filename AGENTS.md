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
