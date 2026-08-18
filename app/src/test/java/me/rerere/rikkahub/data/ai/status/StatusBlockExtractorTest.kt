package me.rerere.rikkahub.data.ai.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StatusBlockExtractor] 的单元测试。
 *
 * 状态块是模型在正文之后输出的元信息区域（日期/角色状态/好感/记忆/选项），
 * UI 层需要把正文与状态区域分离，并把状态区域结构化为 header / sections / options。
 */
class StatusBlockExtractorTest {

    private val narrative = "夜色如墨，山风穿过窗棂。\n\n你盘膝坐在床榻上，掌心握着那只神秘小瓶。"

    /** 线上真实消息样例（情节内容为虚构占位，解析器不得依赖任何具体词）。 */
    private val realWorldSample = """
<maintext>
夜色如墨，山风穿过窗棂。

你盘膝坐在床榻上，掌心握着那只神秘小瓶。
</maintext>
<Status_block>
『📅 日期：秦武阳十五年三月 春 | ⏰ 时间：深夜 | 📍 位置：云山/杂役弟子房』
<details><summary>[角色状态]</summary>
```
- 👨 user的状态
  - 👤 身份：云山宗杂役弟子 (底层)
  - 🧘 修为：练气三层 (巅峰)
```
</details>
<details><summary>[在场角色好感]</summary>
```
  - ❤️ 好感度：
    - 顾雪鸢：0 (陌生)
```
</details>
<details><summary>[剧情导航与记忆]</summary>
```
【当前剧情节点】
📌 琴宗篇 - 第二阶段: 阴谋的展开-【云山论剑】（前夕）

【长期记忆】(0/5)
- (空)

【短期记忆】(2/5)
- [Old] 你在深夜的后山竹林……
- [New] 你使用神秘小瓶催熟并服下黄精……
```
</details>
『剧情发展』
1. [普通] 稳妥起见，继续用小瓶催生普通草药……
2. [最佳] 冒险潜入宗门的药圃，寻找更高阶的灵草种子……
3. [中等] 利用催熟的大量黄精，想办法在杂役弟子中建立自己的小圈子……
4. [推进] 第二天，利用杂役弟子的身份，主动申请去宗主或胡拳住处附近清扫……
</Status_block>
""".trimIndent()

    @Test
    fun `real world sample is fully parsed`() {
        val result = StatusBlockExtractor.extract(realWorldSample)

        // 正文：剥掉 maintext 标签、移除状态区域后只剩叙事
        assertEquals(narrative, result.cleanedText)

        // 首个 『…』 行是 headerLine
        assertEquals("『📅 日期：秦武阳十五年三月 春 | ⏰ 时间：深夜 | 📍 位置：云山/杂役弟子房』", result.headerLine)

        // 3 个 details 分节
        assertEquals(3, result.sections.size)
        assertEquals("[角色状态]", result.sections[0].title)
        assertEquals("[在场角色好感]", result.sections[1].title)
        assertEquals("[剧情导航与记忆]", result.sections[2].title)

        // 分节内容去掉 ``` 围栏，内部结构保留
        assertTrue(result.sections[0].content.contains("👨 user的状态"))
        assertTrue(result.sections[0].content.contains("👤 身份：云山宗杂役弟子 (底层)"))
        assertTrue(result.sections[0].content.contains("🧘 修为：练气三层 (巅峰)"))
        assertFalse(result.sections[0].content.contains("```"))
        assertTrue(result.sections[2].content.contains("【当前剧情节点】"))
        assertTrue(result.sections[2].content.contains("【长期记忆】(0/5)"))
        assertTrue(result.sections[2].content.contains("【短期记忆】(2/5)"))
        assertTrue(result.sections.all { !it.isHtml })

        // 4 个编号选项，label 来自 [标签]
        assertEquals(4, result.options.size)
        assertEquals(listOf("普通", "最佳", "中等", "推进"), result.options.map { it.label })
        assertTrue(result.options[0].text.startsWith("稳妥起见"))
        assertTrue(result.options[1].text.startsWith("冒险潜入"))
        assertTrue(result.options[2].text.startsWith("利用催熟"))
        assertTrue(result.options[3].text.startsWith("第二天"))

        // rawStatusText 非空且包含原始标签
        assertNotNull(result.rawStatusText)
        assertTrue(result.rawStatusText!!.contains("<Status_block>"))
        assertTrue(result.rawStatusText!!.contains("</Status_block>"))
    }

