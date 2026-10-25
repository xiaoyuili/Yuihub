package me.yui.yuihub.data.ai.prompts

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.yui.yuihub.data.model.AssistantMemory
import me.yui.yuihub.data.model.MemoryStatus
import me.yui.yuihub.data.model.MessageNode
import me.yui.yuihub.data.model.toMessageNode
import me.yui.yuihub.data.repository.MemoryRepository
import kotlin.uuid.Uuid

/**
 * 记忆注入消息：结构标签固定，便于历史识别与 UI 过滤。
 * - core：会话首轮固定注入的核心记忆，位置在首条用户消息之前；
 * - hit：每条用户消息前的关键词命中，for 属性绑定目标消息 id，保证同一轮幂等；
 * - rebase：上下文压缩后的重注入，放在最新用户消息之前。
 */
private const val MEMORY_CONTEXT_OPEN = "<memory-context version=\"2\""
private const val MEMORY_CONTEXT_CLOSE = "</memory-context>"

fun UIMessage.isMemorySnapshot(): Boolean {
    if (role != MessageRole.USER || parts.size != 1) return false
    val text = (parts[0] as? UIMessagePart.Text)?.text ?: return false
    return (text.contains(MEMORY_CONTEXT_OPEN) && text.contains(MEMORY_CONTEXT_CLOSE)) ||
        (text.contains("<memories>") && text.contains("</memories>"))
}

fun UIMessage.memorySnapshotKind(): String? {
    if (!isMemorySnapshot()) return null
    val text = toText()
    if (text.contains("kind=\"core\"")) return "core"
    if (text.contains("kind=\"hit\"")) return "hit"
    if (text.contains("kind=\"rebase\"")) return "rebase"
    return "legacy"
}

fun UIMessage.memorySnapshotTarget(): String? {
    val text = toText()
    val match = Regex("for=\"([^\"]+)\"").find(text) ?: return null
    return match.groupValues.getOrNull(1)
}

fun buildCoreMemorySnapshotText(
    core: MemoryRepository.CoreBlock,
    preamble: String = DEFAULT_MEMORY_SNAPSHOT_PROMPT,
): String = buildString {
    append(preamble)
    appendLine()
    appendLine("$MEMORY_CONTEXT_OPEN kind=\"core\">")
    append(core.text.trim())
    if (core.truncated) {
        appendLine()
        appendLine("(Core memory exceeded its budget; please condense it.)")
    }
    appendLine()
    append(MEMORY_CONTEXT_CLOSE)
}

fun buildHitMemorySnapshotText(
    hits: List<AssistantMemory>,
    targetMessageId: Uuid,
    preamble: String = DEFAULT_MEMORY_HIT_PROMPT,
): String = buildString {
    append(preamble)
    appendLine()
    appendLine("$MEMORY_CONTEXT_OPEN kind=\"hit\" for=\"$targetMessageId\">")
    hits.forEach { memory ->
        val kind = memory.kind
        val label = memory.displayTitle
        appendLine("[$kind][#${memory.id}] $label")
        appendLine(memory.content.trim())
        val meta = buildList {
            if (memory.keywords.isNotEmpty()) add(memory.keywords.joinToString(" ") { "#$it" })
            add("weight ${memory.weight}")
            if (memory.status != MemoryStatus.ACTIVE) add(memory.status)
        }.joinToString(" · ")
        if (meta.isNotBlank()) appendLine(meta)
        appendLine()
    }
    append(MEMORY_CONTEXT_CLOSE)
}

fun buildRebaseMemorySnapshotText(
    core: MemoryRepository.CoreBlock?,
    recent: List<AssistantMemory>,
    preamble: String = DEFAULT_MEMORY_REBASE_PROMPT,
): String = buildString {
    append(preamble)
    appendLine()
    appendLine("$MEMORY_CONTEXT_OPEN kind=\"rebase\">")
    if (core != null) {
        appendLine("[Core memory]")
        appendLine(core.text.trim())
        appendLine()
    }
    if (recent.isNotEmpty()) {
        appendLine("[Recently used or written]")
        recent.forEach { memory ->
            appendLine("[${memory.kind}][#${memory.id}] ${memory.displayTitle}")
            appendLine(memory.content.trim())
            appendLine()
        }
    }
    append(MEMORY_CONTEXT_CLOSE)
}

