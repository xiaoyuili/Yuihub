package me.yui.yuihub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart

/**
 * 工具结果契约（纯函数层，无 Android 依赖，可 JVM 单测）。
 *
 * 工具大多以「裸 JSON 文本」返回结果，模型无法可靠区分正常结果与被截断/畸形的结果。
 * 这里把每次工具结果标准化成可判读的信封，统一两个语义：
 *
 * - 结构化错误：结果 JSON 里带 `error` 字段时，补一个稳定的 `errorCode`，让模型按码处理
 *   而不是猜字段含义。
 * - 畸形结果：结果明显是想给 JSON（以 `{`/`[` 开头）但无法解析时（被上游截断、编码损坏），
 *   包装成 `MALFORMED_TOOL_RESULT` 并附原始片段，而不是让畸形文本原样进入上下文。
 *
 * 非 JSON 的普通文本结果原样透传，不改写工具的正常输出。
 */
object ToolResultContract {

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    /** 结果 JSON 已自带稳定错误码时直接采用，避免外层再包一层 */
    private val LITERAL_ERROR_CODES = listOf(
        "MALFORMED_TOOL_RESULT",
        "WRITE_CONFLICT",
        "AGENT_SESSION_NOT_FOUND",
        "AGENT_TASK_NOT_FOUND",
        "BINARY_CONTENT",
        "REJECTED",
    )

    /**
     * 标准化一条工具的文本结果。
     *
     * @param toolName 仅用于错误信息，便于模型定位来源。
     * @return 标准化后的文本；无需要改写时返回原值。
     */
    fun normalize(toolName: String, text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return text

        // 只有「看起来像 JSON」的结果才走契约校验，普通文本原样透传
        val looksLikeJson = trimmed.startsWith("{") || trimmed.startsWith("[")
        if (!looksLikeJson) return text

        val parsed = runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
        if (parsed == null) {
            // 声称是 JSON 却解析不了：多半被截断或编码损坏，显式告诉模型这是畸形结果
            return buildJsonObject {
                put("error", "MALFORMED_TOOL_RESULT: tool result looked like JSON but could not be parsed (tool=$toolName)")
                put("errorCode", "MALFORMED_TOOL_RESULT")
                put("raw", trimmed.take(RAW_PREVIEW_CHARS))
            }.toString()
        }

        if (parsed !is JsonObject) return text
        return classifyObject(toolName, parsed, text)
    }

    /** 结构化错误补码；非错误对象原样返回序列化文本（保持字段顺序与原始文本一致） */
    private fun classifyObject(toolName: String, obj: JsonObject, original: String): String {
        val errorValue = obj["error"]?.jsonPrimitive?.contentOrNull ?: return original
        val existingCode = obj["errorCode"]?.jsonPrimitive?.contentOrNull
            ?: LITERAL_ERROR_CODES.firstOrNull { errorValue.startsWith(it, ignoreCase = true) }
            ?: errorCodeFor(errorValue)
        return buildJsonObject {
            obj.forEach { (key, value) -> put(key, value) }
            put("errorCode", existingCode)
            if (errorValue.isBlank()) {
                // 空错误信息对模型无信息量，补一句可利用的提示
                put("error", "TOOL_ERROR: tool reported an unspecified error (tool=$toolName)")
            }
        }.toString()
    }

    /**
     * 从错误消息里归类稳定错误码。与 [ToolArgumentParser.errorCode] 互补：
     * 那里归类的是抛出的异常，这里归类的是工具主动返回在结果 JSON 里的 error 字段。
     */
    fun errorCodeFor(message: String): String {
        val normalized = message.lowercase()
        return when {
            "timeout" in normalized || "timed out" in normalized -> "TIMEOUT"
            "permission denied" in normalized -> "PERMISSION_DENIED"
            "not found" in normalized ||
                "does not exist" in normalized ||
                "no matching" in normalized -> "NOT_FOUND"
            "too large" in normalized -> "FILE_TOO_LARGE"
            normalized.startsWith("invalid") || "invalid_" in normalized -> "INVALID_ARGS"
            else -> "TOOL_ERROR"
        }
    }

    private const val RAW_PREVIEW_CHARS = 500
}

/**
 * 对一组工具结果 part 逐个执行 [ToolResultContract.normalize]，只改写文本 part。
 * 非文本 part（图片/文档等）原样保留。
 */
fun normalizeToolResultParts(toolName: String, parts: List<UIMessagePart>): List<UIMessagePart> =
    parts.map { part ->
        if (part is UIMessagePart.Text) {
            val normalized = ToolResultContract.normalize(toolName, part.text)
            if (normalized == part.text) part else part.copy(text = normalized)
        } else {
            part
        }
    }
