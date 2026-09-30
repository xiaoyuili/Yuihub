package me.yui.yuihub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P1-2: 工具参数解析降级 + P1-4 错误格式化的纯函数层（无 Android 依赖，可 JVM 单测）。
 *
 * 解析策略：strict 失败后允许 1 次自动重试（宽松模式：容忍未加引号的 key / 尾随逗号等），
 * 仍失败才降级为单行可读错误。重试只是本地再解析一次，不重新请求模型，不会放大 token 消耗。
 */
object ToolArgumentParser {

    private val lenientJson = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    /**
     * 解析工具参数；两次都失败抛 [ToolArgumentException]（携带原始异常供日志记录）。
     */
    fun parse(json: Json, input: String, toolName: String): JsonElement {
        val trimmed = input.ifBlank { "{}" }
        return runCatching { json.parseToJsonElement(trimmed) }
            .recoverCatching { lenientJson.parseToJsonElement(trimmed) }
            .getOrElse { cause -> throw ToolArgumentException(toolName, cause) }
    }

    /** 从 kotlinx 序列化异常消息中提取 offset（如 "Unexpected JSON token at offset 72588"） */
    fun extractOffset(message: String?): Long? =
        message?.let { Regex("offset (\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() }

    /** 单行可读降级信息（P1-2 要求格式），无栈帧 */
    fun invalidArgsMessage(toolName: String, cause: Throwable): String {
        val offsetPart = extractOffset(cause.message)?.let { "offset=$it, " } ?: ""
        return "ToolError: invalid_arguments_json (${offsetPart}tool=$toolName). " +
            "Re-issue the same tool call with complete, valid JSON arguments."
    }

    /** 常见异常 → 稳定错误码（P1-3 回归表覆盖项） */
    fun errorCode(toolName: String, error: Throwable): String {
        val message = error.message?.replace('\n', ' ')?.take(300).orEmpty()
        return when {
            error is kotlinx.serialization.SerializationException && !message.contains("invalid_arguments_json") -> "INVALID_ARGS"
            message.contains("invalid_arguments_json", ignoreCase = true) -> "INVALID_ARGS"
            message.contains("File does not exist", ignoreCase = true) -> "NOT_FOUND"
            message.contains("Path is not a file", ignoreCase = true) -> "IS_DIRECTORY"
            message.contains("File is too large", ignoreCase = true) -> "FILE_TOO_LARGE"
            message.contains("Permission denied", ignoreCase = true) -> "PERMISSION_DENIED"
            message.contains("Binary file", ignoreCase = true) ||
                message.contains("invalid byte", ignoreCase = true) ||
                message.contains("Malformed", ignoreCase = true) -> "BINARY_FILE"
            message.contains("was not found, even with whitespace-tolerant", ignoreCase = true) -> "EDIT_NO_MATCH"
            message.contains("is required", ignoreCase = true) -> "INVALID_ARGS"
            message.contains("must be", ignoreCase = true) -> "INVALID_ARGS"
            message.contains("timed out", ignoreCase = true) -> "TIMEOUT"
            message.contains("requires user approval", ignoreCase = true) -> "APPROVAL_REQUIRED"
            message.startsWith("REJECTED", ignoreCase = false) -> "REJECTED"
            message.contains("WRITE_CONFLICT", ignoreCase = true) -> "WRITE_CONFLICT"
            message.contains("AGENT_SESSION_NOT_FOUND", ignoreCase = true) -> "AGENT_SESSION_NOT_FOUND"
            else -> "TOOL_ERROR"
        }
    }

    /**
     * 面向模型的工具错误统一为单行 JSON（P1-4）：`{"error": "<CODE>: <message> (tool=…)"}`。
     * 完整栈由调用方负责打印到日志，不进入模型上下文。
     */
    fun formatError(toolName: String, error: Throwable): String {
        val message = error.message?.replace('\n', ' ')?.take(300).orEmpty()
        val code = errorCode(toolName, error)
        return buildJsonObject {
            put("error", "$code: $message (tool=$toolName)")
        }.toString()
    }
}

class ToolArgumentException(val toolName: String, cause: Throwable) :
    RuntimeException(cause.message, cause)
