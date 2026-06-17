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
import kotlin.uuid.Uuid

@Serializable
data class GroupRuntimeState(
    val privateNotes: Map<Uuid, String> = emptyMap(),
    @Serializable(with = GroupRelationshipMapSerializer::class)
    val relationships: Map<GroupRelationshipKey, GroupRelationshipState> = emptyMap(),
    val scene: GroupSceneState = GroupSceneState(),
)

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
data class GroupSpeakingIntent(
    val speakerId: Uuid,
    val intent: String,
    val reason: String,
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
