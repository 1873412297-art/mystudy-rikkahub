package me.rerere.rikkahub.data.ai.tavernhelper

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernHelperScriptCodecTest {
    private val codec = TavernHelperScriptCodec(idFactory = { "generated-id" })

    @Test
    fun `current script format imports disabled and preserves unknown fields`() {
        val result = codec.decodeImport(
            """
            {
              "type": "script",
              "enabled": true,
              "name": "按钮脚本",
              "id": "script-1",
              "content": "console.log('ready')",
              "info": "说明",
              "button": {
                "enabled": true,
                "buttons": [{"name":"执行","visible":true}],
                "button_future": 7
              },
              "data": {"count": 2},
              "export_with": {"data": false, "button": true},
              "future_field": {"kept": true}
            }
            """.trimIndent(),
        ) as TavernHelperScript

        assertFalse(result.enabled)
        assertEquals("script-1", result.id)
        assertEquals("按钮脚本", result.name)
        assertEquals("执行", result.button.buttons.single().name)
        assertEquals(JsonPrimitive(7), result.button.compatExtras["button_future"])
        assertEquals(JsonPrimitive(2), result.data["count"])
        assertFalse(result.exportWith.data)
        assertEquals(JsonPrimitive(true), result.compatExtras["future_field"]?.let { it.jsonObject["kept"] })
    }

    @Test
    fun `conflicting id is replaced during import`() {
        val result = codec.decodeImport(
            """{"type":"script","id":"taken","content":"","button":{},"data":{},"export_with":{}}""",
            occupiedIds = setOf("taken"),
        ) as TavernHelperScript

        assertEquals("generated-id", result.id)
    }

    @Test
    fun `invalid nested field reports json path`() {
        val error = runCatching {
            codec.decodeImport(
                """{"type":"script","button":{"buttons":[{"name":"执行","visible":"yes"}]}}""",
            )
        }.exceptionOrNull()

        assertTrue(error is TavernHelperSchemaException)
        assertEquals("$.button.buttons[0].visible", (error as TavernHelperSchemaException).path)
    }

    @Test
    fun `legacy folder and wrapped scripts migrate to current model`() {
        val result = codec.decodeImport(
            """
            {
              "type": "folder",
              "id": "legacy-folder",
              "name": "旧文件夹",
              "value": [
                {
                  "name": "旧脚本",
                  "id": "legacy-script",
                  "content": "console.log('legacy')",
                  "buttons": [{"name":"旧按钮","visible":true}],
                  "data": {"migrated": true}
                }
              ]
            }
            """.trimIndent(),
        ) as TavernHelperScriptFolder

        assertFalse(result.enabled)
        assertEquals("legacy-folder", result.id)
        assertEquals("旧脚本", result.scripts.single().name)
        assertFalse(result.scripts.single().enabled)
        assertEquals("旧按钮", result.scripts.single().button.buttons.single().name)
        assertTrue(result.scripts.single().exportWith.data)
        assertTrue(result.scripts.single().exportWith.button)
    }

    @Test
    fun `export flags remove excluded data while retaining unknown fields`() {
        val script = codec.decodeImport(
            """
            {
              "type":"script",
              "id":"export-me",
              "button":{"buttons":[{"name":"执行","visible":true}]},
              "data":{"secret":1},
              "export_with":{"data":false,"button":false},
              "future":"retained"
            }
            """.trimIndent(),
        )

        val exported = kotlinx.serialization.json.Json.parseToJsonElement(codec.encodeExport(script)).jsonObject

        assertEquals(JsonPrimitive("retained"), exported["future"])
        assertTrue(exported["data"]!!.jsonObject.isEmpty())
        val button = exported["button"]!!.jsonObject
        assertTrue(button["buttons"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `oversized source is rejected at content path`() {
        val source = "x".repeat(TavernHelperScriptCodec.MAX_SOURCE_BYTES + 1)
        val error = runCatching {
            codec.decodeImport(
                """{"type":"script","content":"$source"}""",
            )
        }.exceptionOrNull()

        assertTrue(error is TavernHelperSchemaException)
        assertEquals("$.content", (error as TavernHelperSchemaException).path)
    }

    @Test
    fun `stored format preserves trusted enabled state and all script data`() {
        val trusted = TavernHelperScript(
            id = "trusted",
            name = "已信任",
            enabled = true,
            content = "window.started = true",
            info = "",
            button = TavernHelperButtonConfig(
                enabled = true,
                buttons = listOf(TavernHelperButton("运行", true)),
                compatExtras = kotlinx.serialization.json.JsonObject(emptyMap()),
            ),
            data = kotlinx.serialization.json.JsonObject(mapOf("count" to JsonPrimitive(3))),
            exportWith = TavernHelperExportWith(
                data = false,
                button = false,
                compatExtras = kotlinx.serialization.json.JsonObject(emptyMap()),
            ),
            compatExtras = kotlinx.serialization.json.JsonObject(emptyMap()),
        )

        val restored = codec.decodeStored(codec.encodeStored(trusted)) as TavernHelperScript

        assertTrue(restored.enabled)
        assertEquals(JsonPrimitive(3), restored.data["count"])
        assertEquals("运行", restored.button.buttons.single().name)
    }
}
