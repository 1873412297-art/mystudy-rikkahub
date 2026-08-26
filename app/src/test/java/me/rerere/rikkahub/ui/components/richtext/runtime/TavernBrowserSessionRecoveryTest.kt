package me.rerere.rikkahub.ui.components.richtext.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class TavernBrowserSessionRecoveryTest {
    @Test
    fun `renderer loss rebuilds only affected script and reload rebuilds without live view`() {
        val recovery = TavernBrowserSessionRecovery()

        assertEquals(0, recovery.generation("one"))
        assertEquals(TavernBrowserReloadAction.RELOAD_LIVE, recovery.requestReload("one", hasLiveView = true))
        assertEquals(0, recovery.generation("one"))

        assertEquals(1, recovery.rendererGone("one"))
        assertEquals(1, recovery.generation("one"))
        assertEquals(0, recovery.generation("two"))

        assertEquals(TavernBrowserReloadAction.REBUILD, recovery.requestReload("one", hasLiveView = false))
        assertEquals(2, recovery.generation("one"))
        assertEquals(0, recovery.generation("two"))
    }
}
