package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * AssistantRegex 扩展（options 修饰标志 / minDepth / maxDepth）的语义与序列化兼容测试
 */
class AssistantRegexTest {

    private fun regex(
        find: String,
        replace: String = "",
        scope: Set<AssistantAffectScope> = setOf(AssistantAffectScope.ASSISTANT),
        options: Set<RegexOption> = emptySet(),
        minDepth: Int? = null,
        maxDepth: Int? = null,
        enabled: Boolean = true,
        visualOnly: Boolean = false,
    ) = AssistantRegex(
        id = Uuid.random(),
        name = "test",
        enabled = enabled,
        findRegex = find,
        replaceString = replace,
        affectingScope = scope,
        visualOnly = visualOnly,
        options = options,
        minDepth = minDepth,
        maxDepth = maxDepth,
    )

    private fun assistantWith(vararg regexes: AssistantRegex) = Assistant(regexes = regexes.toList())

    private fun apply(
        input: String,
        vararg regexes: AssistantRegex,
        depth: Int? = null,
    ): String = input.replaceRegexes(
        assistant = assistantWith(*regexes),
        scope = AssistantAffectScope.ASSISTANT,
        visual = false,
        depth = depth,
    )

    // region flags 语义

    @Test
    fun `ignore case option makes matching case insensitive`() {
        val rule = regex("hello", "HI", options = setOf(RegexOption.IGNORE_CASE))
        assertEquals("HI world", apply("HeLLo world", rule))
        // 无标志时保持大小写敏感
        val sensitive = regex("hello", "HI")
        assertEquals("HeLLo world", apply("HeLLo world", sensitive))
    }

    @Test
    fun `multiline option makes anchors match line boundaries`() {
        val rule = regex("^b", "X", options = setOf(RegexOption.MULTILINE))
        assertEquals("a\nX", apply("a\nb", rule))
        // 无 MULTILINE 时 ^ 仅匹配输入开头
        val plain = regex("^b", "X")
        assertEquals("a\nb", apply("a\nb", plain))
    }

    @Test
    fun `dot matches all option makes dot match newline`() {
        val rule = regex("a.b", "X", options = setOf(RegexOption.DOT_MATCHES_ALL))
        assertEquals("X", apply("a\nb", rule))
        // 无 DOT_MATCHES_ALL 时 . 不匹配换行
        val plain = regex("a.b", "X")
        assertEquals("a\nb", apply("a\nb", plain))
    }

    @Test
    fun `combined options apply together`() {
        val rule = regex(
            "^hello",
            "HI",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
        )
        assertEquals("x\nHI z", apply("x\nHeLLo z", rule))
    }

    // endregion

    // region 规则顺序

    @Test
    fun `rules apply in list order and earlier output feeds later rules`() {
        val first = regex("a", "b")
        val second = regex("b", "c")
        assertEquals("c", apply("a", first, second))
        assertEquals("b", apply("a", second, first))
    }

    @Test
    fun `disabled and scope mismatched rules are skipped`() {
        val disabled = regex("a", "b", enabled = false)
        val userScope = regex("a", "b", scope = setOf(AssistantAffectScope.USER))
        assertEquals("a", apply("a", disabled, userScope))
    }

    // endregion

    // region 失败回退

    @Test
    fun `invalid pattern falls back to original string`() {
        val broken = regex("(", "X")
        assertEquals("hello(world", apply("hello(world", broken))
    }

    @Test
    fun `replacement referencing missing group falls back to original string`() {
        val rule = regex("(a)", "$2")
        assertEquals("a", apply("a", rule))
    }

    // endregion

    // region 编译缓存

    @Test
    fun `cache distinguishes same pattern with different options`() {
        val plain = regex("abc", "X")
        val ignoreCase = regex("abc", "X", options = setOf(RegexOption.IGNORE_CASE))
        // 先编译无标志版本，再编译带标志版本，验证缓存键含 options
        assertEquals("ABC X", apply("ABC abc", plain))
        assertEquals("X X", apply("ABC abc", ignoreCase))
        // 反向再来一次，命中缓存也应正确
        assertEquals("ABC X", apply("ABC abc", plain))
    }

    // endregion

    // region 深度过滤

