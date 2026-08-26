# 2.4.10 私有功能适配设计

## 目标

以官方 `2.4.10` 为基线，完整保留当前 `private-main` 已交付的私有能力：群聊运行时、酒馆卡片/状态 HUD、酒馆脚本 API、提示词追踪控制台、世界书整词匹配，以及既有 Android/Web UI 行为。产物必须可编译、测试通过，并能生成可安装的 Debug APK。

## 约束与兼容边界

- 当前 `private-main` 不直接改动；迁移在 `codex/port-private-to-2.4.10` 隔离 worktree 完成。
- 2.4.10 的上游消息模型、流式解码器、Provider 目录和 Gradle/依赖调整优先保留；私有功能通过适配层接回，不回退到 2.4.5 API。
- Room schema 继续单调升级，保留现有 v28/v29 数据迁移和提示词追踪数据；不得删除用户数据。
- 酒馆脚本权限门控、消息事件、宏展开与 sendHook 的现有安全语义保持不变。
- 2.4.10 的版本号/versionCode 以官方值为准，不重新使用 2.4.5 的版本标识。

## 迁移层次

1. **基线与依赖**：初始化 submodule、Firebase 本地配置，确认 2.4.10 原生编译基线。
2. **数据与模型**：迁移私有模型字段、Room entity/DAO/migration、导入导出与 DI 接线，先保证数据库和序列化测试。
3. **生成与服务管线**：适配 `UIMessage`/`UIMessagePart`/`StreamChunk` 新接口，恢复提示词追踪、状态变量、脚本宏/斜杠命令、群聊调度与取消语义。
4. **渲染与运行时**：接回 Markdown/WebView、STABLE_DOM、酒馆上下文/事件桥、状态 HUD、卡片 CSS 和主题变量；保持流式增量优化。
5. **Web UI/API 与页面**：迁移 Tavern SSE/REST、控制台、群聊控制与设置页，处理 2.4.10 Compose/Navigation API 变化。

## 主要冲突策略

- 对上游重写文件采用“先保留 2.4.10，再逐点重接私有调用方”的方式；不整文件覆盖 Provider、`ai/ui`、`GenerationHandler`、`ChatService` 和消息组件。
- 对只新增的私有文件优先直接迁移；对同名文件按数据流顺序处理：类型定义 → 业务服务 → UI/桥接。
- 每解决一层，立即运行该层最小测试；禁止把所有冲突堆到最后一次编译。

## 验收

- `:app:testDebugUnitTest` 全绿，覆盖私有脚本、状态、群聊、追踪和迁移测试。
- `:app:compileDebugKotlin`、`:app:assembleDebug` 全绿，记录 APK 路径和 SHA-256。
- `web-ui` 的 `pnpm test`、`typecheck`、`lint`、`build` 全绿（若本版本仍包含该模块）。
- 在可用 Android 设备/模拟器完成启动、普通对话、酒馆状态块/HUD、宏展开、脚本权限、群聊至少一轮冒烟；logcat 无 FATAL。
- 最终报告明确列出已验证项、仅静态验证项及仍受环境限制的项目。

