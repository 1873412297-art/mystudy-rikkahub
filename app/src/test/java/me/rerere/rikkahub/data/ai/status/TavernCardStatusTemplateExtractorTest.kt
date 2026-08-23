package me.rerere.rikkahub.data.ai.status

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TavernCardStatusTemplateExtractorTest {

    @Test
    fun `extracts enabled visual placeholder template and removes outer markdown fence`() {
        val cardJson =
            """
            {
              "spec": "chara_card_v3",
              "spec_version": "3.0",
              "data": {
                "name": "Aster",
                "first_mes": "<StatusPlaceHolderImpl/>",
                "extensions": {
                  "regex_scripts": [
                    {
                      "scriptName": "状态栏",
                      "findRegex": "<StatusPlaceHolderImpl/>",
                      "replaceString": "```html\n<html><img src=\"https://example.com/aster.png\"><script>init()</script></html>\n```",
                      "disabled": false,
                      "markdownOnly": true,
                      "promptOnly": false
                    }
                  ]
                }
              }
            }
            """.trimIndent()

        assertEquals(
            "<html><img src=\"https://example.com/aster.png\"><script>init()</script></html>",
            extractTavernCardStatusTemplate(cardJson),
        )
    }

    @Test
    fun `ignores disabled and prompt only placeholder scripts`() {
        val extensions = Json.parseToJsonElement(
            """
            {
              "regex_scripts": [
                {"findRegex":"<StatusPlaceHolderImpl/>","replaceString":"disabled","disabled":true,"markdownOnly":true},
                {"findRegex":"<StatusPlaceHolderImpl/>","replaceString":"prompt","promptOnly":true}
              ]
            }
            """.trimIndent(),
        ).jsonObject

        assertNull(extractTavernCardStatusTemplate(extensions))
    }
}
