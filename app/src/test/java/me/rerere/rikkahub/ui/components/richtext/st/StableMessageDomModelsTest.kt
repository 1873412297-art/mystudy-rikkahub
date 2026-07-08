package me.rerere.rikkahub.ui.components.richtext.st

import me.rerere.rikkahub.ui.components.richtext.RichTextSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class StableMessageDomModelsTest {
    @Test
    fun `dom message stores role and ordered segments`() {
        val message = StableDomMessage(
            id = "m1",
            role = StableDomRole.ASSISTANT,
            segments = listOf(
                StableDomSegment("s1", RichTextSegment.Kind.MARKDOWN, "hello"),
                StableDomSegment("s2", RichTextSegment.Kind.STATUS_BLOCK, "<Status_block>x</Status_block>"),
            ),
            streaming = false,
        )

        assertEquals("m1", message.id)
        assertEquals(StableDomRole.ASSISTANT, message.role)
        assertEquals(listOf("s1", "s2"), message.segments.map { it.id })
    }
}
