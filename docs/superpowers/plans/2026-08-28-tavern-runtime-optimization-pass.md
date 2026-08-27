# 酒馆渲染/脚本运行时优化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 清理 AGENTS.md 记录的酒馆渲染/脚本运行时遗留项：流式期间 ~5MB HTML 每 token 重建、宏执行路径回归锁定、死代码与 CDN 本地化收尾、lint 驱动清理。

**Architecture:** 全部改动集中在 app 模块的 richtext 渲染链（`ui/components/richtext/`）与酒馆脚本注册表（`data/ai/slash/`）。流式优化分两层：①模板+vendor 内联产物进程级缓存（对齐 `loadMarkdownPreviewAssets` 已有缓存模式）；②流式期间冻结初始 shell，内容更新全部走既有 `applySegmentPatch` 增量通道。本地化复用 `assets/html/vendor/` 既有 IIFE 库（window 全局变量），不引入新依赖。

**Tech Stack:** Kotlin / Jetpack Compose / WebView / QuickJS / kotlinx.serialization / JUnit4 JVM 单测。

**现状审计（2026-08-28，读码确认，AGENTS.md 部分记录已过时）：**

- ❌ 真实存在：`Markdown.kt:346` 的 `remember(normalizedContent, …)` 导致流式每 token 重建 ~5MB HTML（主线程 assets I/O + 字符串拼接），而 `MarkdownWebView` 流式期间只走 `applySegmentPatch`，成品 HTML 根本不被重载。
- ❌ 真实存在：`StableMessageHtmlRenderer.buildStableMessageHtml(context,…)`（29-62 行）无缓存，每次调用重读模板 + 全部 vendor 文件 + katex 字体 b64；而 `MarkdownWeb.kt:48` 的 `loadMarkdownPreviewAssets` 已有进程级缓存（模式可复用）。
- ✅ 已完成（JS-Slash-Runner 合并后）：宏展开异步化（`expandMacrosAsync`，发送管线 `ChatService.kt:1513` 已在用）、超时可中断（`TavernScriptRunnerService` 独立进程 + 一次性 QuickJS + 超时拆绑停服）、sendHook 独立槽位（不经宏命名空间）、宏名大小写折叠（`macroKey` lowercase）。任务 3 只补回归测试锁定这些语义。
- ❌ 真实存在：`st-message.html:121` `renderMarkdownAll` 桥方法无 Kotlin 调用方（死代码）；`html_viewer.html:98` 仍从 esm.sh  import js-base64；`tavern_card.html:8-9,158-165` 仍依赖 cdn.jsdelivr + esm.sh（katex/hljs CSS、markdown-it/katex/task-lists/mermaid/DOMPurify ES import）；`st-message.html:6` CSP font-src 仍放行 CDN 域名（字体已 b64 内联，白名单可收紧）；`StableSegmentSnapshot.encodePatches` 无序列化契约测试。
- ✅ 已完成：mark.html CDN 本地化、katex 字体 b64 内联（`katex-fonts.b64` + `inlineKatexFontSources`）。

## Global Constraints

- 构建/测试命令必须先设 JDK：`export JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`
- 验证基线：`./gradlew :app:testDebugUnitTest` 全绿（基线 153 类 / 1074 测试）+ `:app:compileDebugKotlin` + `:app:assembleDebug`
- `assets/html/` 运行时路径禁止新增任何 CDN/file:// 依赖；vendor 一律走 `assets/html/vendor/` 内联
- 契约测试断言（`*ContractTest`、`TavernConversationDocumentTest`）被刻意修改时，必须在提交信息中说明理由
- vendor 库暴露的全局变量名（已在 mark/st-message/tavern-conversation 中验证）：`window.MarkdownIt`、`window.MarkdownItTaskLists`、`window.katex`、`window.mermaid`、`window.DOMPurify`、`window.showdown`；katex 插件全局名以 `mark.html:264-266` 用法为准（`vscodeKatex`）
- 每个 Task 完成后单独 commit；DRY、YAGNI、TDD

---

