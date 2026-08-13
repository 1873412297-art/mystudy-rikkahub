# Android 酒馆渲染链路提升 Implementation Plan（子项目 B1）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 提升 Android 端酒馆渲染链路：ST 主题开箱可用（重建 st-message DOM）、Material/角色卡 CSS 主题通道、流式增量文本更新、WebView 生命周期治理、前端库本地化。

**Architecture:** 前端库经 esbuild 打包为 IIFE 全局产物（assets/html/vendor/）并在构建期由 `StableMessageHtmlRenderer` 内联进 st-message 模板（无 file:// / CDN 依赖）；st-message.html DOM 重建为 ST 默认形状（`.mes_text > p` 分段 + `.name_text`）+ CSS 变量默认主题；`MarkdownWebView` 增加 streaming 增量通道（`RikkahubDomBridge.applySegmentPatch` + Kotlin 段快照 diff）与销毁治理；`TavernCardStyleResolver` 统一卡 CSS 解析并注入消息气泡。

**Tech Stack:** Kotlin/Compose、Android WebView、markdown-it/DOMPurify/hljs/katex/mermaid（esbuild IIFE 打包）、esbuild（web-ui 脚本）、JUnit（TDD）。

**Spec:** `docs/superpowers/specs/2026-08-13-android-renderer-upgrade-design.md`

## Global Constraints

- 工作区：`C:\Users\18734\Desktop\HTML\rikkahub-source`（rikkahub，1 个 h）；web-ui 子目录 `web-ui/`（esbuild 打包脚本放这里）
- Android 验证：`.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug`；web-ui：`pnpm test/typecheck/lint/build`（本计划不碰 web-ui app 代码，只加打包脚本）
- Kotlin 4 空格、TS 2 空格、行宽 120
- **工作区有大量与本计划无关的未提交改动（2026-08-08 WIP）。绝对不要 `git add .`——每次 commit 只 add 任务文件**
- 安全不变式：状态块/JSON Patch 段只转义展示；DOMPurify 清洗 markdown 输出；卡 CSS 经 `sanitizeCss`（`</` → `/* */ `）后内联；RikkahubDomBridge 只接受纯文本 patch（esc 后 textContent 替换）
- 每任务 commit；commit message 遵循 repo 风格（feat:/fix:/docs:/chore:）

---

### Task 1: 前端库本地化（vendor 打包 + 模板内联机制）

**Files:**
- Create: `web-ui/scripts/vendor-libs.mjs`（esbuild IIFE 打包脚本）
- Modify: `web-ui/package.json`（devDependencies + `vendor:libs` script）
- Create: `app/src/main/assets/html/vendor/`（打包产物，7 个 .js + 2 个 .css）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/st/StableMessageHtmlRenderer.kt`
- Modify: `app/src/main/assets/html/st-message.html`（本任务只改库加载方式，DOM 重建留给 Task 2）
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/st/StableMessageTemplateContractTest.kt`（更新）

**Interfaces:**
- Produces: `buildStableMessageHtml(message, template, vendorScripts, vendorStyles)` 纯函数版本（Task 2/3 扩展）；assets vendor 文件（Task 2 模板消费）

- [ ] **Step 1: 安装打包依赖**

Run（workdir `web-ui`）: `pnpm add -D esbuild markdown-it@14.0.0 dompurify@3.1.7 highlight.js@11.9.0 markdown-it-task-lists@2.1.1 katex@0.16.8 @vscode/markdown-it-katex mermaid@11`
Expected: devDependencies 出现上述包

- [ ] **Step 2: 写打包脚本**

```js
// web-ui/scripts/vendor-libs.mjs
import { build } from "esbuild";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const OUT = path.join(root, "..", "app", "src", "main", "assets", "html", "vendor");

const libs = [
  { entry: "markdown-it", global: "MarkdownIt" },
  { entry: "dompurify", global: "DOMPurify" },
  { entry: "highlight.js", global: "hljs" },
  { entry: "markdown-it-task-lists", global: "MarkdownItTaskLists" },
  { entry: "katex", global: "katex" },
  { entry: "@vscode/markdown-it-katex", global: "vscodeKatex" },
  { entry: "mermaid", global: "mermaid" },
];

fs.mkdirSync(OUT, { recursive: true });

for (const lib of libs) {
  const outfile = path.join(OUT, `${lib.entry.replace("/", "_")}.min.js`);
  await build({
    entryPoints: [lib.entry],
    bundle: true,
    minify: true,
    format: "iife",
    globalName: lib.global,
    target: "es2018",
    outfile,
    logLevel: "silent",
  });
  console.log(`built ${outfile} (${(fs.statSync(outfile).size / 1024).toFixed(0)} KB)`);
}

const copyFiles = [
  { from: "katex/dist/katex.min.css", to: "katex.min.css" },
  { from: "highlight.js/styles/atom-one-dark.min.css", to: "atom-one-dark.min.css" },
];
for (const { from, to } of copyFiles) {
  const src = path.join(root, "node_modules", from);
  fs.copyFileSync(src, path.join(OUT, to));
  console.log(`copied ${to} (${(fs.statSync(path.join(OUT, to)).size / 1024).toFixed(0)} KB)`);
}
```

`web-ui/package.json` scripts 加：`"vendor:libs": "node scripts/vendor-libs.mjs"`

- [ ] **Step 3: 运行打包**

Run（workdir `web-ui`）: `pnpm vendor:libs`
Expected: 9 个文件出现在 `app/src/main/assets/html/vendor/`（markdown-it.min.js / dompurify.min.js / highlight.js.min.js / markdown-it-task-lists.min.js / katex.min.js / _vscode_markdown-it-katex.min.js / mermaid.min.js / katex.min.css / atom-one-dark.min.css）
注意：`@vscode/markdown-it-katex` 的 entry 名含 `/`，outfile 名会是 `_vscode_markdown-it-katex.min.js`——记录实际文件名，后续引用以实际为准。

- [ ] **Step 4: 改造 `StableMessageHtmlRenderer` 支持内联**

```kotlin
package me.rerere.rikkahub.ui.components.richtext.st

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json {
    encodeDefaults = true
}

private const val MESSAGE_JSON_PLACEHOLDER = "{{MESSAGE_JSON}}"
private const val VENDOR_LIBS_PLACEHOLDER = "{{VENDOR_LIBS}}"
private const val VENDOR_STYLES_PLACEHOLDER = "{{VENDOR_STYLES}}"

/**
 * 从 assets 读取 st-message.html 模板，把本地 vendor 库（assets/html/vendor/）内联为
 * <script>/<style> 块后注入消息 JSON。运行时无 CDN/file:// 依赖。
 */
internal fun buildStableMessageHtml(context: Context, message: StableDomMessage): String {
    val template = context.assets
        .open("html/st-message.html")
        .bufferedReader()
        .use { it.readText() }
    val vendorScripts = context.assets.list("html/vendor")
        .orEmpty()
        .filter { it.endsWith(".js") }
        .sorted()
        .joinToString("\n") { name ->
            val code = context.assets.open("html/vendor/$name").bufferedReader().use { it.readText() }
            "<script>$code</script>"
        }
    val vendorStyles = context.assets.list("html/vendor")
        .orEmpty()
        .filter { it.endsWith(".css") }
        .sorted()
        .joinToString("\n") { name ->
            val css = context.assets.open("html/vendor/$name").bufferedReader().use { it.readText() }
            "<style>$css</style>"
        }
    return buildStableMessageHtml(message, template, vendorScripts, vendorStyles)
}

/** 纯函数版本：给定模板与内联产物注入（JVM 测试用）。 */
internal fun buildStableMessageHtml(
    message: StableDomMessage,
    template: String,
    vendorScripts: String = "",
    vendorStyles: String = "",
): String {
    val messageJson = json.encodeToString(message).replace("</script>", "<\\/script>")
    return template
        .replace(VENDOR_LIBS_PLACEHOLDER, vendorScripts)
        .replace(VENDOR_STYLES_PLACEHOLDER, vendorStyles)
        .replace(MESSAGE_JSON_PLACEHOLDER, messageJson)
}
```

