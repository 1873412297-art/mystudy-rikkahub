package me.rerere.rikkahub.data.ai.status

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class StatusMessageBodyFilterTest {

    @Test
    fun `text display copy keeps narrative and removes status markup`() {
        val original = UIMessagePart.Text(
            "<maintext>Story</maintext>\n<Status_block>HP 10/10</Status_block>",
        )

        val filtered = original.withoutInlineStatus() as UIMessagePart.Text

        assertEquals("Story", filtered.text)
        assertEquals("<maintext>Story</maintext>\n<Status_block>HP 10/10</Status_block>", original.text)
    }

    @Test
    fun `unfinished streaming status is excluded from body immediately`() {
        val filtered = UIMessagePart.Text("Story\n<status_block>partial")
            .withoutInlineStatus() as UIMessagePart.Text

        assertEquals("Story", filtered.text)
    }

    @Test
    fun `structured status placeholder belongs only to the HUD`() {
        assertNull(UIMessagePart.StatusPlaceholder("<b>HP</b>").withoutInlineStatus())
    }

    @Test
    fun `non status parts are preserved by identity`() {
        val image = UIMessagePart.Image("content://portrait")

        assertSame(image, image.withoutInlineStatus())
    }
}
