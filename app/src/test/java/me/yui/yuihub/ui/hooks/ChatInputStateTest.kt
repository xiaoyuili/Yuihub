package me.yui.yuihub.ui.hooks

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ChatInputStateTest {
    @Test
    fun `adding text to an attachment message inserts it once`() {
        val image = UIMessagePart.Image("file:///image.png")
        val state = ChatInputState()
        state.setContents(listOf(image))
        state.editingMessage = Uuid.random()
        state.setMessageText("caption")
        assertEquals(listOf(UIMessagePart.Text("caption"), image), state.getContents())
    }

    @Test
    fun `editing preserves earlier text and attachment order`() {
        val image = UIMessagePart.Image("file:///image.png")
        val state = ChatInputState()
        state.setContents(listOf(UIMessagePart.Text("first"), image, UIMessagePart.Text("last")))
        state.editingMessage = Uuid.random()
        state.setMessageText("updated")
        assertEquals(listOf(UIMessagePart.Text("first"), image, UIMessagePart.Text("updated")), state.getContents())
    }

    @Test
    fun `blank edited text retains attachments without text`() {
        val image = UIMessagePart.Image("file:///image.png")
        val state = ChatInputState()
        state.setContents(listOf(image, UIMessagePart.Text("old")))
        state.editingMessage = Uuid.random()
        state.setMessageText("")
        assertEquals(listOf(image), state.getContents())
    }
}
