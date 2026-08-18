package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Assistant
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.Temporal
import java.util.Locale
import java.util.TimeZone

data class PlaceholderCtx(
    val context: Context,
    val settingsStore: SettingsStore,
    val model: Model,
    val assistant: Assistant,
)

interface PlaceholderProvider {
    val placeholders: Map<String, PlaceholderInfo>
}

data class PlaceholderInfo(
    val displayName: @Composable () -> Unit,
    val resolver: (PlaceholderCtx) -> String
)

class PlaceholderBuilder {
    private val placeholders = mutableMapOf<String, PlaceholderInfo>()

    fun placeholder(
        key: String,
        displayName: @Composable () -> Unit,
        resolver: (PlaceholderCtx) -> String
    ) {
        placeholders[key] = PlaceholderInfo(displayName, resolver)
    }

    fun build(): Map<String, PlaceholderInfo> = placeholders.toMap()
}

fun buildPlaceholders(block: PlaceholderBuilder.() -> Unit): Map<String, PlaceholderInfo> {
    return PlaceholderBuilder().apply(block).build()
}

object DefaultPlaceholderProvider : PlaceholderProvider {
    override val placeholders: Map<String, PlaceholderInfo> = buildPlaceholders {
        placeholder("cur_date", { Text(stringResource(R.string.placeholder_current_date)) }) {
            LocalDate.now().toDateString()
        }

        placeholder("model_id", { Text(stringResource(R.string.placeholder_model_id)) }) {
            it.model.modelId
        }

        placeholder("model_name", { Text(stringResource(R.string.placeholder_model_name)) }) {
            it.model.displayName
        }

        placeholder("locale", { Text(stringResource(R.string.placeholder_locale)) }) {
            Locale.getDefault().displayName
        }

        placeholder("timezone", { Text(stringResource(R.string.placeholder_timezone)) }) {
            TimeZone.getDefault().displayName
        }

        placeholder("system_version", { Text(stringResource(R.string.placeholder_system_version)) }) {
            "Android SDK v${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})"
        }

        placeholder("device_info", { Text(stringResource(R.string.placeholder_device_info)) }) {
            "${Build.BRAND} ${Build.MODEL}"
        }

        placeholder("battery_level", { Text(stringResource(R.string.placeholder_battery_level)) }) {
            it.context.batteryLevel().toString()
        }

        placeholder("nickname", { Text(stringResource(R.string.placeholder_nickname)) }) {
            it.settingsStore.settingsFlow.value.displaySetting.userNickname.ifBlank { "user" }
        }

        placeholder("char", { Text(stringResource(R.string.placeholder_char)) }) {
            it.assistant.name.ifBlank { "assistant" }
        }

        placeholder("user", { Text(stringResource(R.string.placeholder_user)) }) {
            it.settingsStore.settingsFlow.value.displaySetting.userNickname.ifBlank { "user" }
        }
    }

    private fun Temporal.toDateString() = DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(this)

    private fun Context.batteryLevel(): Int {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
}

object PlaceholderTransformer : InputMessageTransformer, KoinComponent {
    private val defaultProvider = DefaultPlaceholderProvider

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val settingsStore = get<SettingsStore>()
        return messages.map {
            it.copy(
                parts = it.parts.map { part ->
                    if (part is UIMessagePart.Text) {
                        part.copy(
                            text = replacePlaceholders(text = part.text, ctx = ctx, settingsStore = settingsStore)
                        )
                    } else {
                        part
                    }
                }
            )
        }
    }

    private fun replacePlaceholders(
        text: String,
        ctx: TransformerContext,
        settingsStore: SettingsStore
    ): String {
        var result = text

        val ctx = PlaceholderCtx(
            context = ctx.context,
            settingsStore = settingsStore,
            model = ctx.model,
            assistant = ctx.assistant
        )
        defaultProvider.placeholders.forEach { (key, placeholderInfo) ->
            val value = placeholderInfo.resolver(ctx)
            result = result
                .replace(oldValue = "{{$key}}", newValue = value, ignoreCase = true)
                .replace(oldValue = "{$key}", newValue = value, ignoreCase = true)
        }

        return result
    }