- [ ] **Step 5: 改 st-message.html 库加载为内联占位符（DOM 暂不变）**

修改 `app/src/main/assets/html/st-message.html`：

1. `<head>` 中删除两行 CDN `<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex...">` 与 highlight.js 的 CDN link，替换为：
   `{{VENDOR_STYLES}}`
2. `<head>` 末尾（`<style>` 块前）加：`{{VENDOR_LIBS}}`
3. CSP meta 改为：
   ```html
   <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; img-src 'self' data: blob: https:; font-src 'self' data: https://cdn.jsdelivr.net https://fonts.gstatic.com https://fontsapi.zeoseven.com; connect-src 'self'; frame-src 'self' data: blob:; child-src 'self' data: blob:;">
   ```
   （font-src 保留 CDN 供 katex 字体；script/connect 全部本地化）
4. `(async function(){...})()` 里 6 个 `import('https://esm.sh/...')` 替换为从全局取：
   ```js
   var libs = {};
   var loadErrors = [];
   try { libs.MarkdownIt = window.MarkdownIt; if (!libs.MarkdownIt) throw 0; } catch(e){ loadErrors.push('markdown-it'); }
   try { libs.DOMPurify = window.DOMPurify; if (!libs.DOMPurify) throw 0; } catch(e){ loadErrors.push('dompurify'); }
   try { libs.hljs = window.hljs; if (!libs.hljs) throw 0; } catch(e){ loadErrors.push('highlight.js'); }
   try { libs.taskLists = window.MarkdownItTaskLists; if (!libs.taskLists) throw 0; } catch(e){ loadErrors.push('task-lists'); }
   try { libs.katex = window.katex; if (!libs.katex) throw 0; } catch(e){ loadErrors.push('katex'); }
   try { libs.mermaid = window.mermaid; if (!libs.mermaid) throw 0; } catch(e){ loadErrors.push('mermaid'); }
   ```
   并把 katex 插件行改为 `var vscodeKatex = window.vscodeKatex;`（try/catch 包裹保留）。

- [ ] **Step 6: 更新契约测试**

`StableMessageTemplateContractTest.kt` 重写：

```kotlin
package me.rerere.rikkahub.ui.components.richtext.st

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 防止 st-message.html 模板回退为纯文本/CDN 渲染的契约测试。
 * JVM 测试直接读仓库源文件（assets 仅在 Android 运行时可用）。
 */
class StableMessageTemplateContractTest {

    private val template: String by lazy {
        val candidates = listOf(
            File("src/main/assets/html/st-message.html"),
            File("app/src/main/assets/html/st-message.html"),
        )
        candidates.firstOrNull { it.exists() }?.readText()
            ?: error("st-message.html template not found in test working dir")
    }

    @Test
    fun templateUsesInlineVendorPlaceholdersNotCdn() {
        assertTrue(template.contains("{{VENDOR_LIBS}}"))
        assertTrue(template.contains("{{VENDOR_STYLES}}"))
        assertFalse(template.contains("esm.sh"))
        assertTrue(template.contains("window.MarkdownIt"))
        assertTrue(template.contains("window.DOMPurify"))
    }

    @Test
    fun templateRendersMarkdownSegmentsThroughMarkdownItAndDomPurify() {
        assertTrue(template.contains("DOMPurify.sanitize"))
        assertTrue(template.contains("md.render"))
    }

    @Test
    fun templateKeepsStableDomShapeForSTCompat() {
        assertTrue(template.contains("mes_block"))
        assertTrue(template.contains("mes_text"))
        assertTrue(template.contains("dataset.segmentId"))
        assertTrue(template.contains("ch_name"))
        assertTrue(template.contains("dataset.messageId"))
    }

    @Test
    fun templateFallsBackToEscapedPlainTextWhenLibsUnavailable() {
        assertTrue(template.contains("esc(segment.raw)"))
        assertTrue(template.contains("renderPlain"))
    }

    @Test
    fun rendererInlinesVendorScriptsIntoPlaceholder() {
        val message = StableDomMessage(
            id = "m1",
            role = StableDomRole.ASSISTANT,
            segments = emptyList(),
            streaming = false,
        )
        val html = buildStableMessageHtml(message, template, vendorScripts = "<script>fake-lib.js</script>")
        assertTrue(html.contains("fake-lib.js"))
        assertFalse(html.contains("{{VENDOR_LIBS}}"))
    }
}
```