### Task 1: STABLE_DOM 模板与 vendor 内联产物进程级缓存

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/st/BundledVendorAssets.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/st/StableMessageHtmlRenderer.kt:29-62`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWeb.kt:48-83`（`loadMarkdownPreviewAssets` 改为复用共享 loader）
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/st/StableMessageTemplateContractTest.kt`（现有，保持绿）

**Interfaces:**
- Consumes: 现有 `loadBundledKatexFontData(context)` / `inlineKatexFontSources(css, fonts::get)`（`ui/components/richtext/` 包）
- Produces: `internal object BundledVendorAssets`，方法 `fun scripts(context: Context): String` 与 `fun styles(context: Context): String`，进程级 `@Volatile` 缓存 + 双检锁；Task 4c 的 `MarkdownWeb.buildTavernCardPreviewHtml` / `buildCharacterCardViewerHtml` 也消费它

- [x] **Step 1: 新建共享 loader（含缓存）**

```kotlin
// app/src/main/java/me/rerere/rikkahub/ui/components/richtext/st/BundledVendorAssets.kt
package me.rerere.rikkahub.ui.components.richtext.st

import android.content.Context
import me.rerere.rikkahub.ui.components.richtext.inlineKatexFontSources
import me.rerere.rikkahub.ui.components.richtext.loadBundledKatexFontData

/**
 * assets/html/vendor/ 内联产物的进程级缓存。
 * vendor 文件打包进 APK，运行期不变，缓存永不失效。
 * katex.min.css 的字体 url 在首次加载时替换为 b64 内联 data（无 CDN/font:// 依赖）。
 */
internal object BundledVendorAssets {
    @Volatile private var cachedScripts: String? = null
    @Volatile private var cachedStyles: String? = null

    fun scripts(context: Context): String {
        cachedScripts?.let { return it }
        return synchronized(this) {
            cachedScripts ?: context.assets.list("html/vendor")
                .orEmpty()
                .filter { it.endsWith(".js") }
                .sorted()
                .joinToString("\n") { name ->
                    val code = context.assets.open("html/vendor/$name").bufferedReader().use { it.readText() }
                    "<script>$code</script>"
                }
                .also { cachedScripts = it }
        }
    }

    fun styles(context: Context): String {
        cachedStyles?.let { return it }
        return synchronized(this) {
            cachedStyles ?: context.assets.list("html/vendor")
                .orEmpty()
                .filter { it.endsWith(".css") }
                .sorted()
                .joinToString("\n") { name ->
                    val css = context.assets.open("html/vendor/$name").bufferedReader().use { it.readText() }
                    val localizedCss = if (name == "katex.min.css") {
                        val fonts = loadBundledKatexFontData(context)
                        inlineKatexFontSources(css, fonts::get)
                    } else {
                        css
                    }
                    "<style>$localizedCss</style>"
                }
                .also { cachedStyles = it }
        }
    }
}
```

- [x] **Step 2: StableMessageHtmlRenderer 复用 loader + 缓存模板**

`StableMessageHtmlRenderer.kt` 的 context 重载改为：

```kotlin
@Volatile private var cachedTemplate: String? = null

internal fun buildStableMessageHtml(
    context: Context,
    message: StableDomMessage,
    cssVariables: Map<String, String> = emptyMap(),
    extraCss: String? = null,
): String {
    val template = cachedTemplate ?: synchronized(this) {
        cachedTemplate ?: context.assets
            .open("html/st-message.html")
            .bufferedReader()
            .use { it.readText() }
            .also { cachedTemplate = it }
    }
    return buildStableMessageHtml(
        message, template,
        BundledVendorAssets.scripts(context),
        BundledVendorAssets.styles(context),
        cssVariables, extraCss,
    )
}
```

（`@Volatile private var cachedTemplate` 放在文件级，`synchronized(this)` 改为 `synchronized(StableMessageHtmlRendererCache)` 之类的文件级私有锁对象均可；保持纯函数重载签名不变。）

- [x] **Step 3: MarkdownWeb.loadMarkdownPreviewAssets 去重**

把 `MarkdownWeb.kt:48-83` 中 vendor 拼接逻辑替换为 `BundledVendorAssets.scripts(context)` / `.styles(context)`，保留其自身的 `cachedMarkdownPreviewAssets`（模板不同，仍由它缓存 mark.html 模板）。行为不变。

- [x] **Step 4: 跑测试验证**

Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.richtext.st.*"`
Expected: PASS（纯函数重载未动，契约测试不受影响）

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/components/richtext/st/BundledVendorAssets.kt \
        app/src/main/java/me/rerere/rikkahub/ui/components/richtext/st/StableMessageHtmlRenderer.kt \
        app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWeb.kt
