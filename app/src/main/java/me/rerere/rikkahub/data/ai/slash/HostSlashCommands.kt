package me.rerere.rikkahub.data.ai.slash

import me.rerere.rikkahub.data.ai.status.StatusVariableStore
import kotlin.random.Random
import kotlin.uuid.Uuid

/**
 * 宿主内建斜杠命令（SillyTavern 风格），变量目标为 chat 作用域 StatusVariableStore。
 * execute 返回 null 表示不是内建命令（调用方继续走磁盘脚本/透传）。
 */
object HostSlashCommands {

    private val SUPPORTED = setOf("setvar", "getvar", "add", "sub", "random", "pick", "roll", "echo", "th")

    fun execute(
        command: String,
        args: String,
        conversationId: Uuid?,
        variableStore: StatusVariableStore,
    ): SlashCommandResult? {
        if (command !in SUPPORTED) return null
        val accessor = StatusVariableStoreAccessor(conversationId, variableStore)
        return when (command) {
            "setvar" -> {
                val (key, value) = splitTwo(args) ?: return SlashCommandResult(error = "Usage: /setvar <key> <value>")
                accessor.set(key, value)
                SlashCommandResult(text = "$key = $value")
            }
            "getvar" -> {
                val key = args.trim()
                if (key.isEmpty()) return SlashCommandResult(error = "Usage: /getvar <key>")
                val value = accessor.get(key)
                if (value == null) SlashCommandResult(error = "$key is not set")
                else SlashCommandResult(text = "$key = $value")
            }
            "add", "sub" -> {
                val (key, raw) = splitTwo(args) ?: return SlashCommandResult(error = "Usage: /$command <key> <number>")
                val delta = raw.toDoubleOrNull() ?: return SlashCommandResult(error = "Not a number: $raw")
                val current = accessor.get(key)?.toDoubleOrNull()
                    ?: return SlashCommandResult(error = "$key is not a number")
                val next = if (command == "add") current + delta else current - delta
                val formatted = if (next % 1.0 == 0.0) next.toLong().toString() else next.toString()
                accessor.set(key, formatted)
                SlashCommandResult(text = "$key = $formatted")
            }
            "random", "pick" -> {
                val options = args.split(',', '|', ':').map { it.trim() }.filter { it.isNotEmpty() }
                if (options.isEmpty()) return SlashCommandResult(error = "Usage: /$command <option1>,<option2>,...")
                SlashCommandResult(text = options[Random.nextInt(options.size)])
            }
            "roll" -> {
                val result = rollDice(args) ?: return SlashCommandResult(error = "Usage: /roll <NdM> (n<=100, M<=1000)")
                SlashCommandResult(text = result.toString())
            }
            "echo" -> SlashCommandResult(text = args)
            "th" -> SlashCommandResult(
                text = "/setvar /getvar /add /sub /random /pick /roll /echo\n" +
                    "  /setvar <key> <value> - set a chat variable\n" +
                    "  /getvar <key> - get a chat variable\n" +
                    "  /add <key> <number> / /sub <key> <number> - numeric variable math\n" +
                    "  /random <a>,<b>,... - pick a random option\n" +
                    "  /roll <NdM> - roll dice\n" +
                    "  /echo <text> - reply with the text"
            )
            else -> null
        }
    }

    private fun splitTwo(args: String): Pair<String, String>? {
        val index = args.indexOfFirst { it.isWhitespace() }
        if (index <= 0) return null
        val key = args.substring(0, index).trim()
        val value = args.substring(index + 1).trim()
        if (key.isEmpty() || value.isEmpty()) return null
        return key to value
    }

    private fun rollDice(args: String): Int? {
        val trimmed = args.trim()
        val match = Regex("^(\\d{1,3})d(\\d{1,4})$", RegexOption.IGNORE_CASE).matchEntire(trimmed) ?: return null
        val count = match.groupValues[1].toInt()
        val sides = match.groupValues[2].toInt()
        if (count !in 1..100 || sides !in 1..1000) return null
        return (1..count).sumOf { Random.nextInt(sides) + 1 }
    }
}
