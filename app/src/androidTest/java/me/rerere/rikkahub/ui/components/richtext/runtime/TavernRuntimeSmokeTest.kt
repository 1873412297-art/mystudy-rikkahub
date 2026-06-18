package me.rerere.rikkahub.ui.components.richtext.runtime

import android.webkit.JavascriptInterface
import android.webkit.WebViewClient
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class TavernRuntimeSmokeTest {
    @Test
    fun runtimePing_roundTripsThroughWebViewBridge() {
        val responseJson = AtomicReference<String>()
        val latch = CountDownLatch(1)
        lateinit var webView: WebView

        ActivityScenario.launch(TavernRuntimeSmokeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                webView = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun signal(payload: String) {
                                responseJson.set(payload)
                                latch.countDown()
                            }
                        },
                        "AndroidSmoke"
                    )
                }

                val bridge = TavernRuntimeBridge(
                    controller = TavernRuntimeController()
                ) { callbackName, resultJson ->
                    val payload = JSONObject.quote(resultJson)
                    webView.post {
                        webView.evaluateJavascript(
                            "(function(){var cb=window['$callbackName'];if(typeof cb==='function'){cb(JSON.parse($payload));}})();",
                            null
                        )
                    }
                }

                webView.addJavascriptInterface(bridge, "TavernRuntimeBridge")
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(
                            """
                                (function(){
                                  window.smoke_cb = function(response) {
                                    AndroidSmoke.signal(JSON.stringify(response));
                                  };
                                  window.TavernRuntimeBridge.call(
                                    JSON.stringify({ id: "1", method: "runtime.ping", params: {} }),
                                    "smoke_cb"
                                  );
                                })();
                            """.trimIndent(),
                            null
                        )
                    }
                }
                activity.setContentView(webView)
                webView.loadDataWithBaseURL(
                    "https://rikkahub.local/",
                    """
                        <!DOCTYPE html>
                        <html>
                          <body>
                          </body>
                        </html>
                    """.trimIndent(),
                    "text/html",
                    "UTF-8",
                    null
                )
            }

            assertTrue("runtime.ping should complete on the emulator", latch.await(10, TimeUnit.SECONDS))
            val response = JSONObject(responseJson.get())
            assertEquals(true, response.getBoolean("ok"))
            assertEquals("pong", response.getString("result"))

            scenario.onActivity {
                webView.destroy()
            }
        }
    }
}
