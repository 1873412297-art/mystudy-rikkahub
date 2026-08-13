package me.rerere.rikkahub.data.ai.status

import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernCardStyleResolverTest {

    @Test
    fun `resolves css from card and derives version key`() {
        val assistant = Assistant(
            name = "A",
            tavernCardJson = """{"data":{"extensions":{"css":"body{color:red}"}}}""",
        )
        val style = TavernCardStyleResolver.resolve(assistant)
        assertEquals("body{color:red}", style?.css)
        assertTrue(style!!.versionKey.isNotBlank())
    }

    @Test
    fun `returns null for assistant without card`() {
        assertNull(TavernCardStyleResolver.resolve(Assistant(name = "B")))
    }

    @Test
    fun `version key changes when card json changes`() {
        val a1 = Assistant(name = "A", tavernCardJson = """{"data":{"extensions":{"css":"a"}}}""")
        val a2 = Assistant(name = "A", tavernCardJson = """{"data":{"extensions":{"css":"b"}}}""")
        val k1 = TavernCardStyleResolver.resolve(a1)!!.versionKey
        val k2 = TavernCardStyleResolver.resolve(a2)!!.versionKey
        assertTrue(k1 != k2)
    }

    @Test
    fun `resolves css when only status render js present without card css`() {
        val assistant = Assistant(name = "C", statusRenderJs = "function renderStatus(){}")
        val style = TavernCardStyleResolver.resolve(assistant)
        // 无 card CSS 时 css 为 null，但版本键仍随 statusRenderJs 变化
        assertNull(style?.css)
        assertTrue(style!!.versionKey.isNotBlank())
    }
}