    @Test
    fun `plain text without any markers is returned as-is`() {
        val text = "就是一段普通正文。\n\n没有任何标记。"
        val result = StatusBlockExtractor.extract(text)

        assertEquals(text, result.cleanedText)
        assertNull(result.rawStatusText)
        assertNull(result.headerLine)
        assertTrue(result.sections.isEmpty())
        assertTrue(result.options.isEmpty())
    }

    @Test
    fun `maintext tags are stripped while content is kept`() {
        val result = StatusBlockExtractor.extract("<maintext>正文内容</maintext>")
        assertEquals("正文内容", result.cleanedText)
        assertNull(result.rawStatusText)
    }

    @Test
    fun `maintext tag stripping is case insensitive and works with open tag only`() {
        assertEquals("正文", StatusBlockExtractor.extract("<MAINtext>正文").cleanedText)
        assertEquals("正文", StatusBlockExtractor.extract("<MainText>\n正文\n</MainText>").cleanedText.trim())
    }

    @Test
    fun `unclosed status block extends to end of text`() {
        val input = "前文叙事。\n<Status_block>\n『头部』\n<details><summary>[S]</summary>\nbody line\n</details>"
        val result = StatusBlockExtractor.extract(input)

        assertEquals("前文叙事。", result.cleanedText)
        assertEquals("『头部』", result.headerLine)
        assertEquals(1, result.sections.size)
        assertEquals("[S]", result.sections[0].title)
        assertEquals("body line", result.sections[0].content)
        assertNotNull(result.rawStatusText)
    }

    @Test
    fun `status tag variant is recognized`() {
        val result = StatusBlockExtractor.extract("正文\n<status>『H』\n1. 选项甲</status>")

        assertEquals("正文", result.cleanedText)
        assertEquals("『H』", result.headerLine)
        assertEquals(1, result.options.size)
        assertEquals("", result.options[0].label)
        assertEquals("选项甲", result.options[0].text)
        assertNotNull(result.rawStatusText)
    }

    @Test
    fun `status block matching is case insensitive`() {
        val result = StatusBlockExtractor.extract("正文\n<STATUS_BLOCK>『H』</STATUS_BLOCK>")

        assertEquals("正文", result.cleanedText)
        assertEquals("『H』", result.headerLine)
        assertNotNull(result.rawStatusText)
    }

    @Test
    fun `empty string is safe`() {
        val result = StatusBlockExtractor.extract("")

        assertEquals("", result.cleanedText)
        assertNull(result.rawStatusText)
        assertNull(result.headerLine)
        assertTrue(result.sections.isEmpty())
        assertTrue(result.options.isEmpty())
    }

    @Test
    fun `status block only without narrative yields empty cleaned text`() {
        val result = StatusBlockExtractor.extract("<status_block>『H』</status_block>")

        assertEquals("", result.cleanedText)
        assertEquals("『H』", result.headerLine)
        assertNotNull(result.rawStatusText)
    }

    @Test
    fun `options without bracket label have empty label`() {
        val input = "<status_block>\n1、加速修炼\n2) 直接离开\n</status_block>"
        val result = StatusBlockExtractor.extract(input)

        assertEquals(2, result.options.size)
        assertEquals("", result.options[0].label)
        assertEquals("加速修炼", result.options[0].text)
        assertEquals("", result.options[1].label)
        assertEquals("直接离开", result.options[1].text)
    }

