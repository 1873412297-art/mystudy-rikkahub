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
        assertTrue(html.contains("getIframeName"))
        assertTrue(html.contains("replaceScriptButtons"))
        assertTrue(html.contains("updateVariablesWith"))
        assertTrue(html.contains("SCRIPT_LOADED"))
        assertTrue(html.contains("SCRIPT_UNLOADING"))
        assertTrue(html.contains("window.TavernHelper"))
        assertTrue(html.contains("RikkahubScriptBridge.replaceData"))
        assertTrue(html.contains("RikkahubScriptBridge.replaceButtons"))
        assertTrue(html.contains("RikkahubScriptBridge.log"))
        assertTrue(html.contains("RikkahubScriptBridge.lifecycle"))
        assertTrue(html.contains("console.debug"))
        assertTrue(html.contains("console.error"))
        assertTrue(html.contains("lifecycle('paused')"))
        assertTrue(html.contains("unhandledrejection"))
        assertTrue(html.contains("runtime_crash"))
        assertTrue(html.contains("script-id"))
    }
}
