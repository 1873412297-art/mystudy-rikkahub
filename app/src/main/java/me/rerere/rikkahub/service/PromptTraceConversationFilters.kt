package me.rerere.rikkahub.service

import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.group.isGroupContinuationNudge

internal fun Conversation.removeInvalidUnresolvedToolMessages(): Conversation {
    var nodes = messageNodes.mapNotNull { node ->
        if (node.messages.isEmpty()) return@mapNotNull null
        val pendingTools = node.currentMessage.getTools().filterNot { it.isExecuted }
        if (pendingTools.isEmpty() || pendingTools.any { it.approvalState.canResumeToolExecution() }) {
            node
        } else {
            node.copy(
                messages = node.messages.filter { it.id != node.currentMessage.id },
                selectIndex = node.selectIndex - 1,
            )
        }
    }

    nodes = nodes.map { node ->
        if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
            node.copy(selectIndex = 0)
        } else {
            node
        }
    }.filter { it.messages.isNotEmpty() }

    return if (nodes == messageNodes) this else copy(messageNodes = nodes)
}

internal fun Conversation.removeGroupContinuationNudgeNodes(): Conversation {
    var changed = false
    val cleanedNodes = messageNodes.mapNotNull { node ->
        val selectedMessageId = runCatching { node.currentMessage.id }.getOrNull()
        val filteredMessages = node.messages.filterNot { message ->
            message.toText().isGroupContinuationNudge()
        }
        if (filteredMessages.size != node.messages.size) changed = true
        if (filteredMessages.isEmpty()) {
            null
        } else {
            val selectedIndex = filteredMessages.indexOfFirst { it.id == selectedMessageId }
                .takeIf { it >= 0 }
                ?: node.selectIndex.coerceAtMost(filteredMessages.lastIndex)
            node.copy(messages = filteredMessages, selectIndex = selectedIndex)
        }
    }
    if (cleanedNodes.size != messageNodes.size) changed = true
    return if (changed) copy(messageNodes = cleanedNodes) else this
}
