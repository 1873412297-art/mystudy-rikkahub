# Android 酒馆沉浸式呈现升级设计

## 目标

在 Android 单人酒馆会话中，以单一 ST 兼容 WebView 承载纯文本/HTML 消息区，同时保留 Compose 顶栏、输入框和原生消息操作。统一角色卡开场、普通 HTML、HUD 与编辑预览的运行时和视觉语义，并在出现不支持的消息部件时无损回退现有 Compose 列表。

## 范围与约束

- 仅 Android；web-ui、群聊和普通非酒馆助手不改变视觉路径。
- ST 模式只用于单人、绑定角色卡且当前消息全部为 Text/HTML 的会话。
- 图片、文件、音视频、推理、工具或状态占位等部件出现时，整页切回 Compose 兼容视图。
- 角色卡完整 CSS/HTML/JS 仅作用于 WebView 内容区，不能影响 Compose 顶栏和输入框。
- 文件访问、content URI、危险协议和 WebView 顶层导航始终禁止。
- 新装与升级用户默认开启最大兼容权限；请求头/API Key 读取仍单独关闭。

## 1. 统一 ST 会话宿主

### 1.1 视图选择

`TavernPresentationResolver` 根据助手和消息快照产生 `ST_WEB` 或 `COMPOSE`。ST 模式要求：助手为 SOLO、`tavernCardJson` 非空、所有当前分支消息仅含 `UIMessagePart.Text`。任何不支持部件均使会话回退 Compose，并向用户显示原因；数据和分支结构不转换。

### 1.2 快照与增量协议

Kotlin 侧构造 `TavernConversationSnapshot`，包含会话 ID、消息节点、当前分支、角色/名字、Text renderMode、生成状态、主题变量和卡片 CSS。首载发送全量快照；后续使用有序 patch：

- `upsert_message`
- `remove_message`
- `select_branch`
- `set_streaming`
- `replace_all`

JS 端以 message/node ID 定位 DOM，使用 ST 兼容结构 `.mes > .mes_block > .name_text + .mes_text`。流式文本仅更新相应段，不重建整份 vendor 模板。

### 1.3 HTML 与原生交互

Markdown 由本地 markdown-it/DOMPurify/KaTeX/Mermaid/highlight.js 渲染。完整 HTML 文档在消息内独立 iframe 中运行，默认最高约一屏，超出时内部滚动并可全屏查看。角色卡 CSS 完整注入 ST 文档作用域。

长按消息、分支左右切换、链接打开和运行时 RPC 通过窄桥回到 Compose/ChatService，复用现有复制、编辑、删除、收藏、重新生成和 `MessageNode.selectIndex` 逻辑。

文档每次 ready/reload 后必须重新推送 context、current message、variables 和宿主事件，不依赖上次内容哈希。首屏超时、渲染进程无响应或崩溃时保留原始消息，显示静态降级、重试与切换 Compose 入口。

## 2. 开场舞台

### 2.1 开场元数据

开场标记存入首条 `UIMessagePart.Text.metadata`，使用带类型的 helper 读写：

- `kind = "tavern_opening"`
- `greetingIndex`
- `contentFingerprint`
- `cardFingerprint`

旧消息通过“首个 HTML 预设消息与角色卡 `first_mes` 匹配”补标，不增加 Room 列。`Screen.Chat` 新增 greeting index 参数，并保留旧 Base64 greeting 的一次性兼容读取。

### 2.2 候选与提交

首条用户消息前，聊天内容区显示全宽开场舞台；`first_mes` 与全部 alternate greetings 同时保活、无产品数量上限，并运行完整 HTML/JS。

每个候选拥有独立的可写覆盖层：消息、聊天/全局变量、世界书变更以及宏/命令注册先记录在候选副本。选择候选时原子提交该副本，丢弃其他候选。外部网络请求即时发生且不可撤销，界面必须提示该事实。

首条用户消息发出后销毁候选舞台，并在右上角显示开场图标。点击后以独立全屏查看器重新加载当前开场，不改变聊天滚动位置。已有用户消息时更换开场必须新建对话。

### 2.3 编辑器预览

角色卡编辑页为 `first_mes` 和 alternate greetings 提供源码/实时预览切换。进入全功能预览前必须由用户手动选择该角色的一段真实会话；预览脚本、网络和写操作直接作用于所选会话，页面持续显示目标并允许重新选择。

## 3. HUD

HUD 从占据列表空间的折叠卡改为顶部悬浮摘要条，显示最新状态块 `headerLine` 和更新提示。点击打开最高约 90% 屏高的底部面板，承载多角色分页、可折叠 section、HTML 状态内容和剧情选项。剧情选项只预填输入框并关闭面板，不直接发送。

HUD HTML 复用统一酒馆运行时和文档生命周期，避免与普通消息、开场预览再次形成独立实现。

## 4. 权限与迁移

`TavernRuntimePermissions` 的新默认和一次性升级迁移开启：scripts、world write、message write、network、variables write、event subscribe、macro register；`allowRequestHeaders` 保持 false。设置页增加“最大兼容”和“保守模式”预设，仍允许逐项调整。

WebView 网络请求受 `allowNetwork` 控制；请求头读取继续受 `allowRequestHeaders` 独立控制。无论权限设置如何，文件/content 访问和危险导航都不开放。

## 5. 验收

- TDD 覆盖视图选择、旧开场识别、候选覆盖层提交、权限迁移、patch 顺序、分支切换、HUD 预填与异常降级。
- 可见 Activity 仪器测试覆盖 HTML/JS、宏、变量、context 重推和 WebView 进程恢复。
- 运行 `:app:testDebugUnitTest`、`:app:compileDebugKotlin`、`:app:assembleDebug` 与筛选后的 `connectedDebugAndroidTest`。
- 在 Huawei MNA-AL00 上使用真实角色卡验证多开场、全屏回看、右上角收纳、ST/Compose 回退、HUD、主题、旋转/返回与 logcat。
- 至少 12 个候选同时保活无崩溃；无数量上限导致的极端 OOM 是明确接受风险。

