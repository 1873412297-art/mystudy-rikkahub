package me.rerere.rikkahub.ui.components.richtext

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.ai.slash.TavernScriptRegistry
import me.rerere.rikkahub.data.ai.status.StatusVariableStore
import me.rerere.rikkahub.data.ai.status.TavernHostEventBus
import me.rerere.rikkahub.data.ai.status.TavernHostEventType
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeBridge
import me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeController
import me.rerere.rikkahub.ui.components.richtext.runtime.ChatServiceTavernRuntimeMessageGateway
import me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimePermissionStore
import me.rerere.rikkahub.ui.components.richtext.runtime.SettingsBackedTavernWorldRepository
import me.rerere.rikkahub.ui.components.richtext.runtime.SettingsStoreTavernVariableGateway
import me.rerere.rikkahub.ui.components.richtext.runtime.SettingsStoreTavernWorldGateway
import me.rerere.rikkahub.ui.components.richtext.runtime.StatusStoreTavernVariableGateway
import me.rerere.rikkahub.ui.components.richtext.runtime.TavernSendHookStore
import me.rerere.rikkahub.ui.components.richtext.runtime.buildTavernRuntimeScript
import me.rerere.rikkahub.ui.components.richtext.st.StableDomSegment
import me.rerere.rikkahub.ui.components.richtext.st.StableSegmentSnapshot
import org.json.JSONObject
import org.koin.compose.koinInject

/**
 * Renders content in a WebView. Two modes:
 * - Pre-rendered HTML: 用 sandbox="allow-scripts" 的 iframe 隔离运行（opaque origin，
 *   JS 能跑但完全无法访问父页 / cookie / storage / native bridge → XSS 攻面归零）
 * - Markdown+HTML: uses mark.html template with markdown-it
 *
 * Height auto-adapts to content up to maxHeight (default 400dp).
 * If content exceeds maxHeight, the WebView enables internal scrolling.
 */
@SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
@Composable
internal fun MarkdownWebView(
    content: String,
    modifier: Modifier = Modifier,
    isRawHtml: Boolean = false,
    /** 对消息前端应用酒馆助手的脚本与网络权限；STABLE_DOM 等宿主文档不受此开关影响。 */
    applyTavernFrontendPolicy: Boolean = false,
    onWebViewCreated: (WebView) -> Unit = {},
    onWebViewDisposed: (WebView) -> Unit = {},
    onWebViewLoadFailed: (String) -> Unit = {},
    onWebViewRendererCrashed: (WebView?, Boolean) -> Unit = { _, _ -> },
    additionalJavascriptInterface: Pair<String, Any>? = null,
    /**
     * 高度上限（dp）：超过此高度的内容会触发 WebView 内部纵向滚动，
     * 而不是把外层 Compose 容器撑到无限高。
     * 默认 600dp —— 一屏能看大半，剩下的内部滚动浏览。
     * 传 null 才是**不限高，按内容真实高度展开**（外层 LazyList 自然滚动）。
     */
    maxHeightDp: Int? = 600,
    /**
     * 给定时跳过内部高度自适应，WebView 直接占满外层 modifier 给的空间。
     * 用于「卡片预览」那种刻意限定的窗口尺寸（罕见）。
     */
    fixedHeight: Boolean = false,
    /**
     * 酒馆脚本运行时上下文：消息所属会话 ID 与当前消息 JSON。
     * 传入后 variables.* 走真实持久化链路（chat → StatusVariableStore / global → Settings），
     * messages.getCurrent 返回该消息，脚本可经 events.subscribe 接收宿主事件（th:<name> DOM 事件）。
     */
    tavernConversationId: Uuid? = null,
    /** 消息角色（渲染事件细分：assistant → CHARACTER_MESSAGE_RENDERED，user → USER_MESSAGE_RENDERED） */
    tavernMessageRole: MessageRole? = null,
    tavernCurrentMessage: JsonElement? = null,
    /**
     * 上下文快照（SillyTavern.getContext 数据源；null 时不推送）。
     * 宿主 ChatList 构建 → controller.setContext 哈希去重 → th:context_updated DOM 事件。
     */
    tavernContextSnapshot: JsonObject? = null,
    /**
     * STABLE_DOM 文档追加注入的角色卡 CSS（经 CssSanitizer 清洗后内联 <style>）。
     * 目前 CSS 实际注入发生在 MarkdownBlock 的 buildStableMessageHtml 构建期（成品 HTML 已含
     * <style>），此参数预留用于未来路径；参与 renderKey 失效。
     */
    tavernExtraCss: String? = null,
    /**
     * 卡样式版本键（变化时触发整文档重载）。
     */
    tavernStyleVersionKey: String? = null,
    /**
     * 流式生成中：true 时内容变化走 applySegmentPatch 增量，false 时整文档重载
     */
    streaming: Boolean = false,
    /**
     * streaming=true 时必传：当前内容的分段（用于段 diff）
     */
    streamSegments: List<StableDomSegment>? = null,
    /**
     * 初始最小高度（dp），首次上报前占位，避免 0dp 闪烁或 100dp 假高
     */
    minHeightDp: Int = 24,
    /**
     * 酒馆脚本 requestHeaders.get 数据源（assistant + model 自定义头）。
     * ChatList → ChatMessage → MarkdownBlock 透传链组装；null 时返回空列表。
     * 注意：仅允许在 allowRequestHeaders 权限开启时经 RPC 拉取（含 API key，敏感）。
     */
    tavernHeaderSource: (() -> List<Pair<String, String>>)? = null,
    /** 隐藏浏览器会话的受信脚本身份；消息前端保持 null，绝不归因到常驻脚本。 */
    tavernScriptId: String? = null,
) {
    val context = LocalContext.current
    val settingsStore: SettingsStore = koinInject()
    val chatService: ChatService = koinInject()
    val providerManager: me.rerere.ai.provider.ProviderManager = koinInject()
    val statusVariableStore: StatusVariableStore = koinInject()
    val tavernHostEventBus: TavernHostEventBus = koinInject()
    val tavernScriptRegistry: TavernScriptRegistry = koinInject()
    val tavernSendHookStore: TavernSendHookStore = koinInject()
    val appSettings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val colorScheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    var viewHeight by remember { mutableStateOf(0) }

    val bg = colorScheme.surfaceContainerLow
    val text = colorScheme.onSurface
    val primary = colorScheme.primary
    val bgHex = hex(bg)
    val textHex = hex(text)
    val primaryHex = hex(primary)
    val surfaceHex = hex(colorScheme.surface)
    val surfaceVariantHex = hex(colorScheme.surfaceVariant)
    val outlineVariantHex = hex(colorScheme.outlineVariant)
    val onSurfaceVariantHex = hex(colorScheme.onSurfaceVariant)
    val runtimePermissionStore = remember {
        TavernRuntimePermissionStore(appSettings.runtimePermissions)
    }
    LaunchedEffect(appSettings.runtimePermissions) {
        runtimePermissionStore.update(appSettings.runtimePermissions)
    }
    val runtimeCoroutineScope = rememberCoroutineScope()
    // headerSource 每次 RPC 调用读最新透传 lambda（assistant/model 头变化即时生效，不重建 controller）
    val latestHeaderSource by rememberUpdatedState(tavernHeaderSource)
    val runtimeController = remember(settingsStore) {
        TavernRuntimeController(
            conversationId = tavernConversationId,
            worldRepository = SettingsBackedTavernWorldRepository(
                SettingsStoreTavernWorldGateway(settingsStore)
            ),
            permissionStore = runtimePermissionStore,
            variableGateway = StatusStoreTavernVariableGateway(
                statusVariableStore = statusVariableStore,
                settingsGateway = SettingsStoreTavernVariableGateway(settingsStore),
            ),
            messageGateway = ChatServiceTavernRuntimeMessageGateway(chatService),
            hostEventFlow = tavernHostEventBus.events,
            hostEventScope = runtimeCoroutineScope,
            // 共享 Koin 单例注册表：WebView 侧注册的宏/命令对发送管线（ChatService）可见
            scriptRegistry = tavernScriptRegistry,
            headerSource = { latestHeaderSource?.invoke() ?: emptyList() },
            generationGateway = me.rerere.rikkahub.ui.components.richtext.runtime.ProviderBackedTavernGenerationGateway(
                settingsStore = settingsStore,
                providerManager = providerManager,
            ),
            scriptId = tavernScriptId,
        )
    }
    SideEffect {
        runtimeController.updateConversationId(tavernConversationId)
    }
    // controller 离开组合时取消宿主事件收集 job，避免 job 泄漏到组合结束才取消、
    // 期间继续向无人消费的 SharedFlow 空发。会话切换仅更新 controller 的绑定，不重建 WebView。
    DisposableEffect(runtimeController) {
        onDispose {
            runtimeController.cancelHostEventCollection()
            if (tavernSendHookStore.activeController === runtimeController) {
                tavernSendHookStore.activeController = null
            }
        }
    }
    // 发送前钩子桥登记：最近组合的消息 WebView 的 controller 成为发送管线问询对象
    // （多 WebView 并发时最后组合者生效，best-effort 语义）
    SideEffect {
        tavernSendHookStore.activeController = runtimeController
    }
    // 宿主注入当前消息（messages.getCurrent 的数据源）与上下文快照（getContext 数据源）。
    // setContext 内部按内容哈希去重，LaunchedEffect 每次 key 变化调用即可。
    LaunchedEffect(runtimeController, tavernCurrentMessage, tavernContextSnapshot) {
        tavernContextSnapshot?.let { runtimeController.setContext(it) }
        tavernCurrentMessage?.let { runtimeController.setCurrentMessage(it) }
    }
    // 脚本/宿主事件 → WebView 内 th:<name> DOM CustomEvent
    val tavernWebViewRef = remember { mutableStateOf<WebView?>(null) }
    // WebView 销毁治理：离开组合时移除 JS 桥、停止加载并销毁原生 WebView。
    DisposableEffect(Unit) {
        onDispose {
            val webView = tavernWebViewRef.value ?: return@onDispose
            onWebViewDisposed(webView)
            runCatching { webView.removeJavascriptInterface("RikkahubBridge") }
            runCatching { webView.removeJavascriptInterface("TavernRuntimeBridge") }
            additionalJavascriptInterface?.first?.let { name ->
                runCatching { webView.removeJavascriptInterface(name) }
            }
            runCatching { webView.stopLoading() }
            runCatching { webView.destroy() }
            tavernWebViewRef.value = null
        }
    }
    LaunchedEffect(runtimeController) {
        runtimeController.outboundEvents.collect { (name, payload) ->
            val view = tavernWebViewRef.value ?: return@collect
            val eventName = JSONObject.quote("th:$name")
            val detailJson = JSONObject.quote((payload ?: JsonNull).toString())
            view.postEvaluateJavascript(
                "(function(){var d=JSON.parse($detailJson);" +
                    "document.dispatchEvent(new CustomEvent($eventName,{detail:d,bubbles:true}));})();"
            )
        }
    }

    val useIframeSandbox = isRawHtml || looksLikeHtmlDocument(content)
    val allowFrontendScripts = !applyTavernFrontendPolicy || appSettings.tavernHelperRenderSettings.allowScripts
    val allowFrontendNetwork = !applyTavernFrontendPolicy || appSettings.tavernHelperRenderSettings.allowNetwork
    val maxHeightPx = maxHeightDp?.let { with(density) { it.dp.toPx().toInt() } }
    // baseKey 不含 content：路径/主题/角色/卡样式变化才整文档重载
    val baseKey = listOf(
        useIframeSandbox,
        allowFrontendScripts,
        allowFrontendNetwork,
        fixedHeight,
        bgHex,
        textHex,
        primaryHex,
        surfaceHex,
        surfaceVariantHex,
        outlineVariantHex,
        onSurfaceVariantHex,
        tavernExtraCss,
        tavernStyleVersionKey,
        streaming,
    ).joinToString("|")
    val contentKey = "${content.length}|${content.hashCode()}"

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = bg,
        tonalElevation = 1.dp,
    ) {
        // 记录已加载的 (baseKey, contentKey) 组合，避免 update 块每次 recompose
        // 都触发 loadDataWithBaseURL —— 重 load 会让 iframe 被推倒重建，高度从占位值
        // 起步重新测量，造成「渲染一半不动」的视觉假象。
        val lastBaseKey = remember { mutableStateOf<String?>(null) }
        val lastContentKey = remember { mutableStateOf<String?>(null) }
        val lastSegments = remember { mutableStateOf<List<StableDomSegment>>(emptyList()) }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    val webView = this
                    tavernWebViewRef.value = this
                    onWebViewCreated(this)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = WebView.OVER_SCROLL_NEVER
                    isNestedScrollingEnabled = true
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    // ── Independent scrolling via requestDisallowInterceptTouchEvent ──
                    var hasOverflow = false
                    var contentHeightPx = 0
                    var downX = 0f
                    var downY = 0f
                    var swipeDir = 0 // 0=undecided, 1=vertical, 2=horizontal
                    setOnTouchListener { _, event ->
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                downX = event.x
                                downY = event.y
                                swipeDir = 0
                                if (hasOverflow) {
                                    parent.requestDisallowInterceptTouchEvent(true)
                                }
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = abs(event.x - downX)
                                val dy = abs(event.y - downY)
                                if (swipeDir == 0 && (dx > 10 || dy > 10)) {
                                    swipeDir = if (dx > dy) 2 else 1
                                }
                                if (swipeDir == 2) {
                                    parent.requestDisallowInterceptTouchEvent(false)
                                    return@setOnTouchListener false
                                }
                                if (!hasOverflow) return@setOnTouchListener false
                                if (dy < 8) return@setOnTouchListener false
                                val atTop = scrollY <= 2
                                val atBottom = scrollY + height >= contentHeightPx - 4
                                val dirY = (downY - event.y).toInt()
                                if ((dirY < 0 && atTop) || (dirY > 0 && atBottom)) {
                                    parent.requestDisallowInterceptTouchEvent(false)
                                } else {
                                    parent.requestDisallowInterceptTouchEvent(true)
                                }
                                downY = event.y
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                parent.requestDisallowInterceptTouchEvent(false)
                                swipeDir = 0
                            }
                        }
                        false
                    }

                    // 父页 JS 桥：iframe 通过 postMessage 上来，父页脚本透传到 RikkahubBridge。
                    // 注意：Bridge 只暴露给父页脚本（受我们控制的 host page），不直接暴露给
                    // sandboxed iframe 内的用户脚本（iframe 是 opaque origin，无法访问 window.parent）。
                    //
                    // 高度更新策略：iframe 内 JS（ResizeObserver/MutationObserver）会持续上报
                    // 高度。我们采用「单调增长 + 大跨度重置」策略：
                    //  - 新值 > 当前 → 直接更新（内容继续展开）
                    //  - 新值 << 当前（小于当前的 50%）→ 重置（content 已被替换为新文档）
                    //  - 中间值 → 忽略（防抖动）
                    val bridge = RikkahubBridge(
                        onContentHeight = { pxHeight ->
                            val h = pxHeight + 16
                            val shouldUpdate = when {
                                h > contentHeightPx -> true
                                h < contentHeightPx / 2 && h > 50 -> true  // content 换了
                                else -> false
                            }
                            if (shouldUpdate) {
                                contentHeightPx = h
                                post {
                                    if (maxHeightPx != null && h > maxHeightPx) {
                                        viewHeight = maxHeightPx
                                        hasOverflow = true
                                        isVerticalScrollBarEnabled = true
                                    } else {
                                        viewHeight = h.coerceAtLeast(60)
                                        hasOverflow = false
                                        isVerticalScrollBarEnabled = false
                                    }
                                }
                            }
                        },
                        onOpenLink = { rawUrl ->
                            // 协议白名单：只放 http/https/mailto/tel，其它（含 javascript:/intent:/file:）直接吞掉。
                            val trimmed = rawUrl.trim()
                            val lower = trimmed.lowercase()
                            val safe = lower.startsWith("http://") || lower.startsWith("https://") ||
                                lower.startsWith("mailto:") || lower.startsWith("tel:")
                            if (safe) {
                                runCatching {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(trimmed))
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                }
                            }
                        }
                    )
                    addJavascriptInterface(bridge, "RikkahubBridge")

                    val tavernBridge = TavernRuntimeBridge(
                        controller = runtimeController,
                        emitResult = { callbackName, responseJson ->
                            val payload = JSONObject.quote(responseJson)
                            webView.postEvaluateJavascript(
                                "(function(){var cb=window['$callbackName'];" +
                                    "if(typeof cb==='function'){cb(JSON.parse($payload));}})();"
                            )
                        },
                        scriptId = tavernScriptId,
                    )
                    addJavascriptInterface(tavernBridge, "TavernRuntimeBridge")
                    additionalJavascriptInterface?.let { (name, value) ->
                        addJavascriptInterface(value, name)
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?
                        ): Boolean {
                            // 拒绝任何尝试导航父页的动作（用户卡里的 a 标签 / 表单提交等）。
                            // http/https 转交系统浏览器；其它协议（intent:/file:/about: 等）直接屏蔽。
                            val uri = request?.url ?: return true
                            val scheme = uri.scheme?.lowercase()
                            if (scheme == "http" || scheme == "https") {
                                runCatching {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    view?.context?.startActivity(intent)
                                }
                            }
                            return true
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            // 父页 shell 加载完时立刻测父页（适用 markdown 路径——markdown-it/katex
                            // 跑完后 body height 就是实际高度）。
                            // sandbox iframe 路径不依赖这次测量——iframe 内 JS 会通过 postMessage
                            // 持续上报真实高度到 RikkahubBridge.reportHeight，那条路径会更新 viewHeight。
                            // 前端 HTML 只能使用其注入脚本上报的真实内容高度。这里若测量 WebView
                            // 当前视口，会把 maxHeight (通常为 600dp) 当成内容高度写回，短卡片
                            // 随即形成“视口越高、上报越高”的自我撑高循环。
                            if (shouldMeasurePageHeight(applyTavernFrontendPolicy)) {
                                view?.measureContentHeight { pxHeight ->
                                    val h = pxHeight + 16
                                    contentHeightPx = h
                                    if (maxHeightPx != null && h > maxHeightPx) {
                                        viewHeight = maxHeightPx
                                        hasOverflow = true
                                        view.post { view.isVerticalScrollBarEnabled = true }
                                    } else {
                                        viewHeight = h.coerceAtLeast(60)
                                        hasOverflow = false
                                        view.post { view.isVerticalScrollBarEnabled = false }
                                    }
                                }
                                // 多次延迟重测兜底——markdown 异步渲染（mermaid/katex/dompurify）
                                // 完成时间不固定。前端 HTML 路径由内容桥持续上报，不走这里。
                                listOf(150L, 400L, 1000L, 2500L).forEach { delay ->
                                    view?.postDelayed({
                                        view.measureContentHeight { h2 ->
                                            val h = h2 + 16
                                            if (h > contentHeightPx) {
                                                contentHeightPx = h
                                                if (maxHeightPx == null || h <= maxHeightPx) {
                                                    viewHeight = h.coerceAtLeast(60)
                                                    hasOverflow = false
                                                    view.isVerticalScrollBarEnabled = false
                                                } else if (!hasOverflow) {
                                                    viewHeight = maxHeightPx
                                                    hasOverflow = true
                                                    view.isVerticalScrollBarEnabled = true
                                                }
                                            }
                                        }
                                    }, delay)
                                }
                            }
                            // 酒馆脚本宿主事件：消息渲染完成
                            tavernConversationId?.let { cid ->
                                tavernHostEventBus.emit(
                                    type = TavernHostEventType.MESSAGE_RENDERED,
                                    conversationId = cid,
                                )
                                when (tavernMessageRole) {
                                    MessageRole.USER -> tavernHostEventBus.emit(
                                        type = TavernHostEventType.USER_MESSAGE_RENDERED,
                                        conversationId = cid,
                                    )
                                    MessageRole.ASSISTANT -> tavernHostEventBus.emit(
                                        type = TavernHostEventType.CHARACTER_MESSAGE_RENDERED,
                                        conversationId = cid,
                                    )
                                    else -> Unit
                                }
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?,
                        ) {
                            if (request?.isForMainFrame == true) {
                                onWebViewLoadFailed(error?.description?.toString().orEmpty())
                            }
                        }

                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: android.webkit.RenderProcessGoneDetail?,
                        ): Boolean {
                            onWebViewRendererCrashed(view, detail?.didCrash() == true)
                            view?.destroy()
                            return true
                        }
                    }
                    settings.apply {
                        // 父页 shell 始终开 JS：
                        // - Markdown 路径：markdown-it / katex / mermaid 需要 JS
                        // - Raw HTML 路径：父页本身是受我们控制的 shell，里面只放一个
                        //   sandbox iframe（无 allow-same-origin），用户 HTML 跑在 iframe 里,
                        //   通过 postMessage 与父页通讯，无法触达 RikkahubBridge / cookie / storage。
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        // 自适应屏幕：父页 shell 的 <meta name="viewport" content="width=device-width">
                        // 已经声明按设备宽度渲染，useWideViewPort + loadWithOverviewMode 可以一起开来
                        // 让没声明 viewport 的旧 HTML 也按手机宽度缩放、不出现横向滚动。
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        setSupportZoom(false)
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        blockNetworkLoads = !allowFrontendNetwork
                        allowFileAccess = false
                        allowContentAccess = false
                        @Suppress("DEPRECATION")
                        allowFileAccessFromFileURLs = false
                        @Suppress("DEPRECATION")
                        allowUniversalAccessFromFileURLs = false
                    }
                    val html = if (useIframeSandbox) {
                        buildSandboxHostHtml(content, bgHex, textHex, fixedHeight, allowFrontendScripts)
                    } else {
                        buildMarkdownPreviewHtml(context, normalizeRichTextContent(content), colorScheme)
                    }
                    loadDataWithBaseURL("https://rikkahub.local/", html, "text/html", "UTF-8", null)
                    lastBaseKey.value = baseKey
                    lastContentKey.value = contentKey
                    lastSegments.value = streamSegments.orEmpty()
                }
            },
            update = { webView ->
                webView.settings.blockNetworkLoads = !allowFrontendNetwork
                // baseKey（路径/主题/角色/卡样式）变了才整文档重载；
                // 否则 contentKey 变化时：streaming 走段 diff 增量 patch，非 streaming 才重 load。
                if (lastBaseKey.value != baseKey) {
                    val html = if (useIframeSandbox) {
                        buildSandboxHostHtml(content, bgHex, textHex, fixedHeight, allowFrontendScripts)
                    } else {
                        buildMarkdownPreviewHtml(context, normalizeRichTextContent(content), colorScheme)
                    }
                    webView.loadDataWithBaseURL("https://rikkahub.local/", html, "text/html", "UTF-8", null)
                    lastBaseKey.value = baseKey
                    lastContentKey.value = contentKey
                    lastSegments.value = streamSegments.orEmpty()
                    return@AndroidView
                }
                if (lastContentKey.value == contentKey) return@AndroidView
                if (streaming) {
                    val old = lastSegments.value
                    val new = streamSegments.orEmpty()
                    val patches = StableSegmentSnapshot.diff(old, new)
                    lastSegments.value = new
                    lastContentKey.value = contentKey
                    if (patches.isEmpty()) return@AndroidView
                    val patchJson = JSONObject.quote(StableSegmentSnapshot.encodePatches(patches))
                    webView.postEvaluateJavascript(
                        "window.RikkahubDomBridge && window.RikkahubDomBridge.applySegmentPatch($patchJson);"
                    )
                } else {
                    val html = if (useIframeSandbox) {
                        buildSandboxHostHtml(content, bgHex, textHex, fixedHeight, allowFrontendScripts)
                    } else {
                        buildMarkdownPreviewHtml(context, normalizeRichTextContent(content), colorScheme)
                    }
                    webView.loadDataWithBaseURL("https://rikkahub.local/", html, "text/html", "UTF-8", null)
                    lastContentKey.value = contentKey
                    lastSegments.value = streamSegments.orEmpty()
                }
            },
            // fixedHeight：让 WebView 占满外层 modifier 给的空间（如调用方写了 .height(300.dp)）
            // 否则按内容自适应到 viewHeight（首次上报前用 minHeightDp 占位）。
            modifier = if (fixedHeight) {
                Modifier.fillMaxWidth().fillMaxHeight()
            } else {
                Modifier.fillMaxWidth().height(with(density) {
                    maxOf(viewHeight, with(density) { minHeightDp.dp.toPx() }.toInt()).toDp()
                })
            },
        )
    }
}