git commit -m "perf: cache st-message template and vendor inline bundles process-wide"
```

---

### Task 2: 流式期间冻结 STABLE_DOM shell（消除每 token ~5MB 重建）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt:333-374`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt:296-300`（参数声明）与 `:935`（lastSegments 初始化）
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/MarkdownStableDomKeyTest.kt`（新建）

**Interfaces:**
- Consumes: Task 1 的缓存版 `buildStableMessageHtml`；既有 `StableDomSegment`、`MarkdownWebView(streamSegments=…)`
- Produces: `internal fun stableDomHtmlContentKey(normalizedContent: String, streaming: Boolean): String`；`MarkdownWebView` 新参数 `initialStreamSegments: List<StableDomSegment>? = null`（null 时行为与现状完全一致）

背景：`Markdown.kt:346` 的 remember 键含 `normalizedContent`，流式每 token 触发 `buildStableMessageHtml` 重建 ~5MB 字符串；但 `MarkdownWebView` 流式期间只用 `contentKey` 驱动 `applySegmentPatch`，成品 HTML 不会被重载（`MarkdownWebView.kt:940-979`）。修复 = 流式期间 html 只在流式开始冻结一次（含初始段快照），后续 token 全走段 diff。

关键约束（防踩坑）：`MarkdownWebView.kt:935` 在初次 `loadDataWithBaseURL` 后用当时的 `streamSegments` 初始化 `lastSegments`。若烘焙进 MESSAGE_JSON 的段（冻结快照）与 `lastSegments` 初值不一致，两者的差集永远不会被 patch，会丢文本。因此必须把同一份冻结快照传给 `initialStreamSegments`。

- [x] **Step 1: 写失败测试（键函数语义）**

```kotlin
// app/src/test/java/me/rerere/rikkahub/ui/components/richtext/MarkdownStableDomKeyTest.kt
package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MarkdownStableDomKeyTest {
    @Test
    fun `streaming content key ignores content changes`() {
        assertEquals(stableDomHtmlContentKey("hello", true), stableDomHtmlContentKey("hello world", true))
    }

    @Test
    fun `non streaming content key tracks content`() {
        assertNotEquals(stableDomHtmlContentKey("hello", false), stableDomHtmlContentKey("hello world", false))
    }

    @Test
    fun `streaming flip changes effective remember inputs`() {
        assertNotEquals(stableDomHtmlContentKey("same", true), stableDomHtmlContentKey("same", false))
    }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests "*MarkdownStableDomKeyTest*"`
Expected: FAIL（函数不存在，编译错误）

- [x] **Step 2: 实现键函数 + MarkdownBlock 冻结逻辑**

`Markdown.kt`（文件级，internal）：

```kotlin
/** STABLE_DOM html 重建键的内容分量：流式期间冻结（内容经 applySegmentPatch 增量推送）。 */
internal fun stableDomHtmlContentKey(normalizedContent: String, streaming: Boolean): String =
    if (streaming) "streaming-frozen" else normalizedContent
```

`Markdown.kt:333-359` 改为：

```kotlin
            val stableSegments = remember(normalizedContent) {
                segments.mapIndexed { index, segment ->
                    StableDomSegment(id = "segment-$index", kind = segment.kind, raw = segment.raw)
                }
            }
            // 流式期间冻结初始段快照：烘焙进 MESSAGE_JSON 的段与 MarkdownWebView 的
            // lastSegments 初值必须同源，否则差集段永远不会被 patch（丢文本）。
            val frozenInitialSegments = remember(streaming) { stableSegments }
            // buildStableMessageHtml 读模板 + 内联 ~5MB vendor（Task 1 已进程级缓存）；
            // 流式期间内容分量冻结，token 更新全走 applySegmentPatch；
            // streaming 翻转 / 主题 / 角色 / 卡样式变化仍触发重建（与 baseKey 失效一致）。
            val html = remember(
                stableDomHtmlContentKey(normalizedContent, streaming),
                tavernCardStyle, streaming, cssVariables, roleName, stableRole,
            ) {
                buildStableMessageHtml(
                    context,
                    StableDomMessage(
                        id = if (streaming) frozenInitialSegments.hashCode().toString()
                             else normalizedContent.hashCode().toString(),
                        role = stableRole?.toStableDomRole() ?: StableDomRole.ASSISTANT,
                        name = roleName,
                        segments = if (streaming) frozenInitialSegments else stableSegments,
                        streaming = streaming,
                    ),
                    cssVariables = cssVariables,
                    extraCss = tavernCardStyle?.css,
                )
            }
            MarkdownWebView(
                content = html,
                // …其余参数不变…
                streamSegments = stableSegments,
                initialStreamSegments = if (streaming) frozenInitialSegments else null,
                // …
            )
```

