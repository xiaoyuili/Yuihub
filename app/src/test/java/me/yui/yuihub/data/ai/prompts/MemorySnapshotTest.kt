package me.yui.yuihub.data.ai.prompts

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.yui.yuihub.data.model.AssistantMemory
import me.yui.yuihub.data.model.MessageNode
import me.yui.yuihub.data.model.toMessageNode
import me.yui.yuihub.data.repository.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class MemorySnapshotTest {

    private fun coreBlock(text: String = "- likes tea") = MemoryRepository.CoreBlock(
        text = text,
        charCount = text.length,
        hash = "hash",
        sectionCounts = mapOf("user" to 1),
        projectTitles = emptyList(),
        truncated = false,
        ids = listOf(1),
    )

    private fun memory(id: Int, content: String) = AssistantMemory(
        id = id,
        content = content,
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
    fun `core snapshot is inserted before first user message`() {
        val text = buildCoreMemorySnapshotText(coreBlock())
        val updated = nodesOf(userMessage("hi"), assistantMessage("hello")).withCoreSnapshot(text)
        assertNotNull(updated)
        assertEquals(3, updated!!.size)
        assertTrue(updated[0].currentMessage.isMemorySnapshot())
        assertEquals("core", updated[0].currentMessage.memorySnapshotKind())
        assertEquals("hi", textOf(updated[1]))
    }

    @Test
    fun `unchanged core snapshot is a no-op and changed one stays in place`() {
        val first = nodesOf(userMessage("hi")).withCoreSnapshot(buildCoreMemorySnapshotText(coreBlock("a")))!!
        assertNull(first.withCoreSnapshot(buildCoreMemorySnapshotText(coreBlock("a"))))
        val changed = first.withCoreSnapshot(buildCoreMemorySnapshotText(coreBlock("- b")))!!
        assertEquals(2, changed.size)
        assertTrue(textOf(changed[0]).contains("- b"))
        assertEquals("hi", textOf(changed[1]))
    }

    @Test
    fun `hit snapshot is inserted before its target and is idempotent`() {
        val target = userMessage("tell me about tea")
        val nodes = nodesOf(userMessage("hi"), assistantMessage("yo"), target)
        val text = buildHitMemorySnapshotText(listOf(memory(7, "likes tea")), target.id)
        val updated = nodes.withHitSnapshot(text, target.id)!!
        assertEquals(4, updated.size)
        assertEquals("hit", updated[2].currentMessage.memorySnapshotKind())
        assertEquals(target.id.toString(), updated[2].currentMessage.memorySnapshotTarget())
        assertEquals("tell me about tea", textOf(updated[3]))
        assertNull(updated.withHitSnapshot(text, target.id))
    }

    @Test
    fun `rebase snapshot replaces previous rebase and sits before latest user`() {
        val nodes = nodesOf(userMessage("hi"), assistantMessage("yo"), userMessage("again"))
        val first = nodes.withRebaseSnapshot(buildRebaseMemorySnapshotText(coreBlock(), emptyList()))!!
        assertEquals(4, first.size)
        val second = first.withRebaseSnapshot(
            buildRebaseMemorySnapshotText(coreBlock("- updated"), listOf(memory(2, "written")))
        )!!
        assertEquals(4, second.size)
        assertEquals(1, second.count { it.currentMessage.memorySnapshotKind() == "rebase" })
        assertEquals("again", textOf(second.last()))
        assertTrue(textOf(second[2]).contains("- updated"))
    }

    @Test
    fun `legacy snapshot marker is still recognized`() {
        val legacy = userMessage("header\n<memories>\n[]\n</memories>").copy(isSynthetic = true)
        assertTrue(legacy.isMemorySnapshot())
        assertEquals("legacy", legacy.memorySnapshotKind())
        assertFalse(userMessage("hello").isMemorySnapshot())
        assertFalse(assistantMessage("<memories></memories>").isMemorySnapshot())
    }
}
