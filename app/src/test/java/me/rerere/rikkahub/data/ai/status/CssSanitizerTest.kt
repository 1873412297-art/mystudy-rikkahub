package me.rerere.rikkahub.data.ai.status

import org.junit.Assert.assertEquals
import org.junit.Test

class CssSanitizerTest {

    @Test
    fun `replaces closing style escape sequences case-insensitively`() {
        val input = "body{}</STYLE><script>alert(1)</script>"
        val out = CssSanitizer.sanitize(input)
        // </ 全替换为 /* */ ：</STYLE> → /* */ STYLE>；</script> → /* */ script>
        // （开标签 <script> 不含 </，保持不变）
        assertEquals("body{}/* */ STYLE><script>alert(1)/* */ script>", out)
        assertEquals(-1, out.lowercase().indexOf("</"))
    }

    @Test
    fun `leaves plain css untouched`() {
        val css = "body { color: red; } .mes { padding: 4px; }"
        assertEquals(css, CssSanitizer.sanitize(css))
    }
}
