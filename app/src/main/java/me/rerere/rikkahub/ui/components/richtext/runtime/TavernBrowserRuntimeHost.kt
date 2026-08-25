package me.rerere.rikkahub.ui.components.richtext.runtime

import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperScope
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperScopeType
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperScript
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperScriptFolder
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperScriptNode
import me.rerere.rikkahub.data.ai.tavernhelper.TavernHelperScriptRepository
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.components.richtext.MarkdownWebView
import org.json.JSONObject
import org.koin.compose.koinInject

internal object TavernBrowserSessionRegistry {
    private val sessions = ConcurrentHashMap<String, WebView>()

    fun register(scriptId: String, webView: WebView) {
        sessions[scriptId] = webView
    }

    fun unregister(scriptId: String, webView: WebView) {
        sessions.remove(scriptId, webView)
    }

    fun emitButton(scriptId: String, buttonName: String) {
        val view = sessions[scriptId] ?: return
        val name = JSONObject.quote(buttonName)
        view.post {
            view.evaluateJavascript(
                "(function(){var n=$name;var e=window.getButtonEvent(n);" +
                    "document.dispatchEvent(new CustomEvent('th:'+e,{detail:{name:n}}));})();",
                null,
            )
        }
    }

    fun reload(scriptId: String) {
        sessions[scriptId]?.let { webView -> webView.post { webView.reload() } }
    }
}

internal data class TavernBrowserScriptSelection(
    val active: List<TavernHelperScript>,
    val overLimit: List<TavernHelperScript>,
)

internal fun selectTavernBrowserScripts(
    global: List<TavernHelperScriptNode>,
    character: List<TavernHelperScriptNode>,
    assistant: List<TavernHelperScriptNode>,
): TavernBrowserScriptSelection {
    val enabled = flattenEnabled(global) + flattenEnabled(character) + flattenEnabled(assistant)
    return TavernBrowserScriptSelection(
        active = enabled.take(MAX_BROWSER_SESSIONS),
        overLimit = enabled.drop(MAX_BROWSER_SESSIONS),
    )
}

internal data class TavernBrowserRuntimeContext(
    val conversationId: String?,
    val assistantId: String?,
)

internal data class TavernBrowserConversationAssistantResolution(
    val sourceConversationId: String?,
    val assistantId: String?,
)

internal fun assistantIdForActiveConversation(
    activeConversationId: String?,
    resolution: TavernBrowserConversationAssistantResolution,
): String? = resolution.assistantId.takeIf { resolution.sourceConversationId == activeConversationId }

internal fun resolveTavernBrowserRuntimeContext(
    backStack: List<NavKey>,
    conversationAssistantId: String?,
    settingsAssistantId: String?,
): TavernBrowserRuntimeContext {
    val chat = backStack.lastOrNull { it is Screen.Chat } as? Screen.Chat
    val managementAssistantId = (backStack.lastOrNull { it is Screen.TavernHelper } as? Screen.TavernHelper)?.assistantId
    return TavernBrowserRuntimeContext(
        conversationId = chat?.id,
        assistantId = conversationAssistantId ?: managementAssistantId ?: settingsAssistantId,
    )
}

@Composable
internal fun rememberTavernBrowserRuntimeContext(
    backStack: List<NavKey>,
    settingsAssistantId: String?,
): TavernBrowserRuntimeContext {
    val conversationRepository: ConversationRepository = koinInject()
    val conversationId = (backStack.lastOrNull { it is Screen.Chat } as? Screen.Chat)?.id
    val conversationAssistantResolution by produceState(
        initialValue = TavernBrowserConversationAssistantResolution(null, null),
        key1 = conversationId,
    ) {
        val assistantId = conversationId
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?.let { runCatching { conversationRepository.getConversationById(it) }.getOrNull() }
            ?.assistantId
            ?.toString()
        value = TavernBrowserConversationAssistantResolution(conversationId, assistantId)
    }
    return resolveTavernBrowserRuntimeContext(
        backStack = backStack,
        conversationAssistantId = assistantIdForActiveConversation(
            activeConversationId = conversationId,
            resolution = conversationAssistantResolution,
        ),
        settingsAssistantId = settingsAssistantId,
    )
}

