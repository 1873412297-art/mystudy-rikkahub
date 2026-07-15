package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Next
import me.rerere.hugeicons.stroke.Pause
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.UserGroup03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.TurnTakingStrategy
import me.rerere.rikkahub.service.group.GroupDirectorCommand
import me.rerere.rikkahub.service.group.GroupPlaybackState
import me.rerere.rikkahub.ui.components.ui.UIAvatar

@Composable
fun GroupDirectorFab(
    state: GroupDirectorUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(onClick = onClick, modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = when (state.playbackState) {
                    GroupPlaybackState.PAUSED -> HugeIcons.Play
                    GroupPlaybackState.PAUSE_AFTER_CURRENT -> HugeIcons.Pause
                    GroupPlaybackState.RUNNING -> HugeIcons.UserGroup03
                },
                contentDescription = stringResource(R.string.group_director_open),
            )
            if (state.oneRoundActive) {
                Text(
                    text = state.oneRoundRemainingCount.toString(),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
fun GroupDirectorSheet(
    state: GroupDirectorUiState,
    onDismiss: () -> Unit,
    onCommand: (GroupDirectorCommand) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        GroupDirectorSheetContent(
            state = state,
            onCommand = onCommand,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .padding(16.dp),
        )
    }
}

@Composable
fun GroupDirectorSheetContent(
    state: GroupDirectorUiState,
    onCommand: (GroupDirectorCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = listOf(
        TurnTakingStrategy.MANUAL to R.string.group_director_mode_manual,
        TurnTakingStrategy.AUTO_ROUND_ROBIN to R.string.group_director_mode_round_robin,
        TurnTakingStrategy.AUTO_MODERATOR to R.string.group_director_mode_moderator,
    )
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.group_director_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = when {
                state.oneRoundActive -> stringResource(
                    R.string.group_director_status_round_remaining,
                    state.oneRoundRemainingCount,
                )

                state.playbackState == GroupPlaybackState.PAUSED ->
                    stringResource(R.string.group_director_status_paused)

                state.playbackState == GroupPlaybackState.PAUSE_AFTER_CURRENT ->
                    stringResource(R.string.group_director_status_pause_pending)

                else -> stringResource(R.string.group_director_status_running)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = { onCommand(GroupDirectorCommand.PauseAfterCurrent) },
                enabled = state.canPause,
                modifier = Modifier.weight(1f),
            ) {
                Icon(HugeIcons.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.group_director_pause_after_current))
            }
            FilledTonalButton(
                onClick = { onCommand(GroupDirectorCommand.ContinueOneRound) },
                enabled = state.canContinueRound,
                modifier = Modifier.weight(1f),
            ) {
                Icon(HugeIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(
                        if (state.oneRoundActive) {
                            R.string.group_director_continue_current_round
                        } else {
                            R.string.group_director_continue_round
                        }
                    )
                )
            }
        }
        FilledTonalButton(
            onClick = { onCommand(GroupDirectorCommand.SkipNext) },
            enabled = state.canSkip,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(HugeIcons.Next, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.group_director_skip_next))
        }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = state.effectiveMode == mode,
                    onClick = { onCommand(GroupDirectorCommand.SetMode(mode)) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                ) {
                    Text(stringResource(label))
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.members, key = { it.id.toString() }) { member ->
                val nominateDescription = stringResource(
                    R.string.group_director_nominate_member,
                    member.name,
                )
                Surface(
                    onClick = { onCommand(GroupDirectorCommand.QueueMemberOnce(member.id)) },
                    modifier = Modifier.semantics {
                        contentDescription = nominateDescription
                    },
                    shape = MaterialTheme.shapes.large,
                    color = if (member.isQueuedNext) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    border = if (member.isQueuedNext) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        null
                    },
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        UIAvatar(
                            name = member.name,
                            value = member.avatar,
                            modifier = Modifier.size(32.dp),
                            onClick = { onCommand(GroupDirectorCommand.QueueMemberOnce(member.id)) },
                        )
                        Text(member.name, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
