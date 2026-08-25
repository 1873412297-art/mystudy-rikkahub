package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import java.util.concurrent.ConcurrentHashMap

/** 带结构化错误码的生成异常（code 原样透传给脚本） */
private class TavernGenerationException(val code: String, override val message: String) : Exception(message)

/**
 * 真实生成网关：走 RikkaHub Provider/Assistant 管线（当前助手的聊天模型）。
 * 非流式一次性生成，结果含纯文本与可选结构化工具调用。
 */
internal class ProviderBackedTavernGenerationGateway(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : TavernRuntimeGenerationGateway {
    private val jobs = ConcurrentHashMap<String, Job>()

    override fun generate(
        params: TavernGenerationParams,
        callback: (TavernGenerationOutcome) -> Unit,
    ): Boolean {
        // LAZY 启动：先把句柄登记进 jobs，消除"快速失败先于注册"的竞态
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val outcome = try {
                TavernGenerationOutcome.Success(doGenerate(params))
            } catch (e: CancellationException) {
                TavernGenerationOutcome.Failure("CANCELLED", "Generation was cancelled")
            } catch (e: TavernGenerationException) {
                TavernGenerationOutcome.Failure(e.code, e.message ?: "Generation failed")
            } catch (e: Exception) {
                TavernGenerationOutcome.Failure("GENERATION_FAILED", e.message ?: "Generation failed")
            }
            jobs.remove(params.requestId)
            callback(outcome)
        }
        jobs[params.requestId] = job
        job.start()
        return true
    }

    override fun cancel(requestId: String): Boolean {
        val job = jobs[requestId] ?: return false
        job.cancel(CancellationException("Generation cancelled by script"))
        return true
    }

    override fun cancelAll(): Int {
        val running = jobs.values.toList()
        running.forEach { it.cancel(CancellationException("Generation cancelled by script")) }
        return running.size
    }

    private suspend fun doGenerate(params: TavernGenerationParams): JsonObject {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.getCurrentChatModel()
            ?: throw TavernGenerationException("NO_CHAT_MODEL", "No chat model is configured")
        val provider = model.findProvider(settings.providers)
            ?: throw TavernGenerationException("PROVIDER_UNAVAILABLE", "No provider available for the chat model")
        val uiMessages = params.messages.map { message ->
            when (message.role.lowercase()) {
                "system" -> UIMessage.system(prompt = message.text)
                "assistant" -> UIMessage.assistant(prompt = message.text)
                else -> UIMessage.user(prompt = message.text)
            }
        }
        val result = providerManager.getProviderByType(provider).generateText(
            providerSetting = provider,
            messages = uiMessages,
            params = TextGenerationParams(
                model = model,
                temperature = params.temperature,
                maxTokens = params.maxTokens?.takeIf { it > 0 },
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
            ),
        )
        return buildJsonObject {
            put("text", JsonPrimitive(result.message.toText()))
            put("model", JsonPrimitive(result.model))
            result.finishReason?.let { put("finishReason", JsonPrimitive(it)) }
            result.usage?.let { usage ->
                put(
                    "usage",
                    buildJsonObject {
                        put("promptTokens", JsonPrimitive(usage.promptTokens))
                        put("completionTokens", JsonPrimitive(usage.completionTokens))
                        put("cachedTokens", JsonPrimitive(usage.cachedTokens))
                        put("totalTokens", JsonPrimitive(usage.totalTokens))
                    }
                )
            }
            val toolCalls = result.message.parts.filterIsInstance<UIMessagePart.Tool>()
            if (toolCalls.isNotEmpty()) {
                put(
                    "toolCalls",
                    JsonArray(
                        toolCalls.map { tool ->
                            buildJsonObject {
                                put("id", JsonPrimitive(tool.toolCallId))
                                put("name", JsonPrimitive(tool.toolName))
                                put("input", JsonPrimitive(tool.input))
                                put("executed", JsonPrimitive(tool.isExecuted))
                            }
                        }
                    )
                )
            }
        }
    }
}
