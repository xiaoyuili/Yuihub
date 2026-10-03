package me.rerere.ai.provider.stream

import me.rerere.ai.ui.StreamChunk

/**
 * 一次 Provider 响应流所使用的有状态、传输无关解码器。
 * 每条响应流必须创建独立实例。
 */
interface StreamChunkDecoder {
    /** 将单个原始 SSE 事件转换为通用流事件。解析失败时直接抛出异常。 */
    fun accept(event: SseEvent): DecodeResult

    /** SSE 正常关闭时收尾。实现必须保证该方法及显式终止事件产生的 Finish 幂等。 */
    fun onClosed(): List<StreamChunk>

    /**
     * 是否收到过显式协议结束标记（[DONE] / message_stop / response.completed 等）。
     * false 表示流以裸 EOF（连接被对端或中转直接关闭）结束，最后的回复可能被截断。
     */
    val explicitEnd: Boolean
}

data class DecodeResult(
    val chunks: List<StreamChunk> = emptyList(),
    /** Provider 协议已经明确结束，传输层可以主动关闭连接。 */
    val completed: Boolean = false,
)

/**
 * 把一条原始 SSE 事件压成单行可读的轨迹，供日志记录「每次请求的原始结束事件」。
 *
 * 只保留诊断所需字段，避免整段正文刷屏；当 data 不是 JSON（如 `[DONE]`）时原样短截。
 */
fun describeSseEvent(id: String?, event: String?, data: String): String {
    val prefix = buildString {
        if (!event.isNullOrBlank()) append("event=$event ")
        if (!id.isNullOrBlank()) append("id=$id ")
    }
    val compact = data.replace("\n", " ").trim()
    val summary = when {
        compact.length <= 220 -> compact
        else -> compact.take(220) + "…(${compact.length} chars)"
    }
    return prefix + "data=" + summary
}
