package me.yui.yuihub.data.ai.prompts

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.yui.yuihub.data.model.AssistantMemory
import me.yui.yuihub.data.model.MessageNode
import me.yui.yuihub.data.model.toMessageNode
import me.yui.yuihub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySnapshotTest {

    private fun memory(id: Int, content: String) = AssistantMemory(
        id = id,
        content = content,
        importance = 0.6f,
        category = "other",
    )

    private fun userMessage(text: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun assistantMessage(text: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun nodesOf(vararg messages: UIMessage): List<MessageNode> = messages.map { it.toMessageNode() }

    private fun textOf(node: MessageNode): String = node.currentMessage.toText()

    @Test
    fun `snapshot text carries preamble and memory json`() {
        val text = buildMemorySnapshotText(listOf(memory(1, "likes tea")))
        assertTrue(text.startsWith(MEMORY_SNAPSHOT_PREAMBLE))
        assertTrue(text.contains("\"likes tea\""))
        assertTrue(text.contains("\"category\""))
    }

    @Test
    fun `isMemorySnapshot recognizes only snapshot messages`() {
        val snapshot = userMessage(buildMemorySnapshotText(listOf(memory(1, "a")))).copy(isSynthetic = true)
        assertTrue(snapshot.isMemorySnapshot())
        assertFalse(userMessage("hello").isMemorySnapshot())
        // 角色不对：assistant 消息即使内容相同也不是快照
        assertFalse(assistantMessage(buildMemorySnapshotText(listOf(memory(1, "a")))).isMemorySnapshot())
    }

    @Test
    fun `snapshot is inserted before the newest user message`() {
        val snap = buildMemorySnapshotText(listOf(memory(1, "a")))
        val nodes = nodesOf(userMessage("hi"), assistantMessage("hello"))
        val updated = nodes.withMemorySnapshot(snap)
        assertNotNull(updated)
        assertEquals(3, updated!!.size)
        assertTrue(updated[0].currentMessage.isMemorySnapshot())
        assertTrue(updated[0].currentMessage.isSynthetic)
        assertEquals("hi", textOf(updated[1]))
        assertEquals("hello", textOf(updated[2]))
    }

    @Test
    fun `unchanged snapshot is a no-op`() {
        val snap = buildMemorySnapshotText(listOf(memory(1, "a")))
        val first = nodesOf(userMessage("hi")).withMemorySnapshot(snap)!!
        assertNull(first.withMemorySnapshot(snap))
    }

    @Test
    fun `changed snapshot appends without rewriting previous bytes`() {
        val snap1 = buildMemorySnapshotText(listOf(memory(1, "a")))
        val snap2 = buildMemorySnapshotText(listOf(memory(1, "b")))
        val turn1 = nodesOf(userMessage("hi"), assistantMessage("yo")).withMemorySnapshot(snap1)!!
        // [SNAP1, u1, a1]
        val turn2 = turn1.withMemorySnapshot(snap2)!!
        // [SNAP1, SNAP2, u1, a1]：旧快照字节不变，新快照追加在其后
        assertEquals(4, turn2.size)
        assertTrue(turn2[0].currentMessage.isMemorySnapshot())
        assertTrue(turn2[1].currentMessage.isMemorySnapshot())
        assertEquals(textOf(turn1[0]), textOf(turn2[0]))
        assertEquals("hi", textOf(turn2[2]))
        assertEquals("yo", textOf(turn2[3]))
    }

    @Test
    fun `request prefix stays stable across turns`() {
        val snap = buildMemorySnapshotText(listOf(memory(1, "a")))
        // 第 1 轮：发送前插入快照，随后生成回复
        val firstTurnNodes = nodesOf(userMessage("hi"), assistantMessage("yo")).withMemorySnapshot(snap)!!
        val request1 = firstTurnNodes.map { it.currentMessage }
        // 第 2 轮：用户追加新消息，快照未变化
        val secondTurnNodes = firstTurnNodes + userMessage("again").toMessageNode()
        assertNull(secondTurnNodes.withMemorySnapshot(snap))
        val request2 = secondTurnNodes.map { it.currentMessage }
        // 前缀缓存命中的前提：请求 1 的消息序列是请求 2 的前缀
        assertEquals(request1, request2.take(request1.size))
    }

    @Test
    fun `isSynthetic survives serialization round trip`() {
        val snapshot = userMessage(buildMemorySnapshotText(listOf(memory(1, "a")))).copy(isSynthetic = true)
        val encoded = JsonInstant.encodeToString(UIMessage.serializer(), snapshot)
        val decoded = JsonInstant.decodeFromString(UIMessage.serializer(), encoded)
        assertTrue(decoded.isSynthetic)
        assertTrue(decoded.isMemorySnapshot())

        val decodedPlain = JsonInstant.decodeFromString(
            UIMessage.serializer(),
            JsonInstant.encodeToString(UIMessage.serializer(), userMessage("hi")),
        )
        assertFalse(decodedPlain.isSynthetic)
    }
}
