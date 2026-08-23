package me.rerere.rikkahub.ui.pages.chat.tavern

import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer

internal fun resolveTavernDisplayText(
    text: String,
    userName: String,
    characterName: String,
): String = PlaceholderTransformer.expandVisualMacros(
    text = text,
    userName = userName.ifBlank { "你" },
    charName = characterName,
)