    @Test
    fun `multiple status regions are all processed`() {
        val input = "甲\n<status_block>『一』</status_block>乙\n<status>『二』\n1. 选A</status>丙"
        val result = StatusBlockExtractor.extract(input)

        assertEquals("甲\n乙\n丙", result.cleanedText)
        // headerLine 只取第一个 『』 行
        assertEquals("『一』", result.headerLine)
        assertEquals(1, result.options.size)
        assertEquals("选A", result.options[0].text)
        assertTrue(result.rawStatusText!!.contains("<status_block>"))
        assertTrue(result.rawStatusText!!.contains("<status>"))
    }

    @Test
    fun `isHtml is true only for tags other than details summary br`() {
        val withFont = StatusBlockExtractor.extract(
            "<status_block><details><summary>状态</summary><font color=\"red\">HP 100</font></details></status_block>"
        )
        assertTrue(withFont.sections[0].isHtml)

        val onlyBr = StatusBlockExtractor.extract(
            "<status_block><details><summary>状态</summary>HP 100<br>MP 50</details></status_block>"
        )
        assertFalse(onlyBr.sections[0].isHtml)
    }

    @Test
    fun `extraction is idempotent`() {
        val once = StatusBlockExtractor.extract(realWorldSample)
        val twice = StatusBlockExtractor.extract(once.cleanedText)

        assertEquals(once.cleanedText, twice.cleanedText)
        assertNull(twice.rawStatusText)
        assertNull(twice.headerLine)
        assertTrue(twice.sections.isEmpty())
        assertTrue(twice.options.isEmpty())
    }

    @Test
    fun `statusbar tag variant is recognized`() {
        val result = StatusBlockExtractor.extract(
            "正文\n<statusbar>『H』\n<details><summary>[S]</summary>body</details></statusbar>"
        )

        assertEquals("正文", result.cleanedText)
        assertEquals("『H』", result.headerLine)
        assertEquals(1, result.sections.size)
        assertEquals("[S]", result.sections[0].title)
        assertEquals("body", result.sections[0].content)
        assertNotNull(result.rawStatusText)
    }

    @Test
    fun `statusblock camelCase tag variant is recognized`() {
        val result = StatusBlockExtractor.extract("正文\n<StatusBlock>『H』\n1. 选A</StatusBlock>")

        assertEquals("正文", result.cleanedText)
        assertEquals("『H』", result.headerLine)
        assertEquals(1, result.options.size)
        assertEquals("选A", result.options[0].text)
        assertNotNull(result.rawStatusText)
    }

    @Test
    fun `chinese status tag variant is recognized`() {
        val result = StatusBlockExtractor.extract(
            "正文\n<状态栏>『H』\n<details><summary>[S]</summary>body</details></状态栏>"
        )

        assertEquals("正文", result.cleanedText)
        assertEquals("『H』", result.headerLine)
        assertEquals(1, result.sections.size)
        assertEquals("[S]", result.sections[0].title)
        assertEquals("body", result.sections[0].content)
        assertNotNull(result.rawStatusText)
    }

    @Test
    fun `statusbar matching is case insensitive`() {
        val result = StatusBlockExtractor.extract("正文\n<STATUSBAR>『H』</STATUSBAR>")

        assertEquals("正文", result.cleanedText)
        assertEquals("『H』", result.headerLine)
        assertNotNull(result.rawStatusText)
    }

    @Test
    fun `unclosed new tag variant extends to end of text`() {
        val result = StatusBlockExtractor.extract(
            "前文。\n<StatusBlock>\n<details><summary>[S]</summary>body</details>"
        )

        assertEquals("前文。", result.cleanedText)
        assertEquals(1, result.sections.size)
        assertEquals("[S]", result.sections[0].title)
        assertEquals("body", result.sections[0].content)
        assertNotNull(result.rawStatusText)
    }