    @Test
    fun `depth null disables depth filtering`() {
        val rule = regex("a", "b", minDepth = 2, maxDepth = 3)
        assertEquals("b", apply("a", rule, depth = null))
    }

    @Test
    fun `rule applies only when depth within range`() {
        val rule = regex("a", "b", minDepth = 1, maxDepth = 2)
        assertEquals("a", apply("a", rule, depth = 0))  // 低于下限
        assertEquals("b", apply("a", rule, depth = 1))
        assertEquals("b", apply("a", rule, depth = 2))
        assertEquals("a", apply("a", rule, depth = 3))  // 高于上限
    }

    @Test
    fun `rule without depth limits applies at any depth`() {
        val rule = regex("a", "b")
        assertEquals("b", apply("a", rule, depth = 0))
        assertEquals("b", apply("a", rule, depth = 99))
    }

    @Test
    fun `matchesDepth boundary semantics`() {
        val unbounded = regex("a", "b")
        assertTrue(unbounded.matchesDepth(0))
        assertTrue(unbounded.matchesDepth(100))

        val minOnly = regex("a", "b", minDepth = 2)
        assertFalse(minOnly.matchesDepth(1))
        assertTrue(minOnly.matchesDepth(2))
        assertTrue(minOnly.matchesDepth(10))

        val maxOnly = regex("a", "b", maxDepth = 2)
        assertTrue(maxOnly.matchesDepth(0))
        assertTrue(maxOnly.matchesDepth(2))
        assertFalse(maxOnly.matchesDepth(3))
    }

    // endregion

    // region 序列化兼容

    @Test
    fun `legacy json without new fields deserializes with defaults`() {
        val legacy = """
            {
              "id": "00000000-0000-4000-8000-000000000301",
              "name": "旧规则",
              "enabled": true,
              "findRegex": "a+",
              "replaceString": "b",
              "affectingScope": ["USER", "ASSISTANT"],
              "visualOnly": true
            }
        """.trimIndent()

        val decoded = JsonInstant.decodeFromString<AssistantRegex>(legacy)

        assertEquals(Uuid.parse("00000000-0000-4000-8000-000000000301"), decoded.id)
        assertEquals("旧规则", decoded.name)
        assertEquals("a+", decoded.findRegex)
        assertEquals(setOf(AssistantAffectScope.USER, AssistantAffectScope.ASSISTANT), decoded.affectingScope)
        assertTrue(decoded.visualOnly)
        // 新字段默认值
        assertTrue(decoded.options.isEmpty())
        assertNull(decoded.minDepth)
        assertNull(decoded.maxDepth)
    }

    @Test
    fun `new fields round trip through json`() {
        val rule = AssistantRegex(
            id = Uuid.parse("00000000-0000-4000-8000-000000000302"),
            name = "新规则",
            findRegex = "^hello",
            replaceString = "hi",
            affectingScope = setOf(AssistantAffectScope.ASSISTANT),
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
            minDepth = 1,
            maxDepth = 5,
        )

        val encoded = JsonInstant.encodeToString(rule)
        val decoded = JsonInstant.decodeFromString<AssistantRegex>(encoded)

        assertEquals(rule, decoded)
        assertTrue(encoded.contains("\"IGNORE_CASE\""))
        assertTrue(encoded.contains("\"MULTILINE\""))
        assertTrue(encoded.contains("\"minDepth\":1"))
        assertTrue(encoded.contains("\"maxDepth\":5"))
    }

    @Test
    fun `assistant containing legacy regex list still deserializes`() {
        val legacy = """
            {
              "id": "00000000-0000-4000-8000-000000000303",
              "name": "旧助手",
              "regexes": [
                {
                  "id": "00000000-0000-4000-8000-000000000304",
                  "name": "r",
                  "findRegex": "x",
                  "replaceString": "y",
                  "affectingScope": ["ASSISTANT"],
                  "visualOnly": false
                }
              ]
            }
        """.trimIndent()

        val decoded = JsonInstant.decodeFromString<Assistant>(legacy)

        val rule = decoded.regexes.single()
        assertEquals("x", rule.findRegex)
        assertTrue(rule.options.isEmpty())
        assertNull(rule.minDepth)
        assertNull(rule.maxDepth)
    }

    // endregion
}
