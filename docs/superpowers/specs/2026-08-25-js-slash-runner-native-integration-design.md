# JS-Slash-Runner 渲染与脚本运行原生融合设计

日期：2026-08-25

## 1. 背景与基线

本设计将 JS-Slash-Runner（界面名“酒馆助手”）的两项核心能力以原生方式融合进 RikkaHub：

1. 把消息中的 HTML 前端代码块渲染成可交互界面。
2. 管理并运行全局、角色和预设作用域的常驻 JavaScript 脚本。

参考上游快照为 `N0VI028/JS-Slash-Runner@c62cdc3`，清单版本 `4.9.3`。目标工作树为
`codex/port-private-to-2.4.10@de407ac8`。目标基线已经具备 STABLE_DOM 消息渲染、沙箱 iframe、
Tavern Runtime RPC、宿主事件、变量/世界书访问、宏、斜杠命令、sendHook，以及 Android 12 隔离 QuickJS
工作进程；本项目在这些能力上增量建设，不另起一套互不相通的运行时。

上游仓库所附 AFPL 文本仍把授权对象写为 AFPL Ghostscript，不能作为直接复制 JS-Slash-Runner 源码的清晰授权。
因此实现采用行为兼容和数据格式兼容的独立代码，不复制上游 Vue、TypeScript、CSS 或构建产物。规格中的上游名称仅用于
说明兼容行为和可互操作格式。

## 2. 目标

- 在 Android 应用内提供适合手机操作的原生“酒馆助手”管理页面。
- 完整管理脚本及文件夹，支持导入、导出、编辑、启停、排序、移动、复制、删除、搜索、脚本数据和按钮。
- 对齐全局、角色、预设三类脚本作用域，并在上下文变化时正确加载、卸载和恢复。
- 运行依赖 DOM、事件、定时器、模块导入和 iframe 生命周期的浏览器脚本，同时保留现有 QuickJS 宏/命令隔离。
- 将消息中的一个或多个可执行 HTML 前端块渲染为相互隔离、自动高度、可重载的 iframe。
- 支持完成态和可选流式渲染，不破坏消息编辑、分支切换、删除、列表回收和返回聊天等路径。
- 为脚本和消息前端提供真实的宿主 API；不以固定值或“成功空响应”冒充支持。
- 在物理 Android 设备上验证管理、导入、渲染、按钮、事件、持久化、异常恢复和权限全流程。

## 3. 非目标

- 不移植上游“工具”“优化”“开发”标签页中的音频播放器、提示词查看器、变量管理器 UI、监听器或 ST 页面优化开关。
- 不实现 SillyTavern 第三方扩展的安装、卸载、更新和管理员权限接口。
- 不模拟 RikkaHub 不存在的 SillyTavern DOM 管理页面，例如角色管理抽屉或预设下拉框。
- 不保证脚本在 Android 进程被系统杀死后后台持续运行；应用重新进入前台后按持久配置重建运行时。
- 不直接嵌入上游 Vue 面板，也不把整套 SillyTavern 网页伪装成宿主。

上述非目标不得影响本项目两项核心能力。脚本调用不可映射的 ST 管理 API 时必须返回结构化
`UNSUPPORTED_HOST_CAPABILITY` 错误，并写入该脚本的日志；不得静默忽略。

## 4. 信息架构与入口

### 4.1 主入口

在现有 `设置 → 扩展` 页面，与“快捷指令”“提示词”“Agent Skills”“工作区”并列新增：

- 标题：`酒馆助手`
- 说明：`管理消息前端渲染与酒馆脚本`
- 点击进入独立全屏路由 `Screen.TavernHelper`。

路由接受可选 `assistantId` 和 `conversationId`。从设置进入时二者为空；页面允许选择角色/助手作用域，但默认显示全局脚本。

### 4.2 聊天快捷入口

