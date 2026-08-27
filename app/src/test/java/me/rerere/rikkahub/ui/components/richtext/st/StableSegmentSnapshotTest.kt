package me.rerere.rikkahub.ui.components.richtext.st

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    @Test
    fun `encodePatches emits the field contract consumed by applySegmentPatch`() {
        // st-message.html applySegmentPatch 逐字消费 p.segmentId / p.kind / p.raw，
        // kind 按枚举名序列化（'MARKDOWN' 等字符串比较）。字段名变更会破坏流式增量渲染。
        val patches = StableSegmentSnapshot.diff(
            old = listOf(seg("segment-0", "old")),
            new = listOf(
                seg("segment-0", "new"),
                seg("segment-1", "<div>x</div>", RichTextSegment.Kind.FRONTEND_HTML),
            ),
        )
        val encoded = StableSegmentSnapshot.encodePatches(patches)
        val array = kotlinx.serialization.json.Json.parseToJsonElement(encoded).jsonArray
        assertEquals(2, array.size)
        val first = array[0].jsonObject
        assertEquals("segment-0", first.getValue("segmentId").jsonPrimitive.content)
        assertEquals("MARKDOWN", first.getValue("kind").jsonPrimitive.content)
        assertEquals("new", first.getValue("raw").jsonPrimitive.content)
        val second = array[1].jsonObject
        assertEquals("FRONTEND_HTML", second.getValue("kind").jsonPrimitive.content)
    }

    @Test
    fun `diff never emits removals for shrunk inputs`() {
        val old = listOf(seg("s0", "a"), seg("s1", "b"))
        assertEquals(emptyList<SegmentPatch>(), StableSegmentSnapshot.diff(old, emptyList()))
    }
}
