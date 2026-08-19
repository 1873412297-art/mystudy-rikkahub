package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.pages.tavern.empty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun `opening metadata is namespaced without overwriting colliding generic metadata`() {
        val ref = TavernOpeningRef(
            greetingIndex = 2,
            contentFingerprint = "opening-content",
            cardFingerprint = "opening-card",
        )
        val text = UIMessagePart.Text(
            text = "Opening",
            metadata = buildJsonObject {
                put("kind", "unrelated-kind")
                put("greetingIndex", 99)
                put("contentFingerprint", "unrelated-content")
                put("cardFingerprint", "unrelated-card")
            },
        )

        val marked = text.withTavernOpening(ref)
        val openingMetadata = marked.metadata?.get("rikkahub_tavern_opening")?.jsonObject

        assertEquals("unrelated-kind", marked.metadata?.get("kind")?.jsonPrimitive?.content)
        assertEquals(99, marked.metadata?.get("greetingIndex")?.jsonPrimitive?.int)
        assertEquals("unrelated-content", marked.metadata?.get("contentFingerprint")?.jsonPrimitive?.content)
        assertEquals("unrelated-card", marked.metadata?.get("cardFingerprint")?.jsonPrimitive?.content)
        assertEquals("tavern_opening", openingMetadata?.get("kind")?.jsonPrimitive?.content)
        assertEquals(ref, marked.tavernOpeningRef())
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
    fun `opening metadata reader rejects noncanonical top level and malformed JSON types`() {
        val malformedMarkers = listOf(
            buildJsonObject {
                put("kind", "tavern_opening")
                put("greetingIndex", "2")
                put("contentFingerprint", "content")
                put("cardFingerprint", "card")
            },
            buildJsonObject {
                put("kind", 7)
                put("greetingIndex", 2)
                put("contentFingerprint", "content")
                put("cardFingerprint", "card")
            },
            buildJsonObject {
                put("kind", "tavern_opening")
                put("greetingIndex", 2)
                put("contentFingerprint", 7)
                put("cardFingerprint", "card")
            },
            buildJsonObject {
                put("kind", "tavern_opening")
                put("greetingIndex", 2)
                put("contentFingerprint", "content")
                put("cardFingerprint", true)
            },
        )

        assertNull(UIMessagePart.Text("Opening", metadata = malformedMarkers.first()).tavernOpeningRef())
        malformedMarkers.forEach { marker ->
            val metadata = buildJsonObject { put("rikkahub_tavern_opening", marker) }
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

    @Test
    fun `legacy opening recognition rejects blank card first mes and blank HTML`() {
        val blankCard = TavernCharacterCard.empty().copy(firstMes = "")

        assertNull(
            inferLegacyOpening(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text("", UIMessagePart.RenderMode.HTML)),
                ),
                blankCard,
            ),
        )
        assertNull(
            inferLegacyOpening(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text("", UIMessagePart.RenderMode.HTML)),
                ),
                TavernCharacterCard.empty().copy(firstMes = "Welcome"),
            ),
        )
    }

    @Test
    fun `card fingerprint includes alternate greeting order and boundaries`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("Opening", UIMessagePart.RenderMode.HTML)),
        )
        val ordered = TavernCharacterCard.empty().copy(
            firstMes = "Opening",
            alternateGreetings = listOf("a", "bc"),
        )
        val reordered = ordered.copy(alternateGreetings = listOf("bc", "a"))
        val differentBoundaries = ordered.copy(alternateGreetings = listOf("ab", "c"))

        val orderedRef = inferLegacyOpening(message, ordered)!!
        val reorderedRef = inferLegacyOpening(message, reordered)!!
        val differentBoundariesRef = inferLegacyOpening(message, differentBoundaries)!!

        assertEquals(orderedRef.contentFingerprint, reorderedRef.contentFingerprint)
        assertNotEquals(orderedRef.cardFingerprint, reorderedRef.cardFingerprint)
        assertNotEquals(orderedRef.cardFingerprint, differentBoundariesRef.cardFingerprint)
    }
}
