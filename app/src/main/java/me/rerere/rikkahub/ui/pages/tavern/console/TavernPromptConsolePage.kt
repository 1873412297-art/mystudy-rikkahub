package me.rerere.rikkahub.ui.pages.tavern.console

import android.content.ClipData
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.trace.PromptTraceReadResult
import me.rerere.rikkahub.data.ai.trace.PromptTraceStatus
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

@Composable
fun TavernPromptConsolePage(conversationId: String) {
    val vm: TavernPromptConsoleVM = koinViewModel(
        parameters = { parametersOf(conversationId) }
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val copiedMessage = stringResource(R.string.tavern_prompt_console_copied)
    val traceClipLabel = stringResource(R.string.tavern_prompt_console_trace_clip_label)
    val messageClipLabel = stringResource(R.string.tavern_prompt_console_message_clip_label)

    TavernPromptConsoleContent(
        state = state,
        onBack = { navController.popBackStack() },
        onSelectTrace = vm::selectTrace,
        onSelectTab = vm::selectTab,
        onCopyAll = {
            vm.copySelectedTrace()?.let { text ->
                scope.launch {
                    clipboard.setClipEntry(
                        ClipEntry(ClipData.newPlainText(traceClipLabel, text))
                    )
                    toaster.show(copiedMessage)
                }
            }
        },
        onCopyMessage = { index ->
            vm.copyMessage(index)?.let { text ->
                scope.launch {
                    clipboard.setClipEntry(
                        ClipEntry(ClipData.newPlainText(messageClipLabel, text))
                    )
                    toaster.show(copiedMessage)
                }
            }
        },
        onClear = vm::clearConversationTraces,
    )
}

@Composable
fun TavernPromptConsoleContent(
    state: TavernPromptConsoleUiState,
    onBack: () -> Unit,
    onSelectTrace: (Uuid) -> Unit,
    onSelectTab: (TavernPromptConsoleTab) -> Unit,
    onCopyAll: () -> Unit,
    onCopyMessage: (Int) -> Unit,
    onClear: () -> Unit,
) {
    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    val traces = remember(state.traces) {
        state.traces.sortedWith(
            compareByDescending<PromptTraceReadResult> { it.createdAtEpochMs }
                .thenByDescending { trace ->
                    (trace as? PromptTraceReadResult.Available)
                        ?.record
                        ?.payload
                        ?.metadata
                        ?.providerStepIndex
                        ?: -1
                }
        )
    }

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tavern_prompt_console_title)) },
                navigationIcon = {
                    BackButton(onClick = onBack)
                },
                actions = {
                    if (state.traces.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = stringResource(
                                    R.string.tavern_prompt_console_clear
                                ),
                            )
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (state.traces.isNotEmpty()) {
                TavernPromptTraceHeader(
                    state = state,
                    traces = traces,
                    onSelectTrace = onSelectTrace,
                )
                SecondaryTabRow(
                    selectedTabIndex = state.selectedTab.ordinal,
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    TavernPromptConsoleTab.entries.forEach { tab ->
                        Tab(
                            selected = state.selectedTab == tab,
                            onClick = { onSelectTab(tab) },
                            text = {
                                Text(
                                    when (tab) {
                                        TavernPromptConsoleTab.OVERVIEW -> stringResource(
                                            R.string.tavern_prompt_console_overview
                                        )
                                        TavernPromptConsoleTab.HITS -> stringResource(
                                            R.string.tavern_prompt_console_hits
                                        )
                                        TavernPromptConsoleTab.SENT_MESSAGES -> stringResource(
                                            R.string.tavern_prompt_console_messages
                                        )
                                        TavernPromptConsoleTab.PREVIEW -> stringResource(
                                            R.string.tavern_prompt_console_preview
                                        )
                                    }
                                )
                            },
                        )
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when {
                    state.loading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                    state.traces.isEmpty() -> TavernPromptEmptyState(
                        title = stringResource(R.string.tavern_prompt_console_no_traces)
                    )
                    state.selectedTab == TavernPromptConsoleTab.PREVIEW -> TavernPromptEmptyState(
                        title = stringResource(R.string.tavern_prompt_console_preview_a2),
                        body = stringResource(R.string.tavern_prompt_console_preview_a2_body),
                    )
                    state.selectedTrace is PromptTraceReadResult.Unavailable -> TavernPromptEmptyState(
                        title = stringResource(R.string.tavern_prompt_console_payload_unavailable)
                    )
                    state.selectedTrace is PromptTraceReadResult.Available -> {
                        val record = state.selectedTrace.record
                        when (state.selectedTab) {
                            TavernPromptConsoleTab.OVERVIEW -> TavernPromptOverview(record)
                            TavernPromptConsoleTab.HITS -> TavernPromptHits(
                                record.payload.injectionHits
                            )
                            TavernPromptConsoleTab.SENT_MESSAGES -> TavernPromptMessages(
                                messages = record.payload.finalMessages,
                                onCopyAll = onCopyAll,
                                onCopyMessage = onCopyMessage,
                            )
                            TavernPromptConsoleTab.PREVIEW -> Unit
                        }
                    }
                    else -> TavernPromptEmptyState(
                        title = stringResource(R.string.tavern_prompt_console_payload_unavailable)
                    )
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.tavern_prompt_console_clear_title)) },
            text = { Text(stringResource(R.string.tavern_prompt_console_clear_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        onClear()
                    }
                ) {
                    Text(stringResource(R.string.tavern_prompt_console_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun TavernPromptTraceHeader(
    state: TavernPromptConsoleUiState,
    traces: List<PromptTraceReadResult>,
    onSelectTrace: (Uuid) -> Unit,
) {
    val selected = state.selectedTrace
    val selectedStatus = when (selected) {
        is PromptTraceReadResult.Available -> selected.record.payload.metadata.status
        is PromptTraceReadResult.Unavailable -> selected.status
        null -> null
    }
    val selectedSpeaker = (selected as? PromptTraceReadResult.Available)
        ?.record
        ?.payload
        ?.metadata
        ?.speakerName

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = state.assistantName.ifBlank { state.conversationTitle },
                style = MaterialTheme.typography.titleMedium,
            )
            if (state.conversationTitle.isNotBlank() && state.conversationTitle != state.assistantName) {
                Text(
                    text = state.conversationTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                selectedStatus?.let {
                    Text(
                        stringResource(R.string.tavern_prompt_console_status, it.name),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                selectedSpeaker?.let {
                    Text(
                        stringResource(R.string.tavern_prompt_console_speaker, it),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            if (
                selected is PromptTraceReadResult.Available &&
                selected.record.payload.metadata.status == PromptTraceStatus.CANCELLED &&
                selected.responseMessageId == null
            ) {
                Text(
                    text = stringResource(R.string.tavern_prompt_console_cancelled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!state.selectedBranchHasTrace) {
                Text(
                    text = stringResource(R.string.tavern_prompt_console_branch_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            Text(
                text = stringResource(R.string.tavern_prompt_console_trace_selector),
                style = MaterialTheme.typography.labelMedium,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                traces.forEachIndexed { index, trace ->
                    val status = when (trace) {
                        is PromptTraceReadResult.Available -> trace.record.payload.metadata.status
                        is PromptTraceReadResult.Unavailable -> trace.status
                    }
                    FilterChip(
                        selected = trace.traceId == state.selectedTraceId,
                        onClick = { onSelectTrace(trace.traceId) },
                        label = {
                            Text(
                                stringResource(
                                    R.string.tavern_prompt_console_trace_call_status,
                                    index + 1,
                                    status.name,
                                )
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun TavernPromptEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
) {
    Box(
        modifier = modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            body?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
