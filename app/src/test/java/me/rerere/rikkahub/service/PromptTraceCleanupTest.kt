package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.trace.removedMessageIds
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class PromptTraceCleanupTest {
    @Test
    fun `removed ids include deleted alternative and truncated tail but not branch selection`() {
        val first = UIMessage.user("one")
        val altA = UIMessage.assistant("A")
        val altB = UIMessage.assistant("B")
        val tail = UIMessage.user("two")
        val before = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(
                MessageNode.of(first),
                MessageNode(messages = listOf(altA, altB), selectIndex = 0),
                MessageNode.of(tail),
            )
        )
        val branchSelected = before.copy(
            messageNodes = before.messageNodes.mapIndexed { index, node ->
                if (index == 1) node.copy(selectIndex = 1) else node
            }
        )
        val alternativeDeleted = before.copy(
            messageNodes = before.messageNodes.mapIndexed { index, node ->
                if (index == 1) node.copy(messages = listOf(altA)) else node
            }
        )
        val truncated = before.copy(messageNodes = before.messageNodes.take(2))

        assertEquals(emptySet<Uuid>(), removedMessageIds(before, branchSelected))
        assertEquals(setOf(altB.id), removedMessageIds(before, alternativeDeleted))
        assertEquals(setOf(tail.id), removedMessageIds(before, truncated))
    }
}
