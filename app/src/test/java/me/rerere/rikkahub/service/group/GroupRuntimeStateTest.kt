package me.rerere.rikkahub.service.group

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.model.TurnTakingStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class GroupRuntimeStateTest {
    private val memberA = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val memberB = Uuid.parse("00000000-0000-0000-0000-000000000002")

    @Test
    fun `runtime state serializes private notes relationships and scene`() {
        val state = GroupRuntimeState(
            privateNotes = mapOf(memberA to "A knows the hidden door."),
            relationships = mapOf(
                GroupRelationshipKey(memberA, memberB) to GroupRelationshipState(
                    affinity = 2,
                    tension = 4,
                    note = "A distrusts B but listens carefully.",
                )
            ),
            scene = GroupSceneState(
                summary = "Night meeting in the shrine.",
                tension = 6,
                activeSecrets = listOf("The guest is not human."),
            ),
            eventState = GroupEventState(
                recentEvents = listOf(
                    GroupEventRecord(
                        sourceMessageId = Uuid.parse("00000000-0000-0000-0000-000000000099"),
                        speakerId = memberA,
                        characters = listOf(memberA, memberB),
                        locations = listOf("Shrine"),
                        items = listOf("Jade key"),
                        events = listOf("warns"),
                        secrets = listOf("The guest is not human."),
                        emotions = listOf("suspicious"),
                        conflicts = listOf("distrust"),
                        importance = 8,
                    )
                ),
                activeFocus = GroupEventFocus(
                    characterIds = listOf(memberA),
                    locations = listOf("Shrine"),
                    items = listOf("Jade key"),
                    events = listOf("warning"),
                    secrets = listOf("hidden guest"),
                    emotions = listOf("tense"),
                    conflicts = listOf("distrust"),
                ),
            ),
            activeAddressedMemberId = memberB,
            activeAddressedTurnId = Uuid.parse("00000000-0000-0000-0000-000000000100"),
        )

        val json = Json.encodeToString(state)
        val decoded = Json.decodeFromString<GroupRuntimeState>(json)

        assertEquals("A knows the hidden door.", decoded.privateNotes[memberA])
        assertEquals(4, decoded.relationships[GroupRelationshipKey(memberA, memberB)]?.tension)
        assertTrue(decoded.scene.activeSecrets.contains("The guest is not human."))
        assertEquals(memberB, decoded.activeAddressedMemberId)
        assertEquals(
            listOf("Jade key"),
            decoded.eventState.recentEvents.single().items,
        )
        assertEquals(
            listOf("hidden guest"),
            decoded.eventState.activeFocus?.secrets,
        )
    }

    @Test
    fun `runtime state round trips every director field`() {
        val state = GroupRuntimeState(
            director = GroupDirectorState(
                modeOverride = TurnTakingStrategy.AUTO_MODERATOR,
                playbackState = GroupPlaybackState.PAUSE_AFTER_CURRENT,
                oneShotNextMemberId = memberA,
                oneShotReturnToPaused = true,
                oneRoundActive = true,
                oneRoundRemainingMemberIds = listOf(memberA, memberB),
                skipNextRequested = true,
            )
        )

        val decoded = Json.decodeFromString<GroupRuntimeState>(Json.encodeToString(state))

        assertEquals(state.director, decoded.director)
    }

    @Test
    fun `legacy runtime json without director uses defaults`() {
        val decoded = Json.decodeFromString<GroupRuntimeState>("{}")

        assertEquals(GroupDirectorState(), decoded.director)
    }
}