仅当当前助手绑定酒馆角色卡或已存在任一酒馆助手脚本时，在聊天页右上角更多菜单显示“酒馆助手”。点击时传入当前
`assistantId` 和 `conversationId`，页面直接显示当前角色和助手/预设作用域。

聊天快捷入口不是唯一入口。即使没有任何对话，用户仍可从“设置 → 扩展”管理全局脚本和渲染设置。

### 4.3 页面布局

页面采用 `LargeFlexibleTopAppBar`，标题“酒馆助手”。顶部使用 Material 3 `PrimaryTabRow`：

- `渲染`
- `脚本`

页面记住最后一个标签，但从聊天快捷入口首次进入时，若当前消息含前端块则默认“渲染”，否则默认“脚本”。

手机首屏不展示许可长文、调试日志或低频高级项。风险提示在首次启用脚本、首次启用消息脚本和导入未信任脚本时按需出现。

## 5. 渲染页面

### 5.1 设置项

渲染页包含三个卡组：

1. **消息前端**
   - 启用前端渲染，默认开启。
   - 允许执行前端脚本，默认关闭；开启时展示风险确认。
   - 渲染深度，整数 `0..500`，`0` 表示全部已加载楼层，默认 `0`。
   - 忽略隐藏楼层，默认关闭。
2. **显示与性能**
   - 代码块显示：`全部折叠 / 仅前端源码折叠 / 不折叠`，默认仅前端源码折叠。
   - 流式渲染，默认关闭；开启时展示兼容性警告。
   - 允许网络资源，默认关闭；控制前端 iframe 的 HTTP(S) 子资源和请求。
3. **维护**
   - 重载当前对话前端。
   - 清理全部消息前端缓存。
   - 打开最近渲染错误列表。

Blob URL、取消 highlight.js 和兜底清理不暴露为用户设置。Android 统一使用 `WebViewAssetLoader` 基址；可渲染块在独立沙箱
iframe 内执行；运行时销毁时统一撤销事件、计时器、桥和 WebView。

### 5.2 前端块识别

新增纯逻辑 `TavernFrontendBlockExtractor`，输入原始消息文本，输出有序片段：普通内容、源码块和前端块。以下内容判定为前端块：

- 围栏语言为 `html`、`htm`、`frontend`、`web` 或 `iframe`，内容包含 HTML 元素。
- 无语言围栏，但内容是完整 `<!doctype html>` 或 `<html>...</html>` 文档。
- 普通消息中完整的 `<body>...</body>` 文档段。
- 现有 RikkaHub 兼容路径已经识别的、整体包在单个代码围栏内的完整 HTML 文档。

仅含 `<script>` 而没有可展示根元素的代码块不作为消息前端；它继续显示为源码。Markdown 中零散的安全 HTML 标签不自动升级为
可执行前端。提取器不得把围栏外叙事文本吞进 iframe。

一条消息可以产生多个前端块，每个块使用稳定键
`conversationId + messageId + blockIndex + contentHash`。编辑只重建内容哈希变化的块。

### 5.3 iframe 文档与沙箱

每个前端块由 `TavernFrontendDocumentBuilder` 生成完整文档：

- 注入 UTF-8、viewport、透明背景、Material/ST CSS 变量和基础 reset。
- 注入 `TavernRuntimeScript`、iframe 高度桥、控制台捕获和生命周期事件。
- 完整文档保留原 `<head>` 与 `<body>`；片段文档放进生成的 `<body>`。
- 通过 DOM 解析插入脚本，不使用可能被 `</script>`、引号或模板字符串破坏的文本拼接。
- 使用 `sandbox="allow-scripts allow-forms allow-modals"`；不授予 `allow-same-origin`。
- `window.open`、下载、文件选择、剪贴板和外部导航均经宿主确认或拒绝，不直接脱离 WebView。

前端脚本总开关关闭时，仍渲染 HTML/CSS，但移除原文脚本和内联事件属性；源码可展开查看。

### 5.4 高度、触摸与资源

