package me.rerere.ai.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response

/**
 * Provider 返回的 HTTP 错误。
 *
 * code/retryAfterMs 供重试策略分类（429/5xx 可重试，其余直接失败）：
 * 429 时 Retry-After 优先于本地退避间隔。
 */
class HttpException(
    message: String,
    val code: Int? = null,
    val retryAfterMs: Long? = null,
) : RuntimeException(message)

fun JsonElement.parseErrorDetail(
    code: Int? = null,
    retryAfterMs: Long? = null,
): HttpException {
    return when (this) {
        is JsonObject -> {
            // 尝试获取常见的错误字段
            val errorFields = listOf("error", "detail", "message", "description")

            // 查找第一个存在的错误字段
            val foundField = errorFields.firstOrNull { this[it] != null }

            if (foundField != null) {
                // 递归解析找到的字段值
                this[foundField]!!.parseErrorDetail(code, retryAfterMs)
            } else {
                // 如果没有找到任何错误字段，序列化整个对象
                HttpException(Json.encodeToString(JsonElement.serializer(), this), code, retryAfterMs)
            }
        }

        is JsonArray -> {
            if (this.isEmpty()) {
                HttpException("Unknown error: Empty JSON array", code, retryAfterMs)
            } else {
                // 递归解析数组的第一个元素
                this.first().parseErrorDetail(code, retryAfterMs)
            }
        }

        is JsonPrimitive -> {
            // 对于基本类型，直接使用其内容
            HttpException(this.jsonPrimitive.content, code, retryAfterMs)
        }

        else -> {
            // 其他情况，序列化整个元素
            HttpException(Json.encodeToString(JsonElement.serializer(), this), code, retryAfterMs)
        }
    }
}

// Retry-After 头解析：仅支持秒数格式（HTTP-date 罕见于 LLM API，忽略），返回毫秒
fun Response.retryAfterMsOrNull(): Long? {
    val raw = headers["Retry-After"] ?: return null
    return raw.trim().toLongOrNull()?.takeIf { it > 0 }?.times(1000)
}
