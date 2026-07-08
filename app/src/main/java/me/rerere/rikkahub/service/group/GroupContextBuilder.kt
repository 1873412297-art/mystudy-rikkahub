package me.rerere.rikkahub.service.group

import me.rerere.ai.ui.UIMessage

class GroupContextBuilder {
    fun build(input: GroupContextBuildInput): GroupContextBuildResult {
        val speaker = input.groupAssistant.groupMembers.find { it.id == input.effectiveMemberId }
        val speakerName = speaker?.displayName?.takeIf { it.isNotBlank() } ?: "Current speaker"
        val visibleMemberIds = input.visibleMessages.mapNotNull { it.memberId }.toSet()

        val system = buildString {
            appendLine("Private viewpoint for $speakerName")
            appendLine("Use this context as hidden roleplay state. Do not quote section labels directly.")
            appendLine()

            if (input.contextOptions.enablePrivateViewpoint) {
                val privateNote = input.runtimeState.privateNotes[input.effectiveMemberId].orEmpty()
                if (privateNote.isNotBlank()) {
                    appendLine("Private memory:")
                    appendLine(privateNote.take(input.contextOptions.maxPrivateNoteChars))
                    appendLine()
                }
            }

            if (input.contextOptions.enableSceneState) {
                if (input.runtimeState.scene.summary.isNotBlank() || input.runtimeState.scene.activeSecrets.isNotEmpty()) {
                    appendLine(
                        "Scene: ${
                            input.runtimeState.scene.summary
                                .toRecentSceneSummary()
                                .ifBlank { "No scene summary." }
                                .take(input.contextOptions.maxSceneSummaryChars)
                        }"
                    )
                    appendLine("Scene tension: ${input.runtimeState.scene.tension}")
                    if (input.runtimeState.scene.activeSecrets.isNotEmpty()) {
                        appendLine("Active secrets:")
                        input.runtimeState.scene.activeSecrets.forEach { appendLine("- $it") }
                    }
                    appendLine()
                }
            }

            if (input.contextOptions.enableRelationshipNotes) {
                val relationships = input.runtimeState.relationships.filterKeys { key ->
                    key.fromMemberId == input.effectiveMemberId && key.toMemberId in visibleMemberIds
                }
                if (relationships.isNotEmpty()) {
                    appendLine("Relationship notes:")
                    relationships.forEach { (key, state) ->
                        val target = input.groupAssistant.groupMembers.find { it.id == key.toMemberId }
                        val targetName = target?.displayName?.takeIf { it.isNotBlank() } ?: key.toMemberId.toString()
                        appendLine("- toward $targetName: affinity=${state.affinity}, tension=${state.tension}, note=${state.note}")
                    }
                    appendLine()
                }
            }

            input.speakingIntent?.let { intent ->
                appendLine("Speaking intent: ${intent.intent}")
                appendLine("Intent reason: ${intent.reason}")
                appendLine("Response guidance: ${intent.toResponseGuidance()}")
                appendLine()
            }
        }.trim()

        val messages = if (system.isBlank()) {
            input.visibleMessages
        } else {
            listOf(UIMessage.system(system)) + input.visibleMessages
        }
        return GroupContextBuildResult(
            messages = messages,
            debugSections = if (system.isBlank()) emptyList() else listOf(system),
        )
    }
}

private fun String.toRecentSceneSummary(maxLines: Int = 5): String {
    return lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .takeLast(maxLines)
        .joinToString("\n")
}

private fun GroupSpeakingIntent.toResponseGuidance(): String {
    return when (intent) {
        "answer_user" -> "Answer directly in this character's established card voice. Use a fuller reply when addressed: usually 120-260 Chinese characters with dialogue plus brief action or emotion."
        "challenge" -> "Let the character push back in their established voice. Use enough detail to show motive, attitude, and body language; usually 100-220 Chinese characters."
        "comfort" -> "Respond warmly in character with dialogue and a small action beat; usually 100-220 Chinese characters."
        else -> "Stay faithful to the character card. Avoid generic one-line replies; use a natural length for the scene, usually 80-180 Chinese characters."
    }
}
