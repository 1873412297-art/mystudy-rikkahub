# 酒馆功能包实施计划（2026-07-19）

用户选定 5 个酒馆（SillyTavern/Tavern Helper）方向的新功能。经 6 路并行勘查，全部基于既有实现扩展，不推倒重来。

## 功能清单与落点

1. **世界书编辑器增强**：`RegexInjection` 加 secondaryKeywords/selective/probability，`Lorebook` 加 tokenBudget/recursiveScanning；`PromptInjectionTransformer` 实现对应匹配语义；新建 `ui/pages/extensions/lorebook/` 独立编辑器页面；`ExportSerializer` 与 `TavernRuntimeWorldBinding` 同步新字段。
2. **ST 式正则扩展**：`AssistantRegex` 加 flags（IGNORE_CASE/MULTILINE/DOT_MATCHES_ALL）与 minDepth/maxDepth；`RegexOutputTransformer` 按深度过滤；`AssistantPromptPage` 正则卡片升级（flags、排序、样文测试）；`AssistantImporter` 解析 ST 字段。（全局规则本期不做，避免热点文件三方冲突）
3. **快速回复 QuickReply**：`QuickMessage` 加 autoSend/mode/order，`Assistant` 加 hiddenQuickMessageIds；新建 `QuickReplyBar` 横滚 chips（单击填入、autoSend 直发、长按替换、编辑入口）；`QuickMessagesPage` 编辑器升级。
4. **作者注释 @ Depth**：新增 `AuthorNote`（enabled/content/depth/role/interval），助手级 + 会话级（`Conversation.authorNote`，Room v28→v29 autoMigration）；在 `PromptInjectionTransformer` 中合成为 ModeInjection 复用安全插入/trace；`TransformerContext`/`ChatService`/`GenerationHandler` 透传；助手提示词页 + 聊天页配置卡片。
5. **酒馆脚本 API 扩展**：变量按 scope（chat→StatusVariableStore 持久化 / global→Settings.tavernGlobalVariables）路由；新增宿主事件总线（MESSAGE_SENDING/GENERATION_FINISHED/MESSAGE_RENDERED）并向 WebView 推送 `th:` 事件；`events.subscribe/unsubscribe`、`variables.delete`、TH 风格别名；权限加 allowVariablesWrite/allowEventSubscribe；值大小校验。

## 波次划分（防并行冲突）

- **第一波（并行 3 代理）**：功能 1（世界书）、功能 3（快速回复）、功能 5（脚本 API）。
  - 共享文件约定：`Assistant.kt` 各自动指定区域（RegexInjection/Lorebook vs QuickMessage）；`PreferencesStore.kt` 由功能 3（sanitize/getter）与功能 5（新 key 四段）分区域改；`TavernRuntimeWorldBinding.kt` 归功能 1；`ChatPage/ChatInput` 归功能 3；`ChatService` 归功能 5；`RouteActivity` 归功能 1。
- **第二波（并行 2 代理）**：功能 2（ST 正则）、功能 4（作者注释）。
  - 共享文件约定：`Assistant.kt` 分区域（AssistantRegex vs AuthorNote 尾追加）；`AssistantPromptPage.kt` 分卡片区域；`ChatService/GenerationHandler/Transformer.kt/Conversation*/AppDatabase` 归功能 4。
- **第三波（1 集成代理）**：全量 JVM 测试 + `:app:compileDebugKotlin -x :web:buildWebUi` + `assembleDebug`，修复残留问题。

## 通用规则

- 不做本地化（AGENTS.md）：新 UI 文案硬编码中文，不动 strings.xml。
- 新字段全带默认值保证 JSON 向后兼容；不动既有 DataStore key。
- 禁止 git 变更操作；追加式最小改动；不回退他人改动。
- 每个功能配套 JVM 单元测试（app/src/test 镜像主包结构）。

## 进度

- [x] 功能 1 世界书编辑器
- [x] 功能 2 ST 正则扩展
- [x] 功能 3 快速回复
- [x] 功能 4 作者注释
- [x] 功能 5 脚本 API 扩展
- [x] 集成验证（测试 + 编译 + assembleDebug）

## 集成验证结果（2026-07-19，集成验证工程师）

### 接线补漏（集成阶段追加）

- `AssistantExtensionsPage.kt`：`QuickMessagesContent` 接上 `hiddenIds = assistant.hiddenQuickMessageIds` 与 `onToggleHidden`（走 `vm.update` 既有助手更新路径）。
- `ui/components/ui/ExtensionSelector.kt`（聊天内扩展选择器，实际路径在 `ui/components/ui/` 而非任务描述的 `ui/components/ai/`）：同样接上 hiddenIds/onToggleHidden，该组件本就有 `onUpdate: (Assistant) -> Unit` 更新路径，直接复用。
- Web 端 authorNote 同步：`web/dto/WebDto.kt` 的 `ConversationDto` 增加 `authorNote: AuthorNote?` 字段并在 `Conversation.toDto()` 映射；`web/routes/ConversationDiff.kt` 的 diff 守卫加入 `authorNote` 比较（变更时回退全量快照）；`web-ui/app/types/conversation.ts` 新增 `AuthorNote` 类型并为 `Conversation` 补字段；`web-ui/app/types/dto.ts` 的 `ConversationDto` 同步补字段。
  - 备注：服务端 web API 不存在"全量会话 PUT"入口，所有更新均为按字段请求 + 服务端 `conversation.copy(...)`，本不会丢 `authorNote`；本次补的是出站 DTO 可见性与前端类型完整性。

### 全量验证（JBR：`C:\Program Files\Android\Android Studio\jbr`）

- `./gradlew :app:testDebugUnitTest`：**BUILD SUCCESSFUL**，63 个测试类共 **434 个测试，0 失败 / 0 错误 / 0 跳过**。（首次运行因 `:web:buildWebUi` 找不到 pnpm 失败，将 `C:\Users\18734\AppData\Roaming\npm` 加入 PATH 后通过，与代码无关。）
- `./gradlew :app:compileDebugKotlin -x :web:buildWebUi`：**BUILD SUCCESSFUL**。
- `./gradlew :app:assembleDebug -x :web:buildWebUi`：**BUILD SUCCESSFUL**，产出 `app-arm64-v8a-debug.apk` / `app-universal-debug.apk` / `app-x86_64-debug.apk`。

### 跨功能冲突与修复

- 未发现跨功能冲突或回归，无需修复；两波并行改动在共享文件（Assistant.kt、ChatService.kt、ChatPage.kt 等）上的分区约定生效。

### 遗留问题

- `authorNote` 仅打通到 web DTO/类型层，web-ui 前端尚无作者注释编辑 UI（按需后续迭代）。
- 手动端到端验证（真机/模拟器操作 5 个功能 UI）未在本轮执行，建议后续按计划做一轮人工冒烟。