- 文档在 `DOMContentLoaded`、`load`、字体加载、图片加载、`ResizeObserver` 和显式 `requestHeightUpdate` 后上报高度。
- 高度在 Kotlin 侧限制为 `24dp..12000dp`，异常值被拒绝并记日志。
- iframe 内部需要滚动时使用固定最大高度和内部纵向滚动；其余情况由外层聊天列表滚动。
- 触摸轴沿用已经验证的 Tavern WebView 手势约束，不用整页透明手势层截获点击。
- 网络关闭时，`WebViewClient.shouldInterceptRequest` 拒绝 HTTP(S) 子资源并返回可诊断错误；本地 asset 和 data URL 可用。
- 网络开启时允许 HTTPS；HTTP 默认拒绝。用户可按脚本或当前前端临时放行特定域名。
- 远程图片继续复用现有酒馆资源代理/重试路径，不绕过应用已有缓存与安全策略。

### 5.5 流式渲染

- 默认关闭。
- 开启后以 120ms 节流提取最后生成消息；仅当某个前端块形成可解析根结构时挂载预览。
- 流式内容更新使用 iframe 内 patch；结构不兼容时重建该块，不重建同消息其他稳定块。
- 生成完成后执行一次完整提取和最终重建，最终 DOM 必须与直接完成态渲染一致。
- 用户在流式 iframe 中的交互状态只在可安全 patch 时保留；发生重建时允许丢失，并在设置警告中说明。

## 6. 脚本页面

### 6.1 作用域

脚本页有三个作用域：

- **全局**：所有酒馆会话可用。
- **角色**：绑定到角色卡稳定键；优先使用角色卡 ID，没有 ID 时使用导入来源哈希，不用可变的显示名称作主键。
- **预设**：在 RikkaHub 映射为 Assistant ID，因为 Assistant 承载模型、提示词、请求参数和扩展绑定；显示文案为“助手/预设”。

每个作用域有独立总开关。全局默认开启；新导入的角色和助手/预设作用域默认关闭，首次进入对应上下文时提示用户启用。

### 6.2 列表与编辑

工具栏提供：新增脚本、新增文件夹、导入 JSON、搜索。列表支持：

- 文件夹展开/折叠与文件夹内排序。
- 长按拖动同作用域排序。
- 脚本在三个作用域间移动或复制。
- 脚本级启停、重载、编辑、复制、导出和删除。
- 文件夹级启停、编辑、移动、导出和删除。
- 普通文本和 `/正则/` 搜索；正则无效时在搜索框下显示错误，不让页面崩溃。

脚本编辑器字段：名称、说明、JavaScript 源码、按钮总开关、按钮列表、导出时是否包含数据、导出时是否包含按钮。
源码编辑使用现有代码编辑/高亮能力；超过 64KB 时默认收起预览，但仍可进入全屏编辑。保存前去掉可选的外围 Markdown 代码围栏。

按钮包含名称和可见性。按钮显示在聊天输入框上方现有 Quick Reply 区域；相同脚本内按钮名必须唯一。点击发射该脚本专属按钮事件，
不把按钮文字直接作为用户消息发送。

### 6.3 导入与导出格式

导入接受单个 `.json`，根对象必须能解析为脚本或文件夹。兼容：

- 当前字段：`type/enabled/name/id/content/info/button/data/export_with`。
- 文件夹字段：`type/enabled/name/id/icon/color/scripts`。
- 旧字段：根对象存在 `buttons` 的 `ScriptData`，以及旧 `TavernHelper_scripts` 形状。

缺省字段按上游 4.9.3 默认值补齐。导入时执行完整 schema 校验：类型错误显示 JSON 路径；未知字段保留在
`compatExtras`，再次导出时不丢失。空 ID 或与任一作用域冲突的 ID 生成新 UUID；导入内容默认禁用，用户确认风险后才能启用。

