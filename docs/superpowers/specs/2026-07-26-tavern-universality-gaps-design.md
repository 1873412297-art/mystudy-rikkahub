# 酒馆角色卡通用性：缺口分析与下一步设计（2026-07-26）

## 背景

酒馆功能包（2026-07-19 计划）已完成 5 大功能：世界书 ST 语义扩展（selective/probability/tokenBudget/recursiveScanning）、ST 正则（flags/minDepth/maxDepth）、快速回复 QuickReply、作者注释 @Depth、脚本 API 扩展（变量作用域/宿主事件总线）。集成验证 434 测试全绿，`assembleDebug` 通过。

2026-07-26 常规审查结论：

- 当前未提交 WIP 编译通过（`:app:compileDebugKotlin`），定向单测（StatusTrailingBlock/ResidualUserName/AuthorNote/Lorebook/RegexOutput/ScriptApi）全绿。
- 抽查 `PromptInjectionTransformer`（作者注释间隔确定性、概率短路、递归扫描上限、tokenBudget 裁剪、关键词正则缓存）与 `RegexOutputTransformer`（深度过滤、流式实例复用）实现质量良好，未发现逻辑错误。

## 通用性现状盘点（已覆盖）

- 角色卡 V1/V2 导入：first_mes、alternate_greetings（含聊天内开场白选择器）、character_book 嵌入世界书、creator_notes、post_history_instructions、ST 正则字段
- 宏：{{user}}/{{char}}/{{persona}}/{{description}}/{{personality}}/{{scenario}}/{{time}}/{{date}}/{{weekday}}/{{isotime}}/{{isodate}}/{{datetimeformat}}/{{random}}/{{pick}}/{{roll}} 等
- JS-Slash-Runner 兼容斜杠脚本（用户输入 `/cmd` 路由到注册脚本）
- Tavern Helper 渲染运行时（WebView 脚本、权限、世界绑定、变量网关、宿主事件）

## 缺口清单（头脑风暴，按价值×工作量排序）

### 候选 1：世界书 sticky / cooldown / delay 装饰器（推荐，本轮实施）

SillyTavern 世界书条目的三个"触发装饰器"，进阶角色卡广泛使用：

- **sticky**：条目命中后，在后续 N 个用户轮次内持续注入（无需再次命中关键词）。
- **cooldown**：条目命中后，N 个用户轮次内不再触发（即使关键词再次命中）。
- **delay**：对话前 N 个用户轮次内该条目不触发。

设计要点：

- 状态**从消息历史确定性推导**，不新增持久化：在**完整非系统消息历史**（不受条目 scanDepth 限制）中定位"条目关键词最近一次命中的消息"，以 USER 消息数作为轮次轴，计算距当前轮的用户轮次差 k。
  - "用户轮次"定义与作者注释 interval 一致：USER 消息计数。
  - 用完整历史而非 scanDepth 窗口做装饰器记账，保证 sticky N > scanDepth 的常见配置仍生效；常规关键词/selective/probability 匹配仍只看 scanDepth 窗口。
- 判定顺序：delay（当前用户轮次 < delay 直接跳过）→ cooldown（历史有命中且 k ≤ cooldown 则整轮跳过）→ 常规关键词/selective/probability（命中即注入）→ sticky（未命中但历史曾命中且 k ≤ sticky 则注入，不消耗概率掷骰）。cooldown 优先级高于 sticky：cooldown 期内 sticky 不生效。
- 数据模型：`PromptInjection.RegexInjection` 追加 `sticky: Int = 0`、`cooldown: Int = 0`、`delay: Int = 0`，默认 0 保证 JSON 向后兼容。
- 落点：`collectLorebookInjectionMatches` / `scanLorebookEntry`（PromptInjectionTransformer.kt）；`PromptInjectionMatch` 追加 `triggerKind`（KEYWORD/STICKY）供 trace 区分。
- 配套：LorebookPage 编辑器加三个数值输入；ExportSerializer 同步；JVM 单测覆盖纯逻辑（用固定 random）。

权衡：另一种做法是持久化"触发记录"到会话（对齐 ST 的内部状态），更精确但引入 Room 迁移与跨设备同步问题；历史推导方案在"删除/编辑消息"场景下语义略有差异，但无状态、可测试、零迁移，明显更适合本期。

### 候选 2：内置 ST 斜杠命令

`/setvar`、`/getvar`、`/add`、`/sub`、`/random`、`/pick`、`/echo`、`/send` 等。社区 QR 脚本大量依赖；当前拦截器无脚本接管时直接透传给 AI（污染对话）。落点：`SlashCommandInterceptor` 加内建命令分发（先于脚本匹配），变量读写接入 `ScriptVariableStore`/`StatusVariableStore`。

### 候选 3：聊天变量查看/编辑面板

状态栏类卡片排查问题的刚需（对齐 ST 的变量面板）。只读列表 + 简单编辑，入口放聊天页调试区（GroupContextDebugSheet 已有先例）。低工作量，与候选 2 的 /setvar 共用变量网关。

### 候选 4：正则作用域扩展到用户输入

ST 正则脚本可作用于用户输入（发送前改写）。当前 `RegexOutputTransformer` 仅处理 ASSISTANT 输出。加 INPUT 作用域 + 输入侧 transformer 调用点。

## 本期决策

实施**候选 1**（世界书 sticky/cooldown/delay）。理由：纯 transformer 逻辑、无 Room 迁移、确定性推导可完整单测，与世界书 ST 语义扩展（功能包功能 1）直接衔接，对进阶角色卡通用性提升最大。

后续轮次按序推进候选 2 → 3 → 4，每个一轮实现 + 测试 + 截图审查。

## 验收标准（候选 1）

1. 三个装饰器语义与上述判定顺序一致，全部有 JVM 单测（含边界：sticky=0/cooldown=0 退化为现状、delay 轮次边界、sticky 不消耗概率、cooldown 优先级高于 sticky）。
2. Lorebook 编辑器可配置三个字段；导出 JSON 包含新字段且向后兼容。
3. `:app:testDebugUnitTest` 全绿，`:app:assembleDebug` 通过。
4. 模拟器截图审查：世界书编辑器新字段 UI + 一次 sticky 触发的 PromptTrace 记录。
