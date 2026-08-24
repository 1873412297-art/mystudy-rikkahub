package me.rerere.rikkahub.ui.pages.chat.tavern

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.slash.MacroExpandContext
import me.rerere.rikkahub.data.ai.slash.TavernScriptRegistry
import me.rerere.rikkahub.ui.components.richtext.runtime.InMemoryTavernRuntimeVariableGateway
import me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeController
import me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeSmokeActivity
import me.rerere.rikkahub.ui.components.richtext.runtime.buildTavernRuntimeScript
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class TavernImmersiveRuntimeInstrumentedTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(TavernConversationRecoveryActivity::class.java)

    @Test
    fun visualOpeningCardTapUpdatesNativeCounterContentAndSwipeEvent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val intent = Intent(instrumentation.targetContext, TavernConversationRecoveryActivity::class.java)
            .putExtra(TavernConversationRecoveryActivity.EXTRA_RICH_OPENING_FIXTURE, true)

        ActivityScenario.launch<TavernConversationRecoveryActivity>(intent).use { scenario ->
            dismissNotificationPermissionDialog(device)
            val activity = currentActivity(scenario)
            assertTrue("rich opening WebView never became ready", awaitCondition(45_000) {
                TavernConversationRenderStatus.READY in activity.renderStatuses
            })
            assertTrue(
                "visual opening card 3 was not exposed through WebView accessibility",
                device.wait(Until.hasObject(By.textContains("Choose Three")), 20_000),
            )
            val thirdCard = requireNotNull(device.findObject(By.textContains("Choose Three")))
            thirdCard.click()

            assertTrue("authoritative opening index did not change", awaitCondition(15_000) {
                activity.richOpeningSelectedIndex.get() == 2
            })
            assertEquals(listOf(2), activity.richOpeningSelections.toList())
            assertTrue("MESSAGE_SWIPED was not observed on the host event bus", awaitCondition(10_000) {
                activity.richOpeningSwipeEvents.get() == 1
            })
            assertTrue("native opening counter did not update", device.wait(Until.hasObject(By.text("3 / 3")), 15_000))
            assertTrue("selected opening content did not rerender", device.wait(
                Until.hasObject(By.textContains("Opening 3")),
                15_000,
            ))
            assertFalse(device.hasObject(By.textContains("{user}")))
            assertTrue(device.hasObject(By.textContains("Welcome, Device User")))
        }
    }

    @Test
    fun showdownRendersCustomTagOpeningAsStructuredSanitizedMarkdown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val document = buildTavernConversationDocument(
            context = instrumentation.targetContext,
            initial = snapshot(
                conversationId = Uuid.parse("00000000-0000-0000-0000-000000000201"),
                nodeId = Uuid.parse("00000000-0000-0000-0000-000000000202"),
                messageId = Uuid.parse("00000000-0000-0000-0000-000000000203"),
                text = """
                    <customize_HCI>
                    <now_plot>
                    <main_plot>
                    # Opening
                    </main_plot>
                    <details><summary>Status</summary>

                    ```body1
                    hp: 10
                    ```
                    </details>
                    ```javascript
                    const hp = 10;
                    ```
                    ```mermaid
                    graph TD; A-->B
                    ```
                    <script>window.__forbiddenOpeningScript=true</script>
                    </now_plot>
                    </customize_HCI>
                """.trimIndent(),
                renderMode = UIMessagePart.RenderMode.MARKDOWN,
            ),
        )
        val result = AtomicReference<JSONObject>()
        val completed = CountDownLatch(1)

        ActivityScenario.launch<TavernRuntimeSmokeActivity>(
            Intent(instrumentation.targetContext, TavernRuntimeSmokeActivity::class.java),
        ).use { scenario ->
            scenario.onActivity { activity ->
                val webView = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            view.evaluateJavascript(
                                """
                                (function(){
                                  var scope=document.querySelector('.mes_text');
                                  return JSON.stringify({
                                    text:scope ? scope.innerText : '',
                                    headings:scope ? scope.querySelectorAll('h1').length : 0,
                                    details:scope ? scope.querySelectorAll('details > summary').length : 0,
                                    code:scope ? scope.querySelectorAll('pre code').length : 0,
                                    highlighted:scope ? scope.querySelectorAll('pre code.hljs').length : 0,
                                    mermaid:scope ? scope.querySelectorAll('.mermaid').length : 0,
                                    forbidden:scope ? scope.querySelectorAll('script,iframe,object,embed,form').length : -1,
                                    executed:window.__forbiddenOpeningScript === true
                                  });
                                })();
                                """.trimIndent(),
                            ) { encoded ->
                                val decoded = JSONTokener(encoded).nextValue() as String
                                result.set(JSONObject(decoded))
                                completed.countDown()
                            }
                        }
                    }
                }
                activity.setContentView(webView)
                webView.loadDataWithBaseURL(
                    TAVERN_CONVERSATION_BASE_URL,
                    document,
                    "text/html",
                    "UTF-8",
                    null,
                )
            }

            assertTrue("structured Markdown probe timed out", completed.await(45, TimeUnit.SECONDS))
            val rendered = requireNotNull(result.get())
            assertFalse(rendered.getString("text").contains("<customize_HCI>"))
            assertFalse(rendered.getString("text").contains("<now_plot>"))
            assertFalse(rendered.getString("text").contains("<main_plot>"))
            assertEquals(1, rendered.getInt("headings"))
            assertEquals(1, rendered.getInt("details"))
            assertEquals(2, rendered.getInt("code"))
            assertEquals(1, rendered.getInt("highlighted"))
            assertEquals(1, rendered.getInt("mermaid"))
            assertEquals(0, rendered.getInt("forbidden"))
            assertFalse(rendered.getBoolean("executed"))
        }
    }

    @Test
    fun fullHtmlRuntimeRetainsMacroVariableContextAndActionsAcrossReload() {
        dismissNotificationPermissionDialog(
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()),
        )
        val conversationId = Uuid.parse("00000000-0000-0000-0000-000000000101")
        val nodeId = Uuid.parse("00000000-0000-0000-0000-000000000102")
        val messageId = Uuid.parse("00000000-0000-0000-0000-000000000103")
        val actionToken = "immersive-device-action-token"
        val scriptRegistry = TavernScriptRegistry()
        val variableGateway = InMemoryTavernRuntimeVariableGateway()
        val context = AtomicReference(contextSnapshot("first"))
        val currentMessage = AtomicReference<JsonElement>(currentMessage("first-current"))
        val observed = CopyOnWriteArrayList<JSONObject>()
        val secondRuntimeResult = CountDownLatch(1)
        val longPressResult = AtomicReference<Uuid>()
        val branchResult = AtomicReference<Pair<Uuid, Int>>()
        val openHtmlResult = AtomicReference<Uuid>()
        val actionLatch = CountDownLatch(3)
        val webViewRef = AtomicReference<WebView>()

        val controller = TavernRuntimeController(
            conversationId = conversationId,
            variableGateway = variableGateway,
            scriptRegistry = scriptRegistry,
        )
        val rawHtml = """
            <!DOCTYPE html><html><head><script>
              (function(){
                var ran = false;
                document.addEventListener('th:context_updated', function(){
                  if (ran) return;
                  ran = true;
                  var context = SillyTavern.getContext();
                  TavernHelperCompat.variables.get('device_reload_count').then(function(previous){
                    var next = Number(previous || 0) + 1;
                    var registration = context && context.marker === 'first'
                      ? MacroHelper.registerMacro(
                          'device_acceptance_macro',
                          function(args){ return 'macro:' + args; }
                        )
                      : Promise.resolve(false);
                    return Promise.all([
                      TavernHelperCompat.variables.set('device_reload_count', next),
                      registration,
                      TavernHelperCompat.messages.getCurrent()
                    ]).then(function(values){
                      parent.postMessage({
                        __immersiveProbe: true,
                        marker: context && context.marker,
                        count: next,
                        currentId: values[2] && values[2].id,
                        registeredThisDocument: values[1] === true
                      }, '*');
                    });
                  });
                });
              })();
            </script></head><body><b id="full-html-probe">full HTML runtime</b></body></html>
        """.trimIndent()
        val snapshot = snapshot(
            conversationId = conversationId,
            nodeId = nodeId,
            messageId = messageId,
            text = rawHtml,
            renderMode = UIMessagePart.RenderMode.HTML,
            branchCount = 2,
        )
        val parentObserver = """
            (function(){
              if (window !== window.top) return;
              window.addEventListener('message', function(event){
                var data = event.data || {};
                if (data.__immersiveProbe) AndroidAcceptance.signal(JSON.stringify(data));
              });
            })();
        """.trimIndent()
        val document = buildTavernConversationDocument(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            initial = snapshot,
            runtimeScript = buildTavernRuntimeScript() + "\n" + parentObserver,
            actionToken = actionToken,
        )
        val actions = object : TavernConversationActions {
            override fun onMessageLongPress(messageId: Uuid) {
                longPressResult.set(messageId)
                actionLatch.countDown()
            }

            override fun onSelectBranch(nodeId: Uuid, index: Int) {
                branchResult.set(nodeId to index)
                actionLatch.countDown()
            }

            override fun onOpenHtml(messageId: Uuid) {
                openHtmlResult.set(messageId)
                actionLatch.countDown()
            }
            override fun onToolApproval(toolCallId: String, approved: Boolean, reason: String) = Unit
            override fun onToolAnswer(toolCallId: String, answer: String) = Unit
        }
        val runtimeScenario = ActivityScenario.launch(TavernRuntimeSmokeActivity::class.java)
        val secureSettings = AtomicReference<Pair<Boolean, Boolean>>()

        try {
            runtimeScenario.onActivity { activity ->
                val webView = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun signal(payload: String) {
                                val value = JSONObject(payload)
                                observed += value
                                if (value.getInt("count") == 1) {
                                    context.set(contextSnapshot("second"))
                                    currentMessage.set(currentMessage("second-current"))
                                    post {
                                        loadDataWithBaseURL(
                                            TAVERN_CONVERSATION_BASE_URL,
                                            document,
                                            "text/html",
                                            "UTF-8",
                                            null,
                                        )
                                    }
                                } else if (value.getInt("count") == 2) {
                                    secondRuntimeResult.countDown()
                                }
                            }
                        },
                        "AndroidAcceptance",
                    )
                    addJavascriptInterface(
                        TavernConversationBridge(
                            actionToken = actionToken,
                            actions = actions,
                            onDocumentReady = {
                                controller.onDocumentReady(context.get(), currentMessage.get())
                                val detail = JSONObject.quote(context.get().toString())
                                post {
                                    evaluateJavascript(
                                        "(function(){var d=JSON.parse($detail),n='th:context_updated';" +
                                            "document.dispatchEvent(new CustomEvent(n,{detail:d,bubbles:true}));" +
                                            "document.querySelectorAll('iframe').forEach(function(f){" +
                                            "f.contentWindow.postMessage({__rikkahubEvent:n,detail:d},'*');});})();",
                                        null,
                                    )
                                }
                            },
                            dispatch = { callback -> post(callback) },
                        ),
                        "TavernConversationBridge",
                    )
                    addJavascriptInterface(
                        TavernConversationRuntimeBridge(
                            actionToken = actionToken,
                            controller = controller,
                        ) { callbackName, responseJson ->
                            val response = JSONObject.quote(responseJson)
                            post {
                                evaluateJavascript(
                                    "(function(){var cb=window['$callbackName'];" +
                                        "if(typeof cb==='function'){cb(JSON.parse($response));}})();",
                                    null,
                                )
                            }
                        },
                        "TavernRuntimeBridge",
                    )
                    webViewClient = WebViewClient()
                }
                webViewRef.set(webView)
                activity.setContentView(webView)
                webView.loadDataWithBaseURL(
                    TAVERN_CONVERSATION_BASE_URL,
                    document,
                    "text/html",
                    "UTF-8",
                    null,
                )
            }

            assertTrue(
                "runtime result after full document reload timed out",
                secondRuntimeResult.await(60, TimeUnit.SECONDS),
            )
            assertEquals(listOf("first", "second"), observed.map { it.getString("marker") })
            assertEquals(listOf(1, 2), observed.map { it.getInt("count") })
            assertEquals(listOf("first-current", "second-current"), observed.map { it.getString("currentId") })
            assertEquals(listOf(true, false), observed.map { it.getBoolean("registeredThisDocument") })
            assertTrue(
                scriptRegistry.listMacros(ownerId = conversationId.toString()).contains("device_acceptance_macro"),
            )
            assertEquals(
                "macro:after-reload",
                scriptRegistry.expandMacro(
                    name = "device_acceptance_macro",
                    args = "after-reload",
                    context = MacroExpandContext(conversationId = conversationId.toString()),
                ),
            )

            runtimeScenario.onActivity {
                requireNotNull(webViewRef.get()).evaluateJavascript(
                    "(function(){var b=window.TavernConversationBridge;" +
                        "b.longPress('$actionToken','$messageId');" +
                        "b.selectBranch('$actionToken','$nodeId',1);" +
                        "b.openHtml('$actionToken','$messageId');})();",
                    null,
                )
            }
            assertTrue("native action bridge callbacks timed out", actionLatch.await(15, TimeUnit.SECONDS))
            assertEquals(messageId, longPressResult.get())
            assertEquals(nodeId to 1, branchResult.get())
            assertEquals(messageId, openHtmlResult.get())
        } finally {
            controller.cancelHostEventCollection()
            runCatching {
                webViewRef.get()?.let { webView ->
                    runtimeScenario.onActivity {
                        secureSettings.set(webView.settings.allowFileAccess to webView.settings.allowContentAccess)
                        webView.removeJavascriptInterface("AndroidAcceptance")
                        webView.removeJavascriptInterface("TavernConversationBridge")
                        webView.removeJavascriptInterface("TavernRuntimeBridge")
                        webView.stopLoading()
                        webView.destroy()
                    }
                }
            }
            runtimeScenario.close()
        }
        assertEquals(false to false, secureSettings.get())
    }

    @Test
    fun realConversationHostAutomaticallyRetriesTwiceThenShowsStOnlyErrorPage() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        dismissNotificationPermissionDialog(device)
        val activity = currentActivity(activityRule.scenario)
        assertTrue("initial conversation WebView never became ready", awaitCondition(45_000) {
            activity.renderStatuses.count { it == TavernConversationRenderStatus.READY } >= 1
        })
        val firstWebView = AtomicReference<WebView>()
        val firstFailure = CountDownLatch(1)
        activityRule.scenario.onActivity { current ->
            firstWebView.set(requireNotNull(findWebView(current.findViewById(android.R.id.content))))
            startMainFrameFailureProbe(current, firstWebView.get(), firstFailure)
        }
        assertTrue("main-frame failure probe timed out", firstFailure.await(15, TimeUnit.SECONDS))
        assertTrue(
            "host did not enter FAILED state: ${activity.renderStatuses}",
            awaitCondition(10_000) { TavernConversationRenderStatus.FAILED in activity.renderStatuses },
        )
        assertTrue("first automatic retry never became ready", awaitCondition(45_000) {
            activity.renderStatuses.count { it == TavernConversationRenderStatus.READY } >= 2
        })

        val retriedWebView = AtomicReference<WebView>()
        activityRule.scenario.onActivity { current ->
            retriedWebView.set(requireNotNull(findWebView(current.findViewById(android.R.id.content))))
        }
        assertNotSame("retry must replace the released WebView generation", firstWebView.get(), retriedWebView.get())
        val secondFailure = CountDownLatch(1)
        activityRule.scenario.onActivity { current ->
            startMainFrameFailureProbe(current, retriedWebView.get(), secondFailure)
        }
        assertTrue("second main-frame failure probe timed out", secondFailure.await(15, TimeUnit.SECONDS))
        assertTrue("second automatic retry never became ready", awaitCondition(45_000) {
            activity.renderStatuses.count { it == TavernConversationRenderStatus.READY } >= 3
        })
        val finalWebView = AtomicReference<WebView>()
        val thirdFailure = CountDownLatch(1)
        activityRule.scenario.onActivity { current ->
            finalWebView.set(requireNotNull(findWebView(current.findViewById(android.R.id.content))))
            startMainFrameFailureProbe(current, finalWebView.get(), thirdFailure)
        }
        assertTrue("third main-frame failure probe timed out", thirdFailure.await(15, TimeUnit.SECONDS))
        assertTrue(
            "terminal ST error page did not preserve source text",
            device.wait(Until.hasObject(By.textContains(TavernConversationRecoveryActivity.FALLBACK_TEXT)), 15_000),
        )
        assertTrue(device.hasObject(By.text("重试酒馆视图")))
        assertFalse(device.hasObject(By.text("切换兼容视图")))
        device.findObject(By.text("重试酒馆视图")).click()
        assertTrue("manual retry did not reset the recovery budget", awaitCondition(45_000) {
            activity.renderStatuses.count { it == TavernConversationRenderStatus.READY } >= 4
        })
    }

    private fun dismissNotificationPermissionDialog(device: UiDevice) {
        val denyButton = device.wait(
            Until.findObject(By.res("com.android.permissioncontroller:id/permission_deny_button")),
            5_000,
        )
        denyButton?.click()
        if (denyButton != null) {
            assertTrue(
                "notification permission dialog did not close",
                device.wait(Until.gone(By.pkg("com.android.permissioncontroller")), 5_000),
            )
        }
        assertTrue(
            "debug fixture did not regain the foreground",
            device.wait(
                Until.hasObject(By.pkg(InstrumentationRegistry.getInstrumentation().targetContext.packageName)),
                5_000,
            ),
        )
    }

    private fun startMainFrameFailureProbe(activity: Activity, target: WebView, delivered: CountDownLatch) {
        val activityRoot = activity.findViewById<ViewGroup>(android.R.id.content)
        val probe = WebView(activity).apply {
            settings.allowFileAccess = false
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?,
                ) {
                    if (request?.isForMainFrame != true || error == null) return
                    target.webViewClient.onReceivedError(target, request, error)
                    activityRoot.removeView(this@apply)
                    destroy()
                    delivered.countDown()
                }
            }
        }
        activityRoot.addView(probe, ViewGroup.LayoutParams(1, 1))
        probe.loadUrl("file:///definitely-missing/tavern-immersive-acceptance.html")
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun currentActivity(
        scenario: ActivityScenario<TavernConversationRecoveryActivity>,
    ): TavernConversationRecoveryActivity {
        val result = AtomicReference<TavernConversationRecoveryActivity>()
        scenario.onActivity { result.set(it) }
        return requireNotNull(result.get())
    }

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            SystemClock.sleep(100)
        }
        return condition()
    }

    private fun contextSnapshot(marker: String): JsonObject = buildJsonObject {
        put("marker", marker)
        put("chat", JsonArray(emptyList()))
    }

    private fun currentMessage(id: String): JsonObject = buildJsonObject {
        put("id", id)
        put("role", "ASSISTANT")
        put("content", "current message $id")
    }

    private fun snapshot(
        conversationId: Uuid,
        nodeId: Uuid,
        messageId: Uuid,
        text: String,
        renderMode: UIMessagePart.RenderMode,
        branchCount: Int = 1,
    ) = TavernConversationSnapshot(
        conversationId = conversationId.toString(),
        nodes = listOf(
            TavernConversationNode(
                id = nodeId.toString(),
                selectedIndex = 0,
                branchCount = branchCount,
                selectedMessage = TavernConversationMessage(
                    id = messageId.toString(),
                    role = MessageRole.ASSISTANT,
                    name = "Acceptance Character",
                    parts = listOf(TavernConversationTextPart(text, renderMode)),
                ),
            ),
        ),
        userName = "Device User",
        characterName = "Acceptance Character",
        themeCssVariables = mapOf("--rikkahub-text" to "#202020"),
        cardCss = ".mes { border-radius: 13px; }",
        streaming = false,
    )
}
