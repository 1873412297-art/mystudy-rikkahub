package me.rerere.rikkahub.ui.components.richtext.runtime

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import me.rerere.rikkahub.ui.components.richtext.MarkdownWebView
import me.rerere.rikkahub.ui.components.richtext.RichTextSegment
import me.rerere.rikkahub.ui.components.richtext.st.StableDomMessage
import me.rerere.rikkahub.ui.components.richtext.st.StableDomRole
import me.rerere.rikkahub.ui.components.richtext.st.StableDomSegment
import me.rerere.rikkahub.ui.components.richtext.st.buildStableMessageHtml
import org.json.JSONObject

private data class CardRender(val name: String, val desc: String, val first: String)

class TavernRuntimeSmokeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cardFiles = listOf("card1.json", "card2.json", "card3.json")
        val cards = cardFiles.map { fileName ->
            val raw = assets.open("cards/$fileName").bufferedReader().use { it.readText() }
            val obj = JSONObject(raw)
            CardRender(
                name = obj.optString("name", fileName),
                desc = obj.optString("description", ""),
                first = obj.optString("first_mes", ""),
            )
        }

        val htmls = cards.mapIndexed { index, card ->
            val segments = mutableListOf<StableDomSegment>()
            if (card.desc.isNotBlank()) {
                segments += StableDomSegment(
                    "desc-$index",
                    RichTextSegment.Kind.MARKDOWN,
                    expandMacros(card.desc, card.name),
                )
            }
            if (card.first.isNotBlank()) {
                segments += StableDomSegment(
                    "first-$index",
                    RichTextSegment.Kind.MARKDOWN,
                    expandMacros(card.first, card.name),
                )
            }
            if (segments.isEmpty()) {
                segments += StableDomSegment("empty-$index", RichTextSegment.Kind.MARKDOWN, "（角色卡内容为空）")
            }
            val message = StableDomMessage(
                id = "card-$index",
                role = StableDomRole.ASSISTANT,
                name = card.name,
                segments = segments,
                streaming = false,
            )
            buildStableMessageHtml(this, message)
        }

        setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    htmls.forEach { html ->
                        MarkdownWebView(
                            content = html,
                            isRawHtml = true,
                            maxHeightDp = null,
                        )
                    }
                }
            }
        }
    }

    private fun expandMacros(text: String, charName: String): String {
        return text
            .replace(Regex("\\{\\{\\//.*?\\}\\}", RegexOption.DOT_MATCHES_ALL), "")
            .replace("{{user}}", "你", ignoreCase = true)
            .replace("{{char}}", charName, ignoreCase = true)
            .replace("{{newline}}", "\n", ignoreCase = true)
            .replace("{{noop}}", "", ignoreCase = true)
            .replace("{{trim}}", "", ignoreCase = true)
    }
}

