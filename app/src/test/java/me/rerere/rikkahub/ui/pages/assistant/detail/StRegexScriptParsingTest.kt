package me.rerere.rikkahub.ui.pages.assistant.detail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.model.AssistantAffectScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AssistantImporter 的 ST 正则脚本 flags/depth 解析测试
 */
class StRegexScriptParsingTest {

    private fun parseObj(json: String) = Json.parseToJsonElement(json).jsonObject

    // region flags 解析

    @Test
    fun `flags js style string ims maps to three options`() {
        val regex = parseStRegexScript(
            parseObj("""{"regex":"a+","replacement":"b","flags":"ims"}""")
        )!!
        assertEquals(
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL),
            regex.options,
        )
    }

    @Test
    fun `flags single letter i maps to ignore case`() {
        val regex = parseStRegexScript(
            parseObj("""{"regex":"a+","flags":"i"}""")
        )!!
        assertEquals(setOf(RegexOption.IGNORE_CASE), regex.options)
    }

    @Test
    fun `flags array of enum names maps to options case insensitively`() {
        val regex = parseStRegexScript(
            parseObj("""{"regex":"a+","flags":["IGNORE_CASE","multiline"]}""")
        )!!
        assertEquals(setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE), regex.options)
    }

    @Test
    fun `flags array of letters maps to options`() {
        val regex = parseStRegexScript(
            parseObj("""{"regex":"a+","flags":["i","s"]}""")
        )!!
        assertEquals(setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL), regex.options)
    }

    @Test
    fun `missing flags yields empty options`() {
        val regex = parseStRegexScript(parseObj("""{"regex":"a+"}"""))!!
        assertTrue(regex.options.isEmpty())
    }

    @Test
    fun `null or unrecognized flags yield empty options`() {
        val nullFlags = parseStRegexScript(parseObj("""{"regex":"a+","flags":null}"""))!!
        assertTrue(nullFlags.options.isEmpty())

        val weird = parseStRegexScript(parseObj("""{"regex":"a+","flags":"xyz"}"""))!!
        assertTrue(weird.options.isEmpty())

        val empty = parseStRegexScript(parseObj("""{"regex":"a+","flags":""}"""))!!
        assertTrue(empty.options.isEmpty())
    }

    // endregion

    // region depth 解析

    @Test
    fun `camelCase depth fields are parsed`() {
        val regex = parseStRegexScript(
            parseObj("""{"regex":"a+","minDepth":1,"maxDepth":4}""")
        )!!
        assertEquals(1, regex.minDepth)
        assertEquals(4, regex.maxDepth)
    }

    @Test
    fun `snake_case depth fields are parsed`() {
        val regex = parseStRegexScript(
            parseObj("""{"regex":"a+","min_depth":2,"max_depth":6}""")
        )!!
        assertEquals(2, regex.minDepth)
        assertEquals(6, regex.maxDepth)
    }

    @Test
    fun `missing depth fields yield null`() {
        val regex = parseStRegexScript(parseObj("""{"regex":"a+"}"""))!!
        assertNull(regex.minDepth)
        assertNull(regex.maxDepth)
    }

    @Test
    fun `non numeric depth values fall back to null`() {
        val regex = parseStRegexScript(
            parseObj("""{"regex":"a+","minDepth":"deep"}""")
        )!!
        assertNull(regex.minDepth)
    }

    // endregion

    // region 兼容既有字段

    @Test
    fun `missing regex field returns null`() {
        assertNull(parseStRegexScript(parseObj("""{"replacement":"b"}""")))
    }

    @Test
    fun `legacy fields are still parsed`() {
        val regex = parseStRegexScript(
            parseObj(
                """
                {
                  "regex": "a+",
                  "replacement": "b",
                  "scope": "user",
                  "enabled": false,
                  "name": "规则A",
                  "visual_only": true
                }
                """.trimIndent()
            )
        )!!
        assertEquals("a+", regex.findRegex)
        assertEquals("b", regex.replaceString)
        assertEquals(setOf(AssistantAffectScope.USER), regex.affectingScope)
        assertFalse(regex.enabled)
        assertEquals("规则A", regex.name)
        assertTrue(regex.visualOnly)
        // 新字段默认
        assertTrue(regex.options.isEmpty())
        assertNull(regex.minDepth)
        assertNull(regex.maxDepth)
    }

    @Test
    fun `global scope maps to both user and assistant`() {
        val regex = parseStRegexScript(
            parseObj("""{"regex":"a+","scope":"global"}""")
        )!!
        assertEquals(setOf(AssistantAffectScope.USER, AssistantAffectScope.ASSISTANT), regex.affectingScope)
    }

    @Test
    fun `name falls back to truncated pattern when absent`() {
        val longPattern = "a".repeat(50)
        val regex = parseStRegexScript(
            parseObj("""{"regex":"$longPattern"}""")
        )!!
        assertEquals(longPattern.take(30), regex.name)
    }

    @Test
    fun `flags helper can be called directly`() {
        assertEquals(
            setOf(RegexOption.MULTILINE),
            parseStRegexFlags(parseObj("""{"flags":"m"}""")),
        )
        assertTrue(parseStRegexFlags(parseObj("""{}""")).isEmpty())
    }

    // endregion
}
