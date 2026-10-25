package me.yui.yuihub.data.model

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.InstantSerializer
import me.yui.yuihub.data.ai.prompts.COMPACTION_CHECKPOINT_PREAMBLE
import me.yui.yuihub.data.ai.prompts.buildCompactionCheckpointText
import me.yui.yuihub.data.ai.prompts.isMemorySnapshot
import me.yui.yuihub.data.ai.tools.local.LocalToolOption
import me.yui.yuihub.data.datastore.DEFAULT_ASSISTANT_ID
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * 一次自动压缩后的对话摘要，独立于消息节点存储，
 * 在聊天页顶部渲染为专属摘要卡片。
 *
 * 轨迹只追加：压缩不删除/改写任何历史节点，只在请求组装时把边界前的节点
 * 替换为本检查点（新段起点，旧前缀字节保留在轨迹中可回溯）。
 */
@Serializable
data class CompressionSummary(
    val id: Uuid = Uuid.random(),
    val content: String = "",
    // 本次压缩覆盖的消息节点数
    val messageCount: Int = 0,
    // 压缩边界：被压缩范围内的最后一个节点 id；请求只发送该节点之后的内容
    val boundaryNodeId: Uuid? = null,
    // 该段起始节点，便于恢复和展示
    val startNodeId: Uuid? = null,
    // 1 = 原始段摘要；2/3 = 由旧摘要合并出的高层摘要
    val tier: Int = 1,
    // 已被更高层摘要取代的段不再进入请求窗口
    val active: Boolean = true,
    // 本条高层摘要覆盖的子摘要
    val parentSummaryIds: List<Uuid> = emptyList(),
    // 压缩前原文/摘要的本地估算 token，用于展示节省量
    val originalTokens: Int = 0,
    val summaryTokens: Int = 0,
    // 本次压缩实际耗时（毫秒）
    val durationMs: Long = 0,
    // 手动压缩（/压缩）：只缩减发送上下文，不在聊天页隐藏旧消息/渲染流程行
    val manual: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant = Instant.now(),
)

