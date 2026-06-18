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

    @Test
    fun `bare json patch is detected for webview rendering`() {
        val content = """- Time passed: a few moments
[ { "op": "replace", "path": "/\u5b5f\u79cb\u5a18/\u8863\u7740", "value": "\u7d20\u8272\u8936\u5b50\u51cc\u4e71\u534a\u89e3" } ]"""

        assertTrue(containsJsonPatchBlockTag(content))
    }

    @Test
    fun `generic json array is not forced into json patch rendering`() {
        assertFalse(containsJsonPatchBlockTag("[1, 2, 3]"))
    }
}
