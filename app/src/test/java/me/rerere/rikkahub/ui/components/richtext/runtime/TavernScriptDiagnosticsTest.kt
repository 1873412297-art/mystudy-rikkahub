package me.rerere.rikkahub.ui.components.richtext.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernScriptDiagnosticsTest {
    @Test
    fun `keeps only the latest 500 entries for one script in insertion order`() {
        var now = 0L
        val store = TavernScriptDiagnosticsStore { ++now }

        repeat(501) { index ->
            store.record("one", TavernScriptDiagnosticLevel.INFO, "console", "entry-$index")
        }

        val entries = store.entries("one")
        assertEquals(500, entries.size)
        assertEquals("entry-1", entries.first().message)
        assertEquals("entry-500", entries.last().message)
    }

    @Test
    fun `redacts secrets before retaining diagnostic messages and errors`() {
        val store = TavernScriptDiagnosticsStore()
        store.record(
            scriptId = "one",
            level = TavernScriptDiagnosticLevel.ERROR,
            category = "rpc",
            message = "Authorization: Bearer top-secret Cookie: session=abc X-Api-Key: key-value {\"Authorization\":\"Bearer json-secret\"}",
            error = "token=second-secret",
        )

        val entry = store.entries("one").single()
        assertFalse(entry.message.contains("top-secret"))
        assertFalse(entry.message.contains("session=abc"))
        assertFalse(entry.message.contains("key-value"))
        assertFalse(entry.message.contains("json-secret"))
        assertFalse(entry.error.orEmpty().contains("second-secret"))
        assertTrue(entry.message.contains("[已隐藏]"))
    }

    @Test
    fun `keeps diagnostics isolated by script and clear affects only selected script`() {
        val store = TavernScriptDiagnosticsStore()
        store.record("one", TavernScriptDiagnosticLevel.INFO, "console", "one-log")
        store.record("two", TavernScriptDiagnosticLevel.ERROR, "console", "two-log")

        store.clear("one")

        assertTrue(store.entries("one").isEmpty())
        assertEquals(listOf("two-log"), store.entries("two").map { it.message })
    }

    @Test
    fun `disabled model status overrides stale runtime state and has Chinese label`() {
        val store = TavernScriptDiagnosticsStore()
        store.setStatus("one", TavernScriptRuntimeStatus.OVER_LIMIT)

        assertEquals(TavernScriptRuntimeStatus.DISABLED, store.statusFor(false, "one"))
        assertEquals("超出运行上限", tavernScriptStatusLabel(TavernScriptRuntimeStatus.OVER_LIMIT))
        assertEquals("加载失败", tavernScriptStatusLabel(TavernScriptRuntimeStatus.LOAD_FAILED))
    }
}
