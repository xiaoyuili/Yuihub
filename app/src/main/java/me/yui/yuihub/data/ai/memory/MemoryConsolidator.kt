package me.yui.yuihub.data.ai.memory

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.yui.yuihub.data.datastore.Settings
import me.yui.yuihub.data.datastore.findProvider
import me.yui.yuihub.data.datastore.getCurrentChatModel
import me.yui.yuihub.data.model.AssistantMemory
import me.yui.yuihub.data.model.MemoryCategory
import me.yui.yuihub.data.repository.MemoryRepository
import me.yui.yuihub.service.backgroundTextGenerationParams
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "MemoryConsolidator"

/**
 * 记忆整理管道：把重复、相似、零散的记忆合并成更精炼的一条，
 * 同时修正归类错误的类别，控制注入上下文的体积。
 *
 * 自动触发按「新增量」节流（见 [autoConsolidateIfNeeded]），
 * 用户也可在记忆页手动触发（[consolidate]）。
 */
class MemoryConsolidator(
    private val memoryRepository: MemoryRepository,
    private val providerManager: ProviderManager,
    private val json: Json,
) {
    // 上次整理时的记忆总数：自动整理按「自上次整理后新增量」节流，避免频繁调用模型
    private val lastConsolidatedSize = ConcurrentHashMap<String, Int>()

    // 单飞集合：手动整理与自动整理可能并发，同一助手只允许一个整理在跑
    private val runningAssistants = ConcurrentHashMap.newKeySet<String>()

    data class ConsolidationResult(
        val created: Int,
        val removed: Int,
    )

    // 自动整理入口：记忆总量达到下限、且自上次整理后新增达到阈值时才执行
    suspend fun autoConsolidateIfNeeded(
        assistantId: String,
        settings: Settings,
    ): ConsolidationResult? {
        val currentCount = memoryRepository.getMemories(assistantId).size
        if (currentCount < AUTO_CONSOLIDATE_MIN_MEMORIES) return null
        val last = lastConsolidatedSize[assistantId] ?: 0
        if (currentCount - last < AUTO_CONSOLIDATE_GROWTH) return null
        return consolidate(assistantId, settings)
    }

    suspend fun consolidate(assistantId: String, settings: Settings): ConsolidationResult =
        withContext(Dispatchers.IO) {
            if (!runningAssistants.add(assistantId)) {
                // 已有整理在跑（手动与自动整理可能并发）：跳过本次，避免重复合并
                return@withContext ConsolidationResult(0, 0)
            }
            try {
                val model = settings.getCurrentChatModel()
                    ?: return@withContext ConsolidationResult(0, 0)
                val provider = model.findProvider(settings.providers)
                    ?: return@withContext ConsolidationResult(0, 0)

                val all = memoryRepository.getMemories(assistantId)
                if (all.size < MIN_MEMORIES_TO_CONSOLIDATE) return@withContext ConsolidationResult(0, 0)

                val merge = mergeMemories(all, model, provider)
                    ?: return@withContext ConsolidationResult(0, 0)

                var created = 0
                var removed = 0
                val consumed = mutableSetOf<Int>()
                merge.forEach { merged ->
                    val content = merged.content?.trim().orEmpty()
                    if (content.isBlank()) return@forEach
                    // 只消费真实存在、且未被其他组合并过的源记忆，防幻觉 id 误删
                    val sources = merged.sources.orEmpty()
                        .filter { id -> all.any { it.id == id } && consumed.add(id) }
                        .distinct()
                    if (sources.isEmpty()) return@forEach
                    val importance = sources
                        .mapNotNull { id -> all.find { it.id == id }?.importance }
                        .maxOrNull() ?: 0.6f
                    // 先落库再删除源，避免中途失败造成信息丢失
                    memoryRepository.addMemory(
                        assistantId = assistantId,
                        content = content,
                        importance = importance,
                        category = MemoryCategory.normalize(merged.category),
                    )
                    created += 1
                    sources.forEach { id ->
                        memoryRepository.deleteMemory(id)
                        removed += 1
                    }
                }
                memoryRepository.trimMemories(assistantId)
                // 模型调用已发生（无论有无可合并项），记录基线供自动整理节流
                lastConsolidatedSize[assistantId] = memoryRepository.getMemories(assistantId).size
                Log.i(TAG, "consolidate assistant=$assistantId created=$created removed=$removed")
                ConsolidationResult(created, removed)
            } finally {
                runningAssistants.remove(assistantId)
            }
        }

    private suspend fun mergeMemories(
        memories: List<AssistantMemory>,
        model: Model,
        provider: ProviderSetting,
    ): List<ParsedMerged>? {
        val listing = memories.joinToString("\n") { m ->
            "id=${m.id} | [${m.category}] ${m.content.take(200)}"
        }
        val prompt = """
            You are consolidating the long-term memory store of a personal AI assistant.
            Below is the full list of stored memories (id | category | content).
            Find memories that overlap, duplicate, or describe the same thing, and merge each group into ONE more concise memory that keeps every distinct fact.
            Also fix wrong categories and tighten wording — a group may contain a single memory if it only needs re-categorization or rewriting.

            Categories:
            - profile: identity and stable facts about the user
            - preference: how the user likes things (communication style, tools, likes/dislikes)
            - coding: software development knowledge — projects, tech stack, conventions, debugging context
            - roleplay: roleplay/persona settings and in-character rules
            - daily: everyday life — schedules, plans, routines
            - temporary: short-lived context likely to go stale soon
            - other: anything that fits nowhere else

            Rules:
            - Only merge memories that truly overlap. Do not merge unrelated ones.
            - Every source memory you consumed MUST be listed in "sources".
            - Keep every distinct fact and constraint from the sources in the merged memory.
            - Write in the same language as the sources. Keep each merged memory concise.
            - Do not invent memories with no source.

            <memories>
            $listing
            </memories>

            Respond with ONLY a JSON object:
            {"merged":[{"content":"...","category":"profile","sources":[1,2]}]}
            If nothing needs merging or fixing, respond with {"merged":[]}.
        """.trimIndent()

        val handler = providerManager.getProviderByType(provider)
        val result = handler.generateText(
            providerSetting = provider,
            messages = listOf(UIMessage.user(prompt = prompt)),
            params = backgroundTextGenerationParams(model, ReasoningLevel.AUTO),
        )
        val parsed = parse(result.message.toText().trim()) ?: return null
        return parsed.merged.filter { !it.content.isNullOrBlank() && !it.sources.isNullOrEmpty() }
    }

    private fun parse(raw: String): ParsedConsolidation? {
        val cleaned = raw
            .substringAfter("```json", raw)
            .substringBefore("```", raw)
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            json.decodeFromString<ParsedConsolidation>(cleaned.substring(start, end + 1))
        }.getOrNull()
    }

    @Serializable
    private data class ParsedConsolidation(
        val merged: List<ParsedMerged> = emptyList(),
    )

    @Serializable
    private data class ParsedMerged(
        val content: String? = null,
        val category: String? = null,
        val sources: List<Int>? = null,
    )

    companion object {
        // 手动整理：条目达到该数量才值得调用模型
        const val MIN_MEMORIES_TO_CONSOLIDATE = 8

        // 自动整理：记忆总量下限
        private const val AUTO_CONSOLIDATE_MIN_MEMORIES = 30

        // 自动整理：自上次整理后新增量阈值
        private const val AUTO_CONSOLIDATE_GROWTH = 10
    }
}