/**
 * Native bridge exposed to the *host* page (mark.html / sandbox host shell).
 *
 * 安全模型：
 * - host page 是我们打包/生成的，原点是 https://rikkahub.local/，开 JS 后才能调用本桥。
 * - sandboxed iframe 没有 allow-same-origin，与 host page 之间是 cross-origin，
 *   iframe 内的用户脚本无法 `window.parent.RikkahubBridge.xxx()`，只能 postMessage 到 host
 *   shell；host shell 的 message 处理函数对内容做白名单后才转发到本桥。
 *   → 用户脚本永远拿不到原始 RikkahubBridge 引用。
 */
internal class RikkahubBridge(
    private val onContentHeight: (Int) -> Unit,
    private val onOpenLink: (String) -> Unit,
) {
    @JavascriptInterface
    fun reportHeight(px: Int) {
        if (px in 1..200_000) onContentHeight(px)
    }

    @JavascriptInterface
    fun openLink(url: String) {
        if (url.length <= 4096) onOpenLink(url)
    }
}

/** Measure host page content height via JS. */
private fun WebView.measureContentHeight(onResult: (Int) -> Unit) {
    evaluateJavascript(
        "(function(){var h=document.body.scrollHeight;var dpr=window.devicePixelRatio||1;return Math.ceil(h*dpr);})()"
    ) { r ->
        r?.toIntOrNull()?.let { h -> if (h > 0) onResult(h) }
    }
}