- [x] **Step 3: MarkdownWebView 增加 initialStreamSegments 参数**

参数区（`streaming`/`streamSegments` 声明之后）加：

```kotlin
    /**
     * 流式冻结 shell 的初始段快照（与烘焙进 content 的 MESSAGE_JSON 同源）。
     * null 时退回 streamSegments（非流式 / 旧调用方行为不变）。
     */
    initialStreamSegments: List<StableDomSegment>? = null,
```

`MarkdownWebView.kt:935` 改为：

```kotlin
                    lastSegments.value = initialStreamSegments ?: streamSegments.orEmpty()
```

注意 `update` 块里 contentKey 变化时 `lastContentKey`/`lastSegments` 的既有推进逻辑不动。

- [x] **Step 4: 跑测试验证**

Run: `./gradlew :app:testDebugUnitTest --tests "*MarkdownStableDomKeyTest*" --tests "*richtext*"`
Expected: PASS

- [x] **Step 5: 全量单测 + 编译**

Run: `./gradlew :app:testDebugUnitTest :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL，0 失败

- [x] **Step 6: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt \
        app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt \
        app/src/test/java/me/rerere/rikkahub/ui/components/richtext/MarkdownStableDomKeyTest.kt
git commit -m "perf: freeze stable-dom shell during streaming, patch tokens via segment diff"
```

---

### Task 3: 宏/脚本执行路径回归锁定（合并后语义防回退）

**Files:**
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/slash/TavernScriptRegistryTest.kt`（已存在则追加，不存在则新建）
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/slash/TavernScriptRegistry.kt`（仅注释，见 Step 3）

**Interfaces:**
- Consumes: `TavernScriptRegistry()` 无 Context 构造（JVM 环境自动降级无引擎模式）、`registerMacro/registerSendHook/listMacros/expandMacrosAsync/expandSendHookAsync/registerBatch`
- Produces: 锁定以下语义的回归测试：宏名大小写折叠、sendHook 槽位不进宏列表、无 runner 时异步 API 安全回退

- [x] **Step 1: 写回归测试**

```kotlin
// 追加到 TavernScriptRegistryTest（无 QuickJS 原生库的 JVM 环境：引擎降级，
// 展开类 API 返回原文/null，注册/列表/折叠逻辑仍可断言）
@Test
fun `macro name lookup folds case ST style`() {
    val registry = TavernScriptRegistry()
    assertTrue(registry.registerMacro("MyMacro", "(function(){return 'x'})"))
    assertTrue(registry.hasMacro("mymacro"))
    assertTrue(registry.hasMacro("MYMACRO"))
}

@Test
fun `send hook slot does not leak into macro list`() {
    val registry = TavernScriptRegistry()
    assertTrue(registry.registerSendHook("(function(args){return args})"))
    assertTrue(registry.listMacros().none { it.contains("send_hook", ignoreCase = true) })
    assertTrue(registry.listMacros().none { it.equals("sendHook", ignoreCase = true) })
}

@Test
fun `async expand falls back safely without runner`() = kotlinx.coroutines.test.runTest {
    val registry = TavernScriptRegistry()
    registry.registerMacro("foo", "(function(){return 'bar'})")
    // JVM 无引擎：保留原文（同步降级语义），不抛异常
    val out = registry.expandMacrosAsync("say {{foo}}", MacroExpandContext())
    assertEquals("say {{foo}}", out)
}

@Test
fun `registerBatch folds macro names case insensitively`() {
    val registry = TavernScriptRegistry()
    assertTrue(registry.registerBatch(mapOf("Hello" to "(function(){return 1})"), emptyMap()))
    assertTrue(registry.hasMacro("hello"))
}
```

