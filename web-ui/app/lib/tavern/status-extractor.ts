import { STATUS_TAG_NAMES } from "./status-tags";

/** 状态区域中的一个分节（对应 `<details><summary>T</summary>body</details>`，或未被 details 包裹的剩余成段文字——此时 title 为空串）。 */
export interface StatusSection {
  title: string;
  content: string;
  isHtml: boolean;
}

/** 状态区域末尾的编号选项（如 `1. [最佳] 冒险潜入……`）。 */
export interface StatusOption {
  label: string;
  text: string;
}

/** 一次状态块提取的结果。 */
export interface StatusExtraction {
  cleanedText: string;
  headerLine: string | null;
  sections: StatusSection[];
  options: StatusOption[];
  rawStatusText: string | null;
}

const maintextTagRegex = /<\/?\s*maintext\s*>/gi;
const detailsRegex = /<\s*details\s*>\s*<\s*summary\s*>(.*?)<\/\s*summary\s*>(.*?)<\/\s*details\s*>/gis;
const optionLineRegex = /^\s*(\d+)\s*[.、）)]\s*(?:\[([^\]]+)])?\s*(.+)$/;
const cornerTitleLineRegex = /^\s*『.*』\s*$/;
const fenceLineRegex = /^\s*```[A-Za-z0-9_-]*\s*$/;
const allowedHtmlRegex = /<\/?\s*(details|summary|br)\b[^>]*>/gi;
const htmlTagRegex = /<\s*\/?\s*[A-Za-z][^>]*>/;
const multiBlankLinesRegex = /\n[ \t]*(?:\n[ \t]*)+/g;

function findAll(regex: RegExp, input: string): RegExpExecArray[] {
  const matches: RegExpExecArray[] = [];
  let match = regex.exec(input);
  while (match !== null) {
    matches.push(match);
    match = regex.exec(input);
  }
  return matches;
}

function stripFenceLines(body: string): string {
  return body
    .split("\n")
    .filter((line) => !fenceLineRegex.test(line))
    .join("\n")
    .trim();
}

function containsHtml(content: string): boolean {
  return htmlTagRegex.test(content.replace(allowedHtmlRegex, ""));
}

export function extractStatusBlock(text: string): StatusExtraction {
  if (text.length === 0) {
    return { cleanedText: "", headerLine: null, sections: [], options: [], rawStatusText: null };
  }
  const input = text.replace(/\r\n/g, "\n").replace(/\r/g, "\n");

  // 1. 定位所有状态区域（未闭合的延伸到文末）。
  const spans: Array<{ start: number; end: number }> = [];
  const contents: string[] = [];
  let searchFrom = 0;
  while (searchFrom < input.length) {
    const openRe = new RegExp(`<\\s*(?:${STATUS_TAG_NAMES})\\s*>`, "gi");
    openRe.lastIndex = searchFrom;
    const open = openRe.exec(input);
    if (open === null) break;
    const openEnd = open.index + open[0].length;
    const closeRe = new RegExp(`</\\s*(?:${STATUS_TAG_NAMES})\\s*>`, "gi");
    closeRe.lastIndex = openEnd;
    const close = closeRe.exec(input);
    const contentEnd = close === null ? input.length : close.index;
    const spanEnd = close === null ? input.length : close.index + close[0].length;
    spans.push({ start: open.index, end: spanEnd });
    contents.push(input.slice(openEnd, contentEnd));
    searchFrom = spanEnd;
  }

  if (spans.length === 0) {
    return (
      extractBareDetailsFallback(input) ?? {
        cleanedText: input.replace(maintextTagRegex, ""),
        headerLine: null,
        sections: [],
        options: [],
        rawStatusText: null,
      }
    );
  }

  // 2. cleanedText：移除状态区域 + 剥 maintext 标签 + trim + 压缩空行。
  let narrative = "";
  let cursor = 0;
  for (const span of spans) {
    narrative += input.slice(cursor, span.start);
    cursor = span.end;
  }
  narrative += input.slice(cursor);
  const cleanedText = narrative
    .replace(maintextTagRegex, "")
    .replace(multiBlankLinesRegex, "\n\n")
    .trim();

  // 3. rawStatusText。
  const rawStatusText = spans.map((span) => input.slice(span.start, span.end)).join("\n");

  // 4. 逐区域解析（跨区域保持文档顺序）。
  const sections: StatusSection[] = [];
  const options: StatusOption[] = [];
  let headerLine: string | null = null;
  for (const content of contents) {
    headerLine = parseRegion(content, sections, options, headerLine);
  }

  return { cleanedText, headerLine, sections, options, rawStatusText };
}

function extractBareDetailsFallback(input: string): StatusExtraction | null {
  const matches = findAll(detailsRegex, input);
  if (matches.length === 0) return null;

  const runs: RegExpExecArray[][] = [];
  let previousEnd = -1;
  for (const match of matches) {
    const matchStart = match.index;
    if (runs.length === 0 || input.slice(previousEnd, matchStart).trim() !== "") {
      runs.push([match]);
    } else {
      runs[runs.length - 1]!.push(match);
    }
    previousEnd = matchStart + match[0].length;
  }

  const run = [...runs]
    .reverse()
    .find((candidates) => {
      const last = candidates[candidates.length - 1]!;
      return candidates.length >= 2 || input.slice(last.index + last[0].length).trim() === "";
    });
  if (run === undefined) return null;

  const runStart = run[0]!.index;
  const runEnd = run[run.length - 1]!.index + run[run.length - 1]![0].length;

  const narrative = input.slice(0, runStart) + input.slice(runEnd);
  const cleanedText = narrative
    .replace(maintextTagRegex, "")
    .replace(multiBlankLinesRegex, "\n\n")
    .trim();

  const sections = run.map((match) => {
    const title = (match[1] ?? "").trim();
    const body = stripFenceLines(match[2] ?? "");
    return { title, content: body, isHtml: containsHtml(body) };
  });

  return {
    cleanedText,
    headerLine: null,
    sections,
    options: [],
    rawStatusText: input.slice(runStart, runEnd),
  };
}

function parseRegion(
  content: string,
  sections: StatusSection[],
  options: StatusOption[],
  headerLine: string | null,
): string | null {
  let header = headerLine;
  let cursor = 0;
  for (const match of findAll(detailsRegex, content)) {
    header = processPlainSegment(content.slice(cursor, match.index), sections, options, header);
    const title = (match[1] ?? "").trim();
    const body = stripFenceLines(match[2] ?? "");
    sections.push({ title, content: body, isHtml: containsHtml(body) });
    cursor = match.index + match[0].length;
  }
  header = processPlainSegment(content.slice(cursor), sections, options, header);
  return header;
}

function processPlainSegment(
  segment: string,
  sections: StatusSection[],
  options: StatusOption[],
  headerLine: string | null,
): string | null {
  let header = headerLine;
  const lines = segment.split("\n");
  const consumed = new Array<boolean>(lines.length).fill(false);

  if (header === null) {
    const idx = lines.findIndex((line) => cornerTitleLineRegex.test(line));
    if (idx >= 0) {
      header = lines[idx]!.trim();
      consumed[idx] = true;
    }
  }

  let i = 0;
  while (i < lines.length) {
    if (!optionLineRegex.test(lines[i] ?? "")) {
      i += 1;
      continue;
    }
    if (i > 0 && !consumed[i - 1] && cornerTitleLineRegex.test(lines[i - 1] ?? "")) {
      consumed[i - 1] = true;
    }
    while (i < lines.length) {
      const m = optionLineRegex.exec(lines[i] ?? "");
      if (m === null) break;
      consumed[i] = true;
      options.push({ label: (m[2] ?? "").trim(), text: (m[3] ?? "").trim() });
      i += 1;
    }
  }

  const rest = lines
    .filter((_, index) => !consumed[index])
    .join("\n")
    .trim();
  if (rest.length > 0) {
    sections.push({ title: "", content: rest, isHtml: containsHtml(rest) });
  }
  return header;
}