- [ ] **Step 7: 验证**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.richtext.st.StableMessageTemplateContractTest" :app:compileDebugKotlin`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add web-ui/package.json web-ui/pnpm-lock.yaml web-ui/scripts/vendor-libs.mjs app/src/main/assets/html/vendor/ app/src/main/assets/html/st-message.html app/src/main/java/me/rerere/rikkahub/ui/components/richtext/st/StableMessageHtmlRenderer.kt app/src/test/java/me/rerere/rikkahub/ui/components/richtext/st/StableMessageTemplateContractTest.kt
git commit -m "feat: bundle markdown/katex/mermaid libs locally and inline into st-message template"
```

---

### Task 2: st-message.html DOM 重建（ST 形状 + CSS 变量默认主题）

**Files:**
- Modify: `app/src/main/assets/html/st-message.html`（重写 DOM 结构与样式）
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/st/StableMessageTemplateContractTest.kt`（契约更新）
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/st/StableMessageHtmlRendererTest.kt`（如有占位符断言则同步）

**Interfaces:**
- Produces: 模板占位符 `{{CSS_VARIABLES}}`、`{{EXTRA_CSS}}`（Task 3 消费）；`RikkahubDomBridge` JS 接口（Task 4 补充 JS 侧实现，本任务只埋接口名）

- [ ] **Step 1: 重写 st-message.html（完整文件）**

```html
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width,initial-scale=1.0">
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; img-src 'self' data: blob: https:; font-src 'self' data: https://cdn.jsdelivr.net https://fonts.gstatic.com https://fontsapi.zeoseven.com; connect-src 'self'; frame-src 'self' data: blob:; child-src 'self' data: blob:;">
  {{VENDOR_STYLES}}
  {{VENDOR_LIBS}}
  <style>
    :root {
      --rikkahub-bg: {{CSS_VAR_BG}};
      --rikkahub-surface: {{CSS_VAR_SURFACE}};
      --rikkahub-surface-variant: {{CSS_VAR_SURFACE_VARIANT}};
      --rikkahub-text: {{CSS_VAR_TEXT}};
      --rikkahub-text-secondary: {{CSS_VAR_TEXT_SECONDARY}};
      --rikkahub-border: {{CSS_VAR_BORDER}};
      --rikkahub-accent: {{CSS_VAR_ACCENT}};
    }
    html, body { margin: 0; padding: 0; background: transparent; color: var(--rikkahub-text); font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', sans-serif; }
    #chat { width: 100%; }
    /* ── ST 默认形状：接近 SillyTavern 原生 DOM，主流主题选择器可用 ── */
    .mes { display: flex; flex-direction: column; border-radius: 12px; padding: 10px 12px; line-height: 1.55; word-break: break-word; }
    .mes.assistant { background: var(--rikkahub-surface); }
    .mes.user { background: var(--rikkahub-surface-variant); }
    .mes_block { display: flex; flex-direction: column; min-width: 0; }
    .name_text { margin-bottom: 4px; }
    .name_text .name, .name_text .ch_name { font-weight: 600; font-size: 13px; opacity: .78; color: var(--rikkahub-text-secondary); }
    .mes_text { display: flex; flex-direction: column; gap: 6px; }
    .mes_text > p { margin: 0 0 8px; }
    .mes_text > p:last-child { margin-bottom: 0; }
    .mes_text h1, .mes_text h2, .mes_text h3, .mes_text h4, .mes_text h5, .mes_text h6 { margin: 12px 0 8px; font-weight: 600; line-height: 1.3; }
    .mes_text h1 { font-size: 1.5em; }
    .mes_text h2 { font-size: 1.3em; border-bottom: 1px solid var(--rikkahub-border); padding-bottom: 4px; }
    .mes_text h3 { font-size: 1.15em; }
    .mes_text ul, .mes_text ol { margin: 0 0 10px; padding-left: 22px; }
    .mes_text li { margin: 2px 0; }
    .mes_text a { color: var(--rikkahub-accent); }
    .mes_text code { background: var(--rikkahub-surface-variant); border-radius: 4px; padding: .15em .35em; font-family: 'SF Mono', Monaco, Consolas, monospace; font-size: .88em; }
    .mes_text pre { background: rgba(20,20,20,.75); border-radius: 8px; padding: 10px 12px; overflow: auto; margin: 0 0 10px; }
    .mes_text pre code { background: transparent; padding: 0; font-size: .86em; }
    .mes_text blockquote { margin: 0 0 10px; padding: 2px 12px; border-left: 3px solid var(--rikkahub-border); opacity: .85; }
    .mes_text table { border-collapse: collapse; margin: 0 0 10px; width: auto; max-width: 100%; }
    .mes_text th, .mes_text td { border: 1px solid var(--rikkahub-border); padding: 4px 8px; font-size: .92em; }
    .mes_text th { background: var(--rikkahub-surface-variant); font-weight: 600; }
    .mes_text img { max-width: 100%; height: auto; border-radius: 8px; }
    .mes_text hr { border: none; border-top: 1px solid var(--rikkahub-border); margin: 12px 0; }
    .mes_text input[type="checkbox"] { margin-right: 4px; vertical-align: middle; }
    .mes_text .task-list-item { list-style-type: none; margin-left: -22px; }
    /* 状态块/JSON Patch 段：转义 <pre> 展示 */
    .mes_text .status_block, .mes_text .json_patch {
      border: 1px solid var(--rikkahub-border);
      border-radius: 10px;
      padding: 8px;
      overflow: auto;
    }
    .mes_text .status_block > pre, .mes_text .json_patch > pre { white-space: pre-wrap; margin: 0; background: transparent; font-family: 'SF Mono', Monaco, Consolas, 'Roboto Mono', monospace; font-size: 13px; }
    /* ST 兼容：mes_buttons 占位（视觉由 Compose 层控制） */
    .mes_buttons, .mes_edit_buttons { display: none; }
    {{EXTRA_CSS}}
  </style>
</head>
<body>
  <div id="chat"></div>
  <script>
    window.__RIKKAHUB_ST_MESSAGE__ = {{MESSAGE_JSON}};
    (function(){
      var message = window.__RIKKAHUB_ST_MESSAGE__;
      var root = document.getElementById('chat');
      var pendingMarkdown = [];
      var mesTextEl = null;
      var libs = {};

      function esc(text){
        return String(text).replace(/[&<>"']/g, function(ch){
          return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'})[ch];
        });
      }
      function report(){
        try {
          var h = Math.max(document.body.scrollHeight, document.documentElement.scrollHeight);
          window.RikkahubBridge && window.RikkahubBridge.reportHeight(Math.ceil(h * (window.devicePixelRatio || 1)));
        } catch(e){}
      }
      function renderPlain(segment, el){
        el.innerHTML = esc(segment.raw).replace(/\n/g, '<br>');
      }
      function renderPre(segment, el){
        el.innerHTML = '<pre>' + esc(segment.raw) + '</pre>';
      }

      // ── RikkahubDomBridge：宿主增量更新接口（纯文本，esc 后 textContent 替换，无 XSS） ──
      window.RikkahubDomBridge = {
        applySegmentPatch: function(patchJson){
          try {
            var patches = JSON.parse(patchJson);
            (patches || []).forEach(function(p){
              var container = mesTextEl || root;
              var el = container.querySelector('[data-segment-id="' + p.segmentId + '"]');
              if (!el) {
                el = document.createElement(p.kind === 'MARKDOWN' ? 'p' : 'div');
                el.dataset.segmentId = p.segmentId;
                el.dataset.kind = p.kind;
                if (p.kind !== 'MARKDOWN') { el.className = p.kind === 'STATUS_BLOCK' ? 'status_block' : 'json_patch'; }
                container.appendChild(el);
              }
              if (p.kind === 'MARKDOWN') {
                el.textContent = p.raw;
                el.dataset.rendered = 'plain';
                var item = null;
                for (var i = 0; i < pendingMarkdown.length; i++) {
                  if (pendingMarkdown[i].el === el) { item = pendingMarkdown[i]; break; }
                }
                if (item) { item.raw = p.raw; } else { pendingMarkdown.push({ el: el, raw: p.raw }); }
              } else {
                el.innerHTML = '<pre>' + esc(p.raw) + '</pre>';
              }
            });
            report();
          } catch(e){}
        },
        renderMarkdownAll: function(){
          renderPendingMarkdown();
          report();
        }
      };

      function buildDom(){
        var mes = document.createElement('div');
        mes.className = 'mes ' + String(message.role || 'assistant').toLowerCase();
        mes.dataset.messageId = message.id;
        mes.dataset.rikkahubRole = String(message.role || 'assistant').toLowerCase();
        var block = document.createElement('div');
        block.className = 'mes_block';
        if (message.name) {
          var nameText = document.createElement('div');
          nameText.className = 'name_text';
          var nameEl = document.createElement('span');
          nameEl.className = 'name ch_name';
          nameEl.textContent = message.name;
          nameText.appendChild(nameEl);
          block.appendChild(nameText);
        }
        var text = document.createElement('div');
        text.className = 'mes_text';
        mesTextEl = text;
        (message.segments || []).forEach(function(segment){
          if (segment.kind === 'MARKDOWN') {
            var p = document.createElement('p');
            p.dataset.kind = segment.kind;
            if (segment.id) { p.dataset.segmentId = segment.id; }
            renderPlain(segment, p);
            text.appendChild(p);
            pendingMarkdown.push({ el: p, raw: segment.raw });
          } else {
            var div = document.createElement('div');
            div.dataset.kind = segment.kind;
            if (segment.id) { div.dataset.segmentId = segment.id; }
            div.className = segment.kind === 'STATUS_BLOCK' ? 'status_block' : 'json_patch';
            renderPre(segment, div);
            text.appendChild(div);
          }
        });
        block.appendChild(text);
        mes.appendChild(block);
        root.appendChild(mes);
        report();
      }

      function renderPendingMarkdown(){
        if (!libs.MarkdownIt || !libs.DOMPurify) { return; }
        var md = buildMarkdownRenderer();
        pendingMarkdown.forEach(function(item){
          if (item.el.dataset.rendered === 'rich') return;
          var rawHtml = md.render(item.raw);
          var cleanHtml = libs.DOMPurify.sanitize(rawHtml, {
            USE_PROFILES: { html: true, svg: false, mathMl: false },
            ADD_TAGS: ['details', 'summary', 'font', 'mark', 'kbd'],
            ADD_ATTR: ['open', 'color', 'face', 'size', 'target', 'class'],
            FORBID_TAGS: ['script', 'iframe', 'object', 'embed', 'frame', 'frameset', 'noframes', 'meta', 'base', 'link', 'form', 'input', 'button', 'textarea', 'select', 'option'],
            FORBID_ATTR: ['onerror', 'onload', 'onclick', 'onmouseover', 'onmouseout', 'onfocus', 'onblur', 'onchange', 'onsubmit', 'formaction', 'srcdoc'],
            ALLOW_DATA_ATTR: false,
            ALLOW_UNKNOWN_PROTOCOLS: false
          });
          item.el.innerHTML = cleanHtml;
          item.el.dataset.rendered = 'rich';
        });
        if (libs.mermaid) {
          var mermaidNodes = root.querySelectorAll('.mermaid');
          if (mermaidNodes.length > 0) {
            try { libs.mermaid.run({ nodes: mermaidNodes }); } catch(e){}
          }
        }
        if (libs.katex) {
          root.querySelectorAll('.math.inline').forEach(function(el){
            try { libs.katex.render(el.textContent, el, { throwOnError: false, errorColor: '#cc0000' }); } catch(e){}
          });
          root.querySelectorAll('.math.block').forEach(function(el){
            try { libs.katex.render(el.textContent, el, { displayMode: true, throwOnError: false, errorColor: '#cc0000' }); } catch(e){}
          });
        }
        root.querySelectorAll('a[href^="http"]').forEach(function(link){
          link.target = '_blank';
          link.rel = 'noopener noreferrer';
        });
      }

      function buildMarkdownRenderer(){
        var md = new libs.MarkdownIt({
          html: true,
          xhtmlOut: false,
          breaks: true,
          langPrefix: 'language-',
          linkify: true,
          typographer: true,
          quotes: '\u201C\u201D\u2018\u2019',
          highlight: function(str, lang){
            if (lang && libs.hljs && libs.hljs.getLanguage(lang)) {
              try {
                return '<pre class="hljs"><code>' +
                  libs.hljs.highlight(str, { language: lang, ignoreIllegals: true }).value +
                  '</code></pre>';
              } catch(e){}
            }
            return '<pre class="hljs"><code>' + md.utils.escapeHtml(str) + '</code></pre>';
          }
        });
        if (libs.taskLists) { md.use(libs.taskLists, { enabled: true, label: true, labelAfter: true }); }
        if (libs.katex && window.vscodeKatex) {
          try { md.use(window.vscodeKatex, { katex: libs.katex }); } catch(e){}
        }
        var defaultFenceRenderer = md.renderer.rules.fence || function(tokens, idx, options, env, renderer){
          return renderer.renderToken(tokens, idx, options);
        };
        md.renderer.rules.fence = function(tokens, idx, options, env, renderer){
          var token = tokens[idx];
          var info = token.info ? md.utils.unescapeAll(token.info).trim() : '';
          if (info === 'mermaid') {
            return '<div class="mermaid" id="m-' + Math.random().toString(36).substr(2, 9) + '">' + token.content.trim() + '</div>';
          }
          return defaultFenceRenderer(tokens, idx, options, env, renderer);
        };
        return md;
      }

      // 库从内联全局取（Task 1 已本地化）
      try { libs.MarkdownIt = window.MarkdownIt; } catch(e){}
      try { libs.DOMPurify = window.DOMPurify; } catch(e){}
      try { libs.hljs = window.hljs; } catch(e){}
      try { libs.taskLists = window.MarkdownItTaskLists; } catch(e){}
      try { libs.katex = window.katex; } catch(e){}
      try { libs.mermaid = window.mermaid; } catch(e){}
      if (libs.mermaid) {
        try {
          libs.mermaid.initialize({
            startOnLoad: false,
            theme: (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) ? 'dark' : 'default',
            securityLevel: 'strict',
            fontFamily: 'inherit'
          });
        } catch(e){}
      }

      buildDom();
      if (libs.MarkdownIt && libs.DOMPurify) {
        try { renderPendingMarkdown(); } catch(e){}
      }
      report();
      window.addEventListener('load', report);
      setTimeout(report, 100);
      setTimeout(report, 400);
    })();
  </script>
</body>
</html>
```

**注意：** 模板引用 `{{CSS_VAR_*}}` 与 `{{EXTRA_CSS}}` 占位符；`StableMessageHtmlRenderer` 需同步替换它们——本任务先加「默认值替换」（CSS 变量默认值为 `transparent/inherit` 等中性值，EXTRA_CSS 为空串），Task 3 再接入真实色值与卡 CSS。`buildStableMessageHtml` 纯函数加：

```kotlin
    return template
        .replace(VENDOR_LIBS_PLACEHOLDER, vendorScripts)
        .replace(VENDOR_STYLES_PLACEHOLDER, vendorStyles)
        .replace("{{CSS_VAR_BG}}", "transparent")
        .replace("{{CSS_VAR_SURFACE}}", "rgba(127,127,127,.08)")
        .replace("{{CSS_VAR_SURFACE_VARIANT}}", "rgba(80,120,255,.10)")
        .replace("{{CSS_VAR_TEXT}}", "inherit")
        .replace("{{CSS_VAR_TEXT_SECONDARY}}", "inherit")
        .replace("{{CSS_VAR_BORDER}}", "rgba(127,127,127,.25)")
        .replace("{{CSS_VAR_ACCENT}}", "#4a90d9")
        .replace("{{EXTRA_CSS}}", "")
        .replace(MESSAGE_JSON_PLACEHOLDER, messageJson)
```

- [ ] **Step 2: 更新契约测试断言新 DOM**

`StableMessageTemplateContractTest.kt` 的 `templateKeepsStableDomShapeForSTCompat` 改为：

```kotlin
    @Test
    fun templateKeepsStableDomShapeForSTCompat() {
        assertTrue(template.contains("mes_block"))
        assertTrue(template.contains("mes_text"))
        assertTrue(template.contains("dataset.segmentId"))
        assertTrue(template.contains("'name ch_name'"))
        assertTrue(template.contains("dataset.messageId"))
        assertTrue(template.contains("{{CSS_VAR_BG}}"))
        assertTrue(template.contains("{{EXTRA_CSS}}"))
        assertTrue(template.contains("RikkahubDomBridge"))
        assertTrue(template.contains("applySegmentPatch"))
        assertTrue(template.contains("renderMarkdownAll"))
        assertFalse(template.contains("mes_segment"))
        assertFalse(template.contains("mes_header"))
    }
```

- [ ] **Step 3: 验证**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.richtext.st.*" :app:compileDebugKotlin`
Expected: PASS（`StableMessageHtmlRendererTest` 如断言旧占位符则同步修改——以实际测试内容为准，保持行为断言不变）

- [ ] **Step 4: Commit**

```bash
git add app/src/main/assets/html/st-message.html app/src/main/java/me/rerere/rikkahub/ui/components/richtext/st/StableMessageHtmlRenderer.kt app/src/test/java/me/rerere/rikkahub/ui/components/richtext/st/StableMessageTemplateContractTest.kt
git commit -m "feat: rebuild st-message DOM to ST shape with css-variable default theme"
```

---

### Task 3: 主题与角色卡 CSS 通道

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/ai/status/TavernCardStyleResolver.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/data/ai/status/CssSanitizer.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/status/StatusRenderer.kt:132-133`（复用 CssSanitizer）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/st/StableMessageHtmlRenderer.kt`（cssVariables/extraCss 参数）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt`（MarkdownBlock 加参数，传递卡样式 + Material 色）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt`（STABLE_DOM 路径注入 CSS 变量与 extraCss；renderKey 加卡样式键）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt`（三处 MarkdownBlock 传卡样式）
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/status/TavernCardStyleResolverTest.kt`、`CssSanitizerTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/st/StableMessageHtmlRendererTest.kt`（cssVariables/extraCss 注入断言）

**Interfaces:**
- Produces: `data class TavernCardStyle(val css: String?, val versionKey: String)`；`TavernCardStyleResolver.resolve(assistant: Assistant?): TavernCardStyle?`；`CssSanitizer.sanitize(css: String): String`；`MarkdownBlock(..., tavernCardStyle: TavernCardStyle? = null, streaming: Boolean = false)`（Task 4/5 用 streaming）

- [ ] **Step 1: 写失败测试**

```kotlin
package me.rerere.rikkahub.data.ai.status

import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernCardStyleResolverTest {

    @Test
    fun `resolves css from card and derives version key`() {
        val assistant = Assistant(
            name = "A",
            tavernCardJson = """{"data":{"extensions":{"css":"body{color:red}"}}}""",
        )
        val style = TavernCardStyleResolver.resolve(assistant)
        assertEquals("body{color:red}", style?.css)
        assertTrue(style!!.versionKey.isNotBlank())
    }

    @Test
    fun `returns null for assistant without card`() {
        assertNull(TavernCardStyleResolver.resolve(Assistant(name = "B")))
    }

    @Test
    fun `version key changes when card json changes`() {
        val a1 = Assistant(name = "A", tavernCardJson = """{"data":{"extensions":{"css":"a"}}}""")
        val a2 = Assistant(name = "A", tavernCardJson = """{"data":{"extensions":{"css":"b"}}}""")
        val k1 = TavernCardStyleResolver.resolve(a1)!!.versionKey
        val k2 = TavernCardStyleResolver.resolve(a2)!!.versionKey
        assertTrue(k1 != k2)
    }

    @Test
    fun `resolves css when only status render js present without card css`() {
        val assistant = Assistant(name = "C", statusRenderJs = "function renderStatus(){}")
        val style = TavernCardStyleResolver.resolve(assistant)
        // 无 card CSS 时 css 为 null，但版本键仍随 statusRenderJs 变化
        assertNull(style?.css)
        assertTrue(style!!.versionKey.isNotBlank())
    }
}
```

```kotlin
package me.rerere.rikkahub.data.ai.status

import org.junit.Assert.assertEquals
import org.junit.Test

class CssSanitizerTest {

    @Test
    fun `replaces closing style escape sequences case-insensitively`() {
        val input = "body{}</STYLE><script>alert(1)</script>"
        val out = CssSanitizer.sanitize(input)
        assertEquals("body{}/* */ STYLE>/* */ script>alert(1)/* */ script>", out)
        // 注意：</ 全替换为 /* */ ，script 标签内 </script 中的 </ 也变 /* */ script
        assertEquals(-1, out.toLowerCase().indexOf("</"))
    }

    @Test
    fun `leaves plain css untouched`() {
        val css = "body { color: red; } .mes { padding: 4px; }"
        assertEquals(css, CssSanitizer.sanitize(css))
    }
}
```

注意第一个测试的期望值需要按实现精确对齐——先写测试，运行失败后按实际替换结果调整断言（`</` 替换为 `/* */ ` 后 `</STYLE>` → `/* */ STYLE>`；`</script>` → `/* */ script>`；末尾无 `</`）。若计算出的期望与直觉不符，以「输出不包含 `</`」为核心断言 + 具体字符串以实际输出为准（测试可先 assert `!out.contains("</")` 与关键替换样例）。

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.status.TavernCardStyleResolverTest" --tests "me.rerere.rikkahub.data.ai.status.CssSanitizerTest"`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 实现**

```kotlin
package me.rerere.rikkahub.data.ai.status

/**
 * 清洗用户提供的 CSS，阻断逃出 <style> 块的注入。
 * 浏览器 HTML 解析器在 <style> 内只识别 "</style"（不区分大小写）作为结束标记，
 * 核心是破坏这个序列。CSS 中正常情况下不会出现 "</"，把所有 "</" 替换为
 * "/* */ " 是无害的（CSS 注释，几乎不影响任何合法选择器或值）。
 */
object CssSanitizer {
    fun sanitize(css: String): String =
        Regex("</", RegexOption.IGNORE_CASE).replace(css, "/* */ ")
}
```

`StatusRenderer.kt` 删除私有 `sanitizeCss`（:132-133），调用点改 `CssSanitizer.sanitize(css)`。

```kotlin
package me.rerere.rikkahub.data.ai.status

import me.rerere.rikkahub.data.model.Assistant

/**
 * 角色卡渲染样式（卡 CSS + 版本键）。
 *
 * @property css 角色卡 CSS（可为 null，表示卡未提供样式）
 * @property versionKey 样式版本键：卡 JSON / renderStatus JS 变化时改变，用于 WebView renderKey 失效
 */
data class TavernCardStyle(
    val css: String?,
    val versionKey: String,
)

/**
 * 解析角色卡样式：CSS 复用 TavernCardCssExtractor；版本键由卡 JSON 与 renderStatus JS 的 hash 组合。
 * 无卡（tavernCardJson/statusRenderJs 均 null）时返回 null（消息渲染无需注入）。
 */
object TavernCardStyleResolver {

    fun resolve(assistant: Assistant?): TavernCardStyle? {
        if (assistant == null) return null
        val cardJson = assistant.tavernCardJson
        val renderJs = assistant.statusRenderJs
        if (cardJson == null && renderJs == null) return null
        val css = cardJson?.let { TavernCardCssExtractor.extract(it) }
        val versionKey = "${cardJson?.hashCode() ?: 0}|${renderJs?.hashCode() ?: 0}"
        return TavernCardStyle(css = css, versionKey = versionKey)
    }
}
```

- [ ] **Step 4: 扩展 renderer 注入参数**

`StableMessageHtmlRenderer.kt` 纯函数签名扩展：

```kotlin
internal fun buildStableMessageHtml(
    message: StableDomMessage,
    template: String,
    vendorScripts: String = "",
    vendorStyles: String = "",
    cssVariables: Map<String, String> = emptyMap(),
    extraCss: String? = null,
): String {
    val messageJson = json.encodeToString(message).replace("</script>", "<\\/script>")
    val variablesInjected = cssVariables.entries.fold(template) { acc, (key, value) ->
        acc.replace("{{$key}}", value)
    }
    return variablesInjected
        .replace(VENDOR_LIBS_PLACEHOLDER, vendorScripts)
        .replace(VENDOR_STYLES_PLACEHOLDER, vendorStyles)
        .replace("{{EXTRA_CSS}}", extraCss?.let { CssSanitizer.sanitize(it) } ?: "")
        .replace(MESSAGE_JSON_PLACEHOLDER, messageJson)
}
```

context 版本同步加参数并传默认 `cssVariables` 与 `extraCss`；`MarkdownWebView` 侧在 STABLE_DOM 调用时注入（见 Step 6）。

- [ ] **Step 5: `Markdown.kt` MarkdownBlock 加参数并传递**

`MarkdownBlock` 签名加：

```kotlin
    /** 角色卡渲染样式（CSS 注入 st-message 文档，null 表示无卡样式） */
    tavernCardStyle: TavernCardStyle? = null,
    /** 流式生成中：true 时走增量 patch，false 时整文档渲染（Task 5 接线） */
    streaming: Boolean = false,
```

STABLE_DOM 分支构建 `MarkdownWebView` 时传（Task 5 再实现 MarkdownWebView 的 streaming 参数，本任务先加 `cssVariables`/`extraCss` 参数——见 Step 6；streaming 参数本任务可先不加进 MarkdownWebView，Task 5 再加，避免空转）：

```kotlin
            val tavernStyle = tavernCardStyle
            MarkdownWebView(
                content = buildStableMessageHtml(
                    context,
                    StableDomMessage(
                        id = normalizedContent.hashCode().toString(),
                        role = stableRole?.toStableDomRole() ?: StableDomRole.ASSISTANT,
                        name = roleName,
                        segments = segments.mapIndexed { index, segment ->
                            StableDomSegment(
                                id = "segment-$index",
                                kind = segment.kind,
                                raw = segment.raw,
                            )
                        },
                        streaming = false,
                    ),
                ),
                modifier = modifier,
                isRawHtml = true,
                tavernExtraCss = tavernStyle?.css,
                tavernStyleVersionKey = tavernStyle?.versionKey,
            )
```

- [ ] **Step 6: MarkdownWebView 加 CSS 变量注入**

关键改动（`MarkdownWebView.kt`）：

1. 已有 6 色提取（:92-101）。加：

```kotlin
    val stCssVariables = mapOf(
        "CSS_VAR_BG" to bgHex,
        "CSS_VAR_SURFACE" to surfaceHex,
        "CSS_VAR_SURFACE_VARIANT" to surfaceVariantHex,
        "CSS_VAR_TEXT" to textHex,
        "CSS_VAR_TEXT_SECONDARY" to onSurfaceVariantHex,
        "CSS_VAR_BORDER" to outlineVariantHex,
        "CSS_VAR_ACCENT" to primaryHex,
    )
```

2. 新参数：

```kotlin
    /** STABLE_DOM 文档追加注入的角色卡 CSS（经 CssSanitizer 清洗后内联 <style>） */
    tavernExtraCss: String? = null,
    /** 卡样式版本键（变化时触发重载） */
    tavernStyleVersionKey: String? = null,
```

3. renderKey（:149-161）加 `tavernStyleVersionKey`；注意 STABLE_DOM 路径的 HTML 由调用方构建（MarkdownBlock），CSS 变量需要注入到生成的 HTML——所以 `MarkdownWebView` 的 STABLE_DOM 用法其实是 `content = buildStableMessageHtml(...)` 的成品 HTML（isRawHtml=true 路径走 buildSandboxHostHtml 外壳）。

   **关键修正**：STABLE_DOM 成品 HTML 已经含 `<style>`（CSS 变量已由 renderer 注入？不——renderer 注入发生在 MarkdownBlock 构建 content 时）。因此 CSS 变量注入点应在 **MarkdownBlock 的 buildStableMessageHtml 调用**（Step 5 里传入 cssVariables）。MarkdownBlock 需要 MaterialTheme 色值：在 MarkdownBlock 内取 `MaterialTheme.colorScheme` 构造 cssVariables 传给 renderer。即 Step 5 的调用改为：

```kotlin
            val colorScheme = MaterialTheme.colorScheme
            val cssVariables = mapOf(
                "CSS_VAR_BG" to "transparent",
                "CSS_VAR_SURFACE" to hex(colorScheme.surface),
                "CSS_VAR_SURFACE_VARIANT" to hex(colorScheme.surfaceVariant),
                "CSS_VAR_TEXT" to hex(colorScheme.onSurface),
                "CSS_VAR_TEXT_SECONDARY" to hex(colorScheme.onSurfaceVariant),
                "CSS_VAR_BORDER" to hex(colorScheme.outlineVariant),
                "CSS_VAR_ACCENT" to hex(colorScheme.primary),
            )
            MarkdownWebView(
                content = buildStableMessageHtml(
                    context,
                    StableDomMessage(...),
                    cssVariables = cssVariables,
                    extraCss = tavernCardStyle?.css,
                ),
                modifier = modifier,
                isRawHtml = true,
                tavernStyleVersionKey = tavernCardStyle?.versionKey,
            )
```

（`hex()` 是 MarkdownWebView.kt 的顶层工具——确认其可见性，若 internal 顶层则在同包可用；Markdown.kt 与 MarkdownWebView.kt 同包 `me.rerere.rikkahub.ui.components.richtext`，可用。若 `hex` 是 private 则复制或提升可见性。）

- [ ] **Step 7: ChatMessage 三处调用传卡样式**

`ChatMessage.kt` 中三处 `MarkdownBlock(...)`（:506/:526/:539）加参数：

```kotlin
tavernCardStyle = remember(assistant) { TavernCardStyleResolver.resolve(assistant) },
```

（`assistant` 已在 ChatMessage 作用域；`TavernCardStyleResolver` import。）

- [ ] **Step 8: renderer 测试更新 + 全量验证**

`StableMessageHtmlRendererTest.kt` 加用例：

```kotlin
    @Test
    fun injectsCssVariablesAndSanitizedExtraCss() {
        val message = StableDomMessage(id = "m", role = StableDomRole.ASSISTANT, segments = emptyList(), streaming = false)
        val template = "<style>{{CSS_VAR_BG}}|{{EXTRA_CSS}}</style>{{MESSAGE_JSON}}"
        val html = buildStableMessageHtml(
            message = message,
            template = template,
            cssVariables = mapOf("CSS_VAR_BG" to "#112233"),
            extraCss = "body{}</style><script>evil</script>",
        )
        assertTrue(html.contains("#112233"))
        assertTrue(html.contains("/* */ style>"))
        assertFalse(html.contains("</style><script>"))
    }
```

Run: `.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin`
Expected: 全绿

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/ai/status/TavernCardStyleResolver.kt app/src/main/java/me/rerere/rikkahub/data/ai/status/CssSanitizer.kt app/src/main/java/me/rerere/rikkahub/data/ai/status/StatusRenderer.kt app/src/main/java/me/rerere/rikkahub/ui/components/richtext/st/StableMessageHtmlRenderer.kt app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt app/src/test/java/me/rerere/rikkahub/data/ai/status/TavernCardStyleResolverTest.kt app/src/test/java/me/rerere/rikkahub/data/ai/status/CssSanitizerTest.kt app/src/test/java/me/rerere/rikkahub/ui/components/richtext/st/StableMessageHtmlRendererTest.kt
git commit -m "feat: inject material css variables and card css into stable-dom messages"
```

---

### Task 4: 段快照 diff（Kotlin 纯逻辑）

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/st/StableSegmentSnapshot.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/st/StableSegmentSnapshotTest.kt`

**Interfaces:**
- Produces: `StableSegmentSnapshot`（`diff(old, new): List<SegmentPatch>`；`SegmentPatch(segmentId, kind, raw)` @Serializable）；`encodePatches(patches): String`（JSON，Task 5 消费）

- [ ] **Step 1: 写失败测试**

```kotlin
package me.rerere.rikkahub.ui.components.richtext.st

import me.rerere.rikkahub.ui.components.richtext.RichTextSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class StableSegmentSnapshotTest {

    private fun seg(id: String, raw: String, kind: RichTextSegment.Kind = RichTextSegment.Kind.MARKDOWN) =
        StableDomSegment(id = id, kind = kind, raw = raw)

    @Test
    fun `no patch when segments unchanged`() {
        val old = listOf(seg("s0", "hello"), seg("s1", "world"))
        val patches = StableSegmentSnapshot.diff(old, old)
        assertEquals(emptyList<SegmentPatch>(), patches)
    }

    @Test
    fun `replace patch when segment text changes`() {
        val old = listOf(seg("s0", "hello"))
        val new = listOf(seg("s0", "hello world"))
        val patches = StableSegmentSnapshot.diff(old, new)
        assertEquals(listOf(SegmentPatch(segmentId = "s0", kind = RichTextSegment.Kind.MARKDOWN, raw = "hello world")), patches)
    }

    @Test
    fun `append patch for new trailing segments`() {
        val old = listOf(seg("s0", "hello"))
        val new = listOf(seg("s0", "hello"), seg("s1", "world"))
        val patches = StableSegmentSnapshot.diff(old, new)
        assertEquals(
            listOf(SegmentPatch(segmentId = "s1", kind = RichTextSegment.Kind.MARKDOWN, raw = "world")),
            patches
        )
    }

    @Test
    fun `mixed replace and append`() {
        val old = listOf(seg("s0", "a"), seg("s1", "b"))
        val new = listOf(seg("s0", "a2"), seg("s1", "b"), seg("s2", "c"))
        val patches = StableSegmentSnapshot.diff(old, new)
        assertEquals(
            listOf(
                SegmentPatch(segmentId = "s0", kind = RichTextSegment.Kind.MARKDOWN, raw = "a2"),
                SegmentPatch(segmentId = "s2", kind = RichTextSegment.Kind.MARKDOWN, raw = "c"),
            ),
            patches
        )
    }

    @Test
    fun `status block segments produce non markdown patches`() {
        val old = listOf(seg("s0", "narrative"), seg("s1", "<status_block>...", RichTextSegment.Kind.STATUS_BLOCK))
        val new = listOf(seg("s0", "narrative"), seg("s1", "<status_block>updated", RichTextSegment.Kind.STATUS_BLOCK))
        val patches = StableSegmentSnapshot.diff(old, new)
        assertEquals(
            listOf(SegmentPatch(segmentId = "s1", kind = RichTextSegment.Kind.STATUS_BLOCK, raw = "<status_block>updated")),
            patches
        )
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.richtext.st.StableSegmentSnapshotTest"`
Expected: 编译失败

- [ ] **Step 3: 实现**

```kotlin
package me.rerere.rikkahub.ui.components.richtext.st

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.ui.components.richtext.RichTextSegment

/**
 * 流式期间对 st-message DOM 的增量 patch（宿主 → RikkahubDomBridge.applySegmentPatch）。
 */
@Serializable
internal data class SegmentPatch(
    val segmentId: String,
    val kind: RichTextSegment.Kind,
    val raw: String,
)

private val patchJson = Json { encodeDefaults = true }

/**
 * 段快照 diff：id 相同 raw 相同 → 跳过；id 相同 raw 不同 → 替换；新增 id → 追加。
 * 不做删除/重排（流式场景只增改）。
 */
internal object StableSegmentSnapshot {

    fun diff(old: List<StableDomSegment>, new: List<StableDomSegment>): List<SegmentPatch> {
        val patches = mutableListOf<SegmentPatch>()
        val oldById = old.associateBy { it.id }
        new.forEach { segment ->
            val previous = oldById[segment.id]
            if (previous == null || previous.raw != segment.raw) {
                patches.add(SegmentPatch(segmentId = segment.id, kind = segment.kind, raw = segment.raw))
            }
        }
        return patches
    }

    fun encodePatches(patches: List<SegmentPatch>): String = patchJson.encodeToString(patches)
}
```

- [ ] **Step 4: 运行确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.richtext.st.StableSegmentSnapshotTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/components/richtext/st/StableSegmentSnapshot.kt app/src/test/java/me/rerere/rikkahub/ui/components/richtext/st/StableSegmentSnapshotTest.kt
git commit -m "feat: add stable-dom segment snapshot diff for streaming patches"
```

---

### Task 5: streaming 增量接线 + 生命周期治理

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt`（MarkdownBlock 传 streaming）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt`（三处传 streaming = loading）
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/st/StableMessageDomModelsTest.kt` 或新增（streaming 序列化含 streaming 字段——已含，无需新增；本任务核心逻辑为 WebView 层，JVM 测试覆盖 renderer 纯函数即可）

**Interfaces:**
- Consumes: `StableSegmentSnapshot.diff/encodePatches`（Task 4）；`MarkdownWebView` 新参数 `streaming: Boolean = false`、`streamSegments: List<StableDomSegment>? = null`
- Produces: 流式增量更新通道 + WebView 销毁治理

- [ ] **Step 1: MarkdownWebView 改造**

核心 diff（对照现状 :90/:149-161/:172/:395-408）：

1. 参数：

```kotlin
    /** 流式生成中：true 时内容变化走 applySegmentPatch 增量，false 时整文档重载 */
    streaming: Boolean = false,
    /** streaming=true 时必传：当前内容的分段（用于段 diff） */
    streamSegments: List<StableDomSegment>? = null,
    /** 初始最小高度（dp），首次上报前占位，避免 0dp 闪烁或 100dp 假高 */
    minHeightDp: Int = 24,
```

2. `viewHeight` 初始值：`var viewHeight by remember { mutableStateOf(0) }`；`AndroidView` 高度 modifier：

```kotlin
            modifier = if (fixedHeight) {
                Modifier.fillMaxWidth().fillMaxHeight()
            } else {
                Modifier.fillMaxWidth().height(with(density) { maxOf(viewHeight, with(density) { minHeightDp.dp.toPx() }.toInt()).toDp() })
            },
```

3. renderKey 拆分（streaming 语义）：

```kotlin
    // baseKey 不含 content：路径/主题/角色/卡样式变化才整文档重载
    val baseKey = listOf(
        useIframeSandbox, fixedHeight, bgHex, textHex, primaryHex,
        surfaceHex, surfaceVariantHex, outlineVariantHex, onSurfaceVariantHex,
        tavernStyleVersionKey, streaming,
    ).joinToString("|")
    val contentKey = "${content.length}|${content.hashCode()}"
```

4. 状态：`lastLoadedKey` 改为两个：`lastBaseKey`、`lastContentKey`，加 `lastSegments = remember { mutableStateOf<List<StableDomSegment>>(emptyList()) }`。

5. factory 内首次加载不变，`lastBaseKey.value = baseKey; lastContentKey.value = contentKey; lastSegments.value = streamSegments.orEmpty()`。

6. update 块：

```kotlin
            update = { webView ->
                if (lastBaseKey.value != baseKey) {
                    val html = if (useIframeSandbox) {
                        buildSandboxHostHtml(content, bgHex, textHex, fixedHeight)
                    } else {
                        buildMarkdownPreviewHtml(context, normalizeRichTextContent(content), colorScheme)
                    }
                    webView.loadDataWithBaseURL("https://rikkahub.local/", html, "text/html", "UTF-8", null)
                    lastBaseKey.value = baseKey
                    lastContentKey.value = contentKey
                    lastSegments.value = streamSegments.orEmpty()
                    return@AndroidView
                }
                if (lastContentKey.value == contentKey) return@AndroidView
                if (streaming) {
                    val old = lastSegments.value
                    val new = streamSegments.orEmpty()
                    val patches = StableSegmentSnapshot.diff(old, new)
                    lastSegments.value = new
                    lastContentKey.value = contentKey
                    if (patches.isEmpty()) return@AndroidView
                    val patchJson = JSONObject.quote(StableSegmentSnapshot.encodePatches(patches))
                    webView.postEvaluateJavascript(
                        "window.RikkahubDomBridge && window.RikkahubDomBridge.applySegmentPatch($patchJson);"
                    )
                } else {
                    val html = if (useIframeSandbox) {
                        buildSandboxHostHtml(content, bgHex, textHex, fixedHeight)
                    } else {
                        buildMarkdownPreviewHtml(context, normalizeRichTextContent(content), colorScheme)
                    }
                    webView.loadDataWithBaseURL("https://rikkahub.local/", html, "text/html", "UTF-8", null)
                    lastContentKey.value = contentKey
                    lastSegments.value = streamSegments.orEmpty()
                }
            },
```

（`postEvaluateJavascript` 是 WebView 已有 API？仓库用 `webView.postEvaluateJavascript(...)`（:140 已有）——确认它是 WebView 扩展（项目内扩展函数）；若不存在则用 `webView.post { webView.evaluateJavascript(...) }` 包裹。执行时以仓库实际 API 为准。）

7. 销毁治理（factory 返回的 WebView 上）：

```kotlin
    DisposableEffect(Unit) {
        onDispose {
            val webView = tavernWebViewRef.value ?: return@onDispose
            runCatching { webView.removeJavascriptInterface("RikkahubBridge") }
            runCatching { webView.removeJavascriptInterface("TavernRuntimeBridge") }
            runCatching { webView.stopLoading() }
            runCatching { webView.destroy() }
            tavernWebViewRef.value = null
        }
    }
```

- [ ] **Step 2: Markdown.kt 传 streaming**

STABLE_DOM 分支（Task 3 已加 `streaming` 参数到 MarkdownBlock）：`StableDomMessage(streaming = streaming)`、`MarkdownWebView(..., streaming = streaming, streamSegments = 当 streaming 时传 segments)`：

```kotlin
            MarkdownWebView(
                content = buildStableMessageHtml(
                    context,
                    StableDomMessage(
                        id = normalizedContent.hashCode().toString(),
                        role = stableRole?.toStableDomRole() ?: StableDomRole.ASSISTANT,
                        name = roleName,
                        segments = segments.mapIndexed { index, segment ->
                            StableDomSegment(
                                id = "segment-$index",
                                kind = segment.kind,
                                raw = segment.raw,
                            )
                        },
                        streaming = streaming,
                    ),
                    cssVariables = cssVariables,
                    extraCss = tavernCardStyle?.css,
                ),
                modifier = modifier,
                isRawHtml = true,
                streaming = streaming,
                streamSegments = segments.mapIndexed { index, segment ->
                    StableDomSegment(id = "segment-$index", kind = segment.kind, raw = segment.raw)
                },
                tavernStyleVersionKey = tavernCardStyle?.versionKey,
            )
```

（StableDomMessage 与 streamSegments 共用同一 segments 映射，构建一次 `val stableSegments = ...` 复用。）

- [ ] **Step 3: ChatMessage 传 streaming**

三处 `MarkdownBlock(...)` 加 `streaming = loading`（`loading` 已是 ChatMessage 的 prop）。

- [ ] **Step 4: 全量验证**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin`
Expected: 全绿（若 postEvaluateJavascript 不存在按 Step 1 注记改用 post + evaluateJavascript）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt
git commit -m "feat: streaming incremental dom patches and webview disposal in markdown webview"
```

---

### Task 6: ChatList contentType + 全量验证 + 文档

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt:323-325`
- Modify: `AGENTS.md`（Current Status 更新）

- [ ] **Step 1: contentType**

`ChatList.kt` 的 `itemsIndexed`（:323）加：

```kotlin
            itemsIndexed(
                items = conversation.messageNodes,
                key = { index, item -> item.id },
                contentType = { _, _ -> "message" },
            ) { index, node ->
```

- [ ] **Step 2: Android 全量验证**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug`
Expected: 全部通过

- [ ] **Step 3: web-ui 回归（打包脚本不破坏 web-ui）**

Run（workdir `web-ui`）: `pnpm typecheck && pnpm test && pnpm build`
Expected: 全绿（web-ui app 代码未动，仅新增 scripts/）

- [ ] **Step 4: 模拟器冒烟**

参照子项目 A 冒烟流程（模拟器 + DB 注入 + uiautomator/agent-browser 或 API 验证）：
1. 安装 assembleDebug，注入状态块测试对话（`<status_block>` 文本消息 + status_placeholder 消息）
2. 验证消息气泡渲染：`adb shell` dump 或观察 HUD；DOM 形状验证（无仪器测试手段时用 TavernRuntimeSmokeActivity debug 入口或截图人工确认）
3. 流式：Mock 模型或真实生成期间观察无全量 reload（日志/高度稳定性）
4. 暗/亮切换：气泡/状态面板正常
5. 长列表滚动：WebView 销毁无崩溃（滚动 + 后退复进）
6. 冒烟结论写入 AGENTS.md

- [ ] **Step 5: AGENTS.md**

在 Current Status 最新块之上加：

```markdown
**2026-08-13：Android 渲染链路提升（子项目 B1）。**

- st-message.html 重建为 ST 默认形状（.mes_text > p 分段、.name_text/.ch_name、mes_buttons 占位、CSS 变量默认主题）；
  前端库（markdown-it/DOMPurify/hljs/katex/mermaid）经 esbuild IIFE 打包到 assets/html/vendor/ 并在构建期内联（无 CDN/file:// 依赖）
- 主题通道：Material 色值 → CSS 变量（--rikkahub-*）；角色卡 CSS 经 TavernCardStyleResolver + CssSanitizer 注入消息气泡
  （StatusRenderer 复用 CssSanitizer）；renderKey 含卡样式版本键
- 流式增量：RikkahubDomBridge.applySegmentPatch + StableSegmentSnapshot 段 diff；streaming 经 ChatMessage→Markdown→MarkdownWebView 传递
- 生命周期：onDispose destroy + removeJavascriptInterface；ChatList contentType；viewHeight 初始 0 + minHeight 占位
- 验证：`:app:testDebugUnitTest`/`:app:compileDebugKotlin`/`:app:assembleDebug` 全绿；模拟器冒烟结果见下
- 待办：子项目 B2（脚本 API 兼容性：SillyTavern.getContext/event_types/MacroHelper/SlashCommandParser）
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt AGENTS.md
git commit -m "feat: add lazy list content type and record B1 renderer upgrade status"
```

---

## Self-Review

1. **Spec coverage**：§2.1→Task 2；§2.2→Task 2；§2.3→Task 1；§3.1→Task 3；§3.2→Task 3；§4.1→Task 2/4；§4.2→Task 4/5；§5.1→Task 5；§5.2→Task 6；§5.3→Task 5；§6→Task 6。无缺口。
2. **Placeholder scan**：无 TBD；代码步骤含完整代码或精确 diff 指令。
3. **Type consistency**：`StableDomSegment/SegmentPatch` 字段在 Task 4/5 一致；`TavernCardStyle` 在 Task 3/5 一致；模板占位符 `{{CSS_VAR_*}}/{{EXTRA_CSS}}` 在 Task 2/3 一致；`streaming/streamSegments/tavernStyleVersionKey` 参数名在 Task 3/5 一致。
