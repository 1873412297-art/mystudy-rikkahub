package me.rerere.rikkahub.data.ai.trace

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class PromptTraceModelsTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `payload round trip and unknown fields remain compatible`() {
        val metadata = PromptTraceMetadata(
            conversationId = Uuid.random(),
            assistantId = Uuid.random(),
            modelId = Uuid.random(),
            isGroup = false,
            providerStepIndex = 0,
            startedAtEpochMs = 123L,
        )
        val payload = PromptTracePayload(
            metadata = metadata,
            sections = emptyList(),
            injectionHits = emptyList(),
            finalMessages = emptyList(),
        )
        val encoded = json.encodeToString(PromptTracePayload.serializer(), payload)
        val withUnknown = encoded.dropLast(1) + ",\"future_field\":true}"

        assertEquals(payload, json.decodeFromString(PromptTracePayload.serializer(), withUnknown))
    }
}
