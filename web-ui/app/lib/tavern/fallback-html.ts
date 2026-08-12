/**
 * 状态变量 fallback HTML 的统一构建器（与 Kotlin StatusFallbackHtml 对齐）。
 * 安全约定：所有来自状态变量的 key/value 一律做 HTML 转义（& < >）。
 */
const ROOT_STYLE = "font-family:sans-serif;font-size:13px;line-height:1.5;";

export function escapeHtml(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function appendRows(html: string[], map: Record<string, unknown>, indent = 0): void {
  for (const [key, value] of Object.entries(map)) {
    if (isRecord(value)) {
      html.push(`<div style="font-weight:600;margin-top:4px;">${escapeHtml(key)}</div>`);
      html.push(`<div style="margin-left:${8 + indent * 8}px;">`);
      appendRows(html, value, indent + 1);
      html.push("</div>");
    } else if (Array.isArray(value)) {
      const joined = value.map((item) => item?.toString() ?? "—").join(", ");
      html.push(`<div><b>${escapeHtml(key)}:</b> ${escapeHtml(joined)}</div>`);
    } else {
      const displayValue = value?.toString() ?? "—";
      html.push(`<div><b>${escapeHtml(key)}:</b> ${escapeHtml(displayValue)}</div>`);
    }
  }
}

export function buildFallbackHtml(
  variables: Record<string, unknown>,
  metadata: Record<string, string>,
): string {
  const html: string[] = [];
  html.push(`<div style="${ROOT_STYLE}">`);
  const expression = metadata["expression"];
  if (expression != null && expression.trim() !== "") {
    html.push(
      `<div style="font-size:16px;font-weight:600;margin-bottom:4px;">${escapeHtml(expression)}</div>`,
    );
  }
  if (Object.keys(variables).length > 0) {
    appendRows(html, variables);
  }
  html.push("</div>");
  return html.join("");
}
