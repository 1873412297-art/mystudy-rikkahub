import Markdown from "~/components/markdown/markdown";
import { HtmlFrame } from "~/components/tavern/html-frame";
import { extractStatusBlock } from "~/lib/tavern/status-extractor";

interface TextPartProps {
  text: string;
  renderMode?: "markdown" | "html";
  isAnimating?: boolean;
  onClickCitation?: (id: string) => void;
}

export function TextPart({ text, renderMode, isAnimating, onClickCitation }: TextPartProps) {
  if (!text) return null;
  if (renderMode === "html") {
    return (
      <div data-part="text">
        <HtmlFrame html={text} maxHeightPx={560} />
      </div>
    );
  }
  // 状态块由 HUD 展示：气泡文本剥离状态区域与 maintext 标签（对齐 Android ChatMessage.kt）
  const cleaned = extractStatusBlock(text).cleanedText;
  if (!cleaned) return null;
  return (
    <div data-part="text">
      <Markdown content={cleaned} isAnimating={isAnimating} onClickCitation={onClickCitation} />
    </div>
  );
}
