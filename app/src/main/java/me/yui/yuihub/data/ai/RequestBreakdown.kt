package me.yui.yuihub.data.ai

/**
 * 单次请求的提示词构成估算（本地 token 估算，实际以供应商 usage 为准）。
 * 分类互斥：system + toolSchema + memory + history = total。
 */
data class RequestBreakdown(
    val systemPromptTokens: Int = 0,
    val toolSchemaTokens: Int = 0,
    val memoryCoreTokens: Int = 0,
    val memoryHitTokens: Int = 0,
    val memoryRebaseTokens: Int = 0,
    val historyTokens: Int = 0,
    val totalEstimatedTokens: Int = 0,
    val toolCount: Int = 0,
    val messageCount: Int = 0,
    val topTools: List<ToolUsage> = emptyList(),
    val updatedAt: Long = 0L,
) {
    val memoryTokens: Int get() = memoryCoreTokens + memoryHitTokens + memoryRebaseTokens

    data class ToolUsage(
        val name: String,
        val tokens: Int,
    )
}
