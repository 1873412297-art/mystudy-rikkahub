package me.rerere.rikkahub.ui.components.richtext

import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.material3.lightColorScheme
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeSmokeActivity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownWebViewReloadInstrumentedTest {
    @Test
    fun localTemplateRendersAndReportsReadyAfterEveryReload() {
        val html = buildMarkdownPreviewHtml(
            InstrumentationRegistry.getInstrumentation().targetContext,
            "# Offline renderer\n\n- [x] local task\n\n\$E = mc^2\$",
            lightColorScheme(),
        )
        val readyCount = AtomicInteger(0)
        val result = AtomicReference<String>()
        val latch = CountDownLatch(1)
        lateinit var webView: WebView

        ActivityScenario.launch(TavernRuntimeSmokeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                webView = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun documentReady() {
                                when (readyCount.incrementAndGet()) {
                                    1 -> webView.post {
                                        webView.loadDataWithBaseURL(
                                            "https://rikkahub.local/",
                                            html,
                                            "text/html",
                                            "UTF-8",
                                            null,
                                        )
                                    }
                                    2 -> webView.postDelayed({
                                        webView.evaluateJavascript(
                                            "JSON.stringify({heading:!!document.querySelector('h1'),task:!!document.querySelector('.task-list-item'),katex:!!document.querySelector('.katex'),katexFont:Array.from(document.fonts||[]).some(function(f){return f.family.indexOf('KaTeX_Main')>=0&&f.status==='loaded';})})",
                                        ) {
                                            result.set(it)
                                            latch.countDown()
                                        }
                                    }, 1_000)
                                }
                            }
                        },
                        "RikkahubBridge",
                    )
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

            assertTrue("same offline document should report ready after both loads", latch.await(60, TimeUnit.SECONDS))
            assertEquals(2, readyCount.get())
            val encoded = JSONObject("{\"value\":${result.get()}}").getString("value")
            val dom = JSONObject(encoded)
            assertTrue(dom.getBoolean("heading"))
            assertTrue(dom.getBoolean("task"))
            assertTrue(dom.getBoolean("katex"))
            assertTrue(dom.getBoolean("katexFont"))
            scenario.onActivity {
                assertFalse(webView.settings.allowFileAccess)
                assertFalse(webView.settings.allowContentAccess)
                webView.destroy()
            }
        }
    }
}
