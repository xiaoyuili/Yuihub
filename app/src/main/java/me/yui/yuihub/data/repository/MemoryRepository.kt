package me.yui.yuihub.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import me.yui.yuihub.data.db.dao.MemoryDAO
import me.yui.yuihub.data.db.entity.MemoryEntity
import me.yui.yuihub.data.model.AssistantMemory
import me.yui.yuihub.data.model.MemoryCategory
import kotlin.math.exp
import kotlin.math.ln

class MemoryRepository(
    private val memoryDAO: MemoryDAO,
) {
    companion object {
        // 单个助手记忆条数上限，超出时提取/整理管道会淘汰低分记忆
        const val MAX_MEMORIES_PER_SCOPE = 300

        // 注入上下文的记忆内容预算（字符数，约 600 token）
        const val PROMPT_MEMORY_CHAR_BUDGET = 1800

        // 记忆时效半衰期（毫秒）：30 天
        private const val RECENCY_HALF_LIFE_MS = 30L * 24 * 60 * 60 * 1000
    }

    fun getMemoriesFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities -> entities.map { it.toModel() } }

    suspend fun getMemories(assistantId: String): List<AssistantMemory> =
        memoryDAO.getMemoriesOfAssistant(assistantId).map { it.toModel() }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
    }

    suspend fun updateMemory(
        id: Int,
        content: String,
        importance: Float? = null,
        category: String? = null,
    ): AssistantMemory? {
        // 记录可能已被后台整理/裁剪并发删除：返回 null 由调用方处理，不抛异常
        val old = memoryDAO.getMemoryById(id) ?: return null
        val newCategory = category
            ?.trim()?.lowercase()
            ?.takeIf { it in MemoryCategory.ALL }
            ?: old.category
        val newMemory = old.copy(
            content = content,
            importance = (importance ?: old.importance).coerceIn(0f, 1f),
            category = newCategory,
            updatedAt = System.currentTimeMillis(),
        )
        memoryDAO.updateMemory(newMemory)
        return newMemory.toModel()
    }

    suspend fun addMemory(
        assistantId: String,
        content: String,
        importance: Float = 0.6f,
        category: String = MemoryCategory.OTHER,
    ): AssistantMemory {
        val now = System.currentTimeMillis()
        val id = memoryDAO.insertMemory(
            MemoryEntity(
                assistantId = assistantId,
                content = content,
                createdAt = now,
                updatedAt = now,
                importance = importance.coerceIn(0f, 1f),
                category = MemoryCategory.normalize(category),
            )
        ).toInt()
        // 极端情况下（并发删除）读回失败时按已知数据构造，不让调用方崩溃
        return memoryDAO.getMemoryById(id)?.toModel() ?: AssistantMemory(
            id = id,
            content = content,
            createdAt = now,
            updatedAt = now,
            importance = importance.coerceIn(0f, 1f),
            category = MemoryCategory.normalize(category),
        )
    }

    suspend fun deleteMemory(id: Int) {
        memoryDAO.deleteMemory(id)
    }

    suspend fun trimMemories(assistantId: String) {
        val all = getMemories(assistantId)
        if (all.size <= MAX_MEMORIES_PER_SCOPE) return
        val now = System.currentTimeMillis()
        val excess = all.size - MAX_MEMORIES_PER_SCOPE
        // 一次删除超过上限的条目，而不是逐条 delete
        val victims = all.sortedBy { memoryScore(it, now) }.take(excess).map { it.id }
        if (victims.isNotEmpty()) memoryDAO.deleteMemories(victims)
    }

    // 打分排序 + 字符预算截断：importance × 时效衰减
    suspend fun selectForPrompt(
        assistantId: String,
        charBudget: Int = PROMPT_MEMORY_CHAR_BUDGET,
    ): List<AssistantMemory> = withContext(Dispatchers.Default) {
        val all = getMemories(assistantId)
        if (all.isEmpty()) return@withContext emptyList()

        val now = System.currentTimeMillis()
        val ranked = all
            .map { it to memoryScore(it, now) }
            .sortedByDescending { it.second }

        val selected = mutableListOf<AssistantMemory>()
        var used = 0
        for ((memory, _) in ranked) {
            val cost = memory.content.length
            if (used + cost > charBudget && selected.isNotEmpty()) continue
            selected += memory
            used += cost
        }
        selected
    }

    private fun memoryScore(memory: AssistantMemory, now: Long): Float {
        val lastActive = if (memory.updatedAt > 0) memory.updatedAt else memory.createdAt
        val age = (now - lastActive).coerceAtLeast(0L)
        val recency = exp(-ln(2.0) * age / RECENCY_HALF_LIFE_MS).toFloat()
        return memory.importance.coerceIn(0f, 1f) * (0.55f + 0.45f * recency)
    }
}

private fun MemoryEntity.toModel() = AssistantMemory(
    id = id,
    content = content,
    createdAt = createdAt,
    updatedAt = updatedAt,
    importance = importance,
    category = category,
)
