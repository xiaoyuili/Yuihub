package me.yui.yuihub.utils

import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage

// 模型上下文长度的默认兜底值（用户未设置时的 256K）
const val DEFAULT_CONTEXT_LENGTH: Int = 256 * 1024

// 自动压缩触发阈值：占模型窗口的比例（对齐 deepseek-harness compaction-basic 默认 thresholdRatio）
const val AUTO_COMPRESS_THRESHOLD_RATIO: Float = 0.8f

// 压缩时保留最近窗口的这个比例作为原文（对齐 harness retainRatio：其余全部进摘要检查点）
const val AUTO_COMPRESS_RETAIN_RATIO: Float = 0.16f

// 自动压缩摘要目标 token（压缩后注入的摘要体量）
const val AUTO_COMPRESS_TARGET_TOKENS: Int = 2000

// 获取模型上下文长度：手动/API/注册表值优先，未设置时返回默认 256K
fun Model.effectiveContextLength(): Int = contextLength ?: DEFAULT_CONTEXT_LENGTH

// 解析 "256k"/"1M"/"2.5m"/"262144" 等格式为 token 数；非法返回 null
fun parseContextLengthInput(text: String): Int? {
    val normalized = text.trim().lowercase().replace(" ", "")
    if (normalized.isEmpty()) return null
    return when {
        normalized.endsWith("m") -> ((normalized.dropLast(1).toDoubleOrNull() ?: return null) * 1_000_000).toLong()
        normalized.endsWith("k") -> ((normalized.dropLast(1).toDoubleOrNull() ?: return null) * 1_000).toLong()
        else -> normalized.toLongOrNull() ?: return null
    }.takeIf { it > 0 && it <= 100_000_000 }?.toInt()
}

// 格式化上下文长度：1_000_000 -> "1M"，256_000 -> "256K"，null -> 空串（UI 用 placeholder 提示默认值）
fun formatContextLength(tokens: Int?): String = when {
    tokens == null -> ""
    tokens >= 1_000_000 && tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
    tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000.0)
    tokens >= 1_000 && tokens % 1_000 == 0 -> "${tokens / 1_000}K"
    tokens >= 1_000 -> "%.1fK".format(tokens / 1_000.0)
    else -> tokens.toString()
}


// 本地估算消息 token 数：CJK 字符按 1.5 字符/token，其余按 4 字符/token（粗略，用于无 usage 时的触发判断）
fun estimateTokenCount(messages: List<UIMessage>): Int {
    var cjk = 0
    var other = 0
    for (message in messages) {
        for (part in message.parts) {
            val text = when (part) {
                is me.rerere.ai.ui.UIMessagePart.Text -> part.text
                is me.rerere.ai.ui.UIMessagePart.Tool -> part.output.filterIsText().joinToString("\n") { it.text }
                else -> continue
            }
            for (ch in text) {
                if (ch in '\u4e00'..'\u9fff' || ch in '\u3040'..'\u30ff' || ch in '\uac00'..'\ud7af') {
                    cjk++
                } else {
                    other++
                }
            }
        }
    }
    return (cjk / 1.5 + other / 4.0).toInt().coerceAtLeast(0)
}

private fun List<me.rerere.ai.ui.UIMessagePart>.filterIsText(): List<me.rerere.ai.ui.UIMessagePart.Text> =
    filterIsInstance(me.rerere.ai.ui.UIMessagePart.Text::class.java)
