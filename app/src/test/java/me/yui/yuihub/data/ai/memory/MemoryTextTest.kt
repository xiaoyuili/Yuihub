package me.yui.yuihub.data.ai.memory

import me.yui.yuihub.data.model.AssistantMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTextTest {

    @Test
    fun `tokenize splits cjk bigrams and lowercases words`() {
        assertEquals(listOf("记忆", "忆插", "插件"), MemoryText.tokenize("记忆插件"))
        assertTrue(MemoryText.tokenize("Memory Plugin").containsAll(listOf("memory", "plugin")))
    }

    @Test
    fun `keywords prefer repeated meaningful tokens`() {
        val keywords = MemoryText.extractKeywords("Kotlin Compose Kotlin memory memory", limit = 3)
        assertTrue(keywords.contains("kotlin"))
        assertTrue(keywords.contains("memory"))
    }

    @Test
    fun `similarity is high for overlapping content`() {
        val same = MemoryText.similarity("用户喜欢简洁的回答", "用户喜欢简洁的回答")
        val different = MemoryText.similarity("用户喜欢简洁的回答", "项目部署在服务器上")
        assertTrue(same > 0.9f)
        assertTrue(different < 0.2f)
    }

    @Test
    fun `rank hits prefers keyword overlap and weight`() {
        val now = 1_700_000_000_000L
        val low = AssistantMemory(
            id = 1,
            content = "deploy notes",
            keywords = listOf("deploy", "server"),
            weight = 1,
            updatedAt = now,
        )
        val high = AssistantMemory(
            id = 2,
            content = "deploy runbook",
            keywords = listOf("deploy", "server", "runbook"),
            weight = 4,
            updatedAt = now,
        )
        val ranked = MemoryText.rankHits("how to deploy server", listOf(low, high), now = now, topK = 2)
        assertEquals(2, ranked.size)
        assertEquals(2, ranked.first().id)
    }

    @Test
    fun `rank hits returns nothing without overlap`() {
        val memory = AssistantMemory(id = 1, content = "tea", keywords = listOf("tea"))
        assertTrue(MemoryText.rankHits("kubernetes cluster", listOf(memory)).isEmpty())
    }
}