@Composable
internal fun rememberTavernBrowserScripts(assistantId: String?): List<TavernHelperScript> {
    val repository: TavernHelperScriptRepository = koinInject()
    val globalFlow = remember(repository) {
        repository.observe(TavernHelperScope(TavernHelperScopeType.GLOBAL))
    }
    val global by globalFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val assistantFlow = remember(repository, assistantId) {
        assistantId?.let { repository.observe(TavernHelperScope(TavernHelperScopeType.ASSISTANT, it)) }
    }
    val assistant by assistantFlow
        ?.collectAsStateWithLifecycle(initialValue = emptyList())
        ?: remember { androidx.compose.runtime.mutableStateOf(emptyList()) }
    val characterFlow = remember(repository, assistantId) {
        assistantId?.let { repository.observe(TavernHelperScope(TavernHelperScopeType.CHARACTER, it)) }
    }
    val character by characterFlow
        ?.collectAsStateWithLifecycle(initialValue = emptyList())
        ?: remember { androidx.compose.runtime.mutableStateOf(emptyList()) }

    val selection = remember(global, character, assistant) {
        selectTavernBrowserScripts(global, character, assistant)
    }
    LaunchedEffect(selection) {
        tavernScriptDiagnostics.applySelection(
            activeIds = selection.active.mapTo(mutableSetOf()) { it.id },
            overLimitIds = selection.overLimit.mapTo(mutableSetOf()) { it.id },
        )
    }
    return selection.active
}

/**
 * Keeps browser scripts alive at the navigation root, independently of an individual chat entry.
 */
@Composable
internal fun TavernBrowserRuntimeCoordinator(context: TavernBrowserRuntimeContext) {
    TavernBrowserRuntimeHost(
        scripts = rememberTavernBrowserScripts(context.assistantId),
        conversationId = context.conversationId,
    )
}

@Composable
internal fun TavernBrowserRuntimeHost(
    scripts: List<TavernHelperScript>,
    conversationId: String?,
) {
    val repository: TavernHelperScriptRepository = koinInject()
    val conversationUuid = remember(conversationId) {
        conversationId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
    }
    Box(modifier = Modifier.size(1.dp)) {
        scripts.forEach { script ->
            key(script.id, script.content.hashCode()) {
                val scriptBridge = remember(script.id, repository) {
                    TavernBrowserScriptBridge(script.id, repository)
                }
                MarkdownWebView(
                    content = buildTavernBrowserSessionHtml(script),
                    modifier = Modifier.size(1.dp),
                    isRawHtml = true,
                    fixedHeight = true,
                    maxHeightDp = 1,
                    tavernConversationId = conversationUuid,
                    onWebViewCreated = {
                        TavernBrowserSessionRegistry.register(script.id, it)
                        tavernScriptDiagnostics.setStatus(script.id, TavernScriptRuntimeStatus.WAITING_PERMISSION)
                    },
                    onWebViewDisposed = {
                        TavernBrowserSessionRegistry.unregister(script.id, it)
                        tavernScriptDiagnostics.setStatus(script.id, TavernScriptRuntimeStatus.PAUSED)
                    },
                    onWebViewLoadFailed = { detail ->
                        tavernScriptDiagnostics.setStatus(script.id, TavernScriptRuntimeStatus.LOAD_FAILED)
                        tavernScriptDiagnostics.record(
                            scriptId = script.id,
                            level = TavernScriptDiagnosticLevel.ERROR,
                            category = "renderer",
                            message = detail.ifBlank { "WebView 加载失败" },
                            error = detail.takeIf { it.isNotBlank() },
                        )
                    },
                    onWebViewRendererCrashed = { didCrash ->
                        val detail = if (didCrash) "WebView 渲染进程崩溃" else "WebView 渲染进程被系统终止"
                        tavernScriptDiagnostics.setStatus(script.id, TavernScriptRuntimeStatus.RUNTIME_CRASH)
                        tavernScriptDiagnostics.record(
                            scriptId = script.id,
                            level = TavernScriptDiagnosticLevel.ERROR,
                            category = "renderer",
                            message = detail,
                            error = detail,
                        )
                    },
                    additionalJavascriptInterface = "RikkahubScriptBridge" to scriptBridge,
                )
            }
        }
    }
}

@Composable
internal fun TavernBrowserScriptButtons(
    scripts: List<TavernHelperScript>,
    modifier: Modifier = Modifier,
) {
    val buttons = remember(scripts) {
        scripts.flatMap { script ->
            if (!script.button.enabled) emptyList() else script.button.buttons
                .filter { it.visible }
                .map { script.id to it.name }
        }
    }
    if (buttons.isEmpty()) return
    FlowRow(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        buttons.forEach { (scriptId, name) ->
            AssistChip(
                onClick = { TavernBrowserSessionRegistry.emitButton(scriptId, name) },
                label = { Text(name) },
            )
        }
    }
}

private fun flattenEnabled(nodes: List<TavernHelperScriptNode>): List<TavernHelperScript> = buildList {
    nodes.forEach { node ->
        when (node) {
            is TavernHelperScript -> if (node.enabled) add(node)
            is TavernHelperScriptFolder -> if (node.enabled) addAll(node.scripts.filter { it.enabled })
        }
    }
}

private const val MAX_BROWSER_SESSIONS = 32
