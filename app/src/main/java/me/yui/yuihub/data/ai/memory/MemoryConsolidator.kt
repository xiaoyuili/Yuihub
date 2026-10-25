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
import me.yui.yuihub.data.ai.prompts.DEFAULT_MEMORY_CORE_PROMPT
import me.yui.yuihub.data.ai.prompts.PromptId
import me.yui.yuihub.data.ai.prompts.isPromptEnabled
import me.yui.yuihub.data.datastore.Settings
import me.yui.yuihub.data.datastore.findModelById
import me.yui.yuihub.data.datastore.findProvider
import me.yui.yuihub.data.datastore.getCurrentChatModel
import me.yui.yuihub.data.model.AssistantMemory
import me.yui.yuihub.data.model.MemoryCategory
import me.yui.yuihub.data.model.MemoryEventType
import me.yui.yuihub.data.model.MemoryKind
import me.yui.yuihub.data.model.MemorySection
import me.yui.yuihub.data.model.MemoryStatus
import me.yui.yuihub.data.repository.MemoryRepository
import me.yui.yuihub.data.repository.TokenUsageRepository
import me.yui.yuihub.data.repository.TokenUsageSource
import me.yui.yuihub.service.backgroundTextGenerationParams
import me.yui.yuihub.utils.applyPlaceholders
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "MemoryConsolidator"

/**
 * 记忆整理管道：去重合并 → 归档无效条目 → 精简核心记忆。
 * 自动触发按新增量与 24h 冷却节流；手动触发不受限制。
 */
