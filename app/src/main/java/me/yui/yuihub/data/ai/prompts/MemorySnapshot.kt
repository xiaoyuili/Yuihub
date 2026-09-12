package me.yui.yuihub.data.ai.prompts

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.yui.yuihub.data.model.AssistantMemory
import me.yui.yuihub.data.model.MessageNode
import me.yui.yuihub.data.model.toMessageNode
import me.yui.yuihub.utils.JsonInstantPretty

/**
 * 记忆快照的固定前导语，用于识别历史中的快照消息。
 *
 * 快照作为一条合成 USER 消息持久化在会话历史中（而不是请求时临时拼接）：
 * 内容仅在变化时追加、既有字节永不重写，后续所有轮次的请求前缀因此保持稳定，
 * 能持续命中各供应商的前缀缓存。
 *
 * 识别分两层：消息同时带 isSynthetic 标记（请求期转换器据此跳过它）与内容前导
 * （[isMemorySnapshot]，供 UI/搜索/导出等消费方过滤，不依赖标记是否在存储中保留）。
 */
internal const val MEMORY_SNAPSHOT_PREAMBLE =
    "Automatically injected memory snapshot: the assistant's current long-term memories about the user, " +
        "selected by importance and recency. Treat them as established background. " +
        "If several memory snapshots appear in this conversation, the latest one is authoritative."

fun UIMessage.isMemorySnapshot(): Boolean {
    if (role != MessageRole.USER || parts.size != 1) return false
    val part = parts[0] as? UIMessagePart.Text ?: return false
    return part.text.startsWith(MEMORY_SNAPSHOT_PREAMBLE)
}

/** 构造快照正文：前导语 + 记忆 JSON。 */
fun buildMemorySnapshotText(memories: List<AssistantMemory>): String = buildString {
    append(MEMORY_SNAPSHOT_PREAMBLE)
    appendLine()
    appendLine("<memories>")
    val json = buildJsonArray {
        memories.forEach { memory ->
            add(buildJsonObject {
                put("id", memory.id)
                put("category", memory.category)
                put("content", memory.content)
            })
        }
    }
    append(JsonInstantPretty.encodeToString(json))
    appendLine()
    append("</memories>")
}

/**
 * 把快照插入到最新一条用户消息之前；与最近一条快照内容相同则返回 null（无需变更）。
 * 插入位置固定，保证后续轮次请求前缀字节级稳定。
 */
fun List<MessageNode>.withMemorySnapshot(snapshotText: String): List<MessageNode>? {
    val lastSnapshot = asReversed()
        .firstOrNull { it.currentMessage.isMemorySnapshot() }
        ?.currentMessage
    if (lastSnapshot?.toText() == snapshotText) return null

    val insertIndex = indexOfLast { it.currentMessage.role == MessageRole.USER }
        .let { if (it < 0) size else it }
    val snapshotNode = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(snapshotText)),
        isSynthetic = true,
    ).toMessageNode()
    return toMutableList().also { it.add(insertIndex, snapshotNode) }
}
