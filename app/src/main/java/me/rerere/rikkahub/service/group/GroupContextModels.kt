package me.rerere.rikkahub.service.group

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.GroupContextOptions
import me.rerere.rikkahub.data.model.TurnTakingStrategy
import kotlin.uuid.Uuid

@Serializable
data class GroupRuntimeState(
    val privateNotes: Map<Uuid, String> = emptyMap(),
    @Serializable(with = GroupRelationshipMapSerializer::class)
    val relationships: Map<GroupRelationshipKey, GroupRelationshipState> = emptyMap(),
    val scene: GroupSceneState = GroupSceneState(),
    val eventState: GroupEventState = GroupEventState(),
    val activeAddressedMemberId: Uuid? = null,
    val activeAddressedTurnId: Uuid? = null,
    val lastResolverDebug: GroupResolverDebugState? = null,
    val director: GroupDirectorState = GroupDirectorState(),
)

@Serializable
data class GroupDirectorState(
    val modeOverride: TurnTakingStrategy? = null,
    val playbackState: GroupPlaybackState = GroupPlaybackState.RUNNING,
    val oneShotNextMemberId: Uuid? = null,
    val oneShotReturnToPaused: Boolean = false,
    val oneRoundActive: Boolean = false,
    val oneRoundRemainingMemberIds: List<Uuid> = emptyList(),
    val skipNextRequested: Boolean = false,
)

@Serializable
enum class GroupPlaybackState {
    RUNNING,
    PAUSE_AFTER_CURRENT,
    PAUSED,
}

@Serializable
data class GroupRelationshipKey(
    val fromMemberId: Uuid,
    val toMemberId: Uuid,
)

@Serializable
data class GroupRelationshipState(
    val affinity: Int = 0,
    val tension: Int = 0,
    val note: String = "",
)

@Serializable
private data class GroupRelationshipEntry(
    val key: GroupRelationshipKey,
    val value: GroupRelationshipState,
)

private object GroupRelationshipMapSerializer : KSerializer<Map<GroupRelationshipKey, GroupRelationshipState>> {
    private val listSerializer = ListSerializer(GroupRelationshipEntry.serializer())

    override val descriptor: SerialDescriptor = listSerializer.descriptor

    override fun serialize(encoder: Encoder, value: Map<GroupRelationshipKey, GroupRelationshipState>) {
        val entries = value.map { (key, state) ->
            GroupRelationshipEntry(key = key, value = state)
        }
        listSerializer.serialize(encoder, entries)
    }

    override fun deserialize(decoder: Decoder): Map<GroupRelationshipKey, GroupRelationshipState> {
        return listSerializer.deserialize(decoder).associate { entry ->
            entry.key to entry.value
        }
    }
}

@Serializable
data class GroupSceneState(
    val summary: String = "",
    val tension: Int = 0,
    val activeSecrets: List<String> = emptyList(),
)

@Serializable
data class GroupEventState(
    val recentEvents: List<GroupEventRecord> = emptyList(),
    val activeFocus: GroupEventFocus? = null,
)

@Serializable
data class GroupEventRecord(
    val sourceMessageId: Uuid,
    val speakerId: Uuid? = null,
    val characters: List<Uuid> = emptyList(),
    val locations: List<String> = emptyList(),
    val items: List<String> = emptyList(),
    val events: List<String> = emptyList(),
    val secrets: List<String> = emptyList(),
    val emotions: List<String> = emptyList(),
    val conflicts: List<String> = emptyList(),
    val importance: Int = 0,
)

@Serializable
data class GroupEventFocus(
    val characterIds: List<Uuid> = emptyList(),
    val locations: List<String> = emptyList(),
    val items: List<String> = emptyList(),
    val events: List<String> = emptyList(),
    val secrets: List<String> = emptyList(),
    val emotions: List<String> = emptyList(),
    val conflicts: List<String> = emptyList(),
)

@Serializable
data class GroupSpeakingIntent(
    val speakerId: Uuid,
    val intent: String,
    val reason: String,
)

enum class GroupContextLayer {
    CORE,
    STRONGLY_RELATED,
    WEAKLY_RELATED,
    ISOLATED,
}

data class GroupContextScoreBreakdown(
    val eventRelevance: Int = 0,
    val recentInteraction: Int = 0,
    val relationshipWeight: Int = 0,
    val total: Int = 0,
)

@Serializable
data class GroupResolverDebugState(
    val speakerId: Uuid,
    val layer: String,
    val eventRelevance: Int = 0,
    val recentInteraction: Int = 0,
    val relationshipWeight: Int = 0,
    val total: Int = 0,
    val focusCharacters: List<Uuid> = emptyList(),
    val focusLocations: List<String> = emptyList(),
    val focusItems: List<String> = emptyList(),
    val focusEvents: List<String> = emptyList(),
    val focusSecrets: List<String> = emptyList(),
    val focusEmotions: List<String> = emptyList(),
    val focusConflicts: List<String> = emptyList(),
)

data class GroupContextBuildInput(
    val visibleMessages: List<UIMessage>,
    val groupAssistant: Assistant,
    val effectiveMemberId: Uuid,
    val runtimeState: GroupRuntimeState,
    val contextOptions: GroupContextOptions = GroupContextOptions(),
    val speakingIntent: GroupSpeakingIntent? = null,
)

data class GroupContextBuildResult(
    val messages: List<UIMessage>,
    val debugSections: List<String>,
)