    /**
     * 视觉路径专用宏展开 —— 不需要 PlaceholderCtx，纯字符串运算。
     * 支持 ST 标准宏 (user/char/model 等) + 项目原生 cur_/model_ + 中文方括号别名 +
     * 时间日期 + 随机选择 + 骰子。仅用于 UI 显示路径，不修改持久化对话。
     */
    fun expandVisualMacros(
        text: String,
        userName: String,
        charName: String,
        modelName: String = "",
        modelId: String = "",
        personaDescription: String = "",
        charDescription: String = "",
        charPersonality: String = "",
        charScenario: String = "",
        batteryLevel: Int = -1,
        seed: Long = 0L,
    ): String {
        if (text.isEmpty()) return text
        if (!text.contains("{{") && !text.contains("{") &&
            !text.contains("[") && !text.contains("【")
        ) return text

        var result = text
        val rng = java.util.Random(seed)

        // 1) 注释最先处理
        result = Regex("\\{\\{//.*?\\}\\}", RegexOption.DOT_MATCHES_ALL).replace(result, "")

        // 2) 静态字符串替换
        val locale = java.util.Locale.getDefault()
        val now = java.time.LocalDateTime.now()
        val curDate = now.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale))
        val curTime = now.format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.MEDIUM).withLocale(locale))
        val curDatetime = now.format(java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM).withLocale(locale))

        val staticMap: List<Pair<String, String>> = buildList {
            add("{{user}}" to userName)
            add("{{char}}" to charName)
            add("{{model}}" to modelName)
            add("{{persona}}" to personaDescription.ifBlank { userName })
            add("{{description}}" to charDescription)
            add("{{personality}}" to charPersonality)
            add("{{scenario}}" to charScenario)
            add("{{newline}}" to "\n")
            add("{{noop}}" to "")
            add("{{trim}}" to "")
            add("{user}" to userName)
            add("{char}" to charName)
            add("[用户]" to userName)
            add("[使用者]" to userName)
            add("【用户】" to userName)
            add("【使用者】" to userName)
            add("[角色]" to charName)
            add("【角色】" to charName)
            add("{{cur_date}}" to curDate)
            add("{{cur_time}}" to curTime)
            add("{{cur_datetime}}" to curDatetime)
            add("{{model_id}}" to modelId)
            add("{{model_name}}" to modelName)
            add("{{nickname}}" to userName)
            add("{{locale}}" to locale.displayName)
            add("{{timezone}}" to java.util.TimeZone.getDefault().displayName)
            add("{{system_version}}" to "Android SDK v${android.os.Build.VERSION.SDK_INT} (${android.os.Build.VERSION.RELEASE})")
            add("{{device_info}}" to "${android.os.Build.BRAND} ${android.os.Build.MODEL}")
            if (batteryLevel >= 0) add("{{battery_level}}" to batteryLevel.toString())
            add("{cur_date}" to curDate)
            add("{cur_time}" to curTime)
            add("{model_name}" to modelName)
            add("{nickname}" to userName)
        }
        for ((k, v) in staticMap) {
            if (result.contains(k, ignoreCase = true)) {
                result = result.replace(k, v, ignoreCase = true)
            }
        }

        // 3) 时间日期短宏
        if (result.contains("{{", ignoreCase = true)) {
            if (result.contains("{{time}}", ignoreCase = true)) {
                result = result.replace("{{time}}", curTime, ignoreCase = true)
            }
            if (result.contains("{{date}}", ignoreCase = true)) {
                result = result.replace("{{date}}", curDate, ignoreCase = true)
            }
            if (result.contains("{{weekday}}", ignoreCase = true)) {
                val s = now.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, locale)
                result = result.replace("{{weekday}}", s, ignoreCase = true)
            }
            if (result.contains("{{isotime}}", ignoreCase = true)) {
                result = result.replace("{{isotime}}", now.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")), ignoreCase = true)
            }
            if (result.contains("{{isodate}}", ignoreCase = true)) {
                result = result.replace("{{isodate}}", now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")), ignoreCase = true)
            }
            if (result.contains("{{datetimeformat", ignoreCase = true)) {
                result = Regex(
                    "\\{\\{datetimeformat\\s+(.*?)\\}\\}",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
                ).replace(result) { m ->
                    val pattern = m.groupValues[1]
                    runCatching { now.format(java.time.format.DateTimeFormatter.ofPattern(pattern, locale)) }
                        .getOrDefault(m.value)
                }
            }
        }

        // 4) {{random:...}} / {{pick:...}}
        result = Regex("\\{\\{(random|pick)[:]([^}]*)\\}\\}", RegexOption.IGNORE_CASE).replace(result) { m ->
            val args = m.groupValues[2]
            val parts = if (args.startsWith(":")) args.removePrefix(":").split("::") else args.split(",")
            val nonempty = parts.map { it.trim() }.filter { it.isNotEmpty() }
            if (nonempty.isEmpty()) "" else nonempty[rng.nextInt(nonempty.size)]
        }

        // 5) {{roll:NdM}} 或 {{roll:M}}
        if (result.contains("{{roll", ignoreCase = true)) {
            result = Regex("\\{\\{roll[:]([0-9]+(?:[dD][0-9]+)?)\\}\\}", RegexOption.IGNORE_CASE).replace(result) { m ->
                val expr = m.groupValues[1].lowercase()
                runCatching {
                    if (expr.contains('d')) {
                        val (nStr, sStr) = expr.split("d")
                        val n = nStr.toIntOrNull() ?: 1
                        val sides = sStr.toIntOrNull() ?: return@runCatching m.value
                        if (n in 1..100 && sides in 1..1000) {
                            (1..n).sumOf { rng.nextInt(sides) + 1 }.toString()
                        } else m.value
                    } else {
                        val sides = expr.toIntOrNull() ?: return@runCatching m.value
                        if (sides in 1..1000) (rng.nextInt(sides) + 1).toString() else m.value
                    }
                }.getOrDefault(m.value)
            }
        }
        return result
    }

    /** 旧 API 适配。新代码请用 [expandVisualMacros]。 */
    fun replaceVisualPersonNames(
        text: String,
        userName: String,
        charName: String,
    ): String = expandVisualMacros(text, userName, charName)
}