- [x] **Step 2: 跑测试验证**

Run: `./gradlew :app:testDebugUnitTest --tests "*TavernScriptRegistryTest*"`
Expected: PASS（若 `hasMacro` 对全局宏的 ownerId 语义与断言不符，按 `TavernScriptRegistry.kt:199-204` 的实际语义修正断言，不改生产代码）

- [x] **Step 3: 同步路径补充注释（无行为变更）**

在 `TavernScriptRegistry.kt` 的 `expandMacros` / `callGlobal` / `runOnExecutor` 注释中明确：

```kotlin
/**
 * 同步路径仅供 JVM 测试与无 Context 环境回退；生产发送管线走 expandMacrosAsync
 * （独立 runner 进程，超时拆绑停服可真正中断）。QuickJS evaluate 不响应线程中断，
 * future.cancel(true) 不能终止死循环——新增生产调用方必须使用 Async 变体。
 */
```

- [x] **Step 4: Commit**

```bash
git add app/src/test/java/me/rerere/rikkahub/data/ai/slash/TavernScriptRegistryTest.kt \
        app/src/main/java/me/rerere/rikkahub/data/ai/slash/TavernScriptRegistry.kt
git commit -m "test: lock in macro case-folding, send-hook slot isolation and async fallback semantics"
```

---

### Task 4: 死代码清理与 CDN 本地化收尾

四个子任务，各自独立 commit。每步先跑 `grep -rn "esm.sh\|cdn.jsdelivr\|unpkg\|cdnjs" app/src/test app/src/androidTest --include=*.kt` 确认无测试断言依赖被删内容。

**Files (4a):**
- Modify: `app/src/main/assets/html/st-message.html:121`（删除 `renderMarkdownAll` 方法）
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/st/StableMessageTemplateContractTest.kt:50`

- [x] **Step 1 (4a): 删死代码 + 改断言**

`st-message.html` 的 `RikkahubDomBridge` 对象字面量中删除 `renderMarkdownAll: function(){…}` 整个属性（注意删除后对象字面量逗号仍然合法）。契约测试第 50 行 `assertTrue(template.contains("renderMarkdownAll"))` 改为 `assertFalse(template.contains("renderMarkdownAll"))`。

先确认无调用方：`grep -rn "renderMarkdownAll" app/src/main --include=*.kt --include=*.html` 应只剩 st-message.html 定义本身。

Run: `./gradlew :app:testDebugUnitTest --tests "*StableMessageTemplateContractTest*"` → PASS
Commit: `git commit -m "chore: drop dead renderMarkdownAll bridge method (no callers)"`

**Files (4b):**
- Modify: `app/src/main/assets/html/html_viewer.html:6,98-101`

- [x] **Step 2 (4b): html_viewer 去掉 esm.sh js-base64**

第 98-101 行的 ES module import 改为原生解码（js-base64 只为 `Base64.decode`，零依赖可替代）：

```js
// 原：import { Base64 } from 'https://esm.sh/js-base64@3.7.5'; … let htmlContent = Base64.decode(htmlBase64);
const htmlBin = atob(htmlBase64);
const htmlBytes = Uint8Array.from(htmlBin, (ch) => ch.charCodeAt(0));
let htmlContent = new TextDecoder('utf-8').decode(htmlBytes);
```

若该 `<script type="module">` 中无其他 import，把 `type="module"` 改为普通 `<script>`（保持顶层语法兼容）。第 6 行 CSP 的 `script-src` 与 `connect-src` 删除 `https://esm.sh`。

Run: `grep -c "esm.sh" app/src/main/assets/html/html_viewer.html` → 0
Commit: `git commit -m "fix: decode base64 natively in html_viewer, drop esm.sh dependency"`

**Files (4c):**
- Modify: `app/src/main/assets/html/tavern_card.html`（CSP + 两个 CDN `<link>` + ES module 脚本段）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWeb.kt:138-157,183-210`（两个 builder 注入 vendor）
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/`（若有 tavern_card 相关契约测试，同步更新）

