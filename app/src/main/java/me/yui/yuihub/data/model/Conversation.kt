package me.yui.yuihub.data.model

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.InstantSerializer
import me.yui.yuihub.data.ai.prompts.buildCompactionCheckpointText
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
    // Absolute path inside the workspace rootfs
    val workspaceCwd: String? = null,
    // 所属文件夹（助手内分组），null 表示未归入任何文件夹
    val folderId: Uuid? = null,
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
     * 当前生效的压缩检查点；边界节点不存在（被 regenerate 截断/分支切换删除）时失效，
     * 回退全量发送。
     */
    fun activeCompression(): CompressionSummary? {
        val checkpoint = compressionSummaries.lastOrNull() ?: return null
        val boundaryId = checkpoint.boundaryNodeId ?: return null
        return if (messageNodes.any { it.id == boundaryId }) checkpoint else null
    }

    /**
     * 请求发送窗口（缓存分段：检查点是新段起点，边界前的旧前缀保留在轨迹中但不发送）：
     * [检查点合成消息] + 边界节点之后的全部消息；无有效检查点时返回全量。
     */
    fun requestWindowMessages(): List<UIMessage> {
        val checkpoint = activeCompression() ?: return currentMessages
        val boundaryIndex = messageNodes.indexOfFirst { it.id == checkpoint.boundaryNodeId }
        if (boundaryIndex < 0) return currentMessages
        val checkpointMessage = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(buildCompactionCheckpointText(checkpoint.content))),
            isSynthetic = true,
        )
        return listOf(checkpointMessage) + messageNodes.drop(boundaryIndex + 1).map { it.messages[it.selectIndex] }
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

    fun updateCurrentMessages(messages: List<UIMessage>): Conversation {
        val newNodes = this.messageNodes.toMutableList()

        messages.forEachIndexed { index, message ->
            val node = newNodes
                .getOrElse(index) { message.toMessageNode() }

            val newMessages = node.messages.toMutableList()
            var newMessageIndex = node.selectIndex
            if (newMessages.any { it.id == message.id }) {
                newMessages[newMessages.indexOfFirst { it.id == message.id }] = message
            } else {
                newMessages.add(message)
                newMessageIndex = newMessages.lastIndex
            }

            val newNode = node.copy(
                messages = newMessages,
                selectIndex = newMessageIndex
            )

            // 更新newNodes
            if (index > newNodes.lastIndex) {
                newNodes.add(newNode)
            } else {
                newNodes[index] = newNode
            }
        }

        return this.copy(
            messageNodes = newNodes
        )
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