单脚本导出文件名 `酒馆助手脚本-<安全名称>.json`，文件夹导出文件名 `酒馆助手脚本文件夹-<安全名称>.json`。
导出对 `export_with.data=false` 的脚本清空 `data`，对 `export_with.button=false` 的脚本清空按钮列表，并在完成提示中列出实际携带的数据和按钮。

角色卡互操作：导入时读取新版 `data.extensions.tavern_helper`，并迁移旧
`data.extensions.TavernHelper_scripts` 与 `TavernHelper_characterScriptVariables`。导出角色卡时写新版字段；旧字段不再写回。

预设互操作：导入时读取 `extensions.tavern_helper`。RikkaHub 自有 Assistant 导出格式保存相同兼容对象；导出成不支持扩展字段的格式时，
必须提示脚本不会被携带，不能静默丢失。

### 6.4 持久化

使用专用 `TavernScriptRepository`，而不是把大段源码塞进通用 Settings 偏好：

- 元数据、树顺序、作用域、启用状态和版本写入 Room。
- 源码与大于 64KB 的脚本数据写入应用私有文件，Room 保存相对路径、长度和 SHA-256。
- 小脚本数据以 JSON 存储；单脚本数据上限 1MB，单脚本源码上限 2MB，单次导入总上限 16MB。
- 文件写入采用临时文件 + 原子替换；数据库事务只在文件落盘成功后提交引用。
- 删除先标记 tombstone，运行时卸载成功后删除文件；应用启动时清理无引用临时文件。
- 现有备份、WebDAV 和 S3 备份必须包含脚本数据库记录和脚本文件；恢复后校验 SHA-256，损坏项禁用并提示。

## 7. 双运行层架构

### 7.1 浏览器脚本运行层

新增 `TavernBrowserRuntimeCoordinator`，在应用前台维护启用脚本集合。每个脚本对应独立 `TavernBrowserSession`：

- 隐藏 WebView 运行，不依赖酒馆助手管理页是否打开。
- WebView 生命周期位于应用主导航宿主，使用主线程创建和销毁。
- 会话键为 `scope + scopeId + scriptId + sourceHash`；源码变化只重建对应脚本。
- 注入脚本身份、作用域、当前对话/助手/角色快照、按钮与脚本数据 API。
- 发送 `APP_READY`、`CHAT_CHANGED`、`SCRIPT_LOADED`；卸载时发送 `SCRIPT_UNLOADING`，随后取消桥调用、计时器、事件订阅并销毁 WebView。
- 单个脚本崩溃、页面无响应或 WebView renderer 终止只禁用该会话；协调器在用户操作后可单独重载。

浏览器运行层最多同时运行 32 个脚本。超出时按全局、角色、助手/预设顺序和列表顺序选择前 32 个，其余明确显示“超过运行上限”，
而不是悄悄不运行。

### 7.2 QuickJS 运行层

现有 `TavernScriptRunnerService` 和 `TavernScriptRegistry` 继续负责：

- 宏函数执行。
- 注册斜杠命令的回调执行。
- sendHook 文本变换。

浏览器脚本通过 RPC 注册这些回调；宿主将函数源码送入隔离进程。执行超时、异常或断连时保留原文本或返回命令错误，下一次调用获得新工作
上下文。不得为了复用浏览器脚本 WebView 而取消现有隔离与超时恢复语义。

### 7.3 协调和事件所有权

- 浏览器会话拥有 DOM、定时器、模块导入、事件监听和脚本 UI 生命周期。
- QuickJS 注册表拥有宏/命令/sendHook 的可调用副本。
- `TavernHostEventBus` 是宿主事件唯一来源；消息 iframe 和常驻脚本都订阅它。
- `TavernScriptRepository` 是脚本元数据、源码、按钮和脚本数据唯一真源。
- 管理页面只编辑仓库，不直接创建 WebView；协调器观察仓库和当前上下文后做差量同步。

## 8. JavaScript 兼容层

### 8.1 必须真实支持

