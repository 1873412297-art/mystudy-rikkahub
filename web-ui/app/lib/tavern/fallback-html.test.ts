import { describe, expect, it } from "vitest";
import { buildFallbackHtml, escapeHtml } from "./fallback-html";

describe("fallback-html", () => {
  it("escapes html in keys and values", () => {
    expect(escapeHtml("<a>&</a>")).toBe("&lt;a&gt;&amp;&lt;/a&gt;");
  });

  it("renders expression header when present", () => {
    const html = buildFallbackHtml({ hp: 42 }, { expression: "战斗" });
    expect(html).toContain("战斗");
    expect(html).toContain("hp");
    expect(html).toContain("42");
  });

  it("escapes variable values against injection", () => {
    const html = buildFallbackHtml({ evil: "<script>alert(1)</script>" }, {});
    expect(html).not.toContain("<script>");
    expect(html).toContain("&lt;script&gt;");
  });

  it("renders nested map and list values", () => {
    const html = buildFallbackHtml(
      { char: { name: "A", tags: ["x", "y"] }, count: 3 },
      {},
    );
    expect(html).toContain("char");
    expect(html).toContain("name");
    expect(html).toContain("x, y");
  });
});
