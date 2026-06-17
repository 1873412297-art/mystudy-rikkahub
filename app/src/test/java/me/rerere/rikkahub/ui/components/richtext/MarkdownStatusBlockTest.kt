package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownStatusBlockTest {
    @Test
    fun `standard status tag is detected`() {
        assertTrue(containsStatusBlockTag("<status>hello</status>"))
    }

    @Test
    fun `legacy status exclamation tag is detected`() {
        assertTrue(containsStatusBlockTag("<status!>hello</status!>"))
    }

    @Test
    fun `status block alias is detected`() {
        assertTrue(containsStatusBlockTag("<Status_block>hello</Status_block>"))
    }

    @Test
    fun `unterminated status block is still detected`() {
        assertTrue(containsStatusBlockTag("<Status_block>hello\nworld"))
    }

    @Test
    fun `plain markdown is not treated as status block`() {
        assertFalse(containsStatusBlockTag("hello\n\nworld"))
    }
}
