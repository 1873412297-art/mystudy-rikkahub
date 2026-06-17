package me.rerere.rikkahub.service.group

import kotlin.uuid.Uuid

internal fun sanitizeManualSelection(
    selectedIds: List<Uuid>,
    availableIds: List<Uuid>,
): List<Uuid> {
    val available = availableIds.toSet()
    return selectedIds.filter { it in available }.distinct()
}

internal fun toggleManualSelection(
    selectedIds: List<Uuid>,
    memberId: Uuid,
): List<Uuid> {
    return if (memberId in selectedIds) {
        selectedIds.filter { it != memberId }
    } else {
        selectedIds + memberId
    }
}

internal fun moveManualSelection(
    selectedIds: List<Uuid>,
    fromIndex: Int,
    toIndex: Int,
): List<Uuid> {
    if (fromIndex !in selectedIds.indices || toIndex !in selectedIds.indices) return selectedIds
    val mutable = selectedIds.toMutableList()
    val item = mutable.removeAt(fromIndex)
    mutable.add(toIndex, item)
    return mutable
}
