package me.rerere.rikkahub.service.group

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import kotlin.uuid.Uuid

enum class AddressingSource {
    DIRECT_NAME,
    AT_MENTION,
    CONTINUATION,
}

data class AddressedMemberResolution(
    val memberId: Uuid,
    val source: AddressingSource,
)

internal fun resolveAddressedMember(
    groupAssistant: Assistant,
    userText: String,
    previousAddressedMemberId: Uuid?,
): AddressedMemberResolution? {
    if (groupAssistant.assistantType != AssistantType.GROUP) return null
    val normalizedText = userText.trim()
    if (normalizedText.isBlank()) return null

    groupAssistant.groupMembers
        .filter { it.enabled && it.displayName.isNotBlank() }
        .forEach { member ->
            val name = member.displayName.trim()
            if (Regex("""@${Regex.escape(name)}(?:\s|$|[，。！？,.!?])""").containsMatchIn(normalizedText)) {
                return AddressedMemberResolution(member.id, AddressingSource.AT_MENTION)
            }
        }

    groupAssistant.groupMembers
        .filter { it.enabled && it.displayName.isNotBlank() }
        .forEach { member ->
            if (normalizedText.contains(member.displayName, ignoreCase = true)) {
                return AddressedMemberResolution(member.id, AddressingSource.DIRECT_NAME)
            }
        }

    if (previousAddressedMemberId != null && normalizedText.isSecondPersonContinuation()) {
        return AddressedMemberResolution(previousAddressedMemberId, AddressingSource.CONTINUATION)
    }

    return null
}

private fun String.isSecondPersonContinuation(): Boolean {
    val continuationPhrases = listOf(
        "你继续",
        "你来说",
        "你先说",
        "你接着说",
        "你怎么看",
        "你觉得呢",
        "你呢",
        "你继续说",
    )
    if (continuationPhrases.any { contains(it, ignoreCase = true) }) {
        return true
    }
    return trim() == "你"
}
