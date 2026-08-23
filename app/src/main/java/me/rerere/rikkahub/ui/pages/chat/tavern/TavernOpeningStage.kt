package me.rerere.rikkahub.ui.pages.chat.tavern

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.status.TavernHostEventBus
import me.rerere.rikkahub.data.ai.status.TavernHostEventType
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.service.tavern.TavernGreetingSession
import me.rerere.rikkahub.service.tavern.TavernGreetingOverlay
import me.rerere.rikkahub.ui.pages.chat.StatusHudBar
import me.rerere.rikkahub.ui.pages.chat.buildStatusHudPresentation
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

internal data class TavernOpeningSelectionMotion(
    val id: Long,
    val direction: Int,
)

internal fun resolveTavernOpeningSelectionDirection(
    previousIndex: Int,
    nextIndex: Int,
    count: Int,
): Int {
    if (count <= 0 || previousIndex !in 0 until count || nextIndex !in 0 until count) return 0
    if (previousIndex == nextIndex) return 0
    return if (nextIndex > previousIndex) 1 else -1
}

internal fun buildTavernOpeningPreviewConversation(
    conversation: Conversation,
    overlay: TavernGreetingOverlay,
): Conversation = conversation.copy(
    messageNodes = overlay.messages.map { it.toMessageNode() },
    statusVariables = overlay.chatVariables,
)

/**
 * ST-style first-message swipes. At most two not-yet-ready candidates are preloaded at once;
 * ready candidates outside the current/adjacent window are reconstructed from their isolated overlay on demand.
 */
@Composable
internal fun TavernOpeningStage(
    session: TavernGreetingSession,
    conversation: Conversation,
    assistant: Assistant,
    settings: Settings,
    onCommit: (Uuid) -> Unit,
    actions: TavernConversationActions,
    onStatusOptionClick: (String) -> Unit,
    allowCardScripts: Boolean,
    autoCommitFirst: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val hostEventBus: TavernHostEventBus = koinInject()
    val candidates = if (autoCommitFirst) session.candidates.take(1) else session.candidates
    if (candidates.isEmpty()) return
    var selectedIndex by rememberSaveable(session.conversationId) { mutableIntStateOf(0) }
    val readyCandidates = remember(session) { mutableStateMapOf<Uuid, Boolean>() }
    val failedCandidates = remember(session) { mutableStateMapOf<Uuid, Boolean>() }
    val mountedCandidates = remember(session) { mutableStateMapOf<Uuid, Boolean>() }.apply {
        if (isEmpty()) candidates.take(2).forEach { put(it.id, true) }
    }
    var selectionMotionId by remember(session) { mutableStateOf(0L) }
    var selectionMotion by remember(session) { mutableStateOf<TavernOpeningSelectionMotion?>(null) }
    var autoCommitStarted by remember(session) { androidx.compose.runtime.mutableStateOf(false) }
    selectedIndex = selectedIndex.coerceIn(candidates.indices)
    LaunchedEffect(session, selectedIndex) {
        if (!session.isLocked) {
            session.selectCandidate(candidates[selectedIndex].id)
            candidates.forEachIndexed { index, candidate ->
                val resident = kotlin.math.abs(index - selectedIndex) <= 1
                if (resident) mountedCandidates[candidate.id] = true
                else if (readyCandidates[candidate.id] == true) mountedCandidates[candidate.id] = false
            }
        }
    }
    val openingActions = remember(session, candidates) {
        object : TavernConversationActions by actions {
            override fun onSelectGreeting(index: Int) {
                if (index !in candidates.indices || session.isLocked) return
                val direction = resolveTavernOpeningSelectionDirection(selectedIndex, index, candidates.size)
                if (direction == 0) return
                selectionMotionId += 1
                selectionMotion = TavernOpeningSelectionMotion(selectionMotionId, direction)
                selectedIndex = index
                session.selectCandidate(candidates[index].id)
                hostEventBus.emit(
                    type = TavernHostEventType.MESSAGE_SWIPED,
                    conversationId = session.conversationId,
                    payload = buildJsonObject {
                        put(
                            "nodeId",
                            candidates[index].runtime.overlayFlow.value.messages.lastOrNull()?.id?.toString()
                                ?: candidates[index].id.toString(),
                        )
                        put("selectIndex", index)
                        put("opening", true)
                    },
                )
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        candidates.forEachIndexed { index, candidate ->
            if (mountedCandidates[candidate.id] != true) return@forEachIndexed
            val overlay by candidate.runtime.overlayFlow.collectAsState()
            val previewConversation = remember(conversation, candidate.id, overlay) {
                buildTavernOpeningPreviewConversation(conversation, overlay)
            }
            val hasPreviewHud = remember(previewConversation) {
                buildStatusHudPresentation(previewConversation) != null
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (index == selectedIndex) 1f else 0f }
                    .zIndex(if (index == selectedIndex) 1f else 0f),
            ) {
                TavernConversationPane(
                    conversation = previewConversation,
                    assistant = assistant,
                    settings = settings,
                    loading = false,
                    actions = openingActions,
                    ownsSendHookController = false,
                    candidateRuntime = candidate.runtime,
                    allowCardScripts = allowCardScripts,
                    openingSwipe = TavernOpeningSwipe(
                        index = index,
                        count = candidates.size,
                        ready = readyCandidates[candidate.id] == true,
                        failed = failedCandidates[candidate.id] == true,
                        swipes = candidates.map { candidate -> candidate.renderedOpening },
                    ),
                    openingSelectionMotion = selectionMotion.takeIf { index == selectedIndex },
                    onRenderStatus = { status ->
                        readyCandidates[candidate.id] = status == TavernConversationRenderStatus.READY
                        failedCandidates[candidate.id] = status == TavernConversationRenderStatus.FAILED
                        if (status == TavernConversationRenderStatus.READY) {
                            session.markCandidateReady(candidate.id)
                            candidates.firstOrNull { next ->
                                readyCandidates[next.id] != true && mountedCandidates[next.id] != true
                            }?.let { next ->
                                val loadingCount = candidates.count { item ->
                                    mountedCandidates[item.id] == true && readyCandidates[item.id] != true
                                }
                                if (loadingCount < 2) mountedCandidates[next.id] = true
                            }
                            candidates.forEachIndexed { otherIndex, other ->
                                if (readyCandidates[other.id] == true && kotlin.math.abs(otherIndex - selectedIndex) > 1) {
                                    mountedCandidates[other.id] = false
                                }
                            }
                        }
                        if (autoCommitFirst && index == 0 && status == TavernConversationRenderStatus.READY && !autoCommitStarted) {
                            autoCommitStarted = true
                            if (session.requestCommit(candidate.id)) onCommit(candidate.id)
                        }
                    },
                    onStaticFallback = {
                        failedCandidates[candidate.id] = false
                        readyCandidates[candidate.id] = true
                        session.markCandidateReady(candidate.id)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = if (hasPreviewHud) 52.dp else 0.dp),
                )
                if (index == selectedIndex) {
                    StatusHudBar(
                        conversation = previewConversation,
                        assistant = assistant,
                        isGenerating = false,
                        onOptionClick = onStatusOptionClick,
                        tavernWorldEntries = overlay.worldEntries.map { entry ->
                            ((entry["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "Entry") to
                                ((entry["content"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "")
                        },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
