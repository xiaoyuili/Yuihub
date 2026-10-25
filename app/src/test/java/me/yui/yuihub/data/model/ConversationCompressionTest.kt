package me.yui.yuihub.data.model

import kotlin.uuid.Uuid
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.yui.yuihub.data.ai.prompts.COMPACTION_SUMMARY_OPEN
import me.yui.yuihub.data.ai.prompts.buildCompactionCheckpointText
import me.yui.yuihub.data.ai.prompts.compactionCheckpointBody
import me.yui.yuihub.data.ai.prompts.isCompactionCheckpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩逻辑单测：检查点边界、发送窗口、手动压缩与 UI 可见性的区分，
 * 以及压缩检查点文本的构造/解析往返。
 */
class ConversationCompressionTest {

    private fun conversationWithNodes(count: Int): Conversation {
        val nodes = (0 until count).map { UIMessage.user("msg-$it").toMessageNode() }
        return Conversation.ofId(id = Uuid.random(), assistantId = Uuid.random(), messages = nodes)
    }

    @Test
    fun `auto compression hides prefix and shrinks send window`() {
        val conversation = conversationWithNodes(5)
        val boundary = conversation.messageNodes[2]
        val compressed = conversation.copy(
            compressionSummaries = listOf(
                CompressionSummary(content = "summary", boundaryNodeId = boundary.id, messageCount = 3)
            )
        )

        // 发送窗口 = 首条用户消息锚点 + 检查点合成消息 + 边界之后的节点
        val window = compressed.requestWindowMessages()
        assertEquals(4, window.size)
        assertEquals("msg-0", window.first().parts.first().let { (it as UIMessagePart.Text).text })
        assertTrue(window[1].isSynthetic)
        assertTrue(window[1].parts.first().let { (it as UIMessagePart.Text).text }
            .contains("summary"))

        // UI 侧隐藏前缀（windowNodes = 边界之后的节点；自动压缩渲染流程行）
        assertEquals(2, compressed.windowNodes().size)
        assertEquals(compressed.activeCompression(), compressed.uiCompression())
    }

    @Test
    fun `manual compression shrinks send window but keeps all messages visible`() {
        val conversation = conversationWithNodes(5)
        val boundary = conversation.messageNodes[2]
        val compressed = conversation.copy(
            compressionSummaries = listOf(
                CompressionSummary(content = "summary", boundaryNodeId = boundary.id, manual = true)
            )
        )

        // 请求仍带摘要和首条用户锚点
        assertEquals(4, compressed.requestWindowMessages().size)
        // 手动压缩：UI 不隐藏（uiCompression 为 null），windowNodes 仍只表示边界之后的节点
        assertNull(compressed.uiCompression())
        assertEquals(2, compressed.windowNodes().size)
        assertEquals(5, compressed.messageNodes.size)
    }

    @Test
    fun `missing boundary invalidates checkpoint and falls back to full context`() {
        val conversation = conversationWithNodes(3)
        val stale = conversation.copy(
            compressionSummaries = listOf(
                CompressionSummary(content = "summary", boundaryNodeId = Uuid.random())
            )
        )
        assertNull(stale.activeCompression())
        assertEquals(3, stale.requestWindowMessages().size)
        assertEquals(3, stale.windowNodes().size)
    }

    @Test
    fun `multiple active summaries are sent in boundary order`() {
        val conversation = conversationWithNodes(6)
        val firstBoundary = conversation.messageNodes[1]
        val secondBoundary = conversation.messageNodes[3]
        val compressed = conversation.copy(
            compressionSummaries = listOf(
                CompressionSummary(content = "first", boundaryNodeId = firstBoundary.id),
                CompressionSummary(content = "second", boundaryNodeId = secondBoundary.id),
            )
        )

        val window = compressed.requestWindowMessages()
        assertEquals(5, window.size) // anchor + 2 summaries + 2 remaining nodes
        assertTrue(window[0].parts.first().let { (it as UIMessagePart.Text).text }.contains("msg-0"))
        assertTrue(window[1].parts.first().let { (it as UIMessagePart.Text).text }.contains("first"))
        assertTrue(window[2].parts.first().let { (it as UIMessagePart.Text).text }.contains("second"))
        assertEquals(2, compressed.windowNodes().size)
    }

    @Test
    fun `inactive summaries are ignored`() {
        val conversation = conversationWithNodes(4)
        val boundary = conversation.messageNodes[2]
        val compressed = conversation.copy(
            compressionSummaries = listOf(
                CompressionSummary(content = "old", boundaryNodeId = boundary.id, active = false),
            )
        )
        assertEquals(4, compressed.requestWindowMessages().size)
        assertTrue(compressed.activeCompressions().isEmpty())
    }

    @Test
    fun `no compression summaries sends everything`() {
        val conversation = conversationWithNodes(4)
        assertNull(conversation.activeCompression())
        assertEquals(4, conversation.requestWindowMessages().size)
    }

