package me.rerere.rikkahub.ui.pages.chat.tavern

import android.app.Activity
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class TavernConversationDocumentInstrumentedTest {

    @Test
    fun documentRendersSTShapePluginsRawHtmlAndSanitizesMarkdownStyleChannel() {
        val rawHtml = "<!DOCTYPE html><html><body><script>window.rawRan=true</script><b>raw-html</b></body></html>"
        val markdown = """
            <style id="bad-style">body { display: none; }</style>
            <span id="unsafe" style="color:red" onclick="window.pwned=true">safe</span>

            - [x] completed

            Inline math: ${'$'}x^2${'$'}

            ```mermaid
            graph TD
              A --> B
            ```
        """.trimIndent()
        val initial = snapshot(
            nodes = listOf(
                node("n1", 0, 1, message("m1", markdown)),
                node("n2", 0, 1, message("m2", rawHtml, UIMessagePart.RenderMode.HTML)),
            ),
        )
        val html = buildTavernConversationDocument(
            InstrumentationRegistry.getInstrumentation().targetContext,
            initial,
        )

        val result = withVisibleWebView(html) { view ->
            awaitJson(view, 60) {
                """
                (function(){
                  var iframe = document.querySelector('[data-html-frame]');
                  var unsafe = document.getElementById('unsafe');
                  var mermaid = document.querySelector('.mermaid');
                  return JSON.stringify({
                    ready: !!(iframe && unsafe && document.querySelector('.task-list-item') &&
                      document.querySelector('.katex') && mermaid && mermaid.querySelector('svg')),
                    mesCount: document.querySelectorAll('.mes').length,
                    hasShape: !!document.querySelector('.mes > .mes_block > .name_text + .mes_text'),
                    taskList: !!document.querySelector('.task-list-item input[type="checkbox"]'),
                    katex: !!document.querySelector('.katex'),
                    mermaidSvg: !!(mermaid && mermaid.querySelector('svg')),
                    badStyleTag: !!document.getElementById('bad-style'),
                    unsafeStyle: unsafe && unsafe.getAttribute('style'),
                    unsafeClick: unsafe && unsafe.getAttribute('onclick'),
                    rawSrcdoc: iframe && iframe.srcdoc,
                    sandbox: iframe && iframe.getAttribute('sandbox')
                  });
                })();
                """.trimIndent()
            }
        }

        assertEquals(2, result.getInt("mesCount"))
        assertTrue(result.getBoolean("hasShape"))
        assertTrue(result.getBoolean("taskList"))
        assertTrue(result.getBoolean("katex"))
        assertTrue(result.getBoolean("mermaidSvg"))
        assertFalse(result.getBoolean("badStyleTag"))
        assertTrue(result.isNull("unsafeStyle"))
        assertTrue(result.isNull("unsafeClick"))
        assertEquals(rawHtml, result.getString("rawSrcdoc"))
        assertEquals("allow-scripts", result.getString("sandbox"))
    }

    @Test
    fun applyPatchesMutatesNodesBranchesStreamingAndClearsStaleThemeVariables() {
        val initial = snapshot(
            nodes = listOf(node("n1", 0, 1, message("m1", "old"))),
            theme = linkedMapOf("--old-only" to "red", "--shared" to "before"),
        )
        val updated = message("m1b", """
            updated **bold**

            ```mermaid
            graph LR
              X --> Y
            ```
        """.trimIndent())
        val added = message("m2", "added")
        val firstPatches: List<TavernConversationPatch> = listOf(
            TavernConversationPatch.UpsertMessage("n1", 0, updated),
            TavernConversationPatch.SelectBranch("n1", 1, "m1b", 2),
            TavernConversationPatch.UpsertMessage("n2", 1, added),
            TavernConversationPatch.SelectBranch("n2", 0, "m2", 1),
            TavernConversationPatch.SetStreaming(true),
        )
        val replacement = snapshot(
            nodes = listOf(node("n3", 0, 1, message("m3", "replacement"))),
            theme = linkedMapOf("--new-only" to "blue", "--shared" to "after"),
        )
        val secondPatches: List<TavernConversationPatch> = listOf(
            TavernConversationPatch.RemoveMessage("n2", "m2"),
            TavernConversationPatch.ReplaceAll(replacement),
        )
        val html = buildTavernConversationDocument(
            InstrumentationRegistry.getInstrumentation().targetContext,
            initial,
        )

        withVisibleWebView(html) { view ->
            applyPatches(view, firstPatches)
            val first = awaitJson(view, 60) {
                """
                (function(){
                  var first = document.querySelector('[data-node-id="n1"]');
                  var mermaid = first && first.querySelector('.mermaid');
                  return JSON.stringify({
                    ready: !!(first && document.querySelector('[data-node-id="n2"]') &&
                      mermaid && mermaid.querySelector('svg')),
                    count: document.querySelectorAll('.mes').length,
                    messageId: first && first.dataset.messageId,
                    branchIndex: first && first.dataset.branchIndex,
                    branchButtons: first && first.querySelectorAll('[data-branch-index]').length,
                    streaming: document.documentElement.dataset.streaming,
                    bold: !!(first && first.querySelector('strong')),
                    mermaidSvg: !!(mermaid && mermaid.querySelector('svg'))
                  });
                })();
                """.trimIndent()
            }
            assertEquals(2, first.getInt("count"))
            assertEquals("m1b", first.getString("messageId"))
            assertEquals("1", first.getString("branchIndex"))
            assertEquals(2, first.getInt("branchButtons"))
            assertEquals("true", first.getString("streaming"))
            assertTrue(first.getBoolean("bold"))
            assertTrue(first.getBoolean("mermaidSvg"))

            applyPatches(view, secondPatches)
            val second = awaitJson(view, 30) {
                """
                (function(){
                  var only = document.querySelector('.mes');
                  var style = document.documentElement.style;
                  return JSON.stringify({
                    ready: !!(only && only.dataset.nodeId === 'n3'),
                    count: document.querySelectorAll('.mes').length,
                    text: only && only.querySelector('.mes_text').textContent.trim(),
                    oldOnly: style.getPropertyValue('--old-only'),
                    newOnly: style.getPropertyValue('--new-only'),
                    shared: style.getPropertyValue('--shared'),
                    streaming: document.documentElement.dataset.streaming
                  });
                })();
                """.trimIndent()
            }
            assertEquals(1, second.getInt("count"))
            assertEquals("replacement", second.getString("text"))
            assertEquals("", second.getString("oldOnly"))
            assertEquals("blue", second.getString("newOnly"))
            assertEquals("after", second.getString("shared"))
            assertEquals("false", second.getString("streaming"))
        }
    }

    private fun applyPatches(view: WebView, patches: List<TavernConversationPatch>) {
        val encoded = PATCH_JSON.encodeToString(patches)
        val latch = CountDownLatch(1)
        view.post {
            view.evaluateJavascript(
                "window.RikkahubConversationDocument.applyPatches(${JSONObject.quote(encoded)});",
            ) { latch.countDown() }
        }
        assertTrue("patch evaluation timed out", latch.await(15, TimeUnit.SECONDS))
    }

    private fun awaitJson(view: WebView, timeoutSeconds: Long, script: () -> String): JSONObject {
        val summary = AtomicReference("{}")
        val latch = CountDownLatch(1)
        var attempts = 0
        fun poll() {
            view.evaluateJavascript(script()) { value ->
                attempts += 1
                if (value != null) {
                    runCatching {
                        val inner = JSONTokener(value).nextValue() as String
                        summary.set(inner)
                        if (JSONObject(inner).optBoolean("ready")) latch.countDown()
                    }.onFailure { summary.set("{\"parseError\":${JSONObject.quote(it.message)}}") }
                }
                if (latch.count > 0L && attempts < timeoutSeconds) {
                    view.postDelayed({ poll() }, 1000)
                }
            }
        }
        view.post { poll() }
        assertTrue("WebView condition timed out: ${summary.get()}", latch.await(timeoutSeconds + 5, TimeUnit.SECONDS))
        return JSONObject(summary.get())
    }

    private fun <T> withVisibleWebView(html: String, block: (WebView) -> T): T {
        lateinit var webView: WebView
        val loaded = CountDownLatch(1)
        @Suppress("UNCHECKED_CAST")
        val activityClass = Class.forName(
            "me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeSmokeActivity",
        ) as Class<Activity>
        return ActivityScenario.launch(activityClass).use { scenario ->
            scenario.onActivity { activity ->
                webView = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            loaded.countDown()
                        }
                    }
                }
                activity.setContentView(webView)
                webView.loadDataWithBaseURL("https://rikkahub.local/", html, "text/html", "UTF-8", null)
            }
            assertTrue("conversation document load timed out", loaded.await(30, TimeUnit.SECONDS))
            try {
                block(webView)
            } finally {
                scenario.onActivity { webView.destroy() }
            }
        }
    }

    private fun snapshot(
        nodes: List<TavernConversationNode>,
        theme: Map<String, String> = emptyMap(),
    ) = TavernConversationSnapshot(
        conversationId = "conversation",
        nodes = nodes,
        userName = "User",
        characterName = "Alice",
        themeCssVariables = theme,
        cardCss = ".mes { border-width: 0; }",
        streaming = false,
    )

    private fun node(
        id: String,
        selectedIndex: Int,
        branchCount: Int,
        message: TavernConversationMessage,
    ) = TavernConversationNode(id, selectedIndex, branchCount, message)

    private fun message(
        id: String,
        text: String,
        renderMode: UIMessagePart.RenderMode = UIMessagePart.RenderMode.MARKDOWN,
    ) = TavernConversationMessage(
        id = id,
        role = MessageRole.ASSISTANT,
        name = "Alice",
        parts = listOf(TavernConversationTextPart(text, renderMode)),
    )

    private companion object {
        val PATCH_JSON = Json {
            encodeDefaults = true
            classDiscriminator = "type"
        }
    }
}
