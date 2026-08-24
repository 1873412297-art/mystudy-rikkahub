# 酒馆 Showdown 消息渲染对齐设计

## 背景与问题

华为 MNA-AL00 真机上的“1.赛博机娘同化”开场选择页已经进入
`TavernConversationWebView`，消息数据、宏替换和开场分页也都存在，但正文未达到
SillyTavern 的显示结果：`<customize_HCI>`、`<now_plot>`、`<main_plot>` 等自定义
XML 包装标签被当作文本显示，内部 `details` 和围栏代码块也没有完整成形。

真机 DevTools 证据表明消息使用 `renderMode=markdown`，MarkdownIt 与 DOMPurify 均已加载；
失败发生在 MarkdownIt 解析阶段。MarkdownIt 不把带下划线的自定义标签识别为原始 HTML，
因而先将其转义，后续 DOMPurify 无法恢复结构。真实角色卡没有 `regex_scripts` 或卡片 CSS，
不能依赖卡内显示脚本修复。

SillyTavern release 的消息管线使用 Showdown 2.1，并在 Markdown 转换前执行宏与显示正则，
再对生成的 HTML 进行 DOMPurify 清洗。使用相同版本的 Showdown 对当前真实卡做只读验证时，
两个复杂开场均保留自定义包装结构，并生成 3 个 `details`、4 个代码块和 1 个标题。

## 目标

- 让沉浸式酒馆消息正文使用与 SillyTavern 同族的 Showdown 2.1 解析语义。
- 自定义 XML 包装标签不再以字面量泄漏。
- 混合 Markdown、原始 HTML、`details/summary`、围栏代码块、引用与标题正确成形。
- 保留现有角色名/用户宏、显示正则、DOMPurify、安全策略、主题、状态 HUD、开场分页和宿主动作。
- 所有依赖继续随 APK 本地打包，不引入 CDN 或运行时网络依赖。

## 非目标

- 不把 Markdown 消息改成任意脚本可执行的原始 HTML iframe。
- 不修改角色卡正文、世界书、图片资源或状态变量。
- 不在本轮重写 SillyTavern 的全部扩展生态或桌面 UI。
- 不把真实敏感角色卡全文提交为测试 fixture。

## 方案选择

采用 Showdown 2.1 替换沉浸式消息正文的 MarkdownIt 主解析器。保留 MarkdownIt vendor，供尚未迁移的
普通 Markdown 模板和兼容回退使用；本轮不强制其他页面同步切换，避免把已经工作的非沉浸式路径
与当前真机缺陷耦合。

没有选择“预处理后继续使用 MarkdownIt”，因为该方案还需自行重建未知标签、HTML 块内 Markdown、
`details` 与代码围栏的组合语义。真卡验证已经证明简单剥离包装标签仍只生成 1 个代码块，而 Showdown
可生成完整的 4 个代码块。也不选择把整个开场改成 iframe，因为开场是 Markdown 与 HTML 混合内容，
并且 iframe 会扩大脚本与资源安全面。

## 组件与数据流

### 本地依赖打包

在 `web-ui/package.json` 增加固定版本 `showdown@2.1.0`，并在
`web-ui/scripts/vendor-libs.mjs` 中增加 IIFE 全局产物 `showdown.min.js`。现有
`TavernConversationDocument` 会按稳定文件名顺序内联 vendor，因此无需新增 WebView 文件访问或网络权限。

### 转换器配置

`tavern-conversation.html` 初始化 Showdown converter，配置与 SillyTavern release 对齐：

- `emoji: true`
- `literalMidWordUnderscores: true`
- `parseImgDimensions: true`
- `tables: true`
- `underline: true`
- `simpleLineBreaks: true`
- `strikethrough: true`
- `disableForced4SpacesIndentedSublists: true`

现有 `wrapSillyTavernQuotes` 在转换前继续运行。角色名与用户宏、角色卡显示正则仍在 Kotlin 快照构建前
完成，不在 JavaScript 中创建第二份业务规则。

### HTML 清洗与增强

Showdown 生成 HTML 后继续使用当前 DOMPurify 配置：禁止 `style`、`script`、`iframe`、`object`、
`embed`、`form` 和事件属性，禁止未知协议。未知包装元素若被 DOMPurify 移除，其已清洗的子内容保留；
包装标签本身不得转成可见文本。

清洗后的 DOM 继续执行现有增强：Mermaid、代码高亮与页面尺寸更新。Showdown 不可用时保留当前
MarkdownIt 兼容回退，确保 vendor 载入异常不会把正文变为空白；测试必须证明正式 APK 使用 Showdown。

### 更新与补丁

消息首次渲染和后续 `replace-all`、`replace-part` 更新必须调用同一个 `renderMarkdownPart`，避免开场初始
正确但切换或流式更新后回退到 MarkdownIt。HTML part、状态 part、媒体 part 与运行时 iframe 不改变。

## 安全与异常处理

- Showdown 只负责 Markdown 到 HTML 的转换，输出永远经过 DOMPurify。
- Markdown 路径继续禁止脚本、样式、iframe、表单、内联事件和危险协议。
- Showdown vendor 缺失或初始化失败时使用 MarkdownIt 回退，并保留纯文本最终回退。
- 不把角色卡未知标签加入可执行白名单；未知标签只承担非执行性的内容分组语义。
- APK 内不得出现 `unpkg.com`、`jsdelivr`、`esm.sh` 或其他 renderer CDN。

## 测试策略

### JVM 合同测试

- vendor 集合包含 `showdown.min.js`。
- 模板优先初始化 Showdown，并保留 MarkdownIt 回退。
- Showdown 输出仍进入现有 DOMPurify 禁止项。
- 更新/补丁路径继续复用 `renderMarkdownPart`。
- 文档构建后不包含外部 CDN。

### WebView 仪器测试

新增最小合成消息，不使用真实卡文本：

````text
<customize_HCI>
<now_plot>
<main_plot>
# Opening
</main_plot>
<details><summary>Status</summary>
```body1
hp: 10
```
</details>
</now_plot>
</customize_HCI>
````

在真实 Android WebView 中断言：

- 页面可访问文本不包含 `<customize_HCI>`、`<now_plot>`、`<main_plot>`；
- DOM 存在标题、`details > summary` 和 `pre code`；
- 页面没有脚本执行探针或被禁止节点；
- 文档报告 READY。

测试必须先在现有 MarkdownIt 实现上失败，再实现 Showdown 迁移并转绿。

### 华为真机验收

构建 `app-arm64-v8a-debug.apk`，安装到 `XHD0223523008702`，打开当前“1.赛博机娘同化”
开场选择页并复验至少第 2、3 个复杂开场：

- 包装标签不显示；
- 正文段落、强调、引用和标题正确；
- 3 个折叠区和 4 个代码块在 DOM 中存在；
- 开场页码、左右切换、头像降级和状态 HUD 保持工作；
- 截图与 UI 层级/DevTools DOM 相互印证；
- 当前应用进程日志无 `FATAL` 或 `AndroidRuntime` 崩溃。

## 完成标准

只有 JVM 测试、WebView 仪器测试、Debug 构建、华为安装和真实卡页面证据全部通过，才能认为本轮
“开场白未渲染”修复完成。单纯的模板字符串测试或本地 Showdown 试算不能替代真机验收。
