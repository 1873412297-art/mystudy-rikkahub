# 2.4.10 私有功能适配验证报告

日期：2026-08-19  
分支：`codex/port-private-to-2.4.10`  
基线：官方 `2.4.10`（`693c2ce5`）  
版本：`versionName=2.4.10`，`versionCode=177`

## 适配内容

- 保留群聊运行时、成员/导演控制、动态上下文与持久化状态。
- 保留 Tavern 卡片、状态 HUD、脚本 API、宏/斜杠命令、WebView 渲染和 Web UI REST/SSE。
- 保留提示词追踪会话/控制台、Room 迁移、世界书整词匹配和现有私有设置页。
- 适配 2.4.10 的 `UIMessage`、`UIMessagePart`、`StreamChunk` 和 `TextGenerationResult` 契约。
- 修复 Android ICU 对稳定消息模板占位符正则的严格解析问题。

## 自动化验证

通过：

1. `./gradlew :app:compileDebugKotlin -x :web:buildWebUi --no-configuration-cache`
2. `./gradlew :app:testDebugUnitTest --no-configuration-cache`
3. `pnpm --dir web-ui typecheck`
4. `pnpm --dir web-ui test`：3 个测试文件，41 个测试通过。
5. `pnpm --dir web-ui exec oxlint app`：0 errors（7 个既有 warning）。
6. `./gradlew :app:assembleDebug --no-configuration-cache`
7. `./gradlew :app:compileDebugAndroidTestKotlin --no-configuration-cache`
8. 定向设备测试 `GenerationHandlerPromptTraceTest`：14/14 通过，覆盖 2.4.10 流式追踪、取消、失败、工具调用和多 provider。

完整设备测试记录：`connectedDebugAndroidTest` 共 55 个测试；核心功能测试可运行，但 Tavern 控制台的两个长文本展开断言在该 Android 12 设备上失败（`collapsedLongTextPartHidesTailUntilExpanded`、`collapsedLongReasoningPartHidesTailUntilExpanded`），并出现后续 Compose hierarchy 不稳定。该失败位于现有 Compose 仪器测试的可见性/滚动断言，不是编译或核心生成链路错误，已保留为后续 UI 测试修复项。

## APK 与设备冒烟

- APK：`app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
- SHA-256：`A87512F7C0BABA8E98C35220EFACFD144E5AF029089C3334B26B2FC5C3AD90B1`
- 大小：84,914,351 bytes
- 设备：`XHD0223523008702` / `MNA-AL00` / Android 12
- `adb install -r`：Success
- Debug 包进程 `me.rerere.rikkahub.debug` 存活，`RouteActivity` 已创建；首次安装会显示系统运行时权限对话框，未自动授予权限。
- 未执行真实外部 provider 请求和真实模型流式冒烟。

