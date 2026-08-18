import { describe, expect, it } from "vitest";
import { closeTagRegex, openTagRegex, segmentRegex, wrapperRegex } from "./status-tags";

describe("status-tags", () => {
  it("matches all tag family variants (case-insensitive)", () => {
    const variants = ["status_block", "statusblock", "statusbar", "status", "status!", "状态栏"];
    for (const v of variants) {
      expect(openTagRegex().test(`<${v}>`)).toBe(true);
      expect(closeTagRegex().test(`</${v}>`)).toBe(true);
    }
  });

  it("matches tags with inner whitespace", () => {
    expect(openTagRegex().test("< status_block >")).toBe(true);
    expect(openTagRegex().test("<STATUS_BLOCK>")).toBe(true);
  });

  it("segmentRegex extends to end of text when close tag missing", () => {
    const m = segmentRegex().exec("<status_block>hello world");
    expect(m).not.toBeNull();
    expect(m![0]).toContain("hello world");
  });

  it("wrapperRegex extracts inner text", () => {
    const m = wrapperRegex().exec("<statusbar>\nline\n</statusbar>");
    expect(m).not.toBeNull();
    expect(m![1]).toContain("line");
  });

  it("segmentRegex matches block up to closing tag", () => {
    const m = segmentRegex().exec("<status_block>甲</status_block>乙");
    expect(m).not.toBeNull();
    expect(m![0]).toBe("<status_block>甲</status_block>");
  });

  it("wrapperRegex accepts unclosed block at end", () => {
    const m = wrapperRegex().exec("<status_block>正文");
    expect(m).not.toBeNull();
    expect(m![1]).toBe("正文");
  });

  it("wrapperRegex strips leading whitespace after open tag", () => {
    const m = wrapperRegex().exec("<status_block>\n  正文内容\n</status_block>");
    expect(m).not.toBeNull();
    expect(m![1]).toBe("正文内容\n");
  });
});