    @Test
    fun `legacy status exclamation tag is now recognized by extractor`() {
        val result = StatusBlockExtractor.extract("正文\n<status!>『H』</status!>")

        assertEquals("正文", result.cleanedText)
        assertEquals("『H』", result.headerLine)
        assertNotNull(result.rawStatusText)
    }

    @Test
    fun `statusbar does not shadow plain status prefix`() {
        // `<status>` 仍需正常工作（statusbar/statusblock 分支不应干扰 status!? 分支）。
        val result = StatusBlockExtractor.extract("正文\n<status>『H』</status>")

        assertEquals("正文", result.cleanedText)
        assertEquals("『H』", result.headerLine)
        assertNotNull(result.rawStatusText)
    }

    @Test
    fun `two consecutive bare details blocks are recognized as status region`() {
        val input = "正文叙事。\n" +
            "<details><summary>[A]</summary>body A</details>\n\n" +
            "<details><summary>[B]</summary>body B</details>"
        val result = StatusBlockExtractor.extract(input)

        assertEquals("正文叙事。", result.cleanedText)
        assertEquals(2, result.sections.size)
        assertEquals("[A]", result.sections[0].title)
        assertEquals("body A", result.sections[0].content)
        assertEquals("[B]", result.sections[1].title)
        assertEquals("body B", result.sections[1].content)
        // 兜底路径保守：不抽 headerLine / options
        assertNull(result.headerLine)
        assertTrue(result.options.isEmpty())
        assertNotNull(result.rawStatusText)
        assertTrue(result.rawStatusText!!.contains("<details>"))
    }

    @Test
    fun `single trailing bare details block is recognized`() {
        val input = "正文叙事。\n\n<details><summary>[状态]</summary>\n```\nHP 100\n```\n</details>\n"
        val result = StatusBlockExtractor.extract(input)

        assertEquals("正文叙事。", result.cleanedText)
        assertEquals(1, result.sections.size)
        assertEquals("[状态]", result.sections[0].title)
        assertEquals("HP 100", result.sections[0].content)
        assertNull(result.headerLine)
        assertNotNull(result.rawStatusText)
    }

    @Test
    fun `single mid-text bare details block is not captured`() {
        val input = "前文。\n<details><summary>[S]</summary>body</details>\n后文。"
        val result = StatusBlockExtractor.extract(input)

        assertEquals(input, result.cleanedText)
        assertNull(result.rawStatusText)
        assertNull(result.headerLine)
        assertTrue(result.sections.isEmpty())
        assertTrue(result.options.isEmpty())
    }

    @Test
    fun `details blocks separated by non-whitespace text are not captured as one run`() {
        val input = "前文。\n" +
            "<details><summary>[A]</summary>body A</details>\n" +
            "中间叙述。\n" +
            "<details><summary>[B]</summary>body B</details>\n" +
            "后文。"
        val result = StatusBlockExtractor.extract(input)

        // 两个孤立的单块 run，都不是末尾块，均不捕获
        assertEquals(input, result.cleanedText)
        assertNull(result.rawStatusText)
        assertTrue(result.sections.isEmpty())
    }

    @Test
    fun `tagged status block wins over bare details fallback`() {
        val input = "正文\n" +
            "<status_block>『H』\n<details><summary>[A]</summary>body A</details></status_block>\n" +
            "<details><summary>[B]</summary>body B</details>"
        val result = StatusBlockExtractor.extract(input)

        // 标签路径生效：只解析标签区域内的 details，兜底不触发
        assertEquals("『H』", result.headerLine)
        assertEquals(1, result.sections.size)
        assertEquals("[A]", result.sections[0].title)
        // 区域外的裸 details 保留在正文
        assertTrue(result.cleanedText.startsWith("正文"))
        assertTrue(result.cleanedText.contains("<details><summary>[B]</summary>body B</details>"))
        assertNotNull(result.rawStatusText)
    }
}
