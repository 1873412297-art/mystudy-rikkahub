package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TavernScriptDiagnosticsTest {
    @Test
    fun `diagnostic entry survives JSON round trip`() {
        val entry = TavernScriptDiagnosticEntry(
            timestamp = 123L,
            level = TavernScriptDiagnosticLevel.ERROR,
            category = "rpc",
            message = "request rejected",
            rpcMethod = "messages.send",
            durationMs = 42L,
            error = "timeout",
        )

        val decoded = Json.decodeFromString<TavernScriptDiagnosticEntry>(Json.encodeToString(entry))

        assertEquals(entry, decoded)
    }

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
    fun `redacts JSON cookie basic authorization token api key and custom header values`() {
        val store = TavernScriptDiagnosticsStore()
        store.record(
            scriptId = "one",
            level = TavernScriptDiagnosticLevel.INFO,
            category = "console",
            message = """{"headers":{"Cookie":"session=raw-cookie","Authorization":"Basic dXNlcjpwYXNz","X-Company-Trace":"custom-header-secret"},"api_key":"api-secret","token":"token-secret"}""",
        )

        val message = store.entries("one").single().message
        assertFalse(message.contains("raw-cookie"))
        assertFalse(message.contains("dXNlcjpwYXNz"))
        assertFalse(message.contains("custom-header-secret"))
        assertFalse(message.contains("api-secret"))
        assertFalse(message.contains("token-secret"))
    }

    @Test
    fun `redacts quoted headers embedded in a console message and root X header JSON`() {
        val store = TavernScriptDiagnosticsStore()
        store.record(
            scriptId = "one",
            level = TavernScriptDiagnosticLevel.INFO,
            category = "console",
            message = "request headers {\"Authorization\":\"Basic basic-secret\",\"Cookie\":\"sid=cookie-secret\",\"X-Company-Trace\":\"custom-secret\"}",
        )
        store.record(
            scriptId = "two",
            level = TavernScriptDiagnosticLevel.INFO,
            category = "console",
            message = "{\"X-Company-Trace\":\"root-custom-secret\"}",
        )

        val embedded = store.entries("one").single().message
        val root = store.entries("two").single().message
        assertFalse(embedded.contains("basic-secret"))
        assertFalse(embedded.contains("cookie-secret"))
        assertFalse(embedded.contains("custom-secret"))
        assertFalse(root.contains("root-custom-secret"))
    }

    @Test
    fun `redacts quoted sensitive properties even without a surrounding JSON object`() {
        val message = redactScriptDiagnostic("header pair \"Authorization\":\"Basic standalone-secret\" \"X-Company-Trace\":\"standalone-custom\"")

        assertFalse(message.contains("standalone-secret"))
        assertFalse(message.contains("standalone-custom"))
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

    @Test
    fun `folder disabled makes an otherwise running child disabled`() {
        assertEquals(
            TavernScriptRuntimeStatus.DISABLED,
            effectiveTavernScriptStatus(
                scriptEnabled = true,
                folderEnabled = false,
                runtimeStatus = TavernScriptRuntimeStatus.RUNNING,
            ),
        )
    }

    @Test
    fun `concurrent script updates retain every status log and revision`() {
        val store = TavernScriptDiagnosticsStore()
        val workers = 64
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val done = CountDownLatch(workers)
        val executor = Executors.newFixedThreadPool(workers)
        repeat(workers) { index ->
            executor.execute {
                ready.countDown()
                start.await()
                store.setStatus("script-$index", TavernScriptRuntimeStatus.RUNNING)
                store.record("script-$index", TavernScriptDiagnosticLevel.INFO, "console", "log-$index")
                done.countDown()
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals(workers, store.statuses.value.size)
        assertEquals(workers, store.revision.value)
        repeat(workers) { index -> assertEquals(listOf("log-$index"), store.entries("script-$index").map { it.message }) }
    }
}