- [x] **Step 3 (4c): tavern_card 本地化**

1. `tavern_card.html` 第 8-9 行的两个 CDN `<link>` 替换为 `{{VENDOR_STYLES}}` 占位符（位置保持在 `<style>` 之前）。
2. 第 158-165 行的 ES import 段整段替换为 `{{VENDOR_LIBS}}` 占位符 + 经典脚本（参考 `mark.html:219-266` 的全局变量用法）：

```html
    {{VENDOR_LIBS}}
    <script>
    (function(){
      const MarkdownIt = window.MarkdownIt;
      const vscodeKatex = window.VscodeMarkdownItKatex || window.vscodeMarkdownItKatex;
      const taskLists = window.MarkdownItTaskLists;
      const katex = window.katex;
      const mermaid = window.mermaid;
      const DOMPurify = window.DOMPurify;
      // 原模块体内全部逻辑原样保留（md 配置 html:true、md.use(vscodeKatex)、
      // md.use(taskLists,…)、mermaid.initialize、render() 内 DOMPurify.sanitize 白名单、
      // mermaid.run、katex.render 循环），仅把 import 的标识符替换为上述 const。
    })();
    </script>
```

注意：先 `head -5 app/src/main/assets/html/vendor/@vscode_markdown-it-katex.min.js` 确认其 UMD 全局名，以实际为准；`mark.html:264-266` 已有现成用法可照抄。
3. 第 6 行 CSP：删除 `https://cdn.jsdelivr.net` / `https://esm.sh` / `https://fonts.gstatic.com` / `https://fonts.googleapis.com` / `https://fontsapi.zeoseven.com`（katex 字体已由 `BundledVendorAssets.styles` b64 内联）。
4. `MarkdownWeb.kt` 两个 builder 在现有 `.replace` 链开头插入：

```kotlin
        .replace("{{VENDOR_LIBS}}", BundledVendorAssets.scripts(context))
        .replace("{{VENDOR_STYLES}}", BundledVendorAssets.styles(context))
```

（import `me.rerere.rikkahub.ui.components.richtext.st.BundledVendorAssets`。）
5. 验证：`grep -n "https://" app/src/main/assets/html/tavern_card.html` 应无 CDN 剩余（注释除外）；`./gradlew :app:testDebugUnitTest --tests "*richtext*"` PASS。

Commit: `git commit -m "fix: localize tavern_card vendor libs and styles, drop CDN dependencies"`

**Files (4d/4e):**
- Modify: `app/src/main/assets/html/st-message.html:6`（CSP font-src 收紧）
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/st/StableMessageTemplateContractTest.kt`（加 CSP 断言）
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/st/StableSegmentSnapshotTest.kt`（已存在则追加 encodePatches 契约测试）

- [x] **Step 4 (4d): st-message CSP 收紧 + 断言**

`st-message.html:6` 的 `font-src 'self' data: https://cdn.jsdelivr.net https://fonts.gstatic.com https://fontsapi.zeoseven.com` 改为 `font-src 'self' data:`。契约测试追加：

```kotlin
@Test
fun `template does not allow remote font or script cdns`() {
    val csp = template.substringAfter("Content-Security-Policy").substringBefore("\">")
    listOf("cdn.jsdelivr", "fonts.gstatic", "fontsapi.zeoseven", "esm.sh", "unpkg", "cdnjs").forEach {
        assertFalse("CSP still allows $it", csp.contains(it))
    }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests "*StableMessageTemplateContractTest*"` → PASS

- [x] **Step 5 (4e): encodePatches 序列化契约测试**

先读 `st-message.html` 中 `applySegmentPatch` 的字段消费代码（`grep -n "applySegmentPatch" -A 20 app/src/main/assets/html/st-message.html`），确认 JS 侧读取的字段名，然后写：