浏览器脚本和消息前端共享版本化 `window.TavernHelper`、`window.TH`、`window.SillyTavern`、`window.eventSource` 和
`window.event_types`。以下能力为本项目完成门槛：

| 能力组 | 必须提供的行为 |
|---|---|
| 身份与生命周期 | `getIframeName`、`getScriptName`、`getScriptInfo`、`reloadIframe`、加载/卸载事件 |
| 脚本数据与按钮 | `getScriptButtons`、`replaceScriptButtons`、`updateScriptButtonsWith`、`appendInexistentScriptButtons`、`getButtonEvent` |
| 变量 | global/character/preset/chat/message/script 六作用域的 get/set/delete/replace/update；JSON 深拷贝和持久化 |
| 事件 | on/once/emit/remove/makeFirst/makeLast；宿主消息、生成、渲染、聊天切换事件 |
| 上下文 | `SillyTavern.getContext()` 同步快照；当前消息、角色、用户、世界书、变量和生成状态 |
| 消息 | 读取选中分支消息；在权限允许时更新、创建、删除消息并触发对应事件和 UI 刷新 |
| 世界书 | 列表与条目 CRUD，复用现有 Lorebook 数据模型和权限 |
| 生成 | `generate`、`generateRaw`、取消单次/全部生成；走 RikkaHub Provider/Assistant 管线并返回文本或结构化工具调用 |
| 宏与斜杠 | 宏替换、`MacroHelper`、`SlashCommandParser`、`triggerSlash`、sendHook |
| 网络与请求头 | 权限控制的 HTTPS fetch/XHR、`getRequestHeaders`；请求头永不自动注入第三方域名 |
| 角色与助手/预设 | 查询当前/已有对象；写操作必须映射到 RikkaHub 模型并受独立权限控制 |

API 参数和返回值能映射上游格式时保持同名同形；RikkaHub 特有扩展放在 `window.RikkaHubTavern`，不污染兼容函数。

### 8.2 明确不支持

以下调用返回 `UNSUPPORTED_HOST_CAPABILITY`：

- 安装、卸载、更新其他 SillyTavern 扩展。
- 查询 SillyTavern 管理员状态或服务器文件系统。
- 返回/操纵 SillyTavern 顶层页面的 jQuery DOM。
- 依赖 ST 专有后端路由且在 RikkaHub 没有等价数据模型的调用。

兼容层不得把 Android Java/Kotlin 对象、文件路径、Context、WebView 或原始数据库句柄暴露给 JavaScript。

## 9. 权限、信任与隔离

### 9.1 权限模型

保留现有 `allowScripts` 总开关，并扩展细粒度权限：

- 运行消息前端脚本。
- 运行常驻浏览器脚本。
- 网络访问与域名白名单。
- 写变量、写消息、写世界书。
- 发起模型生成。
- 读取请求头。
- 注册宏、斜杠命令和 sendHook。
- 修改角色或助手/预设。

权限由全局默认值和脚本哈希级授权共同决定。脚本源码、作用域或请求权限集合变化后，旧授权失效；用户需重新确认。

### 9.2 首次启用与风险展示

导入脚本默认禁用。首次启用时弹窗显示：脚本名、来源文件、SHA-256、请求权限、是否包含网络地址、是否注册宏/命令、是否操作消息或世界书。
用户可取消、仅运行一次或信任当前版本。消息前端脚本采用对话级确认；角色卡更新导致前端内容哈希变化时重新确认。

### 9.3 桥安全

- 所有 RPC 包含 session token、script ID、conversation ID 和单调 request ID。
- 宿主根据 session 注册信息决定身份，不信任 JavaScript 自报的脚本 ID 或作用域。
- 响应只投递给原会话；WebView 销毁后 token 立即失效。
- 单次 RPC 请求上限 256KB，响应上限 1MB；超限返回结构化错误。
- 导航到非本地顶层 URL 会终止脚本会话；第三方子资源不能获得宿主桥。
- 日志脱敏 API Key、Authorization、Cookie 和自定义请求头值。

