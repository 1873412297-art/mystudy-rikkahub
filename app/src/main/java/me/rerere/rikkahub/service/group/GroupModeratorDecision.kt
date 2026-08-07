package me.rerere.rikkahub.service.group

import me.rerere.rikkahub.data.model.GroupMember
import kotlin.uuid.Uuid

internal fun parseGroupModeratorDecision(
    responseText: String,
    enabledMembers: List<GroupMember>,
    localFallback: Uuid?,
    allowStop: Boolean,
): Uuid? {
    val response = responseText.trim()
    if (allowStop && response.equals("STOP", ignoreCase = true)) {
        return null
    }
    return enabledMembers.find { it.id.toString() == response }?.id
        ?: enabledMembers.firstOrNull { response.contains(it.id.toString()) }?.id
        ?: localFallback
}
