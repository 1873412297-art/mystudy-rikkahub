package me.rerere.rikkahub.data.ai.slash

/**
 * A user-defined slash command script.
 *
 * Mirrors the JS-Slash-Runner concept: each script defines one or more
 * slash commands that execute custom JavaScript when invoked.
 */
data class SlashScript(
    val name: String,
    val description: String = "",
    val version: String = "1.0.0",
    val author: String = "",
    /** Raw JavaScript source code */
    val source: String,
    /** Whether this script is currently enabled */
    val enabled: Boolean = true,
)

/**
 * A registered slash command handler extracted from a script.
 */
data class SlashCommand(
    val command: String,          // e.g. "roll", "translate"
    val scriptName: String,       // owning script
    val description: String = "",
    val argsHint: String = "",    // e.g. "<dice> <sides>"
)
