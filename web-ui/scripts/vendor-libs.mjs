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
    // esbuild 对 ESM 入口（markdown-it/hljs/katex/@vscode/markdown-it-katex/mermaid）
    // 的 IIFE 全局是 { default: lib } 命名空间，模板按 plan 直接取 window.X，
    // 这里在产物末尾把 default 解包，让全局直接是库本体（CJS 入口无 default，原样保留）。
    footer: { js: `var ${lib.global} = (${lib.global} && ${lib.global}.default) || ${lib.global};` },
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
