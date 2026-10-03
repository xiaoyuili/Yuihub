package me.yui.yuihub.data.ai.tools

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart

/**
 * 管理类工具（manage_skill / manage_mcp_server / scheduled_task 等）的统一结果契约。
 *
 * 这些工具过去返回面向人的纯文本，与其余工具的 JSON 结果不一致，模型/自动化难以解析。
 * 统一改为 JSON 对象；失败一律走 [toolErrorResult] 的 `{error}` 形式，
 * 由生成管线里的 ToolResultContract 补稳定 errorCode（不在工具层重复定义错误码）。
 */

/** 构造单条 JSON 工具结果 */
internal fun toolJson(build: JsonObjectBuilder.() -> Unit): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject(build).toString()))

/** 构造失败结果：`{"error": "..."}`，errorCode 由 ToolResultContract 统一归类 */
internal fun toolErrorResult(message: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject { put("error", message) }.toString()))
