package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.dokar.sonner.rememberToasterState
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.TurnTakingStrategy
import me.rerere.rikkahub.service.group.GroupDirectorCommand
import me.rerere.rikkahub.service.group.GroupPlaybackState
import me.rerere.rikkahub.ui.context.LocalToaster
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlin.uuid.Uuid

class GroupDirectorControlsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val pausedState = GroupDirectorUiState(
        effectiveMode = TurnTakingStrategy.AUTO_ROUND_ROBIN,
        playbackState = GroupPlaybackState.PAUSED,
        isGenerating = false,
        oneRoundActive = true,
        oneRoundRemainingCount = 2,
        members = emptyList(),
        canPause = false,
        canContinueRound = true,
        canSkip = false,
    )

    @Test
    fun fabUsesDirectorAccessibilityLabel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            MaterialTheme {
                GroupDirectorFab(state = pausedState, onClick = {})
            }
        }

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.group_director_open))
            .assertIsDisplayed()
    }

    @Test
    fun pausedRoundShowsContinueCurrentRoundAndDispatchesCommand() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var command: GroupDirectorCommand? = null
        composeRule.setContent {
            MaterialTheme {
                GroupDirectorSheetContent(
                    state = pausedState,
                    onCommand = { command = it },
                )
            }
        }

        composeRule
            .onNodeWithText(context.getString(R.string.group_director_continue_current_round))
            .performClick()
        assertEquals(GroupDirectorCommand.ContinueOneRound, command)
        composeRule
            .onNodeWithText(context.getString(R.string.group_director_status_round_remaining, 2))
            .assertIsDisplayed()
    }

    @Test
    fun memberNominationDispatchesExactlyOneOneShotCommand() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val memberId = Uuid.parse("00000000-0000-0000-0000-000000000001")
        var commands = emptyList<GroupDirectorCommand>()
        val state = pausedState.copy(
            oneRoundActive = false,
            oneRoundRemainingCount = 0,
            members = listOf(
                GroupDirectorMemberUi(memberId, "Aileen", Avatar.Dummy, isQueuedNext = false)
            ),
        )
        composeRule.setContent {
            val toaster = rememberToasterState()
            CompositionLocalProvider(LocalToaster provides toaster) {
                MaterialTheme {
                    GroupDirectorSheetContent(
                        state = state,
                        onCommand = { commands = commands + it },
                    )
                }
            }
        }

        composeRule
            .onNodeWithContentDescription(
                context.getString(R.string.group_director_nominate_member, "Aileen")
            )
            .performClick()
        assertEquals(listOf(GroupDirectorCommand.QueueMemberOnce(memberId)), commands)
    }
}