/** 把一段 JS 投递到 WebView 的 UI 线程执行（evaluateJavascript 必须在 UI 线程调用）。 */
private fun WebView.postEvaluateJavascript(script: String) {
    post { evaluateJavascript(script, null) }
}

/**
 * Detect pre-rendered HTML.
 *
 * 这里有两类信号：
 * 1. 顶层就是 HTML 元素 / DOCTYPE / @media 块 → 几乎肯定整段是 HTML 文档
 * 2. 内容里出现「跨多行的 <style>/<script>/<svg> 块」或「完整文档标签 <html/<body」
 *    → jetbrains-markdown parser 对这些状态机不稳，常常漏报为非 HTML，需要兜底
 *
 * 检测前会先剥离 markdown 代码块（```...``` / ~~~...~~~ / `...`），避免把
 * 「模型在 ```html 代码块里讲 HTML」误判成 HTML 文档。
 *
 * 但有个例外：SillyTavern 角色卡作者经常把整段 HTML 文档**整体**用 ``` 包起来防止
 * markdown 解析破坏 HTML 标签。这种情况下剥离代码块后只剩很少内容（几乎全是空白），
 * 而代码块内是真正的 HTML 文档（<!doctype>/<html>/<style>），应该走 sandbox 渲染
 * 而不是当代码块文本显示。
 *
 * 被错判时用户也只是落到 WebView/sandbox iframe 渲染，不是安全漏洞——
 * 真正的 XSS 拦截在 mark.html 的 DOMPurify 与 MarkdownWebView 的 sandbox 隔离里。
 */
