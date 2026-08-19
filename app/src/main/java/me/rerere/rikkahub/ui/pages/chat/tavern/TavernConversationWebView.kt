package me.rerere.rikkahub.ui.pages.chat.tavern

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewRenderProcess
import android.webkit.WebViewRenderProcessClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.slash.TavernScriptRegistry
import me.rerere.rikkahub.data.ai.status.StatusVariableStore
import me.rerere.rikkahub.data.ai.status.TavernCardStyleResolver
import me.rerere.rikkahub.data.ai.status.TavernHostEventBus
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.TavernCharacterCard
import me.rerere.rikkahub.service.tavern.TavernGreetingCandidateRuntime
import me.rerere.rikkahub.service.tavern.TavernGreetingRuntimeBindings
import me.rerere.rikkahub.ui.components.richtext.hex
import me.rerere.rikkahub.ui.components.richtext.runtime.SettingsBackedTavernWorldRepository
import me.rerere.rikkahub.ui.components.richtext.runtime.SettingsStoreTavernVariableGateway
import me.rerere.rikkahub.ui.components.richtext.runtime.SettingsStoreTavernWorldGateway
import me.rerere.rikkahub.ui.components.richtext.runtime.StatusStoreTavernVariableGateway
import me.rerere.rikkahub.ui.components.richtext.runtime.TavernContextSnapshotInput
import me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeController
import me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimePermissionStore
import me.rerere.rikkahub.ui.components.richtext.runtime.TavernSendHookStore
import me.rerere.rikkahub.ui.components.richtext.runtime.buildTavernContextSnapshot
import me.rerere.rikkahub.ui.components.richtext.runtime.buildTavernRuntimeScript
import me.rerere.rikkahub.utils.JsonInstant
import org.json.JSONObject
import org.koin.compose.koinInject
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid

private const val INITIAL_RENDER_TIMEOUT_MS = 8_000L
private val hostJson = Json { encodeDefaults = true; classDiscriminator = "type" }

