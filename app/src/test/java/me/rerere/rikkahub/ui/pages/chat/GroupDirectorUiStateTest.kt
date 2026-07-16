package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.GroupMember
import me.rerere.rikkahub.data.model.TurnTakingStrategy
import me.rerere.rikkahub.service.group.GroupDirectorState
import me.rerere.rikkahub.service.group.GroupPlaybackState
import me.rerere.rikkahub.service.group.GroupRuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class GroupDirectorUiStateTest {
    private val memberId = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val source = Assistant(name = "Alice")
    private val group = Assistant(
        name = "Cast",
        assistantType = AssistantType.GROUP,
        turnTakingStrategy = TurnTakingStrategy.AUTO_ROUND_ROBIN,
        groupMembers = listOf(
            GroupMember(id = memberId, assistantId = source.id, displayName = "Aileen")
        ),
    )
    private val settings = Settings(assistants = listOf(group, source))

    @Test
    fun `non group assistant has no director ui`() {
        val solo = Assistant(name = "Solo")
        val conversation = Conversation(assistantId = solo.id, messageNodes = emptyList())

        assertNull(buildGroupDirectorUiState(conversation, solo, Settings(assistants = listOf(solo)), false))
    }

    @Test
    fun `mapper uses override and normalizes stale pending pause for display`() {
        val conversation = Conversation(
            assistantId = group.id,
            messageNodes = emptyList(),
            groupRuntimeState = GroupRuntimeState(
                director = GroupDirectorState(
                    modeOverride = TurnTakingStrategy.MANUAL,
                    playbackState = GroupPlaybackState.PAUSE_AFTER_CURRENT,
                    oneShotNextMemberId = memberId,
                )
            ),
        )

        val state = buildGroupDirectorUiState(conversation, group, settings, false)!!

        assertEquals(TurnTakingStrategy.MANUAL, state.effectiveMode)
        assertEquals(GroupPlaybackState.PAUSED, state.playbackState)
        assertEquals("Aileen", state.members.single().name)
        assertTrue(state.members.single().isQueuedNext)
        assertFalse(state.isGenerating)
    }
}
