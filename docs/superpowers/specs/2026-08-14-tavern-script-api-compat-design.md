# 酒馆脚本 API 兼容性设计（子项目 B2a：上下文与事件）

日期：2026-08-14

## 1. 目标与范围

- B2 总目标：酒馆脚本 API 与 SillyTavern 对齐（getContext / event_types / MacroHelper / SlashCommandParser）。
- B2a 范围：`SillyTavern.getContext()` 宿主推送快照、`event_types` 常量表、宿主事件扩面（生成链路/消息编辑删除切换/渲染细分 + ST 命名对齐）。
- 不在 B2a：MacroHelper.registerMacro、SlashCommandParser/registerSlashCommand、内建斜杠命令、getRequestHeaders（B2b）；MESSAGE_SENDING mutate 语义（B2b 与 QuickJS 宏执行机制一起设计）；完整 ST 事件全集。

## 2. `SillyTavern.getContext()` 宿主推送快照

### 2.1 推送通道

- `TavernRuntimeController` 新增 `setContext(context: JsonObject)`（替代 `setCurrentMessage` 单消息注入；`messages.getCurrent` 数据源改为快照内 chat 当前消息）。
- 快照变化 → controller 经现有 outbound 事件通道发 `th:context_updated` DOM 事件（detail = 快照 JSON）；JS 侧内部自动订阅（无需权限）更新缓存。
- `window.SillyTavern.getContext()` 同步返回缓存快照（对齐 ST 同步调用约定）；初次注入前返回 null。

### 2.2 快照数据面（实用子集）

```json
{
  "chat": [{"role": "assistant|user|system|tool", "text": "纯文本", "messageId": "...", "nodeId": "...", "isCurrent": true}],
  "character": {"name": "...", "description": "...", "personality": "...", "scenario": "..."},
  "user": {"name": "..."},
  "worldInfo": [{"name": "...", "content": "..."}],
  "conversationId": "...",
  "onlineStatus": true,
  "variables": { ... chat scope 变量树 ... }
}
```

- chat 含最近 50 条消息（按节点顺序，当前选中分支），`isCurrent` 标记当前消息。
- character 从 `Assistant`（name/description 来自角色卡或字段）；user 名从 `Settings.displaySetting.userNickname`。
- worldInfo 从世界书条目（名称 + 内容纯文本）。
- onlineStatus = 会话正在生成。
- variables = `StatusVariableStore` chat scope 当前值。

### 2.3 宿主组装点

- ChatList 层（有 conversation/assistant/settings/isGenerating）构建快照 JSON → ChatMessage → MarkdownWebView → `controller.setContext`。
- 刷新时机：conversation 更新（含流式）、生成状态翻转、分支切换。快照构建是纯函数（可 JVM 测试）。

## 3. event_types 常量表

- JS 侧新增 `window.event_types`，与本 B2a 事件扩面一一对应：
  - `GENERATION_STARTED`、`MESSAGE_SENT`、`MESSAGE_RECEIVED`
  - `MESSAGE_EDITED`、`MESSAGE_DELETED`、`MESSAGE_SWIPED`
  - `CHARACTER_MESSAGE_RENDERED`、`USER_MESSAGE_RENDERED`、`MESSAGE_RENDERED`（兼容旧通用事件）
- 订阅机制复用现有 `th:<name>` DOM 事件；`eventSource.on(event_types.X, cb)` 直接可用。
- 旧事件名保留并列发射（`MESSAGE_SENDING`/`GENERATION_FINISHED`），不破坏现有脚本。

## 4. 宿主事件发射点扩面

| 事件 | 发射点 | payload |
|---|---|---|
| `GENERATION_STARTED` | ChatService 生成开始 | { conversationId } |
| `MESSAGE_SENT` | 用户消息持久化后（与现有 MESSAGE_SENDING 同点） | { role: "user", preview } |
| `MESSAGE_RECEIVED` | 生成完成（与现有 GENERATION_FINISHED 同点） | { role: "assistant", messageId } |
| `MESSAGE_EDITED` | editMessage 路径 | { messageId } |
| `MESSAGE_DELETED` | deleteMessage 路径 | { messageId } |
| `MESSAGE_SWIPED` | selectMessageNode 分支切换 | { nodeId, selectIndex } |
| `CHARACTER_MESSAGE_RENDERED` | MarkdownWebView onPageFinished（assistant 消息） | { conversationId } |
| `USER_MESSAGE_RENDERED` | MarkdownWebView onPageFinished（user 消息） | { conversationId } |

- 事件类型枚举扩展 `TavernHostEventType`；payload 用 kotlinx JsonObject（与现有一致）。
- 渲染细分：MarkdownWebView 需要知道消息角色——新增可选参数 `tavernMessageRole`，ChatMessage 调用处传入。
- 事件过滤沿用现有机制（conversationId 匹配 + 已订阅集合 + `allowEventSubscribe` 权限）。

## 5. 权限与安全

- `getContext` 受 `allowScripts` 总开关保护（脚本启用即可读，与 ST 一致；快照仅含本会话数据）。
- context_updated 是内部通道（宿主→WebView），不经 RPC 桥、不受订阅权限约束。
- 快照文本截断：单条消息纯文本截断 2000 字符（防超大数据注入）。

## 6. 测试与验证

- Kotlin 单测：
  - 快照构建纯函数（chat 截断/排序/isCurrent/character/user/worldInfo/variables/onlineStatus）
  - 新事件类型枚举序列化与 payload
  - controller.setContext → outbound context_updated 事件（含 messages.getCurrent 数据源切换）
  - 新发射点（GENERATION_STARTED/MESSAGE_SENT/MESSAGE_RECEIVED/MESSAGE_EDITED/MESSAGE_DELETED/MESSAGE_SWIPED）的 emit 验证（复用现有 ChatService 测试模式）
- JS 模板测试：`SillyTavern.getContext`、`event_types` 常量表、context_updated 内部订阅（TavernRuntimeScriptTest 扩展）
- 全量：`:app:testDebugUnitTest`、`:app:compileDebugKotlin`、`:app:assembleDebug` 全绿。
- 模拟器冒烟：调试入口（TavernRuntimeSmokeActivity）或 DB 注入对话 + 脚本测试页验证 getContext 返回与事件接收。

## 7. 风险与对策

- 快照体积：50 条 × 2000 字符上限 ≈ 100KB 边界；context_updated 走 DOM 事件 JSON 序列化，流式期间高频更新——快照构建时 chat 内容哈希，不变则跳过推送（controller 侧去重）。
- 旧事件名与 ST 名并存的双发射：脚本同时订阅两种名会收两份——文档化约定（新脚本用 ST 名）。
- getContext 初次为 null：脚本应判空；文档与 smoke 测试覆盖。
