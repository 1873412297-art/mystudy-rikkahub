package me.rerere.rikkahub.service.group

import kotlin.uuid.Uuid

internal data class GroupTurnSelection(
    val memberId: Uuid,
    val queue: List<Uuid>,
    val selectedIndex: Int,
)

internal fun normalizeGroupMemberQueue(
    persistedQueue: List<Uuid>,
    enabledMemberIds: List<Uuid>,
): List<Uuid> {
    val enabled = enabledMemberIds.distinct()
    val enabledSet = enabled.toSet()
    val retained = persistedQueue.filter { it in enabledSet }.distinct()
    return retained + enabled.filterNot { it in retained }
}

internal fun nextRoundRobinSelection(
    persistedQueue: List<Uuid>,
    persistedIndex: Int,
    activeMemberId: Uuid?,
    enabledMemberIds: List<Uuid>,
): GroupTurnSelection? {
    val queue = normalizeGroupMemberQueue(persistedQueue, enabledMemberIds)
    if (queue.isEmpty()) return null
    val cursorMemberId = persistedQueue.getOrNull(persistedIndex)?.takeIf { it in queue }
    val lastIndex = when {
        activeMemberId in queue -> queue.indexOf(activeMemberId)
        cursorMemberId != null -> queue.indexOf(cursorMemberId)
        else -> -1
    }
    val nextIndex = (lastIndex + 1) % queue.size
    return GroupTurnSelection(
        memberId = queue[nextIndex],
        queue = queue,
        selectedIndex = nextIndex,
    )
}

internal fun nextDifferentGroupMember(
    queue: List<Uuid>,
    currentMemberId: Uuid?,
): Uuid? = queue.firstOrNull { it != currentMemberId } ?: queue.firstOrNull()

internal fun selectModeratorTurn(
    persistedQueue: List<Uuid>,
    enabledMemberIds: List<Uuid>,
    activeMemberId: Uuid?,
    resolvedMemberId: Uuid?,
    allowConsecutiveSameSpeaker: Boolean,
): GroupTurnSelection? {
    val queue = normalizeGroupMemberQueue(persistedQueue, enabledMemberIds)
    val resolvedId = resolvedMemberId?.takeIf { it in queue } ?: return null
    val activeId = activeMemberId?.takeIf { it in queue }
    val selectedId = when {
        allowConsecutiveSameSpeaker -> resolvedId
        resolvedId != activeId -> resolvedId
        else -> nextDifferentGroupMember(queue, activeId) ?: return null
    }
    return GroupTurnSelection(
        memberId = selectedId,
        queue = queue,
        selectedIndex = queue.indexOf(selectedId),
    )
}

internal fun resolveGroupAutoReplyLimit(configuredLimit: Int): Int = configuredLimit.coerceAtLeast(1)

internal fun shouldContinueGroupAutoReplies(
    alreadySent: Int,
    configuredLimit: Int,
): Boolean = alreadySent < resolveGroupAutoReplyLimit(configuredLimit)
