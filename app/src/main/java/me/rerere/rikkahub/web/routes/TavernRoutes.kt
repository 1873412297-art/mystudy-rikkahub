package me.rerere.rikkahub.web.routes

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import me.rerere.rikkahub.data.ai.status.TavernCardCssExtractor
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.web.dto.TavernRenderDto

/**
 * 酒馆渲染数据端点：供 web-ui 获取角色卡 renderStatus JS 与 CSS，
 * 用于 sandboxed iframe 实时重渲染状态 HTML。
 */
fun Route.tavernRoutes(settingsStore: SettingsStore) {
    route("/assistant") {
        // GET /api/assistant/{id}/tavern-render
        get("/{id}/tavern-render") {
            val assistantId = call.parameters["id"].toUuid("assistant id")
            val settings = settingsStore.settingsFlow.value
            val assistant = settings.assistants.firstOrNull { it.id == assistantId }
                ?: throw NotFoundException("Assistant not found")
            call.respond(
                TavernRenderDto(
                    statusRenderJs = assistant.statusRenderJs,
                    css = assistant.tavernCardJson?.let { TavernCardCssExtractor.extract(it) },
                )
            )
        }
    }
}
