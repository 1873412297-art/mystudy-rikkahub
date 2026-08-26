package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.JsonObject

/** 一条参与生成的消息（role 限 user / assistant / system） */
internal data class TavernGenerationMessage(
    val role: String,
    val text: String,
)

internal data class TavernGenerationParams(
    /** 即 RPC 请求 id：取消单次生成（generation.cancel）以此为准 */
    val requestId: String,
    val messages: List<TavernGenerationMessage>,
    val temperature: Float?,
    val maxTokens: Int?,
)

internal sealed interface TavernGenerationOutcome {
    data class Success(val payload: JsonObject) : TavernGenerationOutcome
    data class Failure(val code: String, val message: String) : TavernGenerationOutcome
}

internal interface TavernRuntimeGenerationGateway {
    /**
     * 启动一次生成。
     * 返回 false 表示网关不可用（调用方应回 UNSUPPORTED_HOST_CAPABILITY）；
     * 返回 true 时保证稍后恰好调用一次 callback（成功 / 失败 / 取消）。
     */
    fun generate(params: TavernGenerationParams, callback: (TavernGenerationOutcome) -> Unit): Boolean

    /**
     * 按请求 id 取消进行中的生成；无此生成时返回 false。
     * 取消为 best-effort：被取消的生成通常以 CANCELLED 完成回调，
     * 但底层调用若无法中断，最终仍可能回传真实结果。
     */
    fun cancel(requestId: String): Boolean

    /** 取消全部进行中的生成，返回取消数量。 */
    fun cancelAll(): Int
}

/** 默认网关：未接线真实生成管线时明确报不支持，不伪装成功。 */
internal class UnsupportedTavernGenerationGateway : TavernRuntimeGenerationGateway {
    override fun generate(
        params: TavernGenerationParams,
        callback: (TavernGenerationOutcome) -> Unit,
    ): Boolean = false

    override fun cancel(requestId: String): Boolean = false

    override fun cancelAll(): Int = 0
}
