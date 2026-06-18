package me.rerere.rikkahub.ui.components.richtext.st

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.ui.components.richtext.RichTextSegment

@Serializable
internal enum class StableDomRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL,
}

@Serializable
internal data class StableDomSegment(
    val id: String,
    val kind: RichTextSegment.Kind,
    val raw: String,
)

@Serializable
internal data class StableDomMessage(
    val id: String,
    val role: StableDomRole,
    val segments: List<StableDomSegment>,
    val streaming: Boolean,
)

