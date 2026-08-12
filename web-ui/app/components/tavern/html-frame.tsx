import * as React from "react";

import { cn } from "~/lib/utils";

interface HtmlFrameProps {
  html: string;
  className?: string;
  maxHeightPx?: number;
  minHeightPx?: number;
}

/**
 * 沙箱 iframe 展示模式：渲染不受信 HTML（无脚本执行），
 * 高度由父页面读取 contentDocument 自适应。
 */
export function HtmlFrame({ html, className, maxHeightPx, minHeightPx = 40 }: HtmlFrameProps) {
  const containerRef = React.useRef<HTMLDivElement>(null);
  const frameRef = React.useRef<HTMLIFrameElement>(null);
  const [height, setHeight] = React.useState(minHeightPx);
  const [visible, setVisible] = React.useState(false);
  const [srcdoc, setSrcdoc] = React.useState<string | null>(null);

  React.useEffect(() => {
    const node = containerRef.current;
    if (!node) return;
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        setVisible(true);
        observer.disconnect();
      }
    });
    observer.observe(node);
    return () => observer.disconnect();
  }, []);

  React.useEffect(() => {
    if (!visible) return;
    setSrcdoc(html);
    setHeight(minHeightPx);
  }, [visible, html, minHeightPx]);

  const syncHeight = React.useCallback(() => {
    const doc = frameRef.current?.contentDocument;
    if (!doc) return;
    const measured = doc.documentElement.scrollHeight;
    setHeight((prev) => (measured !== prev ? measured : prev));
  }, []);

  return (
    <div
      ref={containerRef}
      className={cn("overflow-hidden rounded-md border border-border/60 bg-background", className)}
    >
      {visible && srcdoc !== null && (
        <iframe
          ref={frameRef}
          title="Tavern status"
          sandbox="allow-same-origin"
          srcDoc={srcdoc}
          onLoad={() => {
            syncHeight();
            let attempts = 0;
            const timer = window.setInterval(() => {
              syncHeight();
              attempts += 1;
              if (attempts >= 10) window.clearInterval(timer);
            }, 1000);
          }}
          style={{
            width: "100%",
            height: maxHeightPx !== undefined ? Math.min(height, maxHeightPx) : height,
            border: "none",
            display: "block",
          }}
        />
      )}
    </div>
  );
}

const RENDER_RESULT_MESSAGE = "rikkahub:render-result";

/** 清洗 renderStatus 源码，防 `</script>` 逃逸出 script 标签。 */
function sanitizeScriptSource(source: string): string {
  return source.replace(/<\/script/gi, "<\\/script");
}

/**
 * 沙箱重渲染模式：sandbox="allow-scripts"（opaque origin），
 * 执行角色卡 renderStatus(variables, metadata)，postMessage 回传 HTML 与高度。
 */
export function RenderStatusFrame({
  statusRenderJs,
  variables,
  metadata,
  css,
  fallbackHtml,
  onResult,
}: {
  statusRenderJs: string;
  variables: Record<string, unknown>;
  metadata: Record<string, string>;
  css?: string | null;
  fallbackHtml: string;
  onResult: (html: string) => void;
}) {
  const frameRef = React.useRef<HTMLIFrameElement>(null);
  const [done, setDone] = React.useState(false);

  const srcdoc = React.useMemo(() => {
    const variablesJson = JSON.stringify(variables).replace(/</g, "\\u003c");
    const metadataJson = JSON.stringify(metadata).replace(/</g, "\\u003c");
    return [
      "<!doctype html><html><head>",
      css != null ? `<style>${css}</style>` : "",
      "</head><body><script>",
      "window.addEventListener('error', function() {",
      `parent.postMessage({type:'${RENDER_RESULT_MESSAGE}', error:true}, '*');`,
      "});",
      sanitizeScriptSource(statusRenderJs),
      "try {",
      "  var result = (typeof renderStatus === 'function') ? renderStatus(" +
        variablesJson +
        ", " +
        metadataJson +
        ") : null;",
      "  var html = (result == null) ? '' : String(result);",
      "  parent.postMessage({type:'" + RENDER_RESULT_MESSAGE + "', html: html}, '*');",
      "} catch (e) {",
      `  parent.postMessage({type:'${RENDER_RESULT_MESSAGE}', error: true}, '*');`,
      "}",
      "</scr" + "ipt></body></html>",
    ].join("\n");
  }, [statusRenderJs, variables, metadata, css]);

  React.useEffect(() => {
    const handler = (event: MessageEvent) => {
      if (frameRef.current && event.source !== frameRef.current.contentWindow) return;
      const data = event.data as { type?: string; html?: string; error?: boolean };
      if (data?.type !== RENDER_RESULT_MESSAGE) return;
      setDone(true);
      onResult(typeof data.html === "string" ? data.html : fallbackHtml);
    };
    window.addEventListener("message", handler);
    return () => window.removeEventListener("message", handler);
  }, [fallbackHtml, onResult]);

  React.useEffect(() => {
    const timer = window.setTimeout(() => {
      if (!done) onResult(fallbackHtml);
    }, 5000);
    return () => window.clearTimeout(timer);
  }, [done, fallbackHtml, onResult]);

  return (
    <iframe
      ref={frameRef}
      title="Tavern renderStatus"
      sandbox="allow-scripts"
      srcDoc={srcdoc}
      style={{ display: "none" }}
    />
  );
}
