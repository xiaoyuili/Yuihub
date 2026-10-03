package me.rerere.ai.ui

/**
 * 判断一次响应的结束原因是否表示「模型撞到输出 token 上限」（可续写）。
 *
 * 各供应商命名不一：
 * - OpenAI 兼容（Chat Completions）：`length`
 * - Google Gemini：`MAX_TOKENS`
 * - OpenAI Responses API：`incomplete:max_output_tokens`
 *
 * 属纯函数层，供生成循环与空回复提示共用（避免两处各自字符串比较产生漂移）。
 */
fun String?.isOutputLimitTruncation(): Boolean {
    val reason = this ?: return false
    return reason == "length" ||
        reason.contains("MAX_TOKENS", ignoreCase = true) ||
        reason.contains("max_output_tokens", ignoreCase = true)
}
