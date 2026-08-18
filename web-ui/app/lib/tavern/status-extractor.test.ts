import { describe, expect, it } from "vitest";
import { extractStatusBlock } from "./status-extractor";

describe("status-extractor", () => {
  it("extracts status_block with header, details section and options", () => {
    const text = [
      "正文第一段。",
      "<status_block>",
      "『当前状态』",
      "<details><summary>角色</summary>HP 42/50</details>",
      "1. [继续] 推开大门",
      "2. [撤退] 返回营地",
      "</status_block>",
      "正文第二段。",
    ].join("\n");
    const result = extractStatusBlock(text);
    expect(result.cleanedText).toContain("正文第一段");
    expect(result.cleanedText).toContain("正文第二段");
    expect(result.cleanedText).not.toContain("status_block");
    expect(result.headerLine).toBe("『当前状态』");
    expect(result.sections).toHaveLength(1);
    expect(result.sections[0]!.title).toBe("角色");
    expect(result.sections[0]!.content).toContain("HP 42/50");
    expect(result.options).toHaveLength(2);
    expect(result.options[0]).toEqual({ label: "继续", text: "推开大门" });
    expect(result.rawStatusText).toContain("status_block");
  });

  it("strips maintext tags keeping content", () => {
    const result = extractStatusBlock("<maintext>你好</maintext>");
    expect(result.cleanedText).toBe("你好");
    expect(result.rawStatusText).toBeNull();
  });

  it("bare details fallback: consecutive details blocks at end", () => {
    const text = [
      "剧情正文。",
      "<details><summary>A</summary>内容A</details>",
      "<details><summary>B</summary>内容B</details>",
    ].join("\n");
    const result = extractStatusBlock(text);
    expect(result.cleanedText).toBe("剧情正文。");
    expect(result.sections).toHaveLength(2);
    expect(result.sections[0]!.title).toBe("A");
    expect(result.rawStatusText).not.toBeNull();
  });

  it("unclosed open tag extends status region to end of text", () => {
    const result = extractStatusBlock("开头\n<statusbar>\n剩余都是状态");
    expect(result.cleanedText).toBe("开头");
    expect(result.rawStatusText).toContain("剩余都是状态");
  });

  it("returns original text when no status markers", () => {
    const result = extractStatusBlock("普通消息");
    expect(result.cleanedText).toBe("普通消息");
    expect(result.rawStatusText).toBeNull();
    expect(result.sections).toHaveLength(0);
  });

  it("real world sample is fully parsed", () => {
    const narrative = "夜色如墨，山风穿过窗棂。\n\n你盘膝坐在床榻上，掌心握着那只神秘小瓶。";
    const realWorldSample = [
      "<maintext>",
      "夜色如墨，山风穿过窗棂。",
      "",
      "你盘膝坐在床榻上，掌心握着那只神秘小瓶。",
      "</maintext>",
      "<Status_block>",
      "『📅 日期：秦武阳十五年三月 春 | ⏰ 时间：深夜 | 📍 位置：云山/杂役弟子房』",
      "<details><summary>[角色状态]</summary>",
      "```",
      "- 👨 user的状态",
      "  - 👤 身份：云山宗杂役弟子 (底层)",
      "  - 🧘 修为：练气三层 (巅峰)",
      "```",
      "</details>",
      "<details><summary>[在场角色好感]</summary>",
      "```",
      "  - ❤️ 好感度：",
      "    - 顾雪鸢：0 (陌生)",
      "```",
      "</details>",
      "<details><summary>[剧情导航与记忆]</summary>",
      "```",
      "【当前剧情节点】",
      "📌 琴宗篇 - 第二阶段: 阴谋的展开-【云山论剑】（前夕）",
      "",
      "【长期记忆】(0/5)",
      "- (空)",
      "",
      "【短期记忆】(2/5)",
      "- [Old] 你在深夜的后山竹林……",
      "- [New] 你使用神秘小瓶催熟并服下黄精……",
      "```",
      "</details>",
      "『剧情发展』",
      "1. [普通] 稳妥起见，继续用小瓶催生普通草药……",
      "2. [最佳] 冒险潜入宗门的药圃，寻找更高阶的灵草种子……",
      "3. [中等] 利用催熟的大量黄精，想办法在杂役弟子中建立自己的小圈子……",
      "4. [推进] 第二天，利用杂役弟子的身份，主动申请去宗主或胡拳住处附近清扫……",
      "</Status_block>",
    ].join("\n");
    const result = extractStatusBlock(realWorldSample);

    expect(result.cleanedText).toBe(narrative);
    expect(result.headerLine).toBe("『📅 日期：秦武阳十五年三月 春 | ⏰ 时间：深夜 | 📍 位置：云山/杂役弟子房』");

    expect(result.sections).toHaveLength(3);
    expect(result.sections[0]!.title).toBe("[角色状态]");
    expect(result.sections[1]!.title).toBe("[在场角色好感]");
    expect(result.sections[2]!.title).toBe("[剧情导航与记忆]");

    expect(result.sections[0]!.content).toContain("👨 user的状态");
    expect(result.sections[0]!.content).toContain("👤 身份：云山宗杂役弟子 (底层)");
    expect(result.sections[0]!.content).toContain("🧘 修为：练气三层 (巅峰)");
    expect(result.sections[0]!.content).not.toContain("```");
    expect(result.sections[2]!.content).toContain("【当前剧情节点】");
    expect(result.sections[2]!.content).toContain("【长期记忆】(0/5)");
    expect(result.sections[2]!.content).toContain("【短期记忆】(2/5)");
    expect(result.sections.every((s) => !s.isHtml)).toBe(true);

    expect(result.options).toHaveLength(4);
    expect(result.options.map((o) => o.label)).toEqual(["普通", "最佳", "中等", "推进"]);
    expect(result.options[0]!.text.startsWith("稳妥起见")).toBe(true);
    expect(result.options[1]!.text.startsWith("冒险潜入")).toBe(true);
    expect(result.options[2]!.text.startsWith("利用催熟")).toBe(true);
    expect(result.options[3]!.text.startsWith("第二天")).toBe(true);

    expect(result.rawStatusText).not.toBeNull();
    expect(result.rawStatusText).toContain("<Status_block>");
    expect(result.rawStatusText).toContain("</Status_block>");
  });

  it("empty string is safe", () => {
    const result = extractStatusBlock("");

    expect(result.cleanedText).toBe("");
    expect(result.rawStatusText).toBeNull();
    expect(result.headerLine).toBeNull();
    expect(result.sections).toHaveLength(0);
    expect(result.options).toHaveLength(0);
  });

  it("maintext tag stripping is case insensitive and works with open tag only", () => {
    expect(extractStatusBlock("<MAINtext>正文").cleanedText).toBe("正文");
    expect(extractStatusBlock("<MainText>\n正文\n</MainText>").cleanedText.trim()).toBe("正文");
  });

  it("unclosed status block extends to end of text", () => {
    const input = "前文叙事。\n<Status_block>\n『头部』\n<details><summary>[S]</summary>\nbody line\n</details>";
    const result = extractStatusBlock(input);

    expect(result.cleanedText).toBe("前文叙事。");
    expect(result.headerLine).toBe("『头部』");
    expect(result.sections).toHaveLength(1);
    expect(result.sections[0]!.title).toBe("[S]");
    expect(result.sections[0]!.content).toBe("body line");
    expect(result.rawStatusText).not.toBeNull();
  });

  it("status tag variant is recognized", () => {
    const result = extractStatusBlock("正文\n<status>『H』\n1. 选项甲</status>");

    expect(result.cleanedText).toBe("正文");
    expect(result.headerLine).toBe("『H』");
    expect(result.options).toHaveLength(1);
    expect(result.options[0]!.label).toBe("");
    expect(result.options[0]!.text).toBe("选项甲");
    expect(result.rawStatusText).not.toBeNull();
  });

  it("status block matching is case insensitive", () => {
    const result = extractStatusBlock("正文\n<STATUS_BLOCK>『H』</STATUS_BLOCK>");

    expect(result.cleanedText).toBe("正文");
    expect(result.headerLine).toBe("『H』");
    expect(result.rawStatusText).not.toBeNull();
  });

  it("status block only without narrative yields empty cleaned text", () => {
    const result = extractStatusBlock("<status_block>『H』</status_block>");

    expect(result.cleanedText).toBe("");
    expect(result.headerLine).toBe("『H』");
    expect(result.rawStatusText).not.toBeNull();
  });

  it("options without bracket label have empty label", () => {
    const input = "<status_block>\n1、加速修炼\n2) 直接离开\n</status_block>";
    const result = extractStatusBlock(input);

    expect(result.options).toHaveLength(2);
    expect(result.options[0]!.label).toBe("");
    expect(result.options[0]!.text).toBe("加速修炼");
    expect(result.options[1]!.label).toBe("");
    expect(result.options[1]!.text).toBe("直接离开");
  });

  it("multiple status regions are all processed", () => {
    const input = "甲\n<status_block>『一』</status_block>乙\n<status>『二』\n1. 选A</status>丙";
    const result = extractStatusBlock(input);

    expect(result.cleanedText).toBe("甲\n乙\n丙");
    expect(result.headerLine).toBe("『一』");
    expect(result.options).toHaveLength(1);
    expect(result.options[0]!.text).toBe("选A");
    expect(result.rawStatusText).toContain("<status_block>");
    expect(result.rawStatusText).toContain("<status>");
  });

  it("isHtml is true only for tags other than details summary br", () => {
    const withFont = extractStatusBlock(
      "<status_block><details><summary>状态</summary><font color=\"red\">HP 100</font></details></status_block>",
    );
    expect(withFont.sections[0]!.isHtml).toBe(true);

    const onlyBr = extractStatusBlock(
      "<status_block><details><summary>状态</summary>HP 100<br>MP 50</details></status_block>",
    );
    expect(onlyBr.sections[0]!.isHtml).toBe(false);
  });

  it("extraction is idempotent", () => {
    const sample = [
      "<maintext>",
      "夜色如墨，山风穿过窗棂。",
      "",
      "你盘膝坐在床榻上，掌心握着那只神秘小瓶。",
      "</maintext>",
      "<Status_block>",
      "『📅 日期：秦武阳十五年三月 春』",
      "<details><summary>[角色状态]</summary>",
      "```",
      "👨 user的状态",
      "```",
      "</details>",
      "1. [普通] 稳妥起见，继续用小瓶催生普通草药……",
      "</Status_block>",
    ].join("\n");
    const once = extractStatusBlock(sample);
    const twice = extractStatusBlock(once.cleanedText);

    expect(twice.cleanedText).toBe(once.cleanedText);
    expect(twice.rawStatusText).toBeNull();
    expect(twice.headerLine).toBeNull();
    expect(twice.sections).toHaveLength(0);
    expect(twice.options).toHaveLength(0);
  });

  it("statusbar tag variant is recognized", () => {
    const result = extractStatusBlock(
      "正文\n<statusbar>『H』\n<details><summary>[S]</summary>body</details></statusbar>",
    );

    expect(result.cleanedText).toBe("正文");
    expect(result.headerLine).toBe("『H』");
    expect(result.sections).toHaveLength(1);
    expect(result.sections[0]!.title).toBe("[S]");
    expect(result.sections[0]!.content).toBe("body");
    expect(result.rawStatusText).not.toBeNull();
  });

  it("statusblock camelCase tag variant is recognized", () => {
    const result = extractStatusBlock("正文\n<StatusBlock>『H』\n1. 选A</StatusBlock>");

    expect(result.cleanedText).toBe("正文");
    expect(result.headerLine).toBe("『H』");
    expect(result.options).toHaveLength(1);
    expect(result.options[0]!.text).toBe("选A");
    expect(result.rawStatusText).not.toBeNull();
  });

  it("chinese status tag variant is recognized", () => {
    const result = extractStatusBlock(
      "正文\n<状态栏>『H』\n<details><summary>[S]</summary>body</details></状态栏>",
    );

    expect(result.cleanedText).toBe("正文");
    expect(result.headerLine).toBe("『H』");
    expect(result.sections).toHaveLength(1);
    expect(result.sections[0]!.title).toBe("[S]");
    expect(result.sections[0]!.content).toBe("body");
    expect(result.rawStatusText).not.toBeNull();
  });

  it("statusbar matching is case insensitive", () => {
    const result = extractStatusBlock("正文\n<STATUSBAR>『H』</STATUSBAR>");

    expect(result.cleanedText).toBe("正文");
    expect(result.headerLine).toBe("『H』");
    expect(result.rawStatusText).not.toBeNull();
  });

  it("unclosed new tag variant extends to end of text", () => {
    const result = extractStatusBlock("前文。\n<StatusBlock>\n<details><summary>[S]</summary>body</details>");

    expect(result.cleanedText).toBe("前文。");
    expect(result.sections).toHaveLength(1);
    expect(result.sections[0]!.title).toBe("[S]");
    expect(result.sections[0]!.content).toBe("body");
    expect(result.rawStatusText).not.toBeNull();
  });

  it("legacy status exclamation tag is now recognized by extractor", () => {
    const result = extractStatusBlock("正文\n<status!>『H』</status!>");

    expect(result.cleanedText).toBe("正文");
    expect(result.headerLine).toBe("『H』");
    expect(result.rawStatusText).not.toBeNull();
  });

  it("statusbar does not shadow plain status prefix", () => {
    const result = extractStatusBlock("正文\n<status>『H』</status>");

    expect(result.cleanedText).toBe("正文");
    expect(result.headerLine).toBe("『H』");
    expect(result.rawStatusText).not.toBeNull();
  });

  it("two consecutive bare details blocks are recognized as status region", () => {
    const input =
      "正文叙事。\n" +
      "<details><summary>[A]</summary>body A</details>\n\n" +
      "<details><summary>[B]</summary>body B</details>";
    const result = extractStatusBlock(input);

    expect(result.cleanedText).toBe("正文叙事。");
    expect(result.sections).toHaveLength(2);
    expect(result.sections[0]!.title).toBe("[A]");
    expect(result.sections[0]!.content).toBe("body A");
    expect(result.sections[1]!.title).toBe("[B]");
    expect(result.sections[1]!.content).toBe("body B");
    expect(result.headerLine).toBeNull();
    expect(result.options).toHaveLength(0);
    expect(result.rawStatusText).not.toBeNull();
    expect(result.rawStatusText).toContain("<details>");
  });

  it("single trailing bare details block is recognized", () => {
    const input = "正文叙事。\n\n<details><summary>[状态]</summary>\n```\nHP 100\n```\n</details>\n";
    const result = extractStatusBlock(input);

    expect(result.cleanedText).toBe("正文叙事。");
    expect(result.sections).toHaveLength(1);
    expect(result.sections[0]!.title).toBe("[状态]");
    expect(result.sections[0]!.content).toBe("HP 100");
    expect(result.headerLine).toBeNull();
    expect(result.rawStatusText).not.toBeNull();
  });

  it("single mid-text bare details block is not captured", () => {
    const input = "前文。\n<details><summary>[S]</summary>body</details>\n后文。";
    const result = extractStatusBlock(input);

    expect(result.cleanedText).toBe(input);
    expect(result.rawStatusText).toBeNull();
    expect(result.headerLine).toBeNull();
    expect(result.sections).toHaveLength(0);
    expect(result.options).toHaveLength(0);
  });

  it("details blocks separated by non-whitespace text are not captured as one run", () => {
    const input =
      "前文。\n" +
      "<details><summary>[A]</summary>body A</details>\n" +
      "中间叙述。\n" +
      "<details><summary>[B]</summary>body B</details>\n" +
      "后文。";
    const result = extractStatusBlock(input);

    expect(result.cleanedText).toBe(input);
    expect(result.rawStatusText).toBeNull();
    expect(result.sections).toHaveLength(0);
  });

  it("tagged status block wins over bare details fallback", () => {
    const input =
      "正文\n" +
      "<status_block>『H』\n<details><summary>[A]</summary>body A</details></status_block>\n" +
      "<details><summary>[B]</summary>body B</details>";
    const result = extractStatusBlock(input);

    expect(result.headerLine).toBe("『H』");
    expect(result.sections).toHaveLength(1);
    expect(result.sections[0]!.title).toBe("[A]");
    expect(result.cleanedText.startsWith("正文")).toBe(true);
    expect(result.cleanedText).toContain("<details><summary>[B]</summary>body B</details>");
    expect(result.rawStatusText).not.toBeNull();
  });

  it("remaining plain text outside details merges into one untitled section", () => {
    const input = [
      "<status_block>",
      "<details><summary>A</summary>body A</details>",
      "这里是剩余描述文字",
      "还有第二行",
      "</status_block>",
    ].join("\n");
    const result = extractStatusBlock(input);

    expect(result.sections).toHaveLength(2);
    expect(result.sections[0]!.title).toBe("A");
    expect(result.sections[1]!.title).toBe("");
    expect(result.sections[1]!.content).toBe("这里是剩余描述文字\n还有第二行");
    expect(result.sections[1]!.isHtml).toBe(false);
  });

  it("corner title line right before option block is consumed with options", () => {
    const input = [
      "<status_block>",
      "『日期：某日』",
      "<details><summary>状态</summary>HP 42</details>",
      "『剧情发展』",
      "1. 选项A",
      "</status_block>",
    ].join("\n");
    const result = extractStatusBlock(input);

    expect(result.headerLine).toBe("『日期：某日』");
    expect(result.sections).toHaveLength(1);
    expect(result.sections[0]!.title).toBe("状态");
    expect(result.sections[0]!.content).not.toContain("『剧情发展』");
    expect(result.options).toHaveLength(1);
    expect(result.options[0]!.text).toBe("选项A");
  });
});
