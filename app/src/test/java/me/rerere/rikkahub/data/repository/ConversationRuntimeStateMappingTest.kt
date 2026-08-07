package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.TurnTakingStrategy
import me.rerere.rikkahub.service.group.GroupDirectorState
import me.rerere.rikkahub.service.group.GroupPlaybackState
import me.rerere.rikkahub.service.group.GroupRuntimeState
import me.rerere.rikkahub.service.group.GroupSceneState
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationRuntimeStateMappingTest {
    private val assistantId = Uuid.parse("00000000-0000-0000-0000-000000000010")
    private val memberA = Uuid.parse("00000000-0000-0000-0000-000000000001")

    @Test
    fun `entity mapping round trips group runtime state`() {
        val conversation = Conversation(
            assistantId = assistantId,
            messageNodes = emptyList(),
            groupRuntimeState = GroupRuntimeState(
                scene = GroupSceneState(summary = "Moonlit courtyard", tension = 4),
                director = GroupDirectorState(
                    modeOverride = TurnTakingStrategy.AUTO_MODERATOR,
                    playbackState = GroupPlaybackState.PAUSED,
                    oneShotNextMemberId = memberA,
                    oneRoundActive = true,
                    oneRoundRemainingMemberIds = listOf(memberA),
                    skipNextRequested = true,
                ),
            ),
        )

        val restored = conversationFromEntity(
            entity = conversationToEntity(conversation),
            messageNodes = emptyList(),
        )

        assertEquals(conversation.groupRuntimeState, restored.groupRuntimeState)
    }

    @Test
    fun `malformed runtime json falls back to empty state`() {
        val entity = conversationToEntity(
            Conversation(assistantId = assistantId, messageNodes = emptyList())
        ).copy(groupRuntimeState = "{broken")

        val restored = conversationFromEntity(entity, emptyList())

        assertEquals(GroupRuntimeState(), restored.groupRuntimeState)
    }
}
