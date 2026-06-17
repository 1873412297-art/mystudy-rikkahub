package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class TavernRuntimeController(
    private val eventBus: TavernRuntimeEventBus = TavernRuntimeEventBus(),
    private val worldRepository: TavernWorldRepository = TavernRuntimeWorldStore(),
    private val permissionStore: TavernRuntimePermissionStore = TavernRuntimePermissionStore(),
) {
    private val chatVariables = linkedMapOf<String, JsonElement>()
    private var currentMessage: JsonElement = JsonNull

    fun dispatch(request: TavernRuntimeRequest): TavernRuntimeResponse {
        return try {
            if (!permissionStore.current().allowScripts && request.method != "runtime.ping") {
                return TavernRuntimeResponse.error(
                    id = request.id,
                    code = "PERMISSION_DENIED",
                    message = "Runtime scripts are disabled",
                )
            }
            when (request.method) {
                "runtime.ping" -> TavernRuntimeResponse.success(request.id, JsonPrimitive("pong"))
                "variables.get" -> getVariable(request)
                "variables.set" -> setVariable(request)
                "variables.list" -> listVariables(request)
                "slash.run" -> runSlash(request)
                "events.emit" -> emitEvent(request)
                "world.getEntries" -> getWorldEntries(request)
                "world.upsertEntry" -> upsertWorldEntry(request)
                "world.deleteEntry" -> deleteWorldEntry(request)
                "messages.getCurrent" -> TavernRuntimeResponse.success(request.id, currentMessage)
                "messages.updateCurrent" -> updateCurrentMessage(request)
                else -> TavernRuntimeResponse.error(
                    id = request.id,
                    code = "UNSUPPORTED",
                    message = "Runtime method '${request.method}' is not available in this compatibility layer",
                )
            }
        } catch (e: Exception) {
            TavernRuntimeResponse.error(
                id = request.id,
                code = "INTERNAL_ERROR",
                message = e.message ?: "Unexpected runtime failure",
            )
        }
    }

    private fun getVariable(request: TavernRuntimeRequest): TavernRuntimeResponse {
        val key = request.params.getString("key")
            ?: return TavernRuntimeResponse.error(request.id, "BAD_REQUEST", "variables.get requires params.key")
        val scope = request.params.getString("scope") ?: "chat"
        val value = when (scope) {
            "chat", "global" -> chatVariables[key]
            else -> chatVariables[key]
        } ?: JsonNull
        return TavernRuntimeResponse.success(request.id, value)
    }

    private fun setVariable(request: TavernRuntimeRequest): TavernRuntimeResponse {
        val key = request.params.getString("key")
            ?: return TavernRuntimeResponse.error(request.id, "BAD_REQUEST", "variables.set requires params.key")
        val value = request.params["value"] ?: JsonNull
        chatVariables[key] = value
        return TavernRuntimeResponse.success(request.id, JsonPrimitive(true))
    }

    private fun listVariables(request: TavernRuntimeRequest): TavernRuntimeResponse {
        return TavernRuntimeResponse.success(request.id, JsonObject(chatVariables.toMap()))
    }

    private fun runSlash(request: TavernRuntimeRequest): TavernRuntimeResponse {
        val command = request.params.getString("command")?.trim().orEmpty()
        return when {
            command == "/th help" || command == "th help" -> TavernRuntimeResponse.success(
                request.id,
                JsonPrimitive("/th help\n/th vars\n/th ping"),
            )
            command == "/th ping" || command == "th ping" -> TavernRuntimeResponse.success(
                request.id,
                JsonPrimitive("pong"),
            )
            command == "/th vars" || command == "th vars" -> listVariables(request)
            command.isBlank() -> TavernRuntimeResponse.error(
                request.id,
                "BAD_REQUEST",
                "slash.run requires params.command",
            )
            else -> TavernRuntimeResponse.error(
                request.id,
                "UNSUPPORTED_SLASH_COMMAND",
                "Slash command '$command' is not supported by Rikkahub Tavern compatibility runtime",
            )
        }
    }

    private fun emitEvent(request: TavernRuntimeRequest): TavernRuntimeResponse {
        val name = request.params.getString("name")
            ?: return TavernRuntimeResponse.error(request.id, "BAD_REQUEST", "events.emit requires params.name")
        eventBus.emit(name, request.params["payload"])
        return TavernRuntimeResponse.success(request.id, JsonPrimitive(name))
    }

    private fun getWorldEntries(request: TavernRuntimeRequest): TavernRuntimeResponse {
        return TavernRuntimeResponse.success(request.id, JsonArray(worldRepository.listEntries()))
    }

    private fun upsertWorldEntry(request: TavernRuntimeRequest): TavernRuntimeResponse {
        if (!permissionStore.current().allowWorldWrite) {
            return TavernRuntimeResponse.error(request.id, "PERMISSION_DENIED", "World write access is disabled for this script")
        }
        val entry = request.params["entry"] as? JsonObject
            ?: return TavernRuntimeResponse.error(request.id, "BAD_REQUEST", "world.upsertEntry requires params.entry object")
        val id = worldRepository.upsertEntry(entry)
        return TavernRuntimeResponse.success(request.id, JsonPrimitive(id))
    }

    private fun deleteWorldEntry(request: TavernRuntimeRequest): TavernRuntimeResponse {
        if (!permissionStore.current().allowWorldWrite) {
            return TavernRuntimeResponse.error(request.id, "PERMISSION_DENIED", "World write access is disabled for this script")
        }
        val id = request.params.getString("id")
            ?: return TavernRuntimeResponse.error(request.id, "BAD_REQUEST", "world.deleteEntry requires params.id")
        return TavernRuntimeResponse.success(request.id, JsonPrimitive(worldRepository.deleteEntry(id)))
    }

    private fun updateCurrentMessage(request: TavernRuntimeRequest): TavernRuntimeResponse {
        if (!permissionStore.current().allowMessageWrite) {
            return TavernRuntimeResponse.error(request.id, "PERMISSION_DENIED", "Message write access is disabled for this script")
        }
        currentMessage = request.params["patch"] ?: JsonNull
        return TavernRuntimeResponse.success(request.id, JsonPrimitive(true))
    }
}
