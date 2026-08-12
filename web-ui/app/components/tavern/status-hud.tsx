import * as React from "react";
import { ChevronDown, ChevronUp } from "lucide-react";

import type { MessageDto } from "~/types";
import { extractStatusBlock, type StatusExtraction } from "~/lib/tavern/status-extractor";
import { HtmlFrame } from "./html-frame";

interface HudSource {
  extraction: StatusExtraction;
}

/** 从尾部往前找最近一条含状态块的 assistant 消息（对齐 Android StatusHudBar.findLatestStatusHud）。 */
function findLatestStatusHud(messages: MessageDto[]): HudSource | null {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index];
    if (!message || message.role !== "ASSISTANT") continue;
    const text = message.parts
      .filter((part) => part.type === "text")
      .map((part) => (part.type === "text" ? part.text : ""))
      .join("\n");
    if (!text.trim()) continue;
    const extraction = extractStatusBlock(text);
    if (extraction.rawStatusText != null) {
      return { extraction };
    }
  }
  return null;
}

export function StatusHudBar({
  messages,
  onOptionClick,
}: {
  messages: MessageDto[];
  onOptionClick: (optionText: string) => void;
}) {
  const [expanded, setExpanded] = React.useState(false);
  const hud = React.useMemo(() => findLatestStatusHud(messages), [messages]);
  if (!hud) return null;

  const { extraction } = hud;

  return (
    <div className="rounded-xl border border-border/60 bg-muted/40 px-4 py-3">
      <button
        type="button"
        className="flex w-full items-center justify-between text-sm font-medium text-foreground/80"
        onClick={() => setExpanded((value) => !value)}
      >
        <span>{extraction.headerLine ?? "Status"}</span>
        {expanded ? <ChevronUp className="size-4" /> : <ChevronDown className="size-4" />}
      </button>

      {expanded && (
        <div className="flex flex-col gap-3 pt-3">
          {extraction.sections.map((section, index) => (
            <div key={`${section.title}-${index}`}>
              {section.title && (
                <div className="pb-1 text-xs font-semibold text-foreground/70">{section.title}</div>
              )}
              {section.isHtml ? (
                <HtmlFrame html={section.content} maxHeightPx={300} />
              ) : (
                <pre className="whitespace-pre-wrap text-xs leading-5 text-foreground/80">
                  {section.content}
                </pre>
              )}
            </div>
          ))}

          {extraction.options.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {extraction.options.map((option, index) => (
                <button
                  key={`${option.label}-${index}`}
                  type="button"
                  className="rounded-full border border-border bg-background px-3 py-1 text-xs text-foreground/80 transition-colors hover:bg-accent hover:text-accent-foreground"
                  onClick={() => onOptionClick(option.text)}
                >
                  {option.label ? `[${option.label}] ` : ""}
                  {option.text}
                </button>
              ))}
            </div>
          )}
        </div>
      )}

      {!expanded && extraction.options.length > 0 && (
        <div className="flex flex-wrap gap-2 pt-2">
          {extraction.options.map((option, index) => (
            <button
              key={`${option.label}-${index}`}
              type="button"
              className="rounded-full border border-border bg-background px-3 py-1 text-xs text-foreground/80 transition-colors hover:bg-accent hover:text-accent-foreground"
              onClick={() => onOptionClick(option.text)}
            >
              {option.label ? `[${option.label}] ` : ""}
              {option.text}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
