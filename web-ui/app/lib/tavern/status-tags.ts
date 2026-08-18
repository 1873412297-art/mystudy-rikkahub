/**
 * 状态块标签族的单一事实来源（与 Kotlin StatusTags 对齐）。
 * 标签族（大小写不敏感）：status_block / statusblock / statusbar / status / status! / 状态栏。
 */
export const STATUS_TAG_NAMES = "status_block|statusblock|statusbar|status!?|状态栏";

/** 开标签：`<status_block>` 等，标签名两侧允许空白。 */
export function openTagRegex(): RegExp {
  return new RegExp(`<\\s*(?:${STATUS_TAG_NAMES})\\s*>`, "i");
}

/** 闭标签：`</status_block>` 等。 */
export function closeTagRegex(): RegExp {
  return new RegExp(`</\\s*(?:${STATUS_TAG_NAMES})\\s*>`, "i");
}

/** 段落级：从开标签开始匹配，缺失闭标签时延伸到文本末尾。 */
export function segmentRegex(): RegExp {
  return new RegExp(`<(?:${STATUS_TAG_NAMES})>[\\s\\S]*?(?:</(?:${STATUS_TAG_NAMES})>|$)`, "i");
}

/** 整块包裹：整段内容恰好由一个状态块包裹（用于提取展示文本）。 */
export function wrapperRegex(): RegExp {
  return new RegExp(
    `^\\s*<(?:${STATUS_TAG_NAMES})>\\s*([\\s\\S]*?)(?:</(?:${STATUS_TAG_NAMES})>\\s*)?$`,
    "i",
  );
}
