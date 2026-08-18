# 酒馆脚本 API 兼容性设计（子项目 B2b：宏与命令）

日期：2026-08-14

## 1. 目标与范围

- B2b 总目标：MacroHelper/registerMacro、SlashCommandParser/registerSlashCommand、内建斜杠命令、getRequestHeaders、MESSAGE_SENDING mutate 语义；并入 B2a 遗留首修（主路径 tavern 参数链修复、流式快照优化）。
- 不在 B2b：严格 ST 同步阻塞式 MESSAGE_SENDING 问询（采用宏同步 + 异步钩子 best-effort，偏差文档化）；完整 ST 宏全集（以现有 expandVisualMacros 为基础增量扩展）。

## 2. 主路径 tavern 参数链修复（B2a 遗留首修）

- `MarkdownBlock`（Markdown.kt）加 4 个默认值参数：`tavernConversationId/tavernCurrentMessage/tavernContextSnapshot/tavernMessageRole`；内部 5 处 `MarkdownWebView` 调用与递归调用透传。
- `ChatMessage.kt` 3 处 MarkdownBlock 调用补传（参数已在作用域）；`MultiCharacterStatusView` 加 4 参数 + 调用点透传。
- 效果：普通 markdown / STABLE_DOM 消息 WebView 获得完整脚本上下文（getContext/宿主事件/messages.getCurrent/variables 在酒馆主渲染路径可用）。
- 其他 MarkdownBlock 调用方（翻译/导出/调试页等）保持默认 null。

## 3. 宿主侧脚本注册表与 QuickJS 执行

### 3.1 TavernScriptRegistry（Koin 单例）

- 宿主持久注册表（WebView 重载不丢）：
  - `registerMacro(name, source)`：宏函数源码（约定 `function macro(args) {...}` 或直接表达式）；重名覆盖。
  - `registerSlashCommand(name, callbackSource, aliases, helpString)`。
  - `expandMacros(text, context): String`：对注册宏同步展开。
  - `executeSlashCommand(name, args, context): Result`。
- 执行引擎：独立 QuickJS 单线程 executor（模式同 SlashScriptEngine，独立实例避免污染斜杠脚本上下文）；执行超时 2s 兜底。
- 注册表生命周期：应用级单例（跨会话保留）；提供 clear 入口。

### 3.2 安全

- 注册经 RPC 受 `allowScripts` 总开关 + 新权限位 `allowMacroRegister`（默认 false）保护。
- 宏执行上下文只注入受控数据（args 字符串、变量访问接口），不注入宿主对象。
- 宏/命令函数源码 ≤64KB；注册数上限 64。

## 4. 内建斜杠命令

- 宿主实现，`SlashCommandInterceptor.transform` 中脚本匹配前优先分发：
  - `/setvar <key> <value>`、`/getvar <key>`、`/add <key> <number>`、`/sub <key> <number>`
  - `/random <a>,<b>,...`（等价 /pick）、`/roll <NdM>`、`/echo <text>`、`/th help`（列内建命令）
- 变量目标：chat 作用域 `StatusVariableStore`（与状态面板同树）。抽象 `ScriptVariableAccessor` 为接口，新增 `StatusVariableStoreAccessor(conversationId, store)` 实现（单键操作 = getValue copy 后 set）。
- 输出语义与磁盘脚本一致（文本替换用户消息 / html 追加 assistant 消息）。

## 5. WebView 内注册（SlashCommandParser 垫片）

- JS 侧新增：
  - `window.SlashCommandParser = { add({name, callback, aliases, helpString, returns}) }`——callback 序列化为函数源码经 RPC `slash.register` 注册到宿主表。
  - `window.MacroHelper = { registerMacro(name, fn), getMacro(name), getMacros() }`——fn 序列化为源码经 RPC `macros.register`。
- 宿主侧：`TavernRuntimeController.dispatch` 新增 `macros.register/remove/list`、`slash.register/unregister`、`requestHeaders.get`。
- 命令执行优先级：宿主内建 → 磁盘脚本 → WebView 注册（同优先级时宿主内建优先）。

## 6. MESSAGE_SENDING mutate 语义

- **宏同步展开（核心 mutate 通道）**：`preprocessUserInputParts`（持久化前唯一预处理点）扩展——USER 正则之后、构建 UIMessage 之前，经 `TavernScriptRegistry.expandMacros` 同步展开（超时 2s 兜底原样）。宏展开入库；`MESSAGE_SENDING/MESSAGE_SENT` 事件 payload.preview 反映展开后文本。
- **异步钩子（best-effort）**：新 RPC `sendHook.register(fnSource)`；ChatService 发送前经 controller 问询（异步，超时 500ms 默认原样；无活跃 WebView 时跳过）。
- 偏差文档化：不做严格 ST 同步阻塞语义（Android WebView 无同步 evaluateJavascript）。

## 7. getRequestHeaders

- 新 RPC `requestHeaders.get` + 新权限位 `allowRequestHeaders`（默认 false，Settings 持久化 + 运行时权限 UI 开关）。
- 返回 `assistant.customHeaders + model.customHeaders` 合并列表（name/value 对）；按需 RPC 拉取（不进快照）。
- 宿主侧：`TavernRuntimeController` 注入 assistant/model 上下文（新 `setHeaderSource(json)` 或经 setContext 扩展）。

## 8. 流式快照优化（B2a 遗留）

- ChatList 快照 remember 键降级：`conversation.messageNodes.size + 最后节点 selectIndex + isGenerating + assistant + userName`（流式期间同 size 不重建，消除每 token 全量重建+推送；完成时 size 变触发重建）。
- 快照内容语义不变（构建时取当前 messageNodes 状态）。

## 9. 测试与验证

- 新测试：
  - `TavernScriptRegistryTest`（注册/重名覆盖/重载不丢/配额上限）
  - 内建命令测试（setvar/getvar/add/sub/random/roll/echo 经 StatusVariableStore；错误输入行为）
  - `SlashCommandInterceptorTest`（内建优先于脚本、无匹配透传、变量 accessor 路由）
  - `expandVisualMacrosTest` 基线（roll/random/datetimeformat 缺失覆盖补齐）
  - `TavernRuntimeControllerTest` 增补（macros.register/slash.register/requestHeaders.get 权限矩阵）
  - 主路径参数链：JS 模板/controller 既有测试不回归 + 冒烟复验
- 全量：`:app:testDebugUnitTest`、`:app:compileDebugKotlin`、`:app:assembleDebug` 全绿。
- 模拟器冒烟：主路径（普通 markdown 酒馆对话）getContext 非空复验（B2a 合成路径修复后复验）；/setvar 改变状态面板变量；registerMacro 展开入库；MESSAGE_SENDING preview 为展开后文本。

## 10. 风险与对策

- QuickJS 宏执行性能：注册宏每次展开一次 QuickJS 调用——注册表缓存 evaluate 后的函数句柄（按 name 缓存），展开仅 fn.call。
- 宏展开时机与 USER 正则顺序：正则先（保持既有语义），宏后（新语义）；展开失败静默保留原文。
- WebView 重载丢注册：注册表在宿主侧，重载后 JS 侧重新 registerMacro 幂等覆盖。
- 流式快照键降级后「消息内容更新但 size 不变」场景（同长度编辑）快照不刷新——可接受（编辑触发 selectIndex/内容事件另行重建时兜底）。
