package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernFrontendSanitizerTest {
    @Test
    fun `scripts disabled keeps html and css but removes executable content`() {
        val sanitized = sanitizeTavernFrontendHtml(
            """
            <style>.panel { color: red }</style>
            <div class="panel" onclick="run()">界面</div>
            <a href="javascript:steal()">危险链接</a>
            <script>window.executed = true</script>
            """.trimIndent(),
            allowScripts = false,
        )

        assertTrue(sanitized.contains("界面"))
        assertTrue(sanitized.contains(".panel"))
        assertFalse(sanitized.contains("<script", ignoreCase = true))
        assertFalse(sanitized.contains("onclick", ignoreCase = true))
        assertFalse(sanitized.contains("javascript:", ignoreCase = true))
    }

    @Test
    fun `scripts enabled preserves source unchanged`() {
        val html = "<button onclick=\"go()\">运行</button><script>go()</script>"
        assertTrue(sanitizeTavernFrontendHtml(html, allowScripts = true) === html)
    }
}
