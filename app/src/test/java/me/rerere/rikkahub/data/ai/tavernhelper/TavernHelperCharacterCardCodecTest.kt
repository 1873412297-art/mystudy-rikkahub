package me.rerere.rikkahub.data.ai.tavernhelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernHelperCharacterCardCodecTest {
    private val codec = TavernHelperCharacterCardCodec(
        scriptCodec = TavernHelperScriptCodec(idFactory = { "generated-id" }),
    )

    @Test
    fun `reads current character extension and disables imported scripts`() {
        val bundle = codec.decode(
            """{
              "data": {"extensions": {"tavern_helper": {
                "scripts": [{"type":"script","id":"card-script","name":"UI","enabled":true,"content":"run()"}],
                "variables": {"hp": 42}
              }}}
            }""",
        )

        assertEquals(1, bundle.scripts.size)
        assertFalse((bundle.scripts.single() as TavernHelperScript).enabled)
        assertEquals("42", bundle.variables["hp"].toString())
        assertFalse(bundle.migratedLegacy)
    }

    @Test
    fun `migrates legacy fields when current extension is absent`() {
        val bundle = codec.decode(
            """{
              "data": {"extensions": {
                "TavernHelper_scripts": [{"id":"old","name":"Legacy","enabled":true,"content":"old()","buttons":[]}],
                "TavernHelper_characterScriptVariables": {"coins": 7}
              }}
            }""",
        )

        assertEquals("Legacy", bundle.scripts.single().name)
        assertEquals("7", bundle.variables["coins"].toString())
        assertTrue(bundle.migratedLegacy)
    }

    @Test
    fun `current extension wins over legacy fields even when empty`() {
        val bundle = codec.decode(
            """{
              "data": {"extensions": {
                "tavern_helper": {"scripts": [], "variables": {}},
                "TavernHelper_scripts": [{"id":"old","name":"Legacy","content":"old()"}]
              }}
            }""",
        )

        assertTrue(bundle.scripts.isEmpty())
        assertFalse(bundle.migratedLegacy)
    }
}