## 10. 错误处理与可观察性

每个脚本维护最近 500 条环形日志：时间、级别、生命周期、console 文本、RPC 方法、耗时和脱敏错误。脚本列表显示运行状态：

- 未启用
- 等待权限
- 运行中
- 已暂停
- 加载失败
- 运行时崩溃
- 超过上限

点击状态打开脚本日志页，可复制脱敏日志、清空日志或重载脚本。消息前端错误以可折叠错误卡显示，保留“查看源码”“重载”“临时允许网络”操作。

失败原则：

- 脚本加载失败不影响聊天和其他脚本。
- 消息 iframe 失败时显示原始消息/源码，不显示空白气泡。
- RPC 超时返回 `TIMEOUT`；脚本可捕获 Promise rejection。
- 数据写入失败保持旧版本，绝不留下数据库引用指向不存在文件。
- WebView renderer 被系统终止后协调器清理全部相关句柄；前台时按退避策略最多自动重试一次。

## 11. 数据迁移与兼容

- 新增 Room 迁移只前进，不覆盖现有会话、角色卡、世界书、状态变量、Prompt Trace 或脚本磁盘目录。
- 首次启动扫描现有 `ScriptManager` 的磁盘 slash scripts；它们继续作为宿主斜杠脚本，不自动伪装成浏览器脚本。
- 导入角色卡/预设时解析上游 `tavern_helper` 和旧 `TavernHelper_*` 字段；迁移成功后保留原始未知字段。
- 应用升级后所有新迁移的第三方脚本保持禁用，直到用户确认；应用自带且已验证的现有宏/斜杠功能保持原状态。
- 卸载此功能不可删除用户脚本；仅停止运行时。用户主动“删除全部酒馆助手数据”时需要二次确认并生成可选导出文件。

## 12. 测试策略

### 12.1 JVM 单元与契约测试

- 前端块提取：多块、围栏语言、完整文档、叙事文本、未闭合围栏、脚本-only、恶意闭合标签。
- 文档构建：head/body 保留、DOM 安全插入、主题变量、桥只注入一次、脚本关闭时移除可执行内容。
- 设置校验：深度边界、默认值、流式/网络开关序列化。
- 脚本 schema：新版、旧版、文件夹、未知字段、冲突 ID、大小限制和错误 JSON 路径。
- 仓库事务：原子写、哈希、回滚、tombstone、孤儿清理、备份恢复。
- 作用域解析：全局/角色/助手集合、总开关、上下文切换和 32 脚本上限。
- 权限：脚本哈希变化失效、会话 token、跨脚本伪造、RPC 大小、敏感日志脱敏。
- API 契约：变量六作用域、按钮、事件、上下文、消息、世界书、生成、宏、斜杠和结构化不支持错误。
- QuickJS 回归：超时脚本不污染下一次调用，异常保留原文，sendHook 不泄漏到宏列表。

### 12.2 Android 仪器测试

- 酒馆助手路由、两个标签、设置状态恢复、作用域选择和编辑器。
- 导入文档选择器、导出 SAF、风险确认和权限变更。
- 隐藏 WebView 会话的加载/卸载、按钮注册、console 捕获、renderer 终止恢复。
- 消息多 iframe、自动高度、内部控件点击、图片加载、网络拒绝、编辑/删除/分支切换清理。
- 应用重建后脚本仓库和启用状态恢复；脚本会话重新建立且不重复注册按钮/事件。

### 12.3 Web/JavaScript 契约测试

使用本地 JavaScript fixture 验证：

- 常驻脚本在 `APP_READY` 注册按钮，按钮点击更新 script/chat 变量并修改消息。
- 角色切换卸载旧脚本并加载新脚本，旧计时器不再触发。
- 消息前端读取 `getContext`、订阅变量事件并随变量更新 UI。
- `generate` 经过可控 mock provider 流式返回并可取消。
- 无权限 fetch、写消息、写世界书、读取请求头均得到明确拒绝。
- 死循环宏超时后下一次正常宏仍成功。

