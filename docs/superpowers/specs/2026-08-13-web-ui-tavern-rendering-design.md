# web-ui 酒馆渲染栈设计（子项目 A）

日期：2026-08-13

## 1. 目标与范围

- 让 web-ui（React）完整渲染酒馆对话内容：服务端已下发的 `status_placeholder` part 目前被静默丢弃，`<status_block>` 标签原样泄漏为文本。
- 范围：状态块解析 + StatusPlaceholder 渲染、状态 HUD 栏、角色卡 renderStatus 实时重渲染（sandboxed iframe）、HUD 选项点击发送、变量树只读同步。
- 不在范围：变量写端点、web 端角色卡编辑器、Android 渲染器提升（子项目 B）。

## 2. 后端 API 扩展

### 2.1 `GET /api/assistant/{id}/tavern-render`

- 响应 `TavernRenderDto { statusRenderJs: String?, css: String? }`。
- CSS 从 `tavernCardJson` 提取，逻辑抽自 `StatusPlaceholderTransformer.extractCssFromCard` 为共享工具 `TavernCardCssExtractor`，Kotlin 测试迁移。
- 无角色卡返回空字段。

### 2.2 `ConversationDto.statusVariables`

- 加 `statusVariables: JsonObject?`（`WebDto.kt`），映射 `Conversation.statusVariables` 持久化字段。
- `snapshot` / `node_update` 事件自动携带。

### 2.3 对话 stream 新事件 `status_variables`

- DTO：`ConversationStatusVariablesEvent { type="status_variables", seq, conversationId, variables: JsonObject, serverTime }`。
- stream 挂载时订阅 `StatusVariableStore.getState(id)` StateFlow，`distinctUntilChanged` 后推送；stream 关闭时随 collect 取消订阅。
- 单元测试：事件 DTO 序列化。

## 3. web-ui 数据层

### 3.1 类型（`app/types/`）

- `parts.ts`：`StatusPlaceholderPart`（`type: "status_placeholder"`，`htmlContent` + `characterPages`）、`CharacterStatusPage`、`TextPart.renderMode?`（`"markdown" | "html"`，与 `Message.kt` `@SerialName` 小写一致）。
- `dto.ts`：`ConversationDto.statusVariables`、`StatusVariablesEventDto`。

### 3.2 解析器移植（`app/lib/tavern/`，vitest）

- `status-tags.ts`：`StatusTags` 移植（openTag/closeTag/segment/wrapper 正则）。
- `status-extractor.ts`：`StatusBlockExtractor` 移植（标签族、`<maintext>` 剥离、details→section、编号选项、『…』header、裸 details 兜底、cleanedText）。
- `fallback-html.ts`：`StatusFallbackHtml` 移植（转义 `& < >`、嵌套 map/list 行）。
- 测试用例与 Kotlin 端对齐（复用相同样例数据）。

### 3.3 状态存储（zustand）

- `useTavernStore`：`variablesByConversation`；snapshot 初始化 + `status_variables` 事件增量；`cardsByAssistant` 角色卡缓存（`ensureCardLoaded` 懒加载 `/tavern-render`，失败降级 null）。

### 3.4 SSE 接线

- `conversations.tsx` 流解析加 `status_variables` 分支 → store 更新；snapshot/初始 GET 写入变量树并触发角色卡加载。

## 4. web-ui UI 层

### 4.1 `HtmlFrame`（`components/tavern/html-frame.tsx`）

- 统一 iframe 渲染组件。
- 展示模式：`srcdoc` + `sandbox="allow-same-origin"`（无 `allow-scripts`）——同源可读 `contentDocument` 测量高度，脚本被禁。
- 重渲染模式 `RenderStatusFrame`：`sandbox="allow-scripts"`（无 `allow-same-origin`，opaque origin），执行 `renderStatus(variables, metadata)` 后 `postMessage` 回传 HTML/高度；`</script` 逃逸清洗；5 秒超时降级 fallbackHtml。
- 懒加载：IntersectionObserver 进入视口才注入 srcdoc。
- 高度自适应：onLoad 读 scrollHeight + 1s×10 次兜底轮询。

### 4.2 消息渲染

- `message-part.tsx` switch 加 `status_placeholder` → `StatusPlaceholderView`。
- `StatusPlaceholderView`：`characterPages.length >= 2` → Tabs 多角色分页；单页展示服务端 `htmlContent`；角色卡 JS + 最新变量树可用时用 `RenderStatusFrame` 实时重渲染，成功替换显示。
- `text-part.tsx`：`renderMode === "html"` → `HtmlFrame`；默认 markdown 路径前用 `extractStatusBlock(...).cleanedText` 剥离状态标签（对齐 Android `ChatMessage.kt:170-181`）。

### 4.3 `StatusHudBar`（`components/tavern/status-hud.tsx`）

- 尾部扫描最新含状态块的 assistant 消息 → 提取 → 可折叠卡片（对齐 Android `StatusHudBar.kt`：headerLine 作标题、section 渲染、HTML section → `HtmlFrame` 无脚本、编号选项 chips）。
- 选项点击 → 直接 POST `conversations/{id}/messages`（`{ parts: [{ type: "text", text: optionText }] }`）。
- 挂载在 `conversations.tsx` 消息列表上方。

### 4.4 降级

- 无角色卡 JS/CSS → 直接渲染服务端 `htmlContent`（不启用重渲染）。
- 无 `status_variables` 事件（旧服务端）→ 仅 snapshot 初始化，重渲染不启用（`variables` 缺失即不触发）。
- 重渲染失败/超时 → fallbackHtml（`buildFallbackHtml`）或保留服务端 htmlContent。

## 5. 测试与验证

- Kotlin：`TavernCardCssExtractorTest`、`TavernRenderDtoTest`、`ConversationDtoVariablesTest`、`StatusVariablesEventTest`；`:app:testDebugUnitTest` 全绿、`:app:compileDebugKotlin`、`:app:assembleDebug`。
- web-ui：vitest（新引入）覆盖 3 个解析器；`pnpm test`、`pnpm typecheck`、`pnpm lint`、`pnpm build` 全绿。
- 冒烟：`pnpm dev` + 运行中的 app web 服务器，验证状态块渲染/HUD/多角色/选项发送/变量重渲染/无 JS 降级。

## 6. 风险与对策

- iframe 高度自适应抖动：onLoad 测量 + 轮询兜底，容器 `overflow-hidden`。
- 变量推送频率：流式生成时高频更新 → 服务端 `distinctUntilChanged`；UI 层重渲染由 state 变化驱动。
- 性能：长对话多 iframe → IntersectionObserver 懒加载，离屏不注入 srcdoc。
- 安全：展示模式禁脚本；重渲染模式 opaque origin（`allow-scripts` 无 `allow-same-origin`）+ postMessage source 校验；renderStatus 源码 `</script` 清洗。