class MemoryConsolidator(
    private val memoryRepository: MemoryRepository,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val tokenUsageRepository: TokenUsageRepository,
) {
    private val runningAssistants = ConcurrentHashMap.newKeySet<String>()

    data class ConsolidationResult(
        val created: Int = 0,
        val removed: Int = 0,
        val archived: Int = 0,
        val coreRewritten: Int = 0,
        val before: Int = 0,
        val after: Int = 0,
    )

    data class OrganizeProgress(
        val stage: Stage,
        val index: Int = 0,
        val total: Int = 0,
    ) {
        enum class Stage { MERGE, ARCHIVE, CORE, DONE }
    }

    suspend fun autoOrganizeIfNeeded(
        assistantId: String,
        settings: Settings,
        coreBudget: Int = MemoryRepository.DEFAULT_CORE_CHAR_BUDGET,
        growthThreshold: Int = MemoryRepository.DEFAULT_AUTO_ORGANIZE_GROWTH,
    ): ConsolidationResult? {
        if (!settings.isPromptEnabled(PromptId.MEMORY_CONSOLIDATE)) return null
        val all = memoryRepository.getMemories(assistantId).filter { it.status != MemoryStatus.ARCHIVED }
        if (all.size < MIN_MEMORIES_TO_CONSOLIDATE) return null
        val coreChars = all.filter { MemoryKind.isCore(it.kind) }.sumOf { it.content.length }
        val needsOrganize = coreChars > coreBudget ||
            all.count { it.status == MemoryStatus.ACTIVE } >= growthThreshold
        if (!needsOrganize) return null
        val last = memoryRepository.lastOrganizedAt(assistantId)
        if (last > 0 && System.currentTimeMillis() - last < AUTO_COOLDOWN_MS) return null
        return organize(assistantId, settings, coreBudget)
    }

    suspend fun consolidate(assistantId: String, settings: Settings): ConsolidationResult =
        organize(assistantId, settings, settings.memoryCoreCharBudget)

    suspend fun organize(
        assistantId: String,
        settings: Settings,
        coreBudget: Int = MemoryRepository.DEFAULT_CORE_CHAR_BUDGET,
        onProgress: (OrganizeProgress) -> Unit = {},
    ): ConsolidationResult = withContext(Dispatchers.IO) {
        if (!settings.isPromptEnabled(PromptId.MEMORY_CONSOLIDATE)) return@withContext ConsolidationResult()
        if (!runningAssistants.add(assistantId)) return@withContext ConsolidationResult()
        try {
            val model = settings.findModelById(settings.memoryMaintenanceModelId)
                ?: settings.getCurrentChatModel()
                ?: return@withContext ConsolidationResult()
            val provider = model.findProvider(settings.providers)
                ?: return@withContext ConsolidationResult()

            val before = memoryRepository.getMemories(assistantId)
            val candidates = before
                .filter { it.status == MemoryStatus.ACTIVE && !MemoryKind.isCore(it.kind) }
                .sortedWith(compareByDescending<AssistantMemory> { it.updatedAt })
                .take(80)
            var mergedCount = 0
            var archivedCount = 0
            var coreRewritten = 0

            if (candidates.size >= MIN_MEMORIES_TO_CONSOLIDATE) {
                onProgress(OrganizeProgress(OrganizeProgress.Stage.MERGE, 0, candidates.size))
                val parsed = requestConsolidation(candidates, model, provider, settings.memoryConsolidatePrompt)
                if (parsed != null) {
                    val consumed = mutableSetOf<Int>()
                    parsed.merged.forEach { merged ->
                        val content = merged.content?.trim().orEmpty()
                        if (content.isBlank()) return@forEach
                        val sources = merged.sources.orEmpty()
                            .filter { id -> candidates.any { it.id == id } && consumed.add(id) }
                            .distinct()
                        if (sources.isEmpty()) return@forEach
                        val first = candidates.first { it.id == sources.first() }
                        memoryRepository.updateMemory(
                            first.copy(
                                title = merged.title?.trim().orEmpty().ifBlank { first.title },
                                content = content,
                                kind = MemoryKind.normalize(merged.kind ?: first.kind),
                                category = MemoryCategory.normalize(merged.category ?: first.category),
                                keywords = merged.keywords?.takeIf { it.isNotEmpty() } ?: first.keywords,
                                weight = (merged.weight ?: first.weight).coerceIn(1, 4),
                                status = MemoryStatus.ACTIVE,
                            )
                        )
                        memoryRepository.archiveMemory(sources.drop(1))
                        mergedCount += 1
                        archivedCount += (sources.size - 1)
                    }
                    parsed.archived.forEach { archived ->
                        val id = archived.id ?: return@forEach
                        if (candidates.none { it.id == id }) return@forEach
                        memoryRepository.archiveMemory(listOf(id))
                        archivedCount += 1
                    }
                }
            }

            onProgress(OrganizeProgress(OrganizeProgress.Stage.ARCHIVE, 0, 1))
            val coreAll = memoryRepository.getMemories(assistantId)
                .filter { it.status == MemoryStatus.ACTIVE && MemoryKind.isCore(it.kind) }
            val coreChars = coreAll.sumOf { it.content.length }
            if (coreAll.isNotEmpty() && coreChars > coreBudget) {
                onProgress(OrganizeProgress(OrganizeProgress.Stage.CORE, 0, coreAll.size))
                coreRewritten = rewriteCore(assistantId, coreAll, coreBudget, model, provider)
            }

            val after = memoryRepository.getMemories(assistantId)
            val result = ConsolidationResult(
                created = mergedCount,
                removed = archivedCount,
                archived = archivedCount,
                coreRewritten = coreRewritten,
                before = before.count { it.status != MemoryStatus.ARCHIVED },
                after = after.count { it.status != MemoryStatus.ARCHIVED },
            )
            memoryRepository.trimMemories(assistantId)
            memoryRepository.recordEvent(
                assistantId = assistantId,
                type = MemoryEventType.ORGANIZE,
                summary = "整理完成：合并 $mergedCount · 归档 $archivedCount · 核心重写 $coreRewritten",
                details = """{"merged":$mergedCount,"archived":$archivedCount,"coreRewritten":$coreRewritten,"before":${result.before},"after":${result.after}}""",
            )
            onProgress(OrganizeProgress(OrganizeProgress.Stage.DONE, 1, 1))
            Log.i(TAG, "organize assistant=$assistantId result=$result")
            result
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "organize failed", e)
            ConsolidationResult()
        } finally {
            runningAssistants.remove(assistantId)
        }
    }

    private suspend fun requestConsolidation(
        memories: List<AssistantMemory>,
        model: Model,
        provider: ProviderSetting,
        template: String,
    ): ParsedConsolidation? {
        val listing = memories.joinToString("\n") { memory ->
            buildString {
                append("id=${memory.id} | ${memory.kind} | weight=${memory.weight}")
                append(" | ${memory.category}")
                if (memory.title.isNotBlank()) append(" | ${memory.title.take(60)}")
                if (memory.keywords.isNotEmpty()) append(" | kw: ${memory.keywords.take(6).joinToString(",")}")
                append("\n  ${memory.content.take(300)}")
            }
        }
        val prompt = template.applyPlaceholders("memories" to listing)
        val parsed = requestJson(prompt, model, provider) ?: return null
        return runCatching { json.decodeFromString<ParsedConsolidation>(parsed) }.getOrNull()
    }

    private suspend fun rewriteCore(
        assistantId: String,
        core: List<AssistantMemory>,
        budget: Int,
        model: Model,
        provider: ProviderSetting,
    ): Int {
        val listing = core.joinToString("\n") { memory ->
            "id=${memory.id} | section=${MemorySection.normalize(memory.section)} | weight=${memory.weight}\n  ${memory.content.take(300)}"
        }
        val prompt = DEFAULT_MEMORY_CORE_PROMPT.applyPlaceholders(
            "budget" to budget.toString(),
            "core_memories" to listing,
        )
        val raw = requestJson(prompt, model, provider) ?: return 0
        val parsed = runCatching { json.decodeFromString<ParsedCore>(raw) }.getOrNull() ?: return 0
        val consumed = mutableSetOf<Int>()
        var written = 0
        parsed.entries.forEach { entry ->
            val content = entry.content?.trim().orEmpty()
            if (content.isBlank()) return@forEach
            val sources = entry.sources.orEmpty()
                .filter { id -> core.any { it.id == id } && consumed.add(id) }
                .distinct()
            val section = MemorySection.normalize(entry.section)
            val first = sources.firstOrNull()?.let { id -> core.first { it.id == id } }
            if (first != null) {
                memoryRepository.updateMemory(
                    first.copy(
                        content = content,
                        kind = MemoryKind.CORE,
                        section = section,
                        weight = maxOf(first.weight, 3),
                        status = MemoryStatus.ACTIVE,
                    )
                )
                memoryRepository.archiveMemory(sources.drop(1))
            } else {
                memoryRepository.createMemory(
                    assistantId = assistantId,
                    content = content,
                    kind = MemoryKind.CORE,
                    section = section,
                    category = MemoryCategory.PROFILE,
                    weight = 3,
                )
            }
            written += 1
        }
        parsed.archived.forEach { archived ->
            val id = archived.id ?: return@forEach
            if (core.any { it.id == id }) {
                memoryRepository.archiveMemory(listOf(id))
            }
        }
        return written
    }

    private suspend fun requestJson(
        prompt: String,
        model: Model,
        provider: ProviderSetting,
    ): String? {
        val handler = providerManager.getProviderByType(provider)
        val result = handler.generateText(
            providerSetting = provider,
            messages = listOf(UIMessage.user(prompt = prompt)),
            params = backgroundTextGenerationParams(model, Uuid.random(), ReasoningLevel.AUTO),
        )
        result.usage?.let { usage ->
            tokenUsageRepository.record(
                conversationId = null,
                provider = provider,
                model = model,
                source = TokenUsageSource.MEMORY,
                usage = usage,
            )
        }
        val raw = result.message.toText().trim()
        val cleaned = raw.substringAfter("```json", raw).substringBefore("```", raw).trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return cleaned.substring(start, end + 1)
    }

    @Serializable
    private data class ParsedConsolidation(
        val merged: List<ParsedMerged> = emptyList(),
        val archived: List<ParsedArchived> = emptyList(),
    )

    @Serializable
    private data class ParsedMerged(
        val title: String? = null,
        val content: String? = null,
        val kind: String? = null,
        val category: String? = null,
        val weight: Int? = null,
        val keywords: List<String>? = null,
        val sources: List<Int>? = null,
    )

    @Serializable
    private data class ParsedArchived(
        val id: Int? = null,
        val reason: String? = null,
    )

    @Serializable
    private data class ParsedCore(
        val entries: List<ParsedCoreEntry> = emptyList(),
        val archived: List<ParsedArchived> = emptyList(),
    )

    @Serializable
    private data class ParsedCoreEntry(
        val section: String? = null,
        val content: String? = null,
        val sources: List<Int>? = null,
    )

    companion object {
        const val MIN_MEMORIES_TO_CONSOLIDATE = 8
        private const val AUTO_COOLDOWN_MS = 24 * 60 * 60 * 1000L
    }
}