@Serializable
data class Conversation(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    val isPinned: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    val customSystemPrompt: String? = null,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
    // null = 继承助手；非 null = 当前对话覆盖
    val enabledSkillsOverride: Set<String>? = null,
    val mcpServersOverride: Set<Uuid>? = null,
    val localToolsOverride: List<LocalToolOption>? = null,
    // Absolute path inside the workspace rootfs
    val workspaceCwd: String? = null,
    // 所属文件夹（助手内分组），null 表示未归入任何文件夹
    val folderId: Uuid? = null,
    // 父会话 id（子代理会话的归属），null 表示普通会话
    val parentConversationId: Uuid? = null,
    /** 子代理会话绑定的角色；追问时据此继续使用该角色的模型/推理配置 */
    val subagentPersonaId: Uuid? = null,
    @Transient
    val newConversation: Boolean = false,
    // 自动压缩产生的历史摘要（按时间序递增，最新在后）
    val compressionSummaries: List<CompressionSummary> = emptyList(),
) {
    val files: List<Uri>
        get() = messageNodes
            .flatMap { node -> node.messages.flatMap { it.parts } }
            .localFileUrls()
            .map { it.toUri() }

    /**
     *  当前选中的 message
     */
    val currentMessages
        get(): List<UIMessage> {
            return messageNodes.map { node -> node.messages[node.selectIndex] }
        }

    fun getMessageNodeByMessageId(messageId: Uuid): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.any { it.id == messageId } }
    }

    /**
     * 当前生效的压缩段；边界节点不存在（被 regenerate 截断/分支切换删除）时失效。
     * 多段摘要按创建时间排序，最后一个决定请求窗口的原文起点。
     */
    fun activeCompressions(): List<CompressionSummary> {
        val indexById = messageNodes.mapIndexed { index, node -> node.id to index }.toMap()
        return compressionSummaries
            .filter { it.active && it.boundaryNodeId != null && it.boundaryNodeId in indexById }
            .sortedBy { indexById[it.boundaryNodeId] ?: Int.MAX_VALUE }
    }

    /**
     * 界面侧生效的压缩段：手动压缩只缩减发送上下文，不在聊天页隐藏旧消息、
     * 也不渲染「上下文压缩」流程行（用户要求手动压缩后对话全部保持可见）。
     */
    fun uiCompressions(): List<CompressionSummary> = activeCompressions().filterNot { it.manual }

    fun activeCompression(): CompressionSummary? = activeCompressions().lastOrNull()

    fun uiCompression(): CompressionSummary? = uiCompressions().lastOrNull()

    /**
     * 请求发送窗口（缓存分段）：
     * [首条用户消息锚点] + [所有有效段摘要] + 最新边界之后的全部消息。
     */
    fun requestWindowMessages(checkpointPreamble: String = COMPACTION_CHECKPOINT_PREAMBLE): List<UIMessage> {
        val checkpoints = activeCompressions()
        if (checkpoints.isEmpty()) return currentMessages
        val boundaryIndex = messageNodes.indexOfFirst { it.id == checkpoints.last().boundaryNodeId }
        if (boundaryIndex < 0) return currentMessages
        val summaryMessages = checkpoints.map { checkpoint ->
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text(buildCompactionCheckpointText(checkpoint.content, checkpointPreamble))),
                isSynthetic = true,
            )
        }
        // 首条用户请求是任务锚点，压缩后仍保留原文，避免摘要逐轮重写导致目标漂移。
        val firstUserIndex = messageNodes.indexOfFirst {
            !it.currentMessage.isMemorySnapshot() && it.currentMessage.role == MessageRole.USER
        }
        val anchorMessages = if (firstUserIndex in 0..boundaryIndex) {
            listOf(messageNodes[firstUserIndex].currentMessage.copy(isSynthetic = true))
        } else {
            emptyList()
        }
        return anchorMessages + summaryMessages +
            messageNodes.drop(boundaryIndex + 1).map { it.messages[it.selectIndex] }
    }

    /**
     * 边界之后的节点流（发送窗口对应的存储侧视图）；无有效检查点时为全量。
     * UI 过滤与压缩输入都以它为准。
     */
    fun windowNodes(): List<MessageNode> {
        val checkpoint = activeCompression() ?: return messageNodes
        val boundaryIndex = messageNodes.indexOfFirst { it.id == checkpoint.boundaryNodeId }
        if (boundaryIndex < 0) return messageNodes
        return messageNodes.drop(boundaryIndex + 1)
    }

    fun compressionNodes(keepCount: Int): List<MessageNode> = windowNodes().dropLast(keepCount)

    fun updateCurrentMessages(
        messages: List<UIMessage>,
        responseNodeId: Uuid? = null,
    ): Conversation {
        val newNodes = messageNodes.toMutableList()
        val nodeByMessageId = newNodes.flatMapIndexed { index, node ->
            node.messages.map { it.id to index }
        }.toMap().toMutableMap()
        var responseNodeUsed = false
        var responseCursor = -1

        messages.filterNot { it.isSynthetic }.forEach { message ->
            val existingIndex = nodeByMessageId[message.id]
            if (existingIndex != null) {
                val node = newNodes[existingIndex]
                newNodes[existingIndex] = node.copy(
                    messages = node.messages.map { if (it.id == message.id) message else it }
                )
                if (node.id == responseNodeId) responseNodeUsed = true
                if (responseNodeUsed) responseCursor = existingIndex
            } else {
                val responseIndex = if (!responseNodeUsed && message.role == MessageRole.ASSISTANT) {
                    newNodes.indexOfFirst { it.id == responseNodeId }
                } else {
                    -1
                }
                if (responseIndex >= 0) {
                    val node = newNodes[responseIndex]
                    newNodes[responseIndex] = node.copy(
                        messages = node.messages + message,
                        selectIndex = node.messages.size,
                    )
                    nodeByMessageId[message.id] = responseIndex
                    responseNodeUsed = true
                    responseCursor = responseIndex
                } else {
                    val insertIndex = if (responseNodeUsed && responseCursor >= 0) responseCursor + 1 else newNodes.size
                    nodeByMessageId.replaceAll { _, index -> if (index >= insertIndex) index + 1 else index }
                    newNodes.add(insertIndex, message.toMessageNode())
                    nodeByMessageId[message.id] = insertIndex
                    if (responseNodeUsed) responseCursor = insertIndex
                }
            }
        }
        return copy(messageNodes = newNodes)
    }

    companion object {
        fun ofId(
            id: Uuid,
            assistantId: Uuid = DEFAULT_ASSISTANT_ID,
            messages: List<MessageNode> = emptyList(),
            newConversation: Boolean = false
        ) = Conversation(
            id = id,
            assistantId = assistantId,
            messageNodes = messages,
            newConversation = newConversation,
        )
    }
}

@Serializable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
    @Transient
    val isFavorite: Boolean = false,
) {
    val currentMessage get() = if (messages.isEmpty() || selectIndex !in messages.indices) {
        throw IllegalStateException("MessageNode has no valid current message: messages.size=${messages.size}, selectIndex=$selectIndex")
    } else {
        messages[selectIndex]
    }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage) = MessageNode(
            messages = listOf(message),
            selectIndex = 0
        )
    }
}

fun UIMessage.toMessageNode(): MessageNode {
    return MessageNode(
        messages = listOf(this),
        selectIndex = 0
    )
}

/** 本地附件引用，包含工具结果中的嵌套附件。 */
internal fun List<UIMessagePart>.localFileUrls(): Set<String> = buildSet {
    this@localFileUrls.forEach { part ->
        val url = when (part) {
            is UIMessagePart.Image -> part.url
            is UIMessagePart.Document -> part.url
            is UIMessagePart.Video -> part.url
            is UIMessagePart.Audio -> part.url
            is UIMessagePart.Tool -> {
                addAll(part.output.localFileUrls())
                null
            }
            else -> null
        }
        if (url?.startsWith("file://") == true) add(url)
    }
}
