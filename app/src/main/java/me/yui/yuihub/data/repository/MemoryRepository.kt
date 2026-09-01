package me.yui.yuihub.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import me.yui.yuihub.data.ai.memory.EmbeddingService
import me.yui.yuihub.data.datastore.EmbeddingConfig
import me.yui.yuihub.data.db.dao.MemoryDAO
import me.yui.yuihub.data.db.entity.MemoryEntity
import me.yui.yuihub.data.model.AssistantMemory
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

class MemoryRepository(
    private val memoryDAO: MemoryDAO,
    private val embeddingService: EmbeddingService,
) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"

        // 单个助手记忆条数上限，超出时提取管道会淘汰低分记忆
        const val MAX_MEMORIES_PER_SCOPE = 300

        // 注入上下文的记忆内容预算（字符数，约 600 token）
        const val PROMPT_MEMORY_CHAR_BUDGET = 1800

        // 记忆时效半衰期（毫秒）：30 天
        private const val RECENCY_HALF_LIFE_MS = 30L * 24 * 60 * 60 * 1000
    }

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities -> entities.map { it.toModel() } }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> =
        memoryDAO.getMemoriesOfAssistant(assistantId).map { it.toModel() }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)
            .map { entities -> entities.map { it.toModel() } }

    suspend fun getGlobalMemories(): List<AssistantMemory> =
        memoryDAO.getMemoriesOfAssistant(GLOBAL_MEMORY_ID).map { it.toModel() }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
    }

    suspend fun updateContent(id: Int, content: String): AssistantMemory {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        val now = System.currentTimeMillis()
        // 内容变更后旧向量失效，清空待重新计算
        val newMemory = old.copy(content = content, updatedAt = now, embedding = null)
        memoryDAO.updateMemory(newMemory)
        return newMemory.toModel()
    }

    suspend fun addMemory(
        assistantId: String,
        content: String,
        importance: Float = 0.6f,
    ): AssistantMemory {
        val now = System.currentTimeMillis()
        val id = memoryDAO.insertMemory(
            MemoryEntity(
                assistantId = assistantId,
                content = content,
                createdAt = now,
                updatedAt = now,
                importance = importance.coerceIn(0f, 1f),
            )
        ).toInt()
        return memoryDAO.getMemoryById(id)!!.toModel()
    }

    suspend fun deleteMemory(id: Int) {
        memoryDAO.deleteMemory(id)
    }

    suspend fun trimMemories(assistantId: String) {
        val all = getMemoriesOfAssistant(assistantId)
        if (all.size <= MAX_MEMORIES_PER_SCOPE) return
        val now = System.currentTimeMillis()
        val excess = all.size - MAX_MEMORIES_PER_SCOPE
        all.sortedBy { memoryScore(it, now) }
            .take(excess)
            .forEach { memoryDAO.deleteMemory(it.id) }
    }

    // 检索命中后更新访问统计（异步批量）
    suspend fun markAccessed(memories: List<AssistantMemory>) {
        if (memories.isEmpty()) return
        memoryDAO.updateAccessStats(memories.map { it.id }, System.currentTimeMillis())
    }

    // 打分排序 + token 预算截断：importance × 时效衰减 × (1 + 关键词匹配 + 语义相似度)
    suspend fun selectForPrompt(
        assistantId: String,
        query: String,
        charBudget: Int = PROMPT_MEMORY_CHAR_BUDGET,
        embeddingConfig: EmbeddingConfig = EmbeddingConfig(),
    ): List<AssistantMemory> = withContext(Dispatchers.Default) {
        val all = if (assistantId == GLOBAL_MEMORY_ID) getGlobalMemories() else getMemoriesOfAssistant(assistantId)
        if (all.isEmpty()) return@withContext emptyList()

        val now = System.currentTimeMillis()
        val queryTokens = tokenize(query)
        // 语义检索：embedding 配置了就求一次查询向量，失败自动降级
        val queryEmbedding = if (embeddingService.isConfigured(embeddingConfig) && query.isNotBlank()) {
            embeddingService.embedText(embeddingConfig, query)
        } else null

        val ranked = all
            .map { it to memoryScore(it, now, queryTokens, queryEmbedding) }
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

    private fun memoryScore(
        memory: AssistantMemory,
        now: Long,
        queryTokens: Set<String> = emptySet(),
        queryEmbedding: FloatArray? = null,
    ): Float {
        val lastActive = if (memory.updatedAt > 0) memory.updatedAt else memory.createdAt
        val age = (now - lastActive).coerceAtLeast(0L)
        val recency = exp(-ln(2.0) * age / RECENCY_HALF_LIFE_MS).toFloat()
        val overlap = if (queryTokens.isEmpty()) {
            0f
        } else {
            val contentTokens = tokenize(memory.content)
            if (contentTokens.isEmpty()) 0f
            else queryTokens.count { it in contentTokens }.toFloat() / queryTokens.size
        }
        val semantic = cosineSimilarity(memory.embedding, queryEmbedding)
        return memory.importance.coerceIn(0f, 1f) *
            (0.55f + 0.45f * recency) *
            (1f + 0.3f * overlap + 0.6f * semantic)
    }

    // 批量补齐缺失的记忆向量（提取管道写入后调用）
    suspend fun refreshEmbeddings(assistantId: String, config: EmbeddingConfig) =
        withContext(Dispatchers.Default) {
            if (!embeddingService.isConfigured(config)) return@withContext
            val missing = memoryDAO.getMemoriesWithoutEmbedding(assistantId)
            missing.chunked(32).forEach { chunk ->
                // 失败只放弃当前 batch, 继续补齐剩余记忆
                val vectors = embeddingService.embedTexts(config, chunk.map { it.content }) ?: return@forEach
                vectors.forEachIndexed { index, vector ->
                    if (vector.isNotEmpty()) {
                        memoryDAO.updateEmbedding(chunk[index].id, vector.toJsonArray())
                    }
                }
            }
        }

    private fun cosineSimilarity(embeddingJson: String?, queryEmbedding: FloatArray?): Float {
        val a = queryEmbedding ?: return 0f
        val b = embeddingJson?.let { parseEmbedding(it) } ?: return 0f
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA <= 0.0 || normB <= 0.0) return 0f
        return (dot / (sqrt(normA) * sqrt(normB))).toFloat().coerceIn(0f, 1f)
    }

    // 混合分词：英文/数字单词 + 中文二元组
    private fun tokenize(text: String): Set<String> {
        val result = mutableSetOf<String>()
        Regex("[a-zA-Z0-9]+").findAll(text).forEach { m ->
            val w = m.value.lowercase()
            if (w.length >= 2) result.add(w)
        }
        val cjk = text.filter { it in '\u4e00'..'\u9fff' || it in '\u3040'..'\u30ff' || it in '\uac00'..'\ud7af' }
        if (cjk.length >= 2) {
            for (i in 0 until cjk.length - 1) {
                result.add(cjk.substring(i, i + 2))
            }
        } else if (cjk.length == 1) {
            result.add(cjk)
        }
        return result
    }
}

private fun MemoryEntity.toModel() = AssistantMemory(
    id = id,
    content = content,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastAccessedAt = lastAccessedAt,
    accessCount = accessCount,
    importance = importance,
    embedding = embedding,
)

private fun FloatArray.toJsonArray(): String =
    joinToString(prefix = "[", postfix = "]") { it.toString() }

private fun parseEmbedding(json: String): FloatArray? = runCatching {
    Json.parseToJsonElement(json).jsonArray
        .mapNotNull { it.jsonPrimitive.contentOrNull?.toFloatOrNull() }
        .toFloatArray()
}.getOrNull()
