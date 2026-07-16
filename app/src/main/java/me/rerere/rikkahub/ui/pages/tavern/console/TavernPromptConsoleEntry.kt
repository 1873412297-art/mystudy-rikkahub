package me.rerere.rikkahub.ui.pages.tavern.console

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cards02
import me.rerere.rikkahub.R

@Composable
fun TavernPromptConsoleEntry(
    visible: Boolean,
    onOpen: () -> Unit,
) {
    if (!visible) return

    IconButton(onClick = onOpen) {
        Icon(
            imageVector = HugeIcons.Cards02,
            contentDescription = stringResource(R.string.tavern_prompt_console_open),
        )
    }
}
