package me.rerere.rikkahub.ui.pages.chat.tavern

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.service.tavern.TavernGreetingSession
import kotlin.uuid.Uuid

/**
 * Full-width pre-chat stage. Every candidate stays composed so its WebView/runtime keeps running;
 * only the selected one is raised above the others.
 */
@Composable
internal fun TavernOpeningStage(
    session: TavernGreetingSession,
    conversation: Conversation,
    assistant: Assistant,
    settings: Settings,
    onCommit: (Uuid) -> Unit,
    autoCommitFirst: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val candidates = if (autoCommitFirst) session.candidates.take(1) else session.candidates
    if (candidates.isEmpty()) return
    var selectedIndex by rememberSaveable(session.conversationId) { mutableIntStateOf(0) }
    val readyCandidates = remember(session) { mutableStateMapOf<Uuid, Boolean>() }
    var autoCommitStarted by remember(session) { androidx.compose.runtime.mutableStateOf(false) }
    selectedIndex = selectedIndex.coerceIn(candidates.indices)
    LaunchedEffect(session, selectedIndex) {
        if (!session.isLocked) session.selectCandidate(candidates[selectedIndex].id)
    }
    val inertActions = remember {
        object : TavernConversationActions {
            override fun onMessageLongPress(messageId: Uuid) = Unit
            override fun onSelectBranch(nodeId: Uuid, index: Int) = Unit
            override fun onOpenHtml(messageId: Uuid) = Unit
            override fun onFallbackRequested() = Unit
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        candidates.forEachIndexed { index, candidate ->
            val overlay by candidate.runtime.overlayFlow.collectAsState()
            val previewConversation = remember(conversation, candidate.id, overlay) {
                conversation.copy(
                    messageNodes = overlay.messages.map { it.toMessageNode() },
                    statusVariables = overlay.chatVariables,
                )
            }
            TavernConversationPane(
                conversation = previewConversation,
                assistant = assistant,
                settings = settings,
                loading = false,
                actions = inertActions,
                ownsSendHookController = false,
                candidateRuntime = candidate.runtime,
                onRenderStatus = { status ->
                    readyCandidates[candidate.id] = status == TavernConversationRenderStatus.READY
                    if (status == TavernConversationRenderStatus.READY) session.markCandidateReady(candidate.id)
                    if (autoCommitFirst && index == 0 && status == TavernConversationRenderStatus.READY && !autoCommitStarted) {
                        autoCommitStarted = true
                        if (session.requestCommit(candidate.id)) onCommit(candidate.id)
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (index == selectedIndex) 1f else 0f }
                    .zIndex(if (index == selectedIndex) 1f else 0f),
            )
        }

        if (!autoCommitFirst) Surface(
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .zIndex(2f),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "开场 ${selectedIndex + 1} / ${candidates.size}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = "预览脚本的网络请求会立即发生，选择前无法撤销；消息、变量、世界书和注册仍彼此隔离。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!autoCommitFirst) Surface(
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp)
                .zIndex(2f),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        selectedIndex = (selectedIndex - 1 + candidates.size) % candidates.size
                        session.selectCandidate(candidates[selectedIndex].id)
                    },
                    enabled = candidates.size > 1,
                ) { Text("上一个") }
                Button(
                    onClick = {
                        val id = candidates[selectedIndex].id
                        if (session.requestCommit(id)) onCommit(id)
                    },
                    enabled = readyCandidates[candidates[selectedIndex].id] == true,
                ) { Text("使用这个开场") }
                TextButton(
                    onClick = {
                        selectedIndex = (selectedIndex + 1) % candidates.size
                        session.selectCandidate(candidates[selectedIndex].id)
                    },
                    enabled = candidates.size > 1,
                ) { Text("下一个") }
            }
        }
    }
}
