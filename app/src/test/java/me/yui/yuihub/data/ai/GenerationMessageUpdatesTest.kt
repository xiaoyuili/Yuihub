package me.yui.yuihub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationMessageUpdatesTest {
    @Test
    fun `request history transformations never become persistence updates`() {
        val history = UIMessage.user("original")
        val anchor = history.copy(isSynthetic = true)
        val summary = UIMessage.user("summary").copy(isSynthetic = true)
        val source = listOf(anchor, summary, history)
        val rewritten = history.copy(parts = listOf(UIMessagePart.Text("offloaded request view")))
        val response = UIMessage.assistant("answer")
        assertEquals(listOf(response), generationMessageUpdates(source, listOf(anchor, summary, rewritten, response)))
        assertTrue(generationMessageUpdates(source, listOf(anchor, summary, rewritten)).isEmpty())
    }

    @Test
    fun `continuing an assistant includes that message and new tool steps`() {
        val user = UIMessage.user("question")
        val assistant = UIMessage.assistant("partial")
        val finished = assistant.copy(parts = listOf(UIMessagePart.Text("complete")))
        val next = UIMessage.assistant("next step")
        assertEquals(listOf(finished, next), generationMessageUpdates(listOf(user, assistant), listOf(user, finished, next)))
    }
}
