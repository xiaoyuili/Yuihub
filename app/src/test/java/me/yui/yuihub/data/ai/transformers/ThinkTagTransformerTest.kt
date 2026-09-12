package me.yui.yuihub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Instant

class ThinkTagTransformerTest {
    private val now = Instant.parse("2026-08-20T00:00:00Z")

    @Test
    fun `think tag at start should be converted to reasoning`() {
        val result = transform("<think>reason</think>answer")

        assertEquals("reason", result.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning)
        assertEquals("answer", result.parts.filterIsInstance<UIMessagePart.Text>().single().text)
        assertEquals(now, result.parts.filterIsInstance<UIMessagePart.Reasoning>().single().finishedAt)
    }

    @Test
    fun `think tag in visible answer should be preserved`() {
        val text = "The literal `<think>` tag should stay visible."

        assertEquals(listOf(UIMessagePart.Text(text)), transform(text).parts)
    }

    @Test
    fun `native reasoning should prevent fallback tag parsing`() {
        val nativeReasoning = UIMessagePart.Reasoning("native reasoning")
        val text = UIMessagePart.Text("answer mentions <think> without a closing tag")
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(nativeReasoning, text),
        )

        val result = listOf(message).transformThinkTags(now, generationFinished = true).single()

        assertEquals(listOf(nativeReasoning, text), result.parts)
    }

    @Test
    fun `later think tags should remain visible`() {
        val result = transform("<think>reason</think>answer <think>literal")

        assertEquals("reason", result.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning)
        assertEquals("answer <think>literal", result.parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun `think tag in a later text part should be preserved`() {
        val parts = listOf(
            UIMessagePart.Text("visible answer"),
            UIMessagePart.Text("<think>literal"),
        )
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = parts)

        val result = listOf(message).transformThinkTags(now, generationFinished = true).single()

        assertEquals(parts, result.parts)
    }

    @Test
    fun `unclosed prefix tag should keep the text visible while streaming`() {
        val text = "<think>reason in progress"
        val message = UIMessage.assistant(text)

        val result = listOf(message).transformThinkTags(now, generationFinished = false).single()

        // 不闭合时不得整段吞成思考：否则可见正文变空，表现为「思考完不回复」
        assertEquals(emptyList<UIMessagePart.Reasoning>(), result.parts.filterIsInstance<UIMessagePart.Reasoning>())
        assertEquals(text, result.parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun `unclosed prefix tag should stay visible after generation finish`() {
        val text = "<think>reason in progress"
        val message = UIMessage.assistant(text)

        val result = listOf(message).transformThinkTags(now, generationFinished = true).single()

        assertEquals(emptyList<UIMessagePart.Reasoning>(), result.parts.filterIsInstance<UIMessagePart.Reasoning>())
        assertEquals(text, result.parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun `closed tag should mark reasoning finished immediately`() {
        val message = UIMessage.assistant("<think>reason</think>answer")

        val visual = listOf(message).transformThinkTags(now, generationFinished = false).single()

        assertEquals("answer", visual.parts.filterIsInstance<UIMessagePart.Text>().single().text)
        assertEquals(now, visual.parts.filterIsInstance<UIMessagePart.Reasoning>().single().finishedAt)
    }

    private fun transform(text: String): UIMessage {
        val message = UIMessage.assistant(text)
        return listOf(message).transformThinkTags(now, generationFinished = true).single()
    }
}