```kotlin
@Test
fun `encodePatches emits the field contract consumed by applySegmentPatch`() {
    val patches = StableSegmentSnapshot.diff(
        old = listOf(StableDomSegment("segment-0", RichTextSegment.Kind.MARKDOWN, "old")),
        new = listOf(
            StableDomSegment("segment-0", RichTextSegment.Kind.MARKDOWN, "new"),
            StableDomSegment("segment-1", RichTextSegment.Kind.FRONTEND_HTML, "<div>x</div>"),
        ),
    )
    val encoded = StableSegmentSnapshot.encodePatches(patches)
    val array = kotlinx.serialization.json.Json.parseToJsonElement(encoded).jsonArray
    assertEquals(2, array.size)
    val first = array[0].jsonObject
    // 字段名必须与 st-message.html applySegmentPatch 的消费端逐字一致（以实际读码为准）
    assertEquals("segment-0", first.getValue("segmentId").jsonPrimitive.content)
    assertEquals("MARKDOWN", first.getValue("kind").jsonPrimitive.content)
    assertEquals("new", first.getValue("raw").jsonPrimitive.content)
}

@Test
fun `diff skips unchanged segments and never emits removals`() {
    val same = listOf(StableDomSegment("segment-0", RichTextSegment.Kind.MARKDOWN, "a"))
    assertTrue(StableSegmentSnapshot.diff(same, same).isEmpty())
    assertTrue(StableSegmentSnapshot.diff(same, emptyList()).isEmpty())
}
```

Run: `./gradlew :app:testDebugUnitTest --tests "*StableSegmentSnapshot*"` → PASS
Commit (4d+4e): `git commit -m "fix: tighten st-message CSP and pin segment patch serialization contract"`

---

### Task 5: lint 驱动清理（有界）

**Files:** 视 lint 报告而定

- [x] **Step 1: 生成报告**

Run: `./gradlew :app:lintDebug`
Expected: BUILD SUCCESSFUL 或分析报告路径 `app/build/reports/lint-results-debug.html`（同时产物 `lint-results-debug.txt` 便于 grep）

- [x] **Step 2: 分诊**

只处理本次改动相关包（`ui/components/richtext/`、`data/ai/slash/`、`service/` 酒馆相关）内的 **Error 级** 与高信号 Warning（UnusedResources 不动、上游合并代码不动、需要行为变更的不动）。逐条在提交信息中记录处理/忽略理由。

- [x] **Step 3: 修复 + 复跑**

`./gradlew :app:lintDebug :app:testDebugUnitTest` 全绿后 commit：
`git commit -m "chore: address lint findings in tavern runtime scope"`

---

### Task 6: 全量验证与收尾

- [x] **Step 1: 全量验证**

Run: `./gradlew :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug`
Expected: BUILD SUCCESSFUL，0 失败（基线 153 类 / 1074+ 测试）

- [x] **Step 2: 装机冒烟**

```bash
ADB="C:/Users/18734/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" -s XHD0223523008702 install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
"$ADB" -s XHD0223523008702 shell monkey -p me.rerere.rikkahub.debug -c android.intent.category.LAUNCHER 1
"$ADB" -s XHD0223523008702 logcat -d | grep -i FATAL   # 期望无输出
```

重点手验：酒馆会话流式生成期间滚动流畅（Task 2 收益）；角色卡预览页（tavern_card 本地化后）markdown/公式/mermaid 渲染正常。

- [x] **Step 3: 更新 AGENTS.md 状态块并推送**

在 AGENTS.md 顶部加 2026-08-28 状态块（本计划各任务结果 + 仍遗留项），`git push origin master`，`private-main` 纯快进对齐。

---

## Self-Review 记录

- 覆盖核对：流式重建（Task 1+2）、宏路径（Task 3，现状审计确认主体已完成、补回归锁定）、死代码/CDN（Task 4a-4e）、lint（Task 5）、验证（Task 6）。AGENTS.md 另列的「WebView 重载后 getContext 重推」「MESSAGE_SENDING 严格同步语义」不在本次四个问题范围内，留作后续。
- 类型一致性：`BundledVendorAssets.scripts/styles` 在 Task 1/4c 签名一致；`initialStreamSegments` 在 Task 2 Step 2（调用方）与 Step 3（声明方）一致；`stableDomHtmlContentKey` 在测试与实现中签名一致。
- 已排除的过时项（读码确认已完成，不进任务）：mark.html CDN 本地化、katex 字体内联、宏异步化主体、sendHook 独立槽、宏名大小写折叠。
