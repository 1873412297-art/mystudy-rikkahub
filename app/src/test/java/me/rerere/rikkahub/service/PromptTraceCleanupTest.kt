package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.trace.PromptTraceCleanup
import me.rerere.rikkahub.data.ai.trace.removedMessageIds
import me.rerere.rikkahub.data.ai.trace.removedMessageIdsAfter
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class PromptTraceCleanupTest {
    @Test
    fun `invalid unresolved tool alternatives and emptied nodes are explicit removals`() {
        val keptAlternative = UIMessage.assistant("kept")
        val removedAlternative = unresolvedToolMessage("tool-alt")
        val removedOnlyMessage = unresolvedToolMessage("tool-only")
        val before = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(
                MessageNode(messages = listOf(keptAlternative, removedAlternative), selectIndex = 1),
                MessageNode.of(removedOnlyMessage),
                MessageNode(messages = emptyList()),
            ),
        )

        val after = before.removeInvalidUnresolvedToolMessages()

        assertEquals(listOf(keptAlternative.id), after.messageNodes.single().messages.map { it.id })
        assertEquals(
            setOf(removedAlternative.id, removedOnlyMessage.id),
            PromptTraceCleanup.RemovedMessages(before).removedMessageIdsAfter(after),
        )
    }

    @Test
    fun `continuation nudge filtering and emptied nodes are explicit removals`() {
        val kept = UIMessage.assistant("kept")
        val removedAlternative = UIMessage.user("请继续以[甲]身份回复，不要重复上文。")
        val removedOnlyMessage = UIMessage.user("请继续以[乙]身份回复，不要重复上文。")
        val before = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(
                MessageNode(messages = listOf(kept, removedAlternative), selectIndex = 1),
                MessageNode.of(removedOnlyMessage),
            ),
        )

        val after = before.removeGroupContinuationNudgeNodes()

        assertEquals(listOf(kept.id), after.messageNodes.single().messages.map { it.id })
        assertEquals(
            setOf(removedAlternative.id, removedOnlyMessage.id),
            PromptTraceCleanup.RemovedMessages(before).removedMessageIdsAfter(after),
        )
    }

    @Test
    fun `ordinary stale metadata save never schedules cleanup for a newer response`() {
        val anchor = UIMessage.user("one")
        val newerResponse = UIMessage.assistant("new response")
        val liveConversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(anchor), MessageNode.of(newerResponse)),
        )
        val staleMetadataSave = liveConversation.copy(
            title = "renamed from stale snapshot",
            messageNodes = listOf(MessageNode.of(anchor)),
        )

        assertEquals(setOf(newerResponse.id), removedMessageIds(liveConversation, staleMetadataSave))
        assertEquals(
            emptySet<Uuid>(),
            PromptTraceCleanup.None.removedMessageIdsAfter(staleMetadataSave),
        )
    }

    @Test
    fun `explicit tail removal schedules only intentionally removed messages`() {
        val anchor = UIMessage.user("one")
        val removedResponse = UIMessage.assistant("remove me")
        val before = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(anchor), MessageNode.of(removedResponse)),
        )
        val after = before.copy(messageNodes = before.messageNodes.take(1))

        assertEquals(
            setOf(removedResponse.id),
            PromptTraceCleanup.RemovedMessages(before).removedMessageIdsAfter(after),
        )
    }

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

    private fun unresolvedToolMessage(toolCallId: String) = UIMessage(
        role = me.rerere.ai.core.MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = toolCallId,
                toolName = "fixture_tool",
                input = "{}",
            )
        ),
    )
}
