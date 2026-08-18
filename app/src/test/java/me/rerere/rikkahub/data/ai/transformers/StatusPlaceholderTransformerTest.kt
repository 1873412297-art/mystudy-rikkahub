package me.rerere.rikkahub.data.ai.transformers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class StatusPlaceholderTransformerTest {
    @Test
    fun `bare json patch range includes closing bracket`() {
        val content = """[{ "op": "replace", "path": "/世界/当前时间", "value": "子时" }]"""
        val range = findBareJsonPatch(content)

        assertNotNull(range)
        assertEquals(content, content.substring(range!!.first, range.last + 1))
    }
}
