package me.rerere.rikkahub.ui.pages.tavern.console

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TavernPromptConsoleEntryTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun visibleEntryDispatchesOpenExactlyOnce() {
        var opens = 0
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            MaterialTheme {
                TavernPromptConsoleEntry(visible = true, onOpen = { opens++ })
            }
        }

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.tavern_prompt_console_open))
            .performClick()

        assertEquals(1, opens)
    }

    @Test
    fun hiddenEntryDoesNotExist() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            MaterialTheme {
                TavernPromptConsoleEntry(visible = false, onOpen = {})
            }
        }

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.tavern_prompt_console_open))
            .assertDoesNotExist()
    }
}
