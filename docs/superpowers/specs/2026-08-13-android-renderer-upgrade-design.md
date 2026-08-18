# Android 酒馆渲染链路提升设计（子项目 B1）

日期：2026-08-13

## 1. 目标与范围

- 提升 Android 端酒馆渲染链路：ST（SillyTavern）主题开箱可用、Material 主题/角色卡 CSS 通道打通、流式增量渲染、WebView 生命周期治理。
- 范围：st-message.html 重建、主题与卡 CSS 注入、流式增量文本更新、WebView 销毁与列表优化、前端库本地化。
- 不在范围：脚本 API 兼容性（子项目 B2）；WebView 池化；正文/状态面板 WebView 合并；raw HTML 完整文档路径的主题注入。

## 2. st-message.html 重建（ST 主题开箱可用）

### 2.1 DOM 结构对齐 ST 默认

```
#chat .mes.assistant|user|system|tool [data-message-id][data-rikkahub-role]
├── .avatar（可选占位）
├── .name_text > .name（角色名/用户昵称，非空时渲染）
├── .mes_text
│   ├── p[data-segment-id][data-kind]（每段 markdown 文本一个 <p>）
│   ├── .status_block 段（状态块/JSON Patch 转义 <pre> 展示）
│   └── …
└── .mes_buttons / .mes_edit_buttons（ST 类名占位，视觉由 Compose 层控制）
```

- 移除自造 `.mes_segment` 包裹层与 `.mes_header`（ST 主流主题不认这两个类，且包裹层破坏 `.mes_text > p` 类选择器）。
- 角色名使用 `.name_text > .name`，并在 name 元素上同时输出 `.ch_name` 兼容类（兼容群聊/旧主题选择器）。
- 保留 `data-message-id`/`data-rikkahub-role`/`data-segment-id`/`data-kind` 契约属性（契约测试同步更新）。

### 2.2 默认主题与 CSS 变量

- st-message.html 内置一套接近 ST 默认的浅色样式，作为无卡 CSS 时的 fallback。
- 所有主题相关色值引用 CSS 变量：`--rikkahub-bg / --rikkahub-surface / --rikkahub-text / --rikkahub-text-secondary / --rikkahub-border / --rikkahub-accent`。
- 变量由 Kotlin 端按 Material colorScheme 注入（见第 3 节）。

### 2.3 前端库本地化

- 新增 `app/src/main/assets/html/vendor/`：markdown-it、DOMPurify、highlight.js、katex、mermaid 的单文件构建产物。
- st-message.html 与 mark.html 的库加载从 `https://esm.sh/...` 改为本地 `<script src="file:///android_asset/html/vendor/...">`。
- 加载失败降级路径保留（转义纯文本），但触发率大幅降低。

## 3. 主题与角色卡 CSS 通道

### 3.1 Material → CSS 变量

- `MarkdownWebView` 现有 6-9 色提取（`MaterialTheme.colorScheme` → hex）改为注入 st-message 文档根 `:root { --rikkahub-*: ... }`。
- 暗/亮切换：renderKey 已含色值，变化即重载（现状机制不变，注入点从模板占位符改为 CSS 变量）。
- mark.html（纯 markdown 预览）路径不动（保持现有占位符机制）。

### 3.2 角色卡 CSS → 消息气泡

- 新共享工具 `TavernCardStyleResolver`：`resolve(assistant): TavernCardStyle?`，产出 `{ css: String?, versionKey: String }`；CSS 复用 `TavernCardCssExtractor`。
- 注入点：
  1. 状态面板：`StatusRenderer` 的 `<style>` 前缀（现状不变）。
  2. 消息气泡（新增）：st-message 文档 `<head>` 内联 `<style>`（经 `sanitizeCss` 清洗：所有 `</` → `/* */ `，与 StatusRenderer 同规则，防 `</style><script>` 逃逸）。
- 优先级：st-message 默认主题 < 卡 CSS（后注入自然覆盖）。
- renderKey 加卡 CSS 版本键（assistant 切换/卡更新时重载）。

### 3.3 不做

