package me.rerere.rikkahub.ui.components.richtext.st

import android.webkit.WebView
import android.webkit.WebViewClient
import android.app.Activity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.ui.components.richtext.RichTextSegment
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class StableMessageHtmlRenderInstrumentedTest {

    private fun buildSampleMessage() = StableDomMessage(
        id = "m1",
        role = StableDomRole.ASSISTANT,
        name = "爱丽丝",
        segments = listOf(
            StableDomSegment(
                "md-0",
                RichTextSegment.Kind.MARKDOWN,
                "**加粗** 与 *斜体*，`行内代码`。\n\n- 第一项\n- 第二项\n\n```kotlin\nfun main() {}\n```\n\n| 名称 | 值 |\n| --- | --- |\n| HP | 100 |",
            ),
            StableDomSegment(
                "st-1",
                RichTextSegment.Kind.STATUS_BLOCK,
                "<Status_block>生命值: 100</Status_block>",
            ),
            StableDomSegment("md-2", RichTextSegment.Kind.MARKDOWN, "结尾 **再见**。"),
        ),
        streaming = false,
    )

    @Test
    fun stableDomTemplateRendersRichMarkdownOnDevice() {
        val summary = AtomicReference("{}")
        val latch = CountDownLatch(1)
        lateinit var webView: WebView
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val html = buildStableMessageHtml(context, buildSampleMessage())
        var ready = false

        @Suppress("UNCHECKED_CAST")
        val activityClass = Class.forName(
            "me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeSmokeActivity",
        ) as Class<Activity>
        ActivityScenario.launch(activityClass).use { scenario ->
            scenario.onActivity { activity ->
                webView = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                }
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.postDelayed({ pollUntilRich(view, latch, summary) }, 300)
                    }
                }
                activity.setContentView(webView)
                webView.loadDataWithBaseURL(
                    "https://rikkahub.local/",
                    html,
                    "text/html",
                    "UTF-8",
                    null,
                )
            }

            ready = latch.await(45, TimeUnit.SECONDS)
        }
        val result = JSONObject(summary.get())
        assertTrue("rich markdown render timeout. summary=" + summary.get(), ready)
        if (result.has("jsError")) {
            println("STABLE_DOM_JS_ERROR=" + result.getString("jsError"))
        }
        if (result.has("errors")) {
            println("STABLE_DOM_LOAD_ERRORS=" + result.getJSONArray("errors"))
        }

        assertEquals("爱丽丝", result.getString("name"))
        assertTrue("mes_block", result.getBoolean("hasMesBlock"))
        assertTrue("name_text", result.getBoolean("hasNameText"))
        assertTrue("segment-id", result.getBoolean("hasSegmentId"))
        val segments = result.getJSONArray("segments")
        assertEquals(3, segments.length())

        val md0 = segments.getJSONObject(0)
        assertEquals("MARKDOWN", md0.getString("kind"))
        assertEquals("rich", md0.getString("rendered"))
        val inner = md0.getString("innerHTML")
        assertTrue("bold", inner.contains("<strong>"))
        assertTrue("italic", inner.contains("<em>"))
        assertTrue("code block", inner.contains("<pre class=\"hljs\"><code>") || inner.contains("hljs"))
        assertTrue("table", inner.contains("<table>"))
        assertTrue("list", inner.contains("<li>"))

        val st = segments.getJSONObject(1)
        assertEquals("STATUS_BLOCK", st.getString("kind"))
        assertTrue("status escaped", st.getString("innerHTML").contains("&lt;Status_block&gt;"))
    }

    private fun pollUntilRich(
        view: WebView,
        latch: CountDownLatch,
        summary: AtomicReference<String>,
    ) {
        var attempts = 0
        fun poll() {
            view.evaluateJavascript(
                """
                (function(){
                  var result = { ready: false, segments: [], name: '', hasMesBlock: false, hasNameText: false, hasSegmentId: false, errors: [], jsError: '' };
                  try {
                    var segs = Array.prototype.map.call(document.querySelectorAll('.mes_text > [data-kind]'), function(s){
                      return { kind: s.dataset.kind, rendered: s.dataset.rendered || '', innerHTML: s.innerHTML.slice(0, 600) };
                    });
                    var richCount = segs.filter(function(s){ return s.kind === 'MARKDOWN' && s.rendered === 'rich'; }).length;
                    result.segments = segs;
                    result.ready = richCount > 0;
                    result.name = (document.querySelector('.name_text .ch_name') || {}).textContent || '';
                    result.hasMesBlock = !!document.querySelector('.mes_block');
                    result.hasNameText = !!document.querySelector('.name_text');
                    result.hasSegmentId = !!document.querySelector('[data-segment-id]');
                    result.errors = window.__stLoadErrors || [];
                  } catch(e){ result.jsError = String(e && e.message || e); }
                  return JSON.stringify(result);
                })();
                """.trimIndent(),
            ) { value ->
                attempts += 1
                if (value != null) {
                    try {
                        val inner = JSONTokener(value).nextValue() as String
                        summary.set(inner)
                        if (JSONObject(inner).optBoolean("ready")) {
                            latch.countDown()
                        }
                    } catch (e: Exception) {
                        summary.set("parse-error: " + e.message + " raw=" + value)
                    }
                }
                if (attempts < 40 && latch.count > 0L) {
                    view.postDelayed({ poll() }, 1000)
                }
            }
        }
        view.post { poll() }
    }
}
