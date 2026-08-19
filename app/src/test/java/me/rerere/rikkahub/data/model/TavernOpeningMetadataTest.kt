package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.pages.tavern.empty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernOpeningMetadataTest {

    @Test
    fun `new Tavern runtime permissions use the maximum compatibility defaults`() {
        val permissions = TavernRuntimePermissions()

        assertTrue(permissions.allowScripts)
        assertTrue(permissions.allowWorldWrite)
        assertTrue(permissions.allowMessageWrite)
        assertTrue(permissions.allowNetwork)
        assertTrue(permissions.allowVariablesWrite)
        assertTrue(permissions.allowEventSubscribe)
        assertTrue(permissions.allowMacroRegister)
        assertTrue(!permissions.allowRequestHeaders)
    }

    @Test
    fun `request header opt in preserves the compatibility permission preset`() {
        val permissions = TavernRuntimePermissions(allowRequestHeaders = true)

        assertTrue(permissions.allowScripts)
        assertTrue(permissions.allowWorldWrite)
        assertTrue(permissions.allowMessageWrite)
        assertTrue(permissions.allowNetwork)
        assertTrue(permissions.allowVariablesWrite)
        assertTrue(permissions.allowEventSubscribe)
        assertTrue(permissions.allowMacroRegister)
        assertTrue(permissions.allowRequestHeaders)
    }

    @Test
    fun `opening metadata round trips without discarding existing text metadata`() {
        val ref = TavernOpeningRef(
            greetingIndex = 2,
            contentFingerprint = "content-fingerprint",
            cardFingerprint = "card-fingerprint",
        )
        val text = UIMessagePart.Text(
            text = "Opening",
            metadata = buildJsonObject { put("source", "import") },
        )

        val marked = text.withTavernOpening(ref)

        assertEquals(ref, marked.tavernOpeningRef())
        assertEquals("import", marked.metadata?.get("source")?.toString()?.trim('"'))
    }

    @Test
    fun `malformed opening metadata is ignored`() {
        val malformed = listOf(
            buildJsonObject { put("kind", "tavern_opening") },
            buildJsonObject {
                put("kind", "tavern_opening")
                put("greetingIndex", "wrong-type")
                put("contentFingerprint", "content")
                put("cardFingerprint", "card")
            },
            buildJsonObject {
                put("kind", "different")
                put("greetingIndex", 0)
                put("contentFingerprint", "content")
                put("cardFingerprint", "card")
            },
        )

        malformed.forEach { metadata ->
            assertNull(UIMessagePart.Text("Opening", metadata = metadata).tavernOpeningRef())
        }
    }

    @Test
    fun `legacy first mes recognition uses stable SHA 256 fingerprints`() {
        val firstMes = "Welcome, traveler."
        val card = TavernCharacterCard.empty().copy(firstMes = firstMes)
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(firstMes, UIMessagePart.RenderMode.HTML)),
        )

        val ref = inferLegacyOpening(message, card)

        assertEquals(0, ref?.greetingIndex)
        assertEquals("6910fadd470d0a129d26c7ca366fe18f1edb9250ffd022ca2978579a8f89ea05", ref?.contentFingerprint)
        assertEquals("603c27ec4db7f24f47c57d36dd3dab06561512d6c7152a4751edb65d954a20bc", ref?.cardFingerprint)
        assertTrue(ref?.contentFingerprint?.matches(Regex("[0-9a-f]{64}")) == true)
    }

    @Test
    fun `legacy opening recognition requires a single assistant text message matching a greeting`() {
        val card = TavernCharacterCard.empty().copy(firstMes = "Welcome")

        assertNull(
            inferLegacyOpening(
                UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Welcome"))),
                card,
            ),
        )
        assertNull(
            inferLegacyOpening(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text("Welcome"), UIMessagePart.Text("extra")),
                ),
                card,
            ),
        )
        assertNull(
            inferLegacyOpening(
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Changed"))),
                card,
            ),
        )
    }
}
