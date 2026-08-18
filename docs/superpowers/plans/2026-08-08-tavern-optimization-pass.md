# 酒馆功能整体优化 + 整词匹配实施记录（2026-08-08）

> 本轮目标：在既有酒馆功能栈上做整体优化（去重 + 健壮性 + UI 一致性），
> 并按 TDD 新增 SillyTavern 对齐功能，避免重复性内容。

## 一、整体优化（去重 + 健壮性，全部 TDD）

1. **StatusTags 单一事实来源**（`data/ai/status/StatusTags.kt`，新增）
   - 状态块标签族（status_block/statusblock/statusbar/status!/状态栏）正则原先在
     `StatusBlockExtractor`、`RichTextRenderPolicy`、`Markdown` 三处重复定义；
   现统一由 `StatusTags` 提供 open/close/segment/wrapper 四类正则。
   - 测试：`StatusTagsTest`（标签族全覆盖、大小写不敏感、缺失闭标签容忍、消费方一致性）。

2. **StatusFallbackHtml 共享构建器**（`data/ai/status/StatusFallbackHtml.kt`，新增）
   - `StatusRenderer.buildFallbackHtml` 与 `StatusPlaceholderTransformer.buildFallbackHtmlDirect`
     两份近似内联样式 HTML 构建合并为一份；统一转义（& < >，含 list 值与表达式），
     修复原 transformer 版本漏转义 `>` 的问题。
   - 测试：`StatusFallbackHtmlTest`（空态/表达式/嵌套 map/列表/转义/空值）。

3. **StatusPlaceholderTransformer 调试日志清理**
   - 移除每个流式 chunk 的 ENTER/EXIT 日志、前后两次全量标签扫描校验、
     成功路径的 Log.i/d；仅保留真实异常与空会话警告。
   - 行为不变（`transform` 仍捕获异常回退原文），流式开销下降。

4. **StatusVariableStore 生命周期清理**
   - `ConversationRepository.deleteConversation` 新增调用 `statusVariableStore.remove(id)`，
     消除 per-conversation 状态变量在内存中长期累积（此前 remove 无任何调用方）。
   - 测试：`StatusVariableStoreTest`（init/get/applyPatch/set/remove 幂等/隔离/toJsObject）。

## 二、UI 一致性

5. **统一 EmptyState 组件**（`ui/components/ui/EmptyState.kt`，新增）
   - 应用于 QuickMessagesPage / SkillsPage / LorebookPage / PromptPage / TavernPromptConsolePage，
     替换各处手写空状态；支持 icon/title/hint/contentArrangement 参数。
6. **TavernCardEditorPage TopBar 配色**
   - `LargeFlexibleTopAppBar` 补 `colors = CustomColors.topBarColors`，与其它酒馆页面一致。
7. **TavernCardViewerPage 死代码清理**
   - 移除从未被调用的 `parseCardFromUri`（URI 读取逻辑已内联在 `LaunchedEffect` 中）及其独占的
     `android.util.Base64` import，消除重复解析路径。

## 三、新功能（TDD）：世界书关键词「整词匹配」（SillyTavern Match Whole Words 对齐）

8. 模型：`PromptInjection.RegexInjection` 新增 `matchWholeWords: Boolean = false`（默认关，JSON 向后兼容）。
9. 匹配逻辑：`PromptInjectionTransformer.matchInjectionKeywords` 新增整词匹配分支
   —— 非正则模式下关键词两侧不得紧邻 ASCII 字母/数字/下划线（`(?<![A-Za-z0-9_])kw(?![A-Za-z0-9_])`），
   CJK 按 SillyTavern `\b` 语义保持子串匹配；正则模式不叠加整词限制。
   主关键词与 selective 次关键词均生效。
10. 序列化同步：`TavernRuntimeWorldBinding`（to/from JSON）、`ExportSerializer`（ST 导入支持
    `match_whole_words` / `matchWholeWords` 两种拼写）、`PromptInjectionMatch` trace 新增字段。
11. UI：`LorebookPage.RegexInjectionEditSheet` 新增「整词匹配」开关；strings 新增
    `prompt_page_match_whole_words`（en/zh）。
12. 测试：`PromptInjectionWholeWordTest`（8 例：子串兼容/不命中复合词/独立词命中/大小写/
    CJK 子串语义/selective 次关键词/正则不叠加）、`LorebookSerializerTest`（ST 两种拼写导入）、
    `LorebookSerializationTest`（默认值 + 往返）。

## 验证结果

- `./gradlew :app:testDebugUnitTest -x :web:buildWebUi`：**74 测试类 / 556 测试，0 失败 / 0 跳过**
  （基线 70 类 / 518 测试 → 新增 38 个）。
- `./gradlew :app:compileDebugKotlin -x :web:buildWebUi`：BUILD SUCCESSFUL。
- `./gradlew :app:assembleDebug -x :web:buildWebUi`：BUILD SUCCESSFUL。

## 备注

- 工作区路径为 `C:\Users\18734\Desktop\HTML\rikkahub-source`（注意拼写，勿写成 rikkahhub）。
- 环境曾出现"工作区目录不可访问"误报，实为路径拼写错误导致，已在本轮纠正。