- raw HTML 完整文档路径（卡片自带样式）零注入维持。
- mark.html 路径不动。

## 4. 流式增量文本更新

### 4.1 JS 桥

- st-message.html 新增 `RikkahubDomBridge`：
  - `applySegmentPatch(patchesJson)`：`[{segmentId, kind, text}]` → 按 `data-segment-id` 定位 `<p>` 替换 `textContent`（保留容器）；不存在的段 append 新 `<p>`。
  - `renderMarkdownAll()`：对所有段一次性重跑 markdown-it（完成态修正表格/高亮/行内格式）。
  - 完成后 `reportHeight()`。

### 4.2 Kotlin 侧

- `MarkdownWebView` 加 `streaming` 参数：true 时首次加载初始 HTML，后续内容变化不 `loadDataWithBaseURL`，改 `evaluateJavascript("RikkahubDomBridge.applySegmentPatch(...)")`。
- 段快照 diff：Kotlin 侧维护「段 id → 上次文本」Map（`StableSegmentSnapshot`），仅对变化的段生成 patch（避免每 token 全量替换）。
- `streaming` 真实传递：`Markdown.kt` 的 STABLE_DOM 路径当前硬编码 `streaming = false` → 改为从 `MarkdownBlock` 调用链传入（`ChatMessage` → `Markdown` → `MarkdownBlock`，`isGenerating` 已在 ChatMessage 层可用）。
- 完成态：`onGenerationFinish` 或 streaming=false 加载时走整文档渲染（现有路径），完成后 `renderMarkdownAll()` + 高度重测。

### 4.3 不做

- raw HTML 路径增量（无分段语义）。
- 中间态 markdown 复杂语法实时修正（完成后统一修）。

## 5. WebView 生命周期与列表优化

### 5.1 销毁

- `MarkdownWebView` 加 `DisposableEffect(Unit)`：`removeJavascriptInterface(RikkahubBridge / TavernRuntimeBridge)` + `webView.destroy()`。
- `StatusHudBar`、`MultiCharacterStatusView` 复用同一组件自动获益。

### 5.2 列表

- `ChatList.kt` LazyColumn 加 `contentType`（"message" 等），配合既有 `key`（node.id）稳定回收条目。

### 5.3 高度闪烁

- 初始 `viewHeight = 100` 改为 `viewHeight = 0` + `minHeightDp` 占位（默认 24dp），首次高度上报后展开。

### 5.4 不做

- WebView 池化；多 WebView 合并；WebViewContentCache 接入。

## 6. 测试与验证

- Kotlin 单测：
  - `StableMessageTemplateContractTest` 更新（新 DOM 契约、本地库引用、无 esm.sh）
  - `StableMessageHtmlRendererTest` 更新（CSS 变量注入、卡 CSS 内联）
  - 新增 `StableSegmentSnapshotTest`（段 diff 纯逻辑：新增/替换/不变）
  - `TavernCardStyleResolverTest`（版本键、null 场景）
  - sanitizeCss 复用验证（`</style>` 逃逸样例）
- 全量：`:app:testDebugUnitTest`、`:app:compileDebugKotlin`、`:app:assembleDebug` 全绿。
- 模拟器冒烟：DB 注入状态块对话 → 消息气泡 DOM 含 `.name_text`/`.mes_text > p`；卡 CSS 重样式生效；流式（Mock 模型）无整文档重载（观察日志/高度稳定）；暗/亮切换正常；HUD/多角色分页正常。

## 7. 风险与对策

- ST 主题兼容是「接近」而非「逐字节」：主流主题选择器（`.mes_text > p`、`.name_text`）覆盖，极端主题仍可能错位——接受，契约测试锁住核心形状。
- 增量 patch 与整文档渲染的状态一致性：段快照在每次整文档加载后重置；patch 失败（段不存在）时回退整文档重载。
- 本地库体积：vendor 单文件构建产物约 1-3MB，apk 体积增加可接受；mermaid 最大，如超预算可后置（默认不加载，按需）。
- 多版本 WebView 内核差异：本地 ES5 构建产物保证兼容。