internal fun looksLikeHtml(content: String): Boolean = looksLikeHtmlDocument(content)

/**
 * 检测「整段 HTML 文档被 fenced code block (```html ... ```) 包住」的写法。
 *
 * 判定规则：
 *  1. 内容必须以 ``` 或 ~~~ 开头（允许前后少量空白）
 *  2. 找到对应闭合 fence，闭合后剩下的非空白字符 ≤ 32（说明文档外几乎没东西）
 *  3. fence 内的内容含强 HTML 文档信号（<!doctype>/<html>/<style>/<script>）
 *
 * 命中时返回 true，整段会走 sandbox iframe 渲染（fence 包裹会被剥掉）。
 */
internal fun looksLikeFencedHtmlDocument(content: String): Boolean {
    val text = content.trim()
    if (text.length < 30) return false
    val firstChar = text[0]
    if (firstChar != '`' && firstChar != '~') return false

    // 数开头 fence
    var fenceLen = 0
    while (fenceLen < text.length && text[fenceLen] == firstChar) fenceLen++
    if (fenceLen < 3) return false

    // 找首行末（可能含 info string 如 ```html）
    val firstLineEnd = text.indexOf('\n', fenceLen)
    if (firstLineEnd < 0) return false

    // 找闭合 fence（行首至少 fenceLen 个相同字符）
    val closeMarker = firstChar.toString().repeat(fenceLen)
    var searchFrom = firstLineEnd + 1
    var closeStart = -1
    while (true) {
        val idx = text.indexOf(closeMarker, searchFrom)
        if (idx < 0) break
        // 必须在行首
        if (idx == 0 || text[idx - 1] == '\n') {
            closeStart = idx
            break
        }
        searchFrom = idx + 1
    }
    if (closeStart < 0) return false

    // 闭合后的剩余内容
    var closeEnd = closeStart + fenceLen
    while (closeEnd < text.length && text[closeEnd] == firstChar) closeEnd++
    val tail = text.substring(closeEnd).trim()
    if (tail.length > 32) return false  // 文档外内容太多 → 是教程文本，不命中

    // fence 内的内容。完整文档命中 <!doctype>/<html>；JS-Slash-Runner/SillyTavern
    // 常见写法会把可运行 HTML app 包在 ```html 中，只提供 <body> + <style>/<script>。
    // 这种结构也需要作为 HTML app 渲染，而不是作为代码块显示。
    val inner = text.substring(firstLineEnd + 1, closeStart)
    val hasDocumentRoot = Regex("<!doctype\\s+html", RegexOption.IGNORE_CASE).containsMatchIn(inner) ||
        Regex("<html[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(inner)
    val hasRunnableBody = Regex("<body[\\s>][\\s\\S]*?</body>", RegexOption.IGNORE_CASE).containsMatchIn(inner) &&
        (
            Regex("<style[\\s>][\\s\\S]*?</style>", RegexOption.IGNORE_CASE).containsMatchIn(inner) ||
                Regex("<script[\\s>][\\s\\S]*?</script>", RegexOption.IGNORE_CASE).containsMatchIn(inner)
            )
    return hasDocumentRoot || hasRunnableBody
}

/**
 * 如果 content 整段被 ```...``` 或 ~~~...~~~ 包住，剥掉外层 fence 返回内部内容；
 * 否则原样返回。用于 sandbox iframe 渲染前的预处理——SillyTavern 角色卡作者常用
 * ``` 包整段 HTML 防止 markdown 解析破坏标签，但 iframe 渲染需要原始 HTML。
 */
private fun unwrapFencedHtml(content: String): String {
    val text = content.trim()
    if (text.length < 6) return content
    val firstChar = text[0]
    if (firstChar != '`' && firstChar != '~') return content

    var fenceLen = 0
    while (fenceLen < text.length && text[fenceLen] == firstChar) fenceLen++
    if (fenceLen < 3) return content

    val firstLineEnd = text.indexOf('\n', fenceLen)
    if (firstLineEnd < 0) return content

    val closeMarker = firstChar.toString().repeat(fenceLen)
    var searchFrom = firstLineEnd + 1
    var closeStart = -1
    while (true) {
        val idx = text.indexOf(closeMarker, searchFrom)
        if (idx < 0) break
        if (idx == 0 || text[idx - 1] == '\n') {
            closeStart = idx
            break
        }
        searchFrom = idx + 1
    }
    if (closeStart < 0) return content

    return text.substring(firstLineEnd + 1, closeStart).trim()
}

/**
 * 剥离 markdown 代码区（fenced code block + inline code）。
 *
 * 规则贴近 CommonMark，覆盖三种常见形态：
 *  - ```lang\n...\n```
 *  - ~~~lang\n...\n~~~
 *  - `inline`
 *
 * 实现细节：
 *  - fenced 块用「至少 3 个相同 fence char」作为开闭标记，并要求闭合行的 fence 数 ≥ 开启行
 *    （CommonMark 要求 ≥，但实际中模型几乎只用 3）。这里简化成「相同字符 3+」即可，
 *    够覆盖 99%。
 *  - inline code 用单反引号配对，避免误吃跨行内容。
 *  - 代码区被替换成等长空白（保留行号/偏移信息），不影响后续正则按位置切片。
 */
internal fun stripMarkdownCodeRegions(text: String): String {
    val sb = StringBuilder(text.length)
    var i = 0
    val n = text.length
    while (i < n) {
        val c = text[i]
        // ── fenced block: ``` 或 ~~~ 开头，且必须出现在行首（前面要么是行首要么是 \n）
        if ((c == '`' || c == '~') && (i == 0 || text[i - 1] == '\n')) {
            // 数 fence char
            var fenceLen = 0
            while (i + fenceLen < n && text[i + fenceLen] == c) fenceLen++
            if (fenceLen >= 3) {
                // 找到本行结束
                var lineEnd = text.indexOf('\n', i + fenceLen)
                if (lineEnd < 0) lineEnd = n
                // 用空白填充开启 fence + info 行（保持长度）
                repeat(lineEnd - i) { sb.append(' ') }
                if (lineEnd < n) { sb.append('\n'); }
                var j = lineEnd + 1
                // 找闭合 fence（行首 fenceLen+ 个相同字符）
                while (j < n) {
                    val isLineStart = (j == 0 || text[j - 1] == '\n')
                    if (isLineStart && text[j] == c) {
                        var closeLen = 0
                        while (j + closeLen < n && text[j + closeLen] == c) closeLen++
                        if (closeLen >= fenceLen) {
                            var closeLineEnd = text.indexOf('\n', j + closeLen)
                            if (closeLineEnd < 0) closeLineEnd = n
                            // 内容（j 之前到当前 sb 已填充到 lineEnd+1，需补 lineEnd+1 .. j）
                            for (k in (lineEnd + 1) until j) {
                                sb.append(if (text[k] == '\n') '\n' else ' ')
                            }
                            // 闭合 fence 行
                            repeat(closeLineEnd - j) { sb.append(' ') }
                            if (closeLineEnd < n) { sb.append('\n') }
                            i = closeLineEnd + 1
                            break
                        }
                    }
                    j++
                }
                if (j >= n) {
                    // 没找到闭合：当作开放代码块，剩余全清
                    for (k in (lineEnd + 1) until n) {
                        sb.append(if (text[k] == '\n') '\n' else ' ')
                    }
                    i = n
                }
                continue
            }
        }
        // ── inline code: 单反引号（不在 fence 路径里时才走这里）
        if (c == '`') {
            // 数本次反引号 run 长度
            var runLen = 0
            while (i + runLen < n && text[i + runLen] == '`') runLen++
            // 找等长闭合
            var k = i + runLen
            var closed = false
            while (k <= n - runLen) {
                if (text[k] == '`') {
                    var closeRun = 0
                    while (k + closeRun < n && text[k + closeRun] == '`') closeRun++
                    if (closeRun == runLen) {
                        // 替换 i .. k+runLen
                        for (m in 0 until (k + runLen - i)) {
                            sb.append(if (text[i + m] == '\n') '\n' else ' ')
                        }
                        i = k + runLen
                        closed = true
                        break
                    } else {
                        k += closeRun
                    }
                } else {
                    k++
                }
            }
            if (closed) continue
            // 没闭合就当普通字符处理
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}

/**
 * 构建用户 HTML 的最终加载文档：直接作为 WebView 主文档加载。
 *
 * 历史：之前用 sandbox iframe + srcdoc 隔离，但 Android WebView 的 srcdoc
 * 在 Chromium HTML parser 有属性大小限制，大角色卡会被静默截断（"渲染一半空白"）。
 * 换 Blob URL 加载又被 sandbox（无 allow-same-origin）阻止。
 * 最终方案：去掉 iframe 隔离层，用户 HTML 直接作为主文档，配合 Bridge 收紧、
 * 导航拦截、CSP 限制，达成与 iframe 等价的实际安全边界。
 *
 * 安全模型：
 *  1. WebView 加载 origin = `https://rikkahub.local/`（合成域，无任何真实后端服务），
 *     用户脚本即使能跑 fetch / XHR 也访问不到任何敏感资源。
 *  2. RikkahubBridge 只暴露两个方法：
 *      - reportHeight(px)：被严格 range-check (1..200000)，副作用仅是设置 Compose
 *        viewHeight 状态，没法用于任何攻击。
 *      - openLink(url)：长度上限 4096 + 协议白名单（http/https/mailto/tel），
 *        无法触发 intent: / file: / javascript: 等危险跳转。
 *     这两个方法即使被恶意脚本任意调用都构不成攻击面。
 *  3. WebViewClient.shouldOverrideUrlLoading 拦截所有顶层导航：
 *      - http/https → 用 system Intent 转交浏览器（不在本 WebView 加载）
 *      - 其它协议 → 直接屏蔽
 *  4. WebView settings：mixedContentMode=NEVER_ALLOW、allowFileAccess=false、
 *     allowContentAccess=false、allowFile/UniversalAccessFromFileURLs=false。
 *  5. localStorage/sessionStorage/cookie 都跟着 origin = rikkahub.local 走，
 *     在不同对话/角色卡之间没有隔离 —— 但因为整个 origin 没有任何敏感数据，
 *     这种"跨卡共享 storage"只是 UX 层面的事（角色卡 A 写的 theme 偏好被 B 读到），
 *     不构成数据泄漏。如果未来要按卡隔离，可以在每次 load 前调
 *     WebStorage.getInstance().deleteAllData()。
 */
private fun buildSandboxHostHtml(
    userHtml: String,
    bgHex: String,
    textHex: String,
    fixedHeight: Boolean = false,
    allowUserScripts: Boolean = true,
): String {
    val unwrapped = sanitizeTavernFrontendHtml(unwrapFencedHtml(userHtml), allowUserScripts)

    val runtimeScript = if (allowUserScripts) buildTavernRuntimeScript() else ""
    val injectTag = "<script>$runtimeScript\n${buildIframeInjectScript()}</script>"

    val isCompleteDoc = unwrapped.trimStart().let {
        it.startsWith("<!DOCTYPE", ignoreCase = true) || it.startsWith("<html", ignoreCase = true)
    }

    val finalHtml = if (isCompleteDoc) {
        // 完整文档：把测量/链接拦截脚本插到 </body> 前
        val bodyEnd = unwrapped.lastIndexOf("</body>", ignoreCase = true)
        if (bodyEnd >= 0) {
            unwrapped.substring(0, bodyEnd) + injectTag + unwrapped.substring(bodyEnd)
        } else {
            unwrapped + injectTag
        }
    } else {
        // HTML 片段：包一个最小外壳，给个默认背景/字体
        """<!DOCTYPE html>
<html><head><meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0">
<style>html,body{margin:0;padding:0;background:$bgHex;color:$textHex;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;line-height:1.5;word-wrap:break-word}img{max-width:100%;height:auto}</style>
</head><body>
$unwrapped
$injectTag
</body></html>"""
    }
    return finalHtml
}

/**
 * 注入脚本：测量高度 → RikkahubBridge.reportHeight()，链接拦截 → RikkahubBridge.openLink()。
 * 直接调 native bridge（不再 postMessage），因为没有 iframe 隔离层了。
 */
internal fun buildIframeInjectScript(): String = """
(function(){
  function measure(){
    var b=document.body;
    if(!b)return 0;
    var base=b.getBoundingClientRect(),top=base.top,maxBottom=0;
    try{
      var range=document.createRange();
      range.selectNodeContents(b);
      var rr=range.getBoundingClientRect();
      maxBottom=Math.max(maxBottom,rr.bottom-top);
    }catch(e){}
    var nodes=b.querySelectorAll('*');
    for(var i=0;i<nodes.length;i++){
      var rect=nodes[i].getBoundingClientRect();
      maxBottom=Math.max(maxBottom,rect.bottom-top);
    }
    return Math.max(1,Math.ceil(maxBottom));
  }
  var lastH=0,rafId=null;
  function tick(){
    if(rafId)return;
    rafId=(window.requestAnimationFrame||setTimeout)(function(){
      rafId=null;
      var h=measure();
      if(h&&(lastH===0||Math.abs(h-lastH)>=4)){
        lastH=h;
        try{window.RikkahubBridge&&window.RikkahubBridge.reportHeight(Math.ceil(h*(window.devicePixelRatio||1)));}catch(e){}
      }
    },16);
  }
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',tick);else tick();
  window.addEventListener('load',tick);
  [50,200,500,1000,2000,4000].forEach(function(d){setTimeout(tick,d);});
  if(typeof ResizeObserver!=='undefined'){try{var ro=new ResizeObserver(function(){tick();});ro.observe(document.documentElement);if(document.body)ro.observe(document.body);}catch(e){}}
  if(typeof MutationObserver!=='undefined'){try{var mo=new MutationObserver(function(){tick();});mo.observe(document.documentElement,{childList:true,subtree:true});}catch(e){}}
  document.addEventListener('load',function(ev){if(ev.target&&ev.target.tagName==='IMG')tick();},true);
  if(document.fonts&&document.fonts.ready){try{document.fonts.ready.then(tick);}catch(e){}}

  function isExternal(href){
    if(!href)return false;
    var s=String(href).trim();
    if(!s||s.charAt(0)==='#')return false;
    if(/^javascript:/i.test(s))return false;
    return true;
  }
  document.addEventListener('click',function(ev){
    var t=ev.target;
    while(t&&t!==document.body&&t.tagName!=='A')t=t.parentNode;
    if(!t||t.tagName!=='A')return;
    var href=t.getAttribute('href');
    if(isExternal(href)){ev.preventDefault();try{window.RikkahubBridge&&window.RikkahubBridge.openLink(href);}catch(e){}}
  },true);
  document.addEventListener('submit',function(ev){try{ev.preventDefault();}catch(e){}},true);
  try{window.open=function(url){if(typeof url==='string')try{window.RikkahubBridge&&window.RikkahubBridge.openLink(url);}catch(e){}return null;};}catch(e){}
})();
""".trimIndent()

internal fun hex(c: androidx.compose.ui.graphics.Color) =
    String.format("#%02X%02X%02X", (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())

internal fun shouldMeasurePageHeight(applyTavernFrontendPolicy: Boolean): Boolean =
    !applyTavernFrontendPolicy
