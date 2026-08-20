package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class TavernPersistingVariableGatewayTest {
    private val conversationId = Uuid.parse("30000000-0000-4000-8000-000000000001")

    @Test
    fun `chat writes publish the selected conversation snapshot while global writes do not`() {
        val delegate = InMemoryTavernRuntimeVariableGateway()
        val persisted = mutableListOf<Pair<Uuid, JsonObject>>()
        val gateway = PersistingTavernRuntimeVariableGateway(delegate, conversationId) { id, variables ->
            persisted += id to variables
        }

        gateway.set(conversationId, TAVERN_VARIABLE_SCOPE_CHAT, "hp", JsonPrimitive(7))
        gateway.set(conversationId, TAVERN_VARIABLE_SCOPE_GLOBAL, "theme", JsonPrimitive("dark"))
        assertTrue(gateway.delete(conversationId, TAVERN_VARIABLE_SCOPE_CHAT, "hp"))

        assertEquals(2, persisted.size)
        assertEquals(conversationId, persisted.first().first)
        assertEquals(JsonPrimitive(7), persisted.first().second["hp"])
        assertTrue(persisted.last().second.isEmpty())
    }

    @Test
    fun `stale target is rejected before chat or global variable mutation`() {
        val delegate = InMemoryTavernRuntimeVariableGateway()
        val gateway = PersistingTavernRuntimeVariableGateway(
            delegate = delegate,
            targetConversationId = conversationId,
            validateTarget = { error("stale target") },
            persistChatVariables = { _, _ -> error("must not persist") },
        )

        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            gateway.set(conversationId, TAVERN_VARIABLE_SCOPE_CHAT, "hp", JsonPrimitive(7))
        }
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            gateway.set(conversationId, TAVERN_VARIABLE_SCOPE_GLOBAL, "theme", JsonPrimitive("dark"))
        }

        assertTrue(delegate.list(conversationId, TAVERN_VARIABLE_SCOPE_CHAT).isEmpty())
        assertTrue(delegate.list(conversationId, TAVERN_VARIABLE_SCOPE_GLOBAL).isEmpty())
    }
}
