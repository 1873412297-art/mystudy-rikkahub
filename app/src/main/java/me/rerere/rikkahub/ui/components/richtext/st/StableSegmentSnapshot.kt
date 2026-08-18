package me.rerere.rikkahub.ui.components.richtext.st

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.ui.components.richtext.RichTextSegment

/**
 * 流式期间对 st-message DOM 的增量 patch（宿主 → RikkahubDomBridge.applySegmentPatch）。
 */
@Serializable
internal data class SegmentPatch(
    val segmentId: String,
    val kind: RichTextSegment.Kind,
    val raw: String,
)

private val patchJson = Json { encodeDefaults = true }

/**
 * 段快照 diff：id 相同 raw 相同 → 跳过；id 相同 raw 不同 → 替换；新增 id → 追加。
 * 不做删除/重排（流式场景只增改）。
 */
internal object StableSegmentSnapshot {

    fun diff(old: List<StableDomSegment>, new: List<StableDomSegment>): List<SegmentPatch> {
        val patches = mutableListOf<SegmentPatch>()
        val oldById = old.associateBy { it.id }
        new.forEach { segment ->
            val previous = oldById[segment.id]
            if (previous == null || previous.raw != segment.raw) {
                patches.add(SegmentPatch(segmentId = segment.id, kind = segment.kind, raw = segment.raw))
            }
        }
        return patches
    }

    fun encodePatches(patches: List<SegmentPatch>): String = patchJson.encodeToString(patches)
}
