# 2.4.10 私有功能适配实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将 `private-main` 的群聊、酒馆、状态 HUD、脚本 API、提示词追踪和世界书功能迁移到官方 2.4.10，并交付可构建/可安装 Debug APK。

**Architecture:** 以 2.4.10 为唯一上游基线，把当前私有分支作为一次受控合并源；先解决 `ai` 新消息/流式类型，再向上接 Generation/Chat/Room/UI/Web。每层保留 2.4.10 公共 API，通过小范围适配恢复私有调用。

**Tech Stack:** Kotlin, Android/Compose, Room/KSP, Gradle, Ktor/Web UI, TypeScript/Vitest, WebView/JavaScript bridge.

## Global Constraints

- 所有改动只在 `codex/port-private-to-2.4.10` worktree；不清理当前 `private-main` 的未跟踪目录。
- 版本号/versionCode 使用 2.4.10 基线值；Room 只允许前进迁移并保留 v28/v29 数据。
- 脚本 API 的 allowScripts/细粒度权限门控不能放宽。
- 每个阶段结束都必须有最小测试或编译证据。

---

### Task 1: 建立 2.4.10 合并基线

**Files:** git index only (merge result); Gradle compile test.

**Interfaces:** consumes `private-main` and target `693c2ce53` (`2.4.10`); produces a conflict-free branch.

- [ ] Merge without auto-commit: `git merge --no-commit --no-ff private-main`.
- [ ] Resolve `app/build.gradle.kts`, `gradle/libs.versions.toml`, module build files, and `settings.gradle.kts` by retaining 2.4.10 coordinates.
- [ ] Run `git diff --name-only --diff-filter=U`; it must be empty.
- [ ] Run `./gradlew :app:compileDebugKotlin -x :web:buildWebUi --no-configuration-cache`.
- [ ] Commit `merge: port private features onto 2.4.10`.

### Task 2: Adapt AI message and streaming contracts

**Files:** `ai/src/main/java/me/rerere/ai/ui/{Message.kt,UIMessagePart.kt,StreamChunk.kt,StreamChunkHandler.kt}`, provider decoders, `app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt`, matching tests.

**Interfaces:** consumes 2.4.10 `UIMessage`/`UIMessagePart`/`StreamChunk`; produces private transformers and services using the new shape.

- [ ] Run `./gradlew :ai:testDebugUnitTest :app:testDebugUnitTest --tests '*Message*' --tests '*Generation*'` and capture first failures.
- [ ] Map private role/content/reasoning/tool metadata to 2.4.10 fields; new code uses `UIMessagePart.Tool`.
- [ ] Adapt provider stream consumers and transformer calls to `StreamChunkHandler` while preserving text/reasoning/tool merging.
- [ ] Run `./gradlew :ai:testDebugUnitTest :app:testDebugUnitTest --no-configuration-cache`.
- [ ] Commit `fix: adapt private generation flow to 2.4.10 message APIs`.

### Task 3: Restore Room models, migrations, and DI

**Files:** `app/src/main/java/me/rerere/rikkahub/data/db/**`, `data/repository/PromptTraceRepository.kt`, `di/**`, schemas, migration/serialization tests.

**Interfaces:** consumes private model serialization and 2.4.10 database version; produces a schema-valid database preserving prompt traces, group runtime, and conversation fields.

- [ ] Run migration/serialization tests: `./gradlew :app:testDebugUnitTest --tests '*Migration*' --tests '*Serialization*'`.
- [ ] Keep 2.4.10 tables and add missing private columns/tables with explicit forward migrations; update schema JSON only through Room.
- [ ] Reconnect `PromptTraceRepository`, `StatusVariableStore`, group runtime state, and conversation cleanup in Koin modules.
- [ ] Run focused migration/prompt-trace/conversation tests plus `:app:compileDebugKotlin`.
- [ ] Commit `fix: restore private persistence on 2.4.10 schema`.

### Task 4: Reconnect chat, group runtime, status, and Tavern services

**Files:** `service/ChatService.kt`, `service/ConversationSession.kt`, `service/group/**`, `data/ai/status/**`, `data/ai/slash/**`, `ui/components/richtext/runtime/**`, matching tests.

**Interfaces:** consumes adapted generation/database APIs; produces group scheduling/cancellation, status variables, Tavern context/events/macros/slash commands with existing permissions.

- [ ] Run focused tests: `./gradlew :app:testDebugUnitTest --tests '*Group*' --tests '*Status*' --tests '*Tavern*' --tests '*Slash*'`.
- [ ] Preserve generation ownership, stale cancellation, event ordering, status-variable SSE, macro expansion, and send-hook timeout while translating 2.4.10 messages.
- [ ] Restore DI, host event bus, WebView context pushes, `MacroHelper`, `SlashCommandParser`, and permission checks.
- [ ] Repeat focused tests with `--no-configuration-cache`.
- [ ] Commit `feat: restore private chat and Tavern runtime on 2.4.10`.

### Task 5: Restore Compose/WebView pages and Web UI contracts

**Files:** `ui/components/message/**`, `ui/components/richtext/**`, `ui/pages/chat/**`, `ui/pages/tavern/**`, `web/**`, `web-ui/app/**`, UI/Web tests.

**Interfaces:** consumes Task 4 APIs and 2.4.10 Compose/Navigation APIs; produces chat/group controls, status HUD, Tavern console, REST/SSE payloads, and Web UI build.

- [ ] Run `./gradlew :app:compileDebugKotlin -x :web:buildWebUi --no-configuration-cache` plus web-ui `pnpm test`, `pnpm typecheck`, `pnpm lint`, `pnpm build`.
- [ ] Adapt Compose signatures while keeping Tavern arguments, group controls, HUD, prompt console, and route registration.
- [ ] Keep `tavern-render`, `status_variables`, prompt-trace, and conversation-diff payload shapes; update only 2.4.10 model fields.
- [ ] Repeat all checks with zero failures.
- [ ] Commit `feat: restore private UI and Web UI on 2.4.10`.

### Task 6: Full verification and release evidence

**Files:** create `docs/superpowers/verification-2.4.10.md`; only adjust `app/build.gradle.kts` if version metadata is incorrect.

**Interfaces:** consumes all migrated modules; produces verified Debug APK, checksum, test summary, and explicit caveats.

- [ ] Force tests and compile: `./gradlew :app:testDebugUnitTest --rerun-tasks --no-configuration-cache` and `./gradlew :app:compileDebugKotlin --rerun-tasks --no-configuration-cache`.
- [ ] Build `./gradlew :app:assembleDebug --rerun-tasks --no-configuration-cache`; record APK path and `Get-FileHash -Algorithm SHA256`.
- [ ] Run all web-ui checks; install with `adb install -r` when a device is available.
- [ ] Smoke ordinary chat, Tavern status/HUD, macro permission, and one group path; inspect logcat for `FATAL EXCEPTION`.
- [ ] Record commands, counts, hash, device, screenshots/logs, and unrun live-provider checks; commit `docs: verify private feature port on 2.4.10`.

