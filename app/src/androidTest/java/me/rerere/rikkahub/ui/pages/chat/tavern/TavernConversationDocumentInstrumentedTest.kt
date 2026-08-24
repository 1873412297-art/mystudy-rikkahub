package me.rerere.rikkahub.ui.pages.chat.tavern

import android.app.Activity
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import kotlinx.serialization.json.JsonNull
import me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeController
import me.rerere.rikkahub.ui.components.richtext.runtime.buildTavernRuntimeScript
import android.webkit.JavascriptInterface
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
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient

@RunWith(AndroidJUnit4::class)
class TavernConversationDocumentInstrumentedTest {

    @Test
    fun secureClientLoadsRealRemoteCardImageOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val remoteUrl = "https://catbox.pengcyril.dpdns.org/07l12e.png"
        val html = buildTavernConversationDocument(
            context,
            snapshot(nodes = listOf(node("n1", 0, 1, message("m1", "![remote-card]($remoteUrl)")))),
        )
        val loader = TavernRemoteMediaLoader.create(context.cacheDir, OkHttpClient())
        lateinit var webView: WebView
        @Suppress("UNCHECKED_CAST")
        val activityClass = Class.forName(
            "me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeSmokeActivity",
        ) as Class<Activity>

        try {
            ActivityScenario.launch(activityClass).use { scenario ->
                scenario.onActivity { activity ->
                    webView = WebView(activity).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = secureClient(
                            networkAllowed = AtomicBoolean(true),
                            resourceRegistry = null,
                            remoteMediaLoader = loader,
                            onFailure = {},
                            onOpenExternal = { _: Uri -> },
                        )
                    }
                    activity.setContentView(webView)
                    webView.loadDataWithBaseURL(TAVERN_CONVERSATION_BASE_URL, html, "text/html", "UTF-8", null)
                }
                val result = awaitJson(webView, 45) {
                    """
                    (function(){
                      var image = document.querySelector('img[alt="remote-card"]');
                      return JSON.stringify({
                        ready: !!(image && image.complete && image.naturalWidth > 0),
                        complete: !!(image && image.complete),
                        naturalWidth: image ? image.naturalWidth : 0,
                        src: image && (image.currentSrc || image.src)
                      });
                    })();
                    """.trimIndent()
                }
                assertEquals(remoteUrl, result.getString("src"))
                assertTrue(result.getInt("naturalWidth") > 0)
                scenario.onActivity { webView.destroy() }
            }
        } finally {
            loader.close()
        }
    }

    @Test
    fun markdownMatchesSillyTavernQuoteColorsWithoutTouchingCodeOrAttributes() {
        val markdown = """
            叙述正文

            "alpha"
            “beta”
            «gamma»
            「delta」
            『epsilon』
            ＂zeta＂

            `“inline-code”`

            ```text
            「fenced-code」
            ```

            <span id="quote-attribute" title="『attribute』">普通标签</span>
        """.trimIndent()
        val initial = snapshot(
            nodes = listOf(node("n1", 0, 1, message("m1", markdown))),
            theme = linkedMapOf(
                "--SmartThemeBodyColor" to "rgb(220, 220, 210)",
                "--SmartThemeQuoteColor" to "rgb(225, 138, 36)",
                "--rikkahub-text" to "rgb(220, 220, 210)",
            ),
        )
        val html = buildTavernConversationDocument(
            InstrumentationRegistry.getInstrumentation().targetContext,
            initial,
        )

        val result = withVisibleWebView(html) { view ->
            awaitJson(view, 30) {
                """
                (function(){
                  var text = document.querySelector('.mes_text');
                  var quotes = text ? text.querySelectorAll('q') : [];
                  var code = text ? text.querySelectorAll('code') : [];
                  var attr = document.getElementById('quote-attribute');
                  return JSON.stringify({
                    ready: !!(text && quotes.length === 6 && code.length === 2 && attr),
                    quoteCount: quotes.length,
                    quoteColor: quotes.length ? getComputedStyle(quotes[0]).color : '',
                    proseColor: text ? getComputedStyle(text).color : '',
                    codeQuoteCount: text ? text.querySelectorAll('code q').length : -1,
                    attribute: attr && attr.getAttribute('title')
                  });
                })();
                """.trimIndent()
            }
        }

        assertEquals(6, result.getInt("quoteCount"))
        assertEquals("rgb(225, 138, 36)", result.getString("quoteColor"))
        assertEquals("rgb(220, 220, 210)", result.getString("proseColor"))
        assertEquals(0, result.getInt("codeQuoteCount"))
        assertEquals("『attribute』", result.getString("attribute"))
    }

    @Test
    fun documentRendersAllConversationPartFamiliesInOriginalOrder() {
        val parts = listOf<TavernConversationPart>(
            TavernConversationTextPart("text"),
            TavernConversationStatusPart("<b>status</b>", listOf(TavernConversationStatusPage("A", "<i>page</i>"))),
            TavernConversationImagePart("data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw=="),
            TavernConversationVideoPart("data:video/mp4;base64,"),
            TavernConversationAudioPart("data:audio/mpeg;base64,"),
            TavernConversationDocumentPart("https://rikkahub.local/resource/00000000-0000-4000-8000-000000000001", "file.txt", "text/plain"),
            TavernConversationReasoningPart("thinking", true),
            TavernConversationToolPart("call", "tool", "{}", emptyList(), ToolApprovalState.Pending),
            TavernConversationToolCallPart("legacy", "legacy-tool", "{}", ToolApprovalState.Pending),
            TavernConversationToolResultPart("legacy", "legacy-tool", JsonNull, JsonNull),
            TavernConversationSearchPart,
        )
        val initial = snapshot(nodes = listOf(node(
            "n1", 0, 1, TavernConversationMessage("m1", MessageRole.ASSISTANT, "Alice", parts = parts),
        )))
        val html = buildTavernConversationDocument(InstrumentationRegistry.getInstrumentation().targetContext, initial)

        val result = withVisibleWebView(html) { view ->
            awaitJson(view, 30) {
                """
                (function(){
                  var children = Array.from(document.querySelector('.mes_text').children);
                  return JSON.stringify({
                    ready: children.length === 11,
                    count: children.length,
                    tags: children.map(function(node){ return node.tagName; }),
                    media: document.querySelectorAll('img,video,audio').length,
                    details: document.querySelectorAll('details').length,
                    status: document.querySelectorAll('.status-part').length,
                    documents: document.querySelectorAll('.document-part').length,
                    tools: document.querySelectorAll('.tool-part').length
                  });
                })();
                """.trimIndent()
            }
        }

        assertEquals(11, result.getInt("count"))
        assertEquals(3, result.getInt("media"))
        assertEquals(4, result.getInt("details"))
        assertEquals(1, result.getInt("status"))
        assertEquals(1, result.getInt("documents"))
        assertEquals(3, result.getInt("tools"))
    }

    @Test
    fun rawHtmlEarlyScriptSeesRuntimeAndIframeRpcReturnsToOriginatingFrame() {
        val observed = AtomicReference<String>()
        val responseLatch = CountDownLatch(1)
        val rawHtml = """
            <!DOCTYPE html><html><head>
              <script>
                (function(){
                  var earlyApi = !!(window.TavernHelperCompat && window.TavernHelperCompat.runtime);
                  if (!earlyApi) {
                    parent.postMessage({__runtimeProbe:true,earlyApi:false,result:'missing'}, '*');
                    return;
                  }
                  window.TavernHelperCompat.runtime.ping().then(function(result){
                    parent.postMessage({__runtimeProbe:true,earlyApi:true,result:String(result)}, '*');
                  }).catch(function(error){
                    parent.postMessage({__runtimeProbe:true,earlyApi:true,result:'error:' + String(error && error.code)}, '*');
                  });
                })();
              </script>
            </head><body>runtime probe</body></html>
        """.trimIndent()
        val parentObserver = """
            (function(){
              if (window !== window.top) return;
              window.addEventListener('message', function(event){
                var data = event.data || {};
                if (data.__runtimeProbe) AndroidSmoke.signal(JSON.stringify(data));
              });
            })();
        """.trimIndent()
        val html = buildTavernConversationDocument(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            initial = snapshot(
                nodes = listOf(node("n1", 0, 1, message("m1", rawHtml, UIMessagePart.RenderMode.HTML))),
            ),
            runtimeScript = buildTavernRuntimeScript() + "\n" + parentObserver,
            actionToken = "instrumentation-action-token",
        )

        withVisibleWebView(
            html = html,
            configure = { webView ->
                webView.addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun signal(payload: String) {
                            observed.set(payload)
                            responseLatch.countDown()
                        }
                    },
                    "AndroidSmoke",
                )
                webView.addJavascriptInterface(
                    TavernConversationRuntimeBridge(
                        actionToken = "instrumentation-action-token",
                        controller = TavernRuntimeController(),
                    ) { callbackName, responseJson ->
                        val payload = JSONObject.quote(responseJson)
                        webView.post {
                            webView.evaluateJavascript(
                                "(function(){var cb=window['$callbackName'];" +
                                    "if(typeof cb==='function'){cb(JSON.parse($payload));}})();",
                                null,
                            )
                        }
                    },
                    "TavernRuntimeBridge",
                )
            },
        ) {
            assertTrue("iframe runtime RPC timed out", responseLatch.await(20, TimeUnit.SECONDS))
        }

        val result = JSONObject(observed.get())
        assertTrue("runtime APIs must exist before the first user script", result.getBoolean("earlyApi"))
        assertEquals("pong", result.getString("result"))
    }

    @Test
    fun richOpeningIframeCanReadSwipesAndSelectAuthoritativeNativeOpening() {
        val observed = AtomicReference<String>()
        val selectedGreeting = AtomicReference<Int>()
        val responseLatch = CountDownLatch(1)
        val selectionLatch = CountDownLatch(1)
        val rawHtml = """
            <!DOCTYPE html><html><head>
              <script>
                (async function(){
                  try {
                    var messages = await getChatMessages('0', { include_swipes: true });
                    var opening = messages[0];
                    var result = await setChatMessage(opening.swipes[2], 0, {
                      swipe_id: 2,
                      refresh: 'display_and_render_current'
                    });
                    parent.postMessage({
                      __openingProbe: true,
                      count: opening.swipes.length,
                      selected: opening.swipe_id,
                      result: result
                    }, '*');
                  } catch (error) {
                    parent.postMessage({
                      __openingProbe: true,
                      error: String(error && (error.code || error.message || error))
                    }, '*');
                  }
                })();
              </script>
            </head><body><button id="opening-two">Choose opening three</button></body></html>
        """.trimIndent()
        val parentObserver = """
            (function(){
              if (window !== window.top) return;
              window.addEventListener('message', function(event){
                var data = event.data || {};
                if (data.__openingProbe) AndroidSmoke.signal(JSON.stringify(data));
              });
            })();
        """.trimIndent()
        val openingSwipe = TavernOpeningSwipe(
            index = 0,
            count = 3,
            ready = true,
            swipes = listOf(rawHtml, "<p>opening two</p>", "<p>opening three</p>"),
        )
        val initial = TavernConversationSnapshot(
            conversationId = "conversation",
            nodes = listOf(node("n1", 0, 1, message("m1", rawHtml, UIMessagePart.RenderMode.HTML))),
            userName = "User",
            characterName = "Alice",
            themeCssVariables = emptyMap(),
            cardCss = "",
            streaming = false,
            openingSwipe = openingSwipe,
            revision = 17,
        )
        val gateway = TavernConversationMessageGateway(
            snapshotProvider = { initial },
            dispatchGreeting = { index, count, revision ->
                if (count == 3 && revision == 17L) {
                    selectedGreeting.set(index)
                    selectionLatch.countDown()
                }
            },
        )
        val html = buildTavernConversationDocument(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            initial = initial,
            runtimeScript = buildTavernRuntimeScript() + "\n" + parentObserver,
            actionToken = "opening-instrumentation-action-token",
        )

        withVisibleWebView(
            html = html,
            configure = { webView ->
                webView.addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun signal(payload: String) {
                            observed.set(payload)
                            responseLatch.countDown()
                        }
                    },
                    "AndroidSmoke",
                )
                webView.addJavascriptInterface(
                    TavernConversationRuntimeBridge(
                        actionToken = "opening-instrumentation-action-token",
                        controller = TavernRuntimeController(chatMessageGateway = gateway),
                    ) { callbackName, responseJson ->
                        val payload = JSONObject.quote(responseJson)
                        webView.post {
                            webView.evaluateJavascript(
                                "(function(){var cb=window['$callbackName'];" +
                                    "if(typeof cb==='function'){cb(JSON.parse($payload));}})();",
                                null,
                            )
                        }
                    },
                    "TavernRuntimeBridge",
                )
            },
        ) {
            assertTrue("opening iframe RPC timed out", responseLatch.await(20, TimeUnit.SECONDS))
            assertTrue("native opening selection timed out", selectionLatch.await(5, TimeUnit.SECONDS))
        }

        val result = JSONObject(observed.get())
        assertFalse("opening RPC failed: ${result.optString("error")}", result.has("error"))
        assertEquals(3, result.getInt("count"))
        assertEquals(0, result.getInt("selected"))
        assertTrue(result.getBoolean("result"))
        assertEquals(2, selectedGreeting.get())
    }

    @Test
    fun richIframeKeepsGoodMediaMarksBrokenMediaAndResizesAfterExpansion() {
        val observed = AtomicReference<String>()
        val responseLatch = CountDownLatch(1)
        val rawHtml = """
            <!DOCTYPE html><html><head>
              <style>body{margin:0}#expander{height:40px;transition:height 80ms ease}</style>
              <script>
                addEventListener('load', function(){
                  setTimeout(function(){ document.getElementById('expander').style.height='720px'; }, 80);
                  var attempts = 0;
                  function report(){
                    var good = document.getElementById('good-media');
                    var broken = document.getElementById('broken-media');
                    attempts += 1;
                    if (good && good.complete && good.naturalWidth > 0 &&
                        broken && broken.dataset.rikkahubMediaError === 'true') {
                      parent.postMessage({
                        __mediaProbe: true,
                        goodWidth: good.naturalWidth,
                        brokenMarked: true,
                        placeholder: !!document.querySelector('.rikkahub-media-error')
                      }, '*');
                    } else if (attempts < 30) {
                      setTimeout(report, 100);
                    }
                  }
                  report();
                });
              </script>
            </head><body>
              <img id="good-media" src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==">
              <img id="broken-media" src="https://127.0.0.1:1/missing.png">
              <div id="expander"></div>
            </body></html>
        """.trimIndent()
        val parentObserver = """
            (function(){
              if (window !== window.top) return;
              window.addEventListener('message', function(event){
                var data = event.data || {};
                if (data.__mediaProbe) AndroidSmoke.signal(JSON.stringify(data));
              });
            })();
        """.trimIndent()
        val html = buildTavernConversationDocument(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            initial = snapshot(
                nodes = listOf(node("n1", 0, 1, message("m1", rawHtml, UIMessagePart.RenderMode.HTML))),
            ).copy(
                openingSwipe = TavernOpeningSwipe(
                    index = 0,
                    count = 3,
                    ready = true,
                    swipes = listOf(rawHtml, "<p>opening two</p>", "<p>opening three</p>"),
                ),
            ),
            runtimeScript = buildTavernRuntimeScript() + "\n" + parentObserver,
            actionToken = "media-instrumentation-action-token",
        )

        val frameResult = withVisibleWebView(
            html = html,
            configure = { webView ->
                webView.addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun signal(payload: String) {
                            observed.set(payload)
                            responseLatch.countDown()
                        }
                    },
                    "AndroidSmoke",
                )
            },
        ) { view ->
            assertTrue("rich iframe media probe timed out", responseLatch.await(20, TimeUnit.SECONDS))
            awaitJson(view, 20) {
                """
                (function(){
                  var frame = document.querySelector('iframe[data-html-frame]');
                  var part = frame && frame.closest('.html-part');
                  var message = frame && frame.closest('.mes');
                  var block = frame && frame.closest('.mes_block');
                  var height = frame ? parseFloat(frame.style.height || '0') : 0;
                  var messageWidth = message ? message.getBoundingClientRect().width : 0;
                  var blockWidth = block ? block.getBoundingClientRect().width : 0;
                  return JSON.stringify({
                    ready: !!(frame && frame.isConnected && part &&
                      part.classList.contains('rikkahub-frame-ready') && height >= 700 &&
                      messageWidth > 0 && blockWidth >= messageWidth - 24),
                    mounted: !!(frame && frame.isConnected),
                    revealed: !!(part && part.classList.contains('rikkahub-frame-ready')),
                    height: height,
                    messageWidth: messageWidth,
                    blockWidth: blockWidth,
                    viewport: window.innerHeight
                  });
                })();
                """.trimIndent()
            }
        }

        val mediaResult = JSONObject(observed.get())
        assertTrue(mediaResult.getInt("goodWidth") > 0)
        assertTrue(mediaResult.getBoolean("brokenMarked"))
        assertTrue(mediaResult.getBoolean("placeholder"))
        assertTrue(frameResult.getBoolean("mounted"))
        assertTrue(frameResult.getBoolean("revealed"))
        assertTrue(frameResult.getDouble("height") >= 700.0)
        assertTrue(frameResult.getDouble("height") <= maxOf(960.0, frameResult.getDouble("viewport") * 4.0))
        assertTrue(frameResult.getDouble("blockWidth") >= frameResult.getDouble("messageWidth") - 24.0)
    }

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
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            initial = initial,
            runtimeScript = buildTavernRuntimeScript(),
            actionToken = "render-instrumentation-action-token",
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
        val rawSrcdoc = result.getString("rawSrcdoc")
        assertTrue(rawSrcdoc.contains("raw-html"))
        assertTrue(rawSrcdoc.indexOf("__RIKKAHUB_RUNTIME_CALL__") < rawSrcdoc.indexOf("window.rawRan"))
        assertEquals("allow-scripts", result.getString("sandbox"))
    }

    @Test
    fun markdownItTakesOverWhenShowdownRenderingThrows() {
        val html = buildTavernConversationDocument(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            initial = snapshot(
                nodes = listOf(node("n1", 0, 1, message("m1", "# Fallback\n\n**markdown-it**"))),
            ),
            runtimeScript = """
                window.showdown.Converter = function () {
                  return { makeHtml: function () { throw new Error('forced Showdown failure'); } };
                };
            """.trimIndent(),
        )

        val result = withVisibleWebView(html) { view ->
            awaitJson(view, 30) {
                """
                (function(){
                  var scope=document.querySelector('.mes_text');
                  return JSON.stringify({
                    ready:!!(scope && scope.querySelector('h1') && scope.querySelector('strong')),
                    heading:scope && scope.querySelector('h1') && scope.querySelector('h1').textContent,
                    strong:scope && scope.querySelector('strong') && scope.querySelector('strong').textContent
                  });
                })();
                """.trimIndent()
            }
        }

        assertEquals("Fallback", result.getString("heading"))
        assertEquals("markdown-it", result.getString("strong"))
    }

    @Test
    fun escapedTextTakesOverWhenBothMarkdownParsersAreUnavailable() {
        val html = buildTavernConversationDocument(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            initial = snapshot(
                nodes = listOf(
                    node(
                        "n1",
                        0,
                        1,
                        message("m1", "# Literal fallback\n\n**not bold** <script>window.fallbackPwned=true</script>"),
                    ),
                ),
            ),
            runtimeScript = "window.showdown = undefined; window.MarkdownIt = undefined;",
        )

        val result = withVisibleWebView(html) { view ->
            awaitJson(view, 30) {
                """
                (function(){
                  var fallback=document.querySelector('.mes_text [data-render-mode="markdown"]');
                  return JSON.stringify({
                    ready:!!fallback,
                    text:fallback ? fallback.textContent : '',
                    headings:document.querySelectorAll('.mes_text h1').length,
                    strong:document.querySelectorAll('.mes_text strong').length,
                    scripts:document.querySelectorAll('.mes_text script').length,
                    executed:window.fallbackPwned === true
                  });
                })();
                """.trimIndent()
            }
        }

        assertTrue(result.getString("text").contains("# Literal fallback"))
        assertTrue(result.getString("text").contains("**not bold**"))
        assertEquals(0, result.getInt("headings"))
        assertEquals(0, result.getInt("strong"))
        assertEquals(0, result.getInt("scripts"))
        assertFalse(result.getBoolean("executed"))
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

    private fun <T> withVisibleWebView(
        html: String,
        configure: (WebView) -> Unit = {},
        block: (WebView) -> T,
    ): T {
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
                configure(webView)
                activity.setContentView(webView)
                webView.loadDataWithBaseURL(TAVERN_CONVERSATION_BASE_URL, html, "text/html", "UTF-8", null)
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
