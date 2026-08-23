package me.rerere.rikkahub.ui.pages.chat.tavern

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.ui.pages.chat.tavern.render.buildTavernViewportAdapterScript

private val conversationJson = Json {
    encodeDefaults = true
    classDiscriminator = "type"
}

private data class TavernConversationDocumentAssets(
    val template: String,
    val vendorScripts: String,
    val vendorStyles: String,
)

@Volatile
private var cachedDocumentAssets: TavernConversationDocumentAssets? = null

/** Loads and caches the conversation template plus every bundled vendor file in stable filename order. */
fun buildTavernConversationDocument(
    context: Context,
    initial: TavernConversationSnapshot,
    runtimeScript: String = "",
    actionToken: String = "",
): String {
    val assets = cachedDocumentAssets ?: synchronized(TavernConversationDocumentAssets::class.java) {
        cachedDocumentAssets ?: context.loadTavernConversationDocumentAssets().also { cachedDocumentAssets = it }
    }
    return buildTavernConversationDocument(
        initial = initial,
        template = assets.template,
        vendorScripts = assets.vendorScripts,
        vendorStyles = assets.vendorStyles,
        runtimeScript = runtimeScript,
        actionToken = actionToken,
    )
}

internal fun buildTavernConversationDocument(
    initial: TavernConversationSnapshot,
    template: String,
    vendorScripts: String,
    vendorStyles: String,
    runtimeScript: String = "",
    actionToken: String = "",
): String {
    val initialJson = conversationJson.encodeToString(initial).replace("<", "\\u003c")
    val runtimeJson = conversationJson.encodeToString(runtimeScript).replace("<", "\\u003c")
    val safeRuntime = RUNTIME_SCRIPT_END.replace(runtimeScript) { "<\\/script" }
    val runtimeMarkup = if (runtimeScript.isBlank()) {
        ""
    } else {
        "<script>window.__RIKKAHUB_RUNTIME_SOURCE__=$runtimeJson;\n$safeRuntime</script>"
    }
    val replacements = mapOf(
        "INITIAL_SNAPSHOT" to initialJson,
        "VENDOR_LIBS" to vendorScripts,
        "VENDOR_STYLES" to vendorStyles,
        "RUNTIME_LIB" to runtimeMarkup,
        "ACTION_TOKEN" to conversationJson.encodeToString(actionToken).replace("<", "\\u003c"),
        "VIEWPORT_ADAPTER" to buildTavernViewportAdapterScript(),
    )
    return DOCUMENT_PLACEHOLDER.replace(template) { match ->
        replacements[match.groupValues[1]] ?: match.value
    }
}

private fun Context.loadTavernConversationDocumentAssets(): TavernConversationDocumentAssets {
    val template = assets.open("html/tavern-conversation.html").bufferedReader().use { it.readText() }
    val vendorNames = assets.list("html/vendor").orEmpty().sorted()
    val scripts = vendorNames.filter { it.endsWith(".js") }.joinToString("\n") { name ->
        val source = assets.open("html/vendor/$name").bufferedReader().use { it.readText() }
        "<script>$source</script>"
    }
    val styles = vendorNames.filter { it.endsWith(".css") }.joinToString("\n") { name ->
        val source = assets.open("html/vendor/$name").bufferedReader().use { it.readText() }
        "<style>$source</style>"
    }
    return TavernConversationDocumentAssets(template, scripts, styles)
}

private val DOCUMENT_PLACEHOLDER = Regex("\\{\\{([A-Z_0-9]+)\\}\\}")
private val RUNTIME_SCRIPT_END = Regex("</script", RegexOption.IGNORE_CASE)