    @Test
    fun `compression takes prefix and retains disjoint tail`() {
        val conversation = conversationWithNodes(10)
        val prefix = conversation.compressionNodes(3)
        assertEquals(conversation.messageNodes.take(7), prefix)
        val compressed = conversation.copy(compressionSummaries = listOf(
            CompressionSummary(content = "summary", boundaryNodeId = prefix.last().id)
        ))
        assertEquals(conversation.messageNodes.takeLast(3), compressed.windowNodes())
        assertTrue(prefix.none { it in compressed.windowNodes() })
        assertEquals(conversation.messageNodes.takeLast(3), compressed.compressionNodes(0))
        assertTrue(compressed.compressionNodes(3).isEmpty())
        assertEquals(conversation.messageNodes, conversation.compressionNodes(0))
    }

    @Test
    fun `compressed generation updates by id without persisting synthetic messages`() {
        val base = conversationWithNodes(8)
        val compressed = base.copy(compressionSummaries = listOf(
            CompressionSummary(content = "summary", boundaryNodeId = base.messageNodes[4].id)
        ))
        val window = compressed.requestWindowMessages()
        val response = UIMessage.assistant("partial")
        val updated = compressed.updateCurrentMessages(window + response)

        assertEquals(base.messageNodes, updated.messageNodes.take(8))
        assertEquals(response, updated.messageNodes.last().currentMessage)
        assertEquals(9, updated.messageNodes.size)
        assertTrue(updated.messageNodes.flatMap { it.messages }.none { it.isSynthetic })

        val finished = response.copy(parts = listOf(UIMessagePart.Text("finished")))
        val checkpoint = updated.updateCurrentMessages(window + finished)
        assertEquals(9, checkpoint.messageNodes.size)
        assertEquals(base.messageNodes, checkpoint.messageNodes.take(8))
        assertEquals(finished, checkpoint.messageNodes.last().currentMessage)
    }

    @Test
    fun `subset update preserves unrelated nodes and selected branches`() {
        val original = UIMessage.assistant("original")
        val alternative = UIMessage.assistant("alternative")
        val node = MessageNode(messages = listOf(original, alternative), selectIndex = 1)
        val conversation = conversationWithNodes(3).let {
            it.copy(messageNodes = it.messageNodes + node)
        }
        val changed = original.copy(parts = listOf(UIMessagePart.Text("updated original")))
        val updated = conversation.updateCurrentMessages(listOf(changed))
        assertEquals(conversation.messageNodes.take(3), updated.messageNodes.take(3))
        assertEquals(1, updated.messageNodes.last().selectIndex)
        assertEquals(alternative, updated.messageNodes.last().currentMessage)
        assertEquals(changed, updated.messageNodes.last().messages.first())
    }

    @Test
    fun `regeneration adds candidate only to explicit response node`() {
        val prefix = conversationWithNodes(3)
        val original = UIMessage.assistant("old answer").toMessageNode()
        val later = UIMessage.user("later").toMessageNode()
        val conversation = prefix.copy(messageNodes = prefix.messageNodes + original + later)
        val answer = UIMessage.assistant("new answer")
        val messages = prefix.currentMessages + answer
        val updated = conversation.updateCurrentMessages(messages, original.id)
        val checkpoint = updated.updateCurrentMessages(
            messages.dropLast(1) + answer.copy(parts = listOf(UIMessagePart.Text("complete"))),
            original.id,
        )
        assertEquals(5, checkpoint.messageNodes.size)
        assertEquals(prefix.messageNodes, checkpoint.messageNodes.take(3))
        assertEquals(listOf(original.currentMessage, checkpoint.messageNodes[3].currentMessage),
            checkpoint.messageNodes[3].messages)
        assertEquals(1, checkpoint.messageNodes[3].selectIndex)
        assertEquals(later, checkpoint.messageNodes.last())
        assertEquals("complete", checkpoint.messageNodes[3].currentMessage.toText())
    }

    @Test
    fun `new tool loop messages append after regenerated response`() {
        val conversation = conversationWithNodes(2)
        val first = UIMessage.assistant("first")
        val second = UIMessage.assistant("second")
        val target = UIMessage.assistant("old").toMessageNode()
        val later = UIMessage.user("later").toMessageNode()
        val initial = conversation.copy(messageNodes = conversation.messageNodes + target + later)
        val updated = initial.updateCurrentMessages(conversation.currentMessages + first + second, target.id)
        assertEquals(5, updated.messageNodes.size)
        assertEquals(first, updated.messageNodes[2].currentMessage)
        assertEquals(second, updated.messageNodes[3].currentMessage)
        assertEquals(later, updated.messageNodes[4])
        assertEquals(2, updated.messageNodes[2].messages.size)
        val streamed = updated.updateCurrentMessages(listOf(first, second), target.id)
        assertEquals(updated, streamed)
    }

    @Test
    fun `checkpoint text round trips`() {
        val text = buildCompactionCheckpointText("## 摘要\n- 事实 A")
        assertTrue(text.contains(COMPACTION_SUMMARY_OPEN))
        assertEquals("## 摘要\n- 事实 A", compactionCheckpointBody(text))
    }

    @Test
    fun `checkpoint message is recognized as compaction`() {
        val text = buildCompactionCheckpointText("summary body")
        assertTrue(UIMessage.user(text).isCompactionCheckpoint())
        assertFalse(UIMessage.user("ordinary question").isCompactionCheckpoint())
    }

    @Test
    fun `checkpoint body falls back to raw text when markers absent`() {
        assertEquals("no markers here", compactionCheckpointBody("no markers here"))
    }
}
