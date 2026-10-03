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

        // 发送窗口 = 检查点合成消息 + 边界之后的节点
        val window = compressed.requestWindowMessages()
        assertEquals(3, window.size) // 1 checkpoint + 2 remaining nodes
        assertTrue(window.first().isSynthetic)
        assertTrue(window.first().parts.first().let { (it as UIMessagePart.Text).text }
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

        // 请求仍带摘要
        assertEquals(3, compressed.requestWindowMessages().size)
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
    fun `no compression summaries sends everything`() {
        val conversation = conversationWithNodes(4)
        assertNull(conversation.activeCompression())
        assertEquals(4, conversation.requestWindowMessages().size)
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
