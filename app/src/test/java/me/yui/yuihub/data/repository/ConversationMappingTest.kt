package me.yui.yuihub.data.repository

import kotlin.uuid.Uuid
import me.rerere.ai.ui.UIMessage
import me.yui.yuihub.data.ai.tools.local.LocalToolOption
import me.yui.yuihub.data.model.Conversation
import me.yui.yuihub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationMappingTest {
    private fun roundTrip(conversation: Conversation): Conversation {
        val entity = ConversationRepository.conversationToConversationEntity(conversation)
        return ConversationRepository.conversationEntityToConversation(entity, conversation.messageNodes)
    }

    @Test
    fun `explicit extension overrides and persona survive persistence`() {
        val original = Conversation.ofId(Uuid.random(), messages = listOf(UIMessage.user("hello").toMessageNode()))
            .copy(
                enabledSkillsOverride = setOf("test-skill"),
                mcpServersOverride = setOf(Uuid.random()),
                localToolsOverride = listOf(LocalToolOption.Clipboard, LocalToolOption.TimeInfo),
                subagentPersonaId = Uuid.random(),
            )
        assertEquals(original.copy(
            createAt = java.time.Instant.ofEpochMilli(original.createAt.toEpochMilli()),
            updateAt = java.time.Instant.ofEpochMilli(original.updateAt.toEpochMilli()),
        ), roundTrip(original))
    }

    @Test
    fun `empty overrides remain distinct from inherited overrides`() {
        val inherited = roundTrip(Conversation.ofId(Uuid.random()))
        assertNull(inherited.enabledSkillsOverride)
        assertNull(inherited.mcpServersOverride)
        assertNull(inherited.localToolsOverride)
        assertNull(inherited.subagentPersonaId)

        val disabled = inherited.copy(
            enabledSkillsOverride = emptySet(),
            mcpServersOverride = emptySet(),
            localToolsOverride = emptyList(),
        )
        assertEquals(disabled.copy(
            createAt = java.time.Instant.ofEpochMilli(disabled.createAt.toEpochMilli()),
            updateAt = java.time.Instant.ofEpochMilli(disabled.updateAt.toEpochMilli()),
        ), roundTrip(disabled))
    }
}
