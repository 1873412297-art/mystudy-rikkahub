package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.JsonElement

internal class TavernRuntimeEventBus {
    private val history = ArrayDeque<Pair<String, JsonElement?>>()

    fun emit(name: String, payload: JsonElement?) {
        history.addLast(name to payload)
        while (history.size > 100) {
            history.removeFirst()
        }
    }

    fun recent(): List<Pair<String, JsonElement?>> = history.toList()
}
