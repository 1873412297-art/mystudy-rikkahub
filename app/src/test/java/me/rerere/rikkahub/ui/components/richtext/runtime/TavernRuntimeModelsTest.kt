package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernRuntimeModelsTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `request decodes method params and id`() {
        val request = json.decodeFromString(
            TavernRuntimeRequest.serializer(),
            """{"id":"1","method":"variables.get","params":{"scope":"chat","key":"x"}}"""
        )

        assertEquals("1", request.id)
        assertEquals("variables.get", request.method)
        assertEquals("chat", request.params.getString("scope"))
        assertEquals("x", request.params.getString("key"))
    }

    @Test
    fun `success response encodes ok true result and id`() {
        val encoded = json.encodeToString(
            TavernRuntimeResponse.serializer(),
            TavernRuntimeResponse.success("7", JsonPrimitive("done"))
        )

        assertTrue(encoded.contains(""""ok":true"""))
        assertTrue(encoded.contains(""""id":"7""""))
        assertTrue(encoded.contains(""""result":"done""""))
    }

    @Test
    fun `error response encodes code and message`() {
        val encoded = json.encodeToString(
            TavernRuntimeResponse.serializer(),
            TavernRuntimeResponse.error("8", "UNSUPPORTED", "Method is not available")
        )

        assertFalse(encoded.contains(""""ok":true"""))
        assertTrue(encoded.contains(""""ok":false"""))
        assertTrue(encoded.contains(""""code":"UNSUPPORTED""""))
        assertTrue(encoded.contains(""""message":"Method is not available""""))
    }
}