### 12.4 全量构建

最终至少运行：

```powershell
./gradlew :app:testDebugUnitTest --rerun-tasks --no-configuration-cache
./gradlew :app:compileDebugKotlin --rerun-tasks --no-configuration-cache
./gradlew :app:assembleDebug --rerun-tasks --no-configuration-cache
./gradlew :app:assembleDebugAndroidTest --rerun-tasks --no-configuration-cache
```

若 `web-ui` 被改动，额外运行 `pnpm test`、`pnpm typecheck`、`pnpm lint` 和 `pnpm build`；本设计优先使用原生 Compose，
不因没有改动 web-ui 而强制制造前端变更。

## 13. 真机端到端验收

目标设备优先使用当前可用 Huawei MNA-AL00 / Android 12 / arm64。验收必须使用本次分支构建的 arm64 Debug APK，并核对
package、versionName、versionCode、Git HEAD 和 APK SHA-256。

### 13.1 管理流程

1. 从“设置 → 扩展 → 酒馆助手”进入，两个标签和中文文案完整。
2. 导入一个当前格式脚本、一个旧格式脚本和一个脚本文件夹。
3. 编辑、搜索、排序、移动、复制、禁用、重新启用并导出；重启应用后数据一致。
4. 风险弹窗显示真实权限；取消不会运行，信任当前版本后运行，修改源码后授权失效。

### 13.2 常驻脚本流程

1. 全局脚本加载一次；切换对话不重复实例化。
2. 角色脚本只在目标角色激活；助手/预设脚本只在目标 Assistant 激活。
3. 脚本注册两个按钮；点击分别更新变量和消息，UI 与持久数据同步。
4. 脚本接收 MESSAGE_SENT、MESSAGE_RECEIVED、MESSAGE_SWIPED 和 CHAT_CHANGED。
5. 脚本重载、停用、删除后旧监听器、计时器、按钮和宏全部消失。
6. 人为触发异常和超时；其他脚本、聊天和下一次 QuickJS 调用正常。

### 13.3 消息前端流程

1. 使用真实消息同时包含叙事文本、两个 HTML 前端块和普通代码块；顺序、源码折叠和两个 iframe 正确。
2. 前端包含按钮、输入框、details、图片和动态高度变化；点击、输入、展开、加载和外层滚动正常。
3. 网络关闭时远程资源显示可诊断失败；按域名允许后资源加载。
4. 前端读取上下文和变量，变量变更后 UI 更新；权限关闭后脚本不执行但 HTML/CSS 仍可预览。
5. 编辑消息、切换分支、删除消息、返回再进入、暗亮主题切换均无残留、重复 iframe 或空白气泡。
6. 打开流式渲染，用 mock provider 输出前端；生成中可见，完成态与直接渲染一致。

### 13.4 稳定性证据

- 连续切换至少 10 次对话，WebView 数量回落到预期基线，无单调增长。
- 前后台切换和进程重建后恢复正常。
- `adb logcat` 无 `FATAL EXCEPTION`、ANR 和重复 renderer crash。
- 保存关键步骤截图、UI hierarchy、运行日志、测试计数、APK 哈希和安装输出。

## 14. 完成定义

只有同时满足以下条件才能宣称完成：

- 第 4 至第 10 节中的页面、渲染、脚本管理、双运行层、API、权限与错误处理均有实际实现。
- 当前和旧版脚本 JSON、角色卡和预设扩展字段能够往返，不发生未提示的数据丢失。
- 单元、仪器、JavaScript 契约、编译和 APK 构建全部通过。
- 第 13 节真机管理、常驻脚本、消息前端和稳定性流程全部执行并留下证据。
- 任何无法映射的 ST 管理 API 都以明确错误记录，不被误报为已支持。
- 最终报告逐项列出完成证据、已知偏差和未执行项；存在未执行的必验项时不得标记目标完成。