/** Builds the app/runtime inputs for the single conversation WebView. */
@Composable
internal fun TavernConversationPane(
    conversation: Conversation,
    assistant: Assistant,
    settings: Settings,
    loading: Boolean,
    actions: TavernConversationActions,
    visibleMessageId: Uuid? = null,
    ownsSendHookController: Boolean = true,
    candidateRuntime: TavernGreetingCandidateRuntime? = null,
    modifier: Modifier = Modifier,
) {
    val statusVariableStore: StatusVariableStore = koinInject()
    val persistedVariables by statusVariableStore.getState(conversation.id).collectAsState()
    val variables = candidateRuntime?.snapshot()?.chatVariables ?: persistedVariables
    val colorScheme = MaterialTheme.colorScheme
    val card = remember(assistant.tavernCardJson) {
        assistant.tavernCardJson?.let { runCatching { TavernCharacterCard.fromJson(it) }.getOrNull() }
    }
    val userName = settings.displaySetting.userNickname.ifBlank { "User" }
    val characterName = card?.name?.ifBlank { assistant.name } ?: assistant.name.ifBlank { "Assistant" }
    val themeVariables = mapOf(
        "--rikkahub-bg" to "transparent",
        "--rikkahub-surface" to hex(colorScheme.surface),
        "--rikkahub-surface-variant" to hex(colorScheme.surfaceVariant),
        "--rikkahub-text" to hex(colorScheme.onSurface),
        "--rikkahub-text-secondary" to hex(colorScheme.onSurfaceVariant),
        "--rikkahub-border" to hex(colorScheme.outlineVariant),
        "--rikkahub-accent" to hex(colorScheme.primary),
    )
    val visibleConversation = remember(conversation, visibleMessageId) {
        if (visibleMessageId == null) {
            conversation
        } else {
            conversation.copy(
                messageNodes = conversation.messageNodes.filter { node ->
                    node.messages.any { it.id == visibleMessageId }
                },
            )
        }
    }
    val snapshot = remember(visibleConversation, userName, characterName, themeVariables, assistant, loading) {
        buildTavernConversationSnapshot(
            conversation = visibleConversation,
            userName = userName,
            characterName = characterName,
            themeCssVariables = themeVariables,
            cardCss = TavernCardStyleResolver.resolve(assistant)?.css,
            streaming = loading,
        )
    }
    val worldEntries = remember(settings.lorebooks, conversation.lorebookIds, candidateRuntime) {
        candidateRuntime?.snapshot()?.worldEntries?.map { entry ->
            ((entry["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "Entry") to
                ((entry["content"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "")
        } ?: settings.lorebooks
            .filter { it.id in conversation.lorebookIds }
            .flatMap { book -> book.entries.map { it.name to it.content } }
    }
    val runtimeContext = remember(conversation, assistant, card, userName, loading, variables, worldEntries) {
        buildTavernContextSnapshot(
            TavernContextSnapshotInput(
                conversation = conversation,
                assistant = assistant,
                characterCard = card,
                userName = userName,
                isGenerating = loading,
                variables = variables,
                worldEntries = worldEntries,
            ),
        )
    }
    val currentMessage = remember(conversation.currentMessages.lastOrNull()) {
        conversation.currentMessages.lastOrNull()?.let {
            runCatching { JsonInstant.encodeToJsonElement(UIMessage.serializer(), it) }.getOrNull()
        }
    }
    val modelById = remember(settings.providers) { settings.providers.flatMap { it.models }.associateBy { it.id } }
    val headerSource = remember(settings, assistant, modelById) {
        {
            val model = modelById[assistant.chatModelId ?: settings.chatModelId]
            (assistant.customHeaders + model?.customHeaders.orEmpty()).map { it.name to it.value }
        }
    }

    TavernConversationWebView(
        snapshot = snapshot,
        contextSnapshot = runtimeContext,
        currentMessage = currentMessage,
        headerSource = headerSource,
        actions = actions,
        ownsSendHookController = ownsSendHookController,
        runtimeBindings = candidateRuntime?.runtimeBindings(),
        modifier = modifier,
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun TavernConversationWebView(
    snapshot: TavernConversationSnapshot,
    contextSnapshot: JsonObject,
    currentMessage: JsonElement?,
    headerSource: () -> List<Pair<String, String>>,
    actions: TavernConversationActions,
    ownsSendHookController: Boolean = true,
    runtimeBindings: TavernGreetingRuntimeBindings? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settingsStore: SettingsStore = koinInject()
    val statusVariableStore: StatusVariableStore = koinInject()
    val hostEventBus: TavernHostEventBus = koinInject()
    val scriptRegistry: TavernScriptRegistry = koinInject()
    val sendHookStore: TavernSendHookStore = koinInject()
    val appSettings by settingsStore.settingsFlow.collectAsState()
    val runtimeScope = rememberCoroutineScope()
    val permissionStore = remember { TavernRuntimePermissionStore(appSettings.runtimePermissions) }
    val latestHeaderSource by rememberUpdatedState(headerSource)
    val runtimeController = remember(snapshot.conversationId, runtimeBindings) {
        TavernRuntimeController(
            conversationId = runCatching { kotlin.uuid.Uuid.parse(snapshot.conversationId) }.getOrNull(),
            worldRepository = runtimeBindings?.worldRepository
                ?: SettingsBackedTavernWorldRepository(SettingsStoreTavernWorldGateway(settingsStore)),
            permissionStore = permissionStore,
            variableGateway = runtimeBindings?.variableGateway
                ?: StatusStoreTavernVariableGateway(
                    statusVariableStore,
                    SettingsStoreTavernVariableGateway(settingsStore),
                ),
            hostEventFlow = hostEventBus.events,
            hostEventScope = runtimeScope,
            scriptRegistry = runtimeBindings?.scriptRegistry ?: scriptRegistry,
            headerSource = { latestHeaderSource() },
            registrationObserver = runtimeBindings?.registrationObserver
                ?: me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeRegistrationObserver.NONE,
            currentMessageWriter = runtimeBindings?.currentMessageWriter,
        )
    }
    val sendHookBinding = remember(sendHookStore, runtimeController, ownsSendHookController) {
        TavernSendHookControllerBinding(
            store = sendHookStore,
            controller = runtimeController,
            enabled = ownsSendHookController,
        )
    }
    val latestSnapshot by rememberUpdatedState(snapshot)
    val latestContext by rememberUpdatedState(contextSnapshot)
    val latestCurrentMessage by rememberUpdatedState(currentMessage)
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val networkAllowed = remember { AtomicBoolean(appSettings.runtimePermissions.allowNetwork) }
    var renderState by remember(snapshot.conversationId) { mutableStateOf(TavernConversationRenderState.initial()) }

    SideEffect {
        permissionStore.update(appSettings.runtimePermissions)
        networkAllowed.set(appSettings.runtimePermissions.allowNetwork)
        sendHookBinding.attach()
    }
    DisposableEffect(runtimeController, sendHookBinding) {
        onDispose {
            runtimeController.cancelHostEventCollection()
            sendHookBinding.detach()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            webViewRef.value?.let { webView ->
                runCatching { webView.removeJavascriptInterface("TavernConversationBridge") }
                runCatching { webView.removeJavascriptInterface("TavernRuntimeBridge") }
                runCatching { webView.stopLoading() }
                runCatching { webView.destroy() }
            }
            webViewRef.value = null
        }
    }
    LaunchedEffect(renderState.generation, renderState.status) {
        if (renderState.status == TavernConversationRenderStatus.LOADING) {
            val generation = renderState.generation
            delay(INITIAL_RENDER_TIMEOUT_MS)
            if (renderState.generation == generation && renderState.status == TavernConversationRenderStatus.LOADING) {
                renderState = renderState.onFailure(generation, "酒馆消息区加载超时，已保留原始文本")
            }
        }
    }
    LaunchedEffect(runtimeController) {
        runtimeController.outboundEvents.collect { (name, payload) ->
            webViewRef.value?.postRuntimeEvent(name, payload ?: JsonNull)
        }
    }
    LaunchedEffect(runtimeController, contextSnapshot, currentMessage) {
        runtimeController.setCurrentMessage(currentMessage ?: JsonNull)
        runtimeController.setContext(contextSnapshot)
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (renderState.status != TavernConversationRenderStatus.FAILED) {
            key(renderState.generation) {
                val generation = renderState.generation
                var renderedSnapshot by remember(generation) { mutableStateOf(snapshot) }
                val actionToken = remember(generation) { java.util.UUID.randomUUID().toString() }
                val initialDocument = remember(generation, snapshot.conversationId, actionToken) {
                    buildRuntimeConversationDocument(context, snapshot, actionToken)
                }
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            val webView = this
                            webViewRef.value = webView
                            setBackgroundColor(AndroidColor.TRANSPARENT)
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            isVerticalScrollBarEnabled = true
                            isHorizontalScrollBarEnabled = false
                            settings.applySecureConversationSettings()
                            val actionBridge = TavernConversationBridge(
                                actionToken = actionToken,
                                actions = actions,
                                onOpenLink = { openExternalLink(ctx, it) },
                                onDocumentReady = {
                                    runtimeController.setCurrentMessage(latestCurrentMessage ?: JsonNull)
                                    runtimeController.setContext(latestContext)
                                    webView.postRuntimeContext(latestContext)
                                    val patches = diffTavernSnapshots(renderedSnapshot, latestSnapshot)
                                    if (patches.isNotEmpty()) webView.postConversationPatches(patches)
                                    renderedSnapshot = latestSnapshot
                                    renderState = renderState.onReady(generation)
                                },
                                dispatch = { callback -> webView.post(callback) },
                            )
                            addJavascriptInterface(actionBridge, "TavernConversationBridge")
                            addJavascriptInterface(
                                TavernConversationRuntimeBridge(
                                    actionToken = actionToken,
                                    controller = runtimeController,
                                ) { callbackName, responseJson ->
                                    val payload = JSONObject.quote(responseJson)
                                    webView.postEvaluate(
                                        "(function(){var cb=window['$callbackName'];" +
                                            "if(typeof cb==='function'){cb(JSON.parse($payload));}})();",
                                    )
                                },
                                "TavernRuntimeBridge",
                            )
                            webViewClient = secureClient(
                                networkAllowed = networkAllowed,
                                onFailure = { reason ->
                                    renderState = renderState.onFailure(generation, reason)
                                },
                                onOpenExternal = { openExternalLink(ctx, it.toString()) },
                            )
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                setWebViewRenderProcessClient(
                                    ContextCompat.getMainExecutor(ctx),
                                    object : WebViewRenderProcessClient() {
                                        override fun onRenderProcessResponsive(
                                            view: WebView,
                                            renderer: WebViewRenderProcess?,
                                        ) = Unit

                                        override fun onRenderProcessUnresponsive(
                                            view: WebView,
                                            renderer: WebViewRenderProcess?,
                                        ) {
                                            renderState = renderState.onFailure(
                                                generation,
                                                "酒馆渲染进程无响应，已切换静态降级",
                                            )
                                        }
                                    },
                                )
                            }
                            loadDataWithBaseURL(
                                TAVERN_CONVERSATION_BASE_URL,
                                initialDocument,
                                "text/html",
                                "UTF-8",
                                null,
                            )
                        }
                    },
                    update = { webView ->
                        if (renderState.status == TavernConversationRenderStatus.READY && renderedSnapshot != snapshot) {
                            val patches = diffTavernSnapshots(renderedSnapshot, snapshot)
                            if (patches.isNotEmpty()) webView.postConversationPatches(patches)
                            renderedSnapshot = snapshot
                        }
                    },
                    onRelease = { webView ->
                        if (webViewRef.value === webView) webViewRef.value = null
                        runCatching { webView.removeJavascriptInterface("TavernConversationBridge") }
                        runCatching { webView.removeJavascriptInterface("TavernRuntimeBridge") }
                        runCatching { webView.stopLoading() }
                        runCatching { webView.destroy() }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        when (renderState.status) {
            TavernConversationRenderStatus.LOADING -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
            )
            TavernConversationRenderStatus.FAILED -> StaticConversationFallback(
                snapshot = snapshot,
                reason = renderState.reason.orEmpty(),
                onRetry = { renderState = renderState.retry() },
                onCompose = actions::onFallbackRequested,
                modifier = Modifier.fillMaxSize(),
            )
            TavernConversationRenderStatus.READY -> Unit
        }
    }
}

@Composable
private fun StaticConversationFallback(
    snapshot: TavernConversationSnapshot,
    reason: String,
    onRetry: () -> Unit,
    onCompose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
            snapshot.nodes.forEach { node ->
                Text(node.selectedMessage.name, style = MaterialTheme.typography.labelMedium)
                Text(node.selectedMessage.parts.joinToString("\n\n") { it.text })
            }
            TextButton(onClick = onRetry) { Text("重试酒馆视图") }
            TextButton(onClick = onCompose) { Text("切换兼容视图") }
        }
    }
}

private fun buildRuntimeConversationDocument(
    context: android.content.Context,
    snapshot: TavernConversationSnapshot,
    actionToken: String,
): String {
    val runtime = buildTavernRuntimeScript()
    return buildTavernConversationDocument(
        context = context,
        initial = snapshot,
        runtimeScript = runtime,
        actionToken = actionToken,
    )
}

private fun WebSettings.applySecureConversationSettings() {
    javaScriptEnabled = true
    domStorageEnabled = true
    loadWithOverviewMode = true
    useWideViewPort = true
    setSupportZoom(false)
    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    allowFileAccess = false
    allowContentAccess = false
    @Suppress("DEPRECATION")
    allowFileAccessFromFileURLs = false
    @Suppress("DEPRECATION")
    allowUniversalAccessFromFileURLs = false
}

private fun secureClient(
    networkAllowed: AtomicBoolean,
    onFailure: (String) -> Unit,
    onOpenExternal: (Uri) -> Unit,
): WebViewClient = object : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val uri = request?.url ?: return true
        if (shouldOpenTavernNavigation(uri.toString(), request.hasGesture())) onOpenExternal(uri)
        return true
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val uri = request?.url ?: return blockedResponse()
        if (!shouldAllowTavernSubresource(uri.toString(), networkAllowed.get())) {
            return blockedResponse()
        }
        return null
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: android.webkit.WebResourceError?,
    ) {
        if (request?.isForMainFrame == true) onFailure("酒馆消息区加载失败，已保留原始文本")
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        onFailure(if (detail?.didCrash() == true) "酒馆渲染进程崩溃，已保留原始文本" else "酒馆渲染进程已退出")
        return true
    }
}

private fun blockedResponse() = WebResourceResponse(
    "text/plain",
    "UTF-8",
    403,
    "Blocked",
    mapOf("Cache-Control" to "no-store"),
    ByteArrayInputStream(ByteArray(0)),
)

private fun openExternalLink(context: android.content.Context, rawUrl: String) {
    if (!isAllowedTavernConversationLink(rawUrl)) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(rawUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun WebView.postConversationPatches(patches: List<TavernConversationPatch>) {
    val json = hostJson.encodeToString(ListSerializer(TavernConversationPatch.serializer()), patches)
    val quoted = JSONObject.quote(json)
    postEvaluate("window.RikkahubConversationDocument&&window.RikkahubConversationDocument.applyPatches($quoted);")
}

private fun WebView.postRuntimeContext(context: JsonObject) {
    val detail = JSONObject.quote(context.toString())
    postEvaluate(
        "(function(){var d=JSON.parse($detail),n='th:context_updated';" +
            "document.dispatchEvent(new CustomEvent(n,{detail:d,bubbles:true}));" +
            "document.querySelectorAll('iframe').forEach(function(f){try{f.contentWindow.postMessage({__rikkahubEvent:n,detail:d},'*');}catch(_){}});})();",
    )
}

private fun WebView.postRuntimeEvent(name: String, payload: JsonElement) {
    val eventName = JSONObject.quote("th:$name")
    val detail = JSONObject.quote(payload.toString())
    postEvaluate(
        "(function(){var d=JSON.parse($detail),n=$eventName;" +
            "document.dispatchEvent(new CustomEvent(n,{detail:d,bubbles:true}));" +
            "document.querySelectorAll('iframe').forEach(function(f){try{f.contentWindow.postMessage({__rikkahubEvent:n,detail:d},'*');}catch(_){}});})();",
    )
}

private fun WebView.postEvaluate(script: String) {
    post { runCatching { evaluateJavascript(script, null) } }
}
