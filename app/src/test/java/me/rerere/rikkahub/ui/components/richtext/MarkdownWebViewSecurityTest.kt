package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownWebViewSecurityTest {
    @Test
    fun `renderer failure preserves source through retry generations`() {
        val source = "# opening\n<script>window.demo = true</script>"
        val failed = MarkdownWebViewRenderState.initial(source)
            .onFailure(generation = 0, reason = "renderer crashed")
        val retrying = failed.retry()

        assertEquals(MarkdownWebViewRenderStatus.FAILED, failed.status)
        assertEquals(source, failed.rawContent)
        assertEquals(1, retrying.generation)
        assertEquals(MarkdownWebViewRenderStatus.LOADING, retrying.status)
        assertEquals(source, retrying.rawContent)
        assertEquals(retrying, retrying.onReady(generation = 0))
        assertEquals(MarkdownWebViewRenderStatus.READY, retrying.onReady(generation = 1).status)
    }

    @Test
    fun `compose fallback preserves source and ignores stale renderer callbacks`() {
        val switched = MarkdownWebViewRenderState.initial("raw source")
            .onFailure(generation = 0, reason = "timed out")
            .switchToCompose()

        assertEquals(MarkdownWebViewRenderStatus.COMPOSE, switched.status)
        assertEquals("raw source", switched.rawContent)
        assertEquals(switched, switched.onReady(generation = 0))
        assertEquals(switched, switched.onFailure(generation = 0, reason = "late crash"))
    }

    @Test
    fun `external navigation permits only explicit safe protocols`() {
        assertTrue(isAllowedMarkdownExternalLink(" HTTPS://example.com/path "))
        assertTrue(isAllowedMarkdownExternalLink("mailto:user@example.com"))
        assertTrue(isAllowedMarkdownExternalLink("tel:+8612345"))
        assertFalse(isAllowedMarkdownExternalLink("javascript:alert(1)"))
        assertFalse(isAllowedMarkdownExternalLink("file:///sdcard/secret"))
        assertFalse(isAllowedMarkdownExternalLink("content://provider/private"))
        assertFalse(isAllowedMarkdownExternalLink("intent://scan/#Intent;end"))
        assertFalse(isAllowedMarkdownExternalLink("data:text/html,boom"))
        assertFalse(isAllowedMarkdownExternalLink("httpsx://example.com"))
    }

    @Test
    fun `subresource policy always blocks local providers and gates tavern network`() {
        assertFalse(shouldAllowMarkdownSubresource("file:///sdcard/secret", true, false))
        assertFalse(shouldAllowMarkdownSubresource("content://provider/private", true, false))
        assertFalse(shouldAllowMarkdownSubresource("javascript:alert(1)", true, false))
        assertTrue(shouldAllowMarkdownSubresource("data:image/png;base64,AA==", false, true))
        assertTrue(shouldAllowMarkdownSubresource("blob:https://rikkahub.local/id", false, true))
        assertFalse(shouldAllowMarkdownSubresource("https://example.com/image.png", false, true))
        assertTrue(shouldAllowMarkdownSubresource("https://example.com/image.png", true, true))
        assertTrue(shouldAllowMarkdownSubresource("https://example.com/image.png", false, false))
    }
}
