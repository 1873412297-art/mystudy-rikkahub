import * as React from "react";

import type { StatusPlaceholderPart } from "~/types";
import { useTavernStore } from "~/stores";
import { buildFallbackHtml } from "~/lib/tavern/fallback-html";
import { HtmlFrame, RenderStatusFrame } from "./html-frame";

/**
 * StatusPlaceholder 部件渲染：
 * - characterPages >= 2：Tabs 多角色分页
 * - 单页：展示服务端 htmlContent；若角色卡 renderStatus JS 与最新变量树可用，
 *   用 sandboxed iframe 实时重渲染，成功后替换显示。
 */
export function StatusPlaceholderView({
  part,
  conversationId,
  assistantId,
}: {
  part: StatusPlaceholderPart;
  conversationId: string;
  assistantId: string;
}) {
  const variables = useTavernStore((state) => state.variablesByConversation[conversationId]);
  const card = useTavernStore((state) => state.cardsByAssistant[assistantId]);
  const [reRenderedHtml, setReRenderedHtml] = React.useState<string | null>(null);
  const [renderNonce, setRenderNonce] = React.useState(0);
  const [activeIndex, setActiveIndex] = React.useState(0);

  React.useEffect(() => {
    void useTavernStore.getState().ensureCardLoaded(assistantId);
  }, [assistantId]);

  React.useEffect(() => {
    if (variables) setRenderNonce((n) => n + 1);
  }, [variables]);

  const pages = part.characterPages ?? [];

  if (pages.length >= 2) {
    return (
      <div className="w-full">
        <div className="flex flex-wrap gap-1 pb-2">
          {pages.map((page, index) => (
            <button
              key={page.name}
              type="button"
              onClick={() => setActiveIndex(index)}
              className={
                index === activeIndex
                  ? "rounded-full bg-primary px-3 py-1 text-xs font-medium text-primary-foreground"
                  : "rounded-full bg-muted px-3 py-1 text-xs font-medium text-muted-foreground"
              }
            >
              {page.name}
            </button>
          ))}
        </div>
        <HtmlFrame html={pages[activeIndex]?.html ?? ""} maxHeightPx={420} />
      </div>
    );
  }

  const displayHtml = reRenderedHtml ?? part.htmlContent;
  const statusRenderJs = card?.statusRenderJs;

  return (
    <div className="w-full">
      <HtmlFrame html={displayHtml} maxHeightPx={560} />
      {statusRenderJs && variables && renderNonce > 0 && (
        <RenderStatusFrame
          key={renderNonce}
          statusRenderJs={statusRenderJs}
          variables={variables}
          metadata={{ expression: extractExpression(variables) }}
          css={card?.css}
          fallbackHtml={buildFallbackHtml(variables, {})}
          onResult={(html) => setReRenderedHtml(html)}
        />
      )}
    </div>
  );
}

function extractExpression(variables: Record<string, unknown>): string {
  const value = variables["_expression"];
  return typeof value === "string" ? value : "";
}