/**
 * 核心快照：首条用户消息之前；内容未变返回 null。
 * 变更时原位替换历史快照，保持这段前缀的位置稳定。
 */
fun List<MessageNode>.withCoreSnapshot(snapshotText: String): List<MessageNode>? {
    val existingIndex = indexOfFirst { it.currentMessage.memorySnapshotKind() == "core" }
    if (existingIndex >= 0) {
        val existing = this[existingIndex]
        val message = existing.currentMessage
        if (message.toText() == snapshotText) return null
        val updated = message.copy(
            parts = listOf(UIMessagePart.Text(snapshotText)),
            isSynthetic = true,
        )
        return toMutableList().also { it[existingIndex] = existing.copy(messages = listOf(updated)) }
    }
    // 升级前遗留的旧版快照：原位替换，避免新核心块与旧 JSON 快照同时进入请求
    val legacyIndex = indexOfFirst { it.currentMessage.memorySnapshotKind() == "legacy" }
    if (legacyIndex >= 0) {
        val existing = this[legacyIndex]
        val updated = existing.currentMessage.copy(
            parts = listOf(UIMessagePart.Text(snapshotText)),
            isSynthetic = true,
        )
        return toMutableList().also { it[legacyIndex] = existing.copy(messages = listOf(updated)) }
    }
    val firstUserIndex = indexOfFirst {
        it.currentMessage.role == MessageRole.USER && !it.currentMessage.isMemorySnapshot()
    }.let { if (it < 0) size else it }
    val node = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(snapshotText)),
        isSynthetic = true,
    ).toMessageNode()
    return toMutableList().also { it.add(firstUserIndex, node) }
}

/** 核心记忆被清空时移除旧核心块，避免历史快照继续生效。 */
fun List<MessageNode>.withoutCoreSnapshot(): List<MessageNode>? {
    val index = indexOfFirst { it.currentMessage.memorySnapshotKind() == "core" }
    if (index < 0) return null
    return toMutableList().also { it.removeAt(index) }
}

/**
 * 命中快照：插到目标用户消息之前。同一目标消息已有命中块时返回 null，
 * 避免重试/重新生成时重复注入。
 */
fun List<MessageNode>.withHitSnapshot(
    snapshotText: String,
    targetMessageId: Uuid,
): List<MessageNode>? {
    val targetIndex = indexOfFirst { node ->
        node.messages.any { it.id == targetMessageId }
    }
    if (targetIndex < 0) return null
    val already = any { it.currentMessage.memorySnapshotTarget() == targetMessageId.toString() }
    if (already) return null
    val node = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(snapshotText)),
        isSynthetic = true,
    ).toMessageNode()
    return toMutableList().also { it.add(targetIndex, node) }
}

/** 压缩后重注入：只保留一个 rebase 块，放在最新用户消息之前。 */
fun List<MessageNode>.withRebaseSnapshot(snapshotText: String): List<MessageNode>? {
    val existingIndex = indexOfFirst { it.currentMessage.memorySnapshotKind() == "rebase" }
    if (existingIndex >= 0 && this[existingIndex].currentMessage.toText() == snapshotText) return null
    val updated = toMutableList()
    if (existingIndex >= 0) updated.removeAt(existingIndex)
    val lastUserIndex = updated.indexOfLast {
        it.currentMessage.role == MessageRole.USER && !it.currentMessage.isMemorySnapshot()
    }.let { if (it < 0) updated.size else it }
    val node = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(snapshotText)),
        isSynthetic = true,
    ).toMessageNode()
    updated.add(lastUserIndex, node)
    return updated
}
