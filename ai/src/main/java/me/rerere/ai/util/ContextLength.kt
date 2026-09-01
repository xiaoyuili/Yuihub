package me.rerere.ai.util

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 从模型列表项 JSON 中宽松解析上下文长度（token 数）。
 *
 * 覆盖各供应商/兼容服务的常见字段名：
 * - Anthropic：input_token_limit
 * - Google：inputTokenLimit
 * - OpenAI 兼容（SiliconFlow 等）：context_length / max_context_length / max_input_tokens 等
 * 无字段或值非法时返回 null，不抛异常。
 */
fun parseContextLength(modelObj: JsonObject): Int? {
    val keys = listOf(
        "context_length",
        "contextLength",
        "context_length_limit",
        "context_window",
        "max_context_length",
        "max_input_tokens",
        "input_token_limit",
        "inputTokenLimit",
    )
    for (key in keys) {
        val value = modelObj[key]?.jsonPrimitive?.contentOrNull ?: continue
        val parsed = value.toLongOrNull()
        if (parsed != null && parsed > 0 && parsed <= 100_000_000) {
            return parsed.toInt()
        }
    }
    return null
}
