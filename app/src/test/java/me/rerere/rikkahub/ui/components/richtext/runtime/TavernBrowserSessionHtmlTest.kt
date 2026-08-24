package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperButton
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperButtonConfig
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperExportWith
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperScript
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernBrowserSessionHtmlTest {
    @Test
    fun `script source is encoded and session exposes stable identity and button api`() {
        val script = TavernHelperScript(
            id = "script-id",
            name = "测试脚本",
            enabled = true,
            content = "document.body.dataset.ready='yes';</script><script>escape()",
            info = "",
            button = TavernHelperButtonConfig(
                true,
                listOf(TavernHelperButton("执行", true)),
                JsonObject(emptyMap()),
            ),
            data = JsonObject(emptyMap()),
            exportWith = TavernHelperExportWith(true, true, JsonObject(emptyMap())),
            compatExtras = JsonObject(emptyMap()),
        )

        val html = buildTavernBrowserSessionHtml(script)

        assertFalse(html.contains("</script><script>escape()"))
        assertTrue(html.contains("getScriptId"))
        assertTrue(html.contains("getButtonEvent"))
        assertTrue(html.contains("script-id"))
    }
}
