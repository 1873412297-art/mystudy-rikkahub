package me.rerere.rikkahub.ui.components.richtext.st

import me.rerere.rikkahub.ui.components.richtext.RichTextSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class StableSegmentSnapshotTest {

    private fun seg(id: String, raw: String, kind: RichTextSegment.Kind = RichTextSegment.Kind.MARKDOWN) =
        StableDomSegment(id = id, kind = kind, raw = raw)

    @Test
    fun `no patch when segments unchanged`() {
        val old = listOf(seg("s0", "hello"), seg("s1", "world"))
        val patches = StableSegmentSnapshot.diff(old, old)
        assertEquals(emptyList<SegmentPatch>(), patches)
    }

    @Test
    fun `replace patch when segment text changes`() {
        val old = listOf(seg("s0", "hello"))
        val new = listOf(seg("s0", "hello world"))
        val patches = StableSegmentSnapshot.diff(old, new)
        assertEquals(listOf(SegmentPatch(segmentId = "s0", kind = RichTextSegment.Kind.MARKDOWN, raw = "hello world")), patches)
    }

    @Test
    fun `append patch for new trailing segments`() {
        val old = listOf(seg("s0", "hello"))
        val new = listOf(seg("s0", "hello"), seg("s1", "world"))
        val patches = StableSegmentSnapshot.diff(old, new)
        assertEquals(
            listOf(SegmentPatch(segmentId = "s1", kind = RichTextSegment.Kind.MARKDOWN, raw = "world")),
            patches
        )
    }

    @Test
    fun `mixed replace and append`() {
        val old = listOf(seg("s0", "a"), seg("s1", "b"))
        val new = listOf(seg("s0", "a2"), seg("s1", "b"), seg("s2", "c"))
        val patches = StableSegmentSnapshot.diff(old, new)
        assertEquals(
            listOf(
                SegmentPatch(segmentId = "s0", kind = RichTextSegment.Kind.MARKDOWN, raw = "a2"),
                SegmentPatch(segmentId = "s2", kind = RichTextSegment.Kind.MARKDOWN, raw = "c"),
            ),
            patches
        )
    }

    @Test
    fun `status block segments produce non markdown patches`() {
        val old = listOf(seg("s0", "narrative"), seg("s1", "<status_block>...", RichTextSegment.Kind.STATUS_BLOCK))
        val new = listOf(seg("s0", "narrative"), seg("s1", "<status_block>updated", RichTextSegment.Kind.STATUS_BLOCK))
        val patches = StableSegmentSnapshot.diff(old, new)
        assertEquals(
            listOf(SegmentPatch(segmentId = "s1", kind = RichTextSegment.Kind.STATUS_BLOCK, raw = "<status_block>updated")),
            patches
        )
    }
}
