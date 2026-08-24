package me.rerere.rikkahub.ui.components.richtext

import org.jsoup.Jsoup

internal fun sanitizeTavernFrontendHtml(html: String, allowScripts: Boolean): String {
    if (allowScripts) return html

    val document = Jsoup.parse(html)
    document.select("script, iframe, object, embed, meta[http-equiv=refresh]").remove()
    document.allElements.forEach { element ->
        element.attributes().asList().forEach { attribute ->
            val key = attribute.key.lowercase()
            val value = attribute.value.trimStart()
            if (
                key.startsWith("on") ||
                key == "srcdoc" ||
                ((key == "href" || key == "src" || key == "action" || key == "formaction") &&
                    value.startsWith("javascript:", ignoreCase = true))
            ) {
                element.removeAttr(attribute.key)
            }
        }
    }
    return document.outerHtml()
}
