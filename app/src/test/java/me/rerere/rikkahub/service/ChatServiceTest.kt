package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatServiceTest {
    @Test
    fun `background generation params include model custom request configuration`() {
        val headers = listOf(CustomHeader(name = "X-Gateway-Token", value = "test-token"))
        val bodies = listOf(CustomBody(key = "gateway_mode", value = JsonPrimitive("strict")))
        val model = Model(
            modelId = "custom-chat-model",
            customHeaders = headers,
            customBodies = bodies,
        )

        val params = backgroundTextGenerationParams(model)

        assertEquals(model, params.model)
        assertEquals(ReasoningLevel.OFF, params.reasoningLevel)
        assertEquals(headers, params.customHeaders)
        assertEquals(bodies, params.customBody)
    }

    @Test
    fun `preset message macros are expanded without losing html render mode`() {
        val model = Model(modelId = "test-model", displayName = "Test Model")
        val assistant = Assistant(name = "Alice")
        val settings = Settings(
            displaySetting = DisplaySetting(userNickname = "Bob"),
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
        )
        val messages = listOf(
            UIMessage.assistantHtml("<main>{{user}} meets {{char}} on {{model_name}}</main>")
        )

        val rendered = renderPresetMessageMacros(
            messages = messages,
            settings = settings,
            assistant = assistant,
            model = model,
        )

        val text = rendered.single().parts.single() as UIMessagePart.Text
        assertEquals("<main>Bob meets Alice on Test Model</main>", text.text)
        assertEquals(UIMessagePart.RenderMode.HTML, text.renderMode)
    }
}
