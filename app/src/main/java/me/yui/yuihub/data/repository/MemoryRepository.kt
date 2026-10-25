package me.yui.yuihub.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.yui.yuihub.data.ai.memory.MemoryText
import me.yui.yuihub.data.db.dao.MemoryDAO
import me.yui.yuihub.data.db.dao.MemoryEventDAO
import me.yui.yuihub.data.db.dao.MemorySessionDAO
import me.yui.yuihub.data.db.dao.MemoryUsageDAO
import me.yui.yuihub.data.db.entity.MemoryEntity
import me.yui.yuihub.data.db.entity.MemoryEventEntity
import me.yui.yuihub.data.db.entity.MemorySessionEntity
import me.yui.yuihub.data.db.entity.MemoryUsageEntity
import me.yui.yuihub.data.model.AssistantMemory
import me.yui.yuihub.data.model.MemoryCategory
import me.yui.yuihub.data.model.MemoryEvent
import me.yui.yuihub.data.model.MemoryEventType
import me.yui.yuihub.data.model.MemoryKind
import me.yui.yuihub.data.model.MemoryScope
import me.yui.yuihub.data.model.MemorySection
import me.yui.yuihub.data.model.MemoryStats
import me.yui.yuihub.data.model.MemoryStatus
import kotlin.math.exp
import kotlin.math.ln

class MemoryRepository(
    private val memoryDAO: MemoryDAO,
    private val eventDAO: MemoryEventDAO,
    private val usageDAO: MemoryUsageDAO,
    private val sessionDAO: MemorySessionDAO,
) {
    companion object {
        const val MAX_MEMORIES_PER_SCOPE = 300
        const val DEFAULT_CORE_CHAR_BUDGET = 1200
        const val DEFAULT_HIT_CHAR_BUDGET = 440
        const val DEFAULT_AUTO_ORGANIZE_GROWTH = 20

        private const val RECENCY_HALF_LIFE_MS = 45L * 24 * 60 * 60 * 1000
        private const val DEDUP_THRESHOLD = 0.8f
    }

    data class CoreBlock(
        val text: String,
        val charCount: Int,
        val hash: String,
        val sectionCounts: Map<String, Int>,
        val projectTitles: List<String>,
        val truncated: Boolean,
        val ids: List<Int>,
    )

    data class WriteResult(
        val memory: AssistantMemory,
        val merged: Boolean,
    )

    private val json = Json { ignoreUnknownKeys = true }

    // ---- 读取 ----

    fun getMemoriesFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities -> entities.map { it.toModel() } }

    suspend fun getMemories(assistantId: String): List<AssistantMemory> =
        memoryDAO.getMemoriesOfAssistant(assistantId).map { it.toModel() }

    suspend fun getMemory(id: Int): AssistantMemory? =
        memoryDAO.getMemoryById(id)?.toModel()

    suspend fun getMemoriesByIds(ids: List<Int>): List<AssistantMemory> =
        if (ids.isEmpty()) emptyList() else memoryDAO.getMemoriesByIds(ids).map { it.toModel() }

    fun eventsFlow(assistantId: String, limit: Int = 40): Flow<List<MemoryEvent>> =
        eventDAO.flowOfAssistant(assistantId, limit).map { list ->
            list.map { MemoryEvent(it.id, it.type, it.summary, it.details, it.createdAt) }
        }

    fun statsFlow(
        assistantId: String,
        coreBudget: Int = DEFAULT_CORE_CHAR_BUDGET,
        growthThreshold: Int = DEFAULT_AUTO_ORGANIZE_GROWTH,
    ): Flow<MemoryStats> = combine(
        getMemoriesFlow(assistantId),
        eventsFlow(assistantId, 20),
    ) { memories, events ->
        val active = memories.filter { it.status == MemoryStatus.ACTIVE }
        val core = active.filter { MemoryKind.isCore(it.kind) }
        val coreChars = core.sumOf { it.content.length }
        val lastOrganizedAt = events.firstOrNull { it.type == MemoryEventType.ORGANIZE }?.createdAt ?: 0L
        MemoryStats(
            total = memories.size,
            active = active.size,
            stale = memories.count { it.status == MemoryStatus.STALE },
            archived = memories.count { it.status == MemoryStatus.ARCHIVED },
            byKind = active.groupingBy { it.kind }.eachCount(),
            coreChars = coreChars,
            coreCount = core.size,
            pendingOrganize = coreChars > coreBudget ||
                memories.count { it.status == MemoryStatus.STALE } > 0 ||
                (active.size >= growthThreshold &&
                    System.currentTimeMillis() - lastOrganizedAt > 24 * 60 * 60 * 1000L),
            lastOrganizedAt = lastOrganizedAt,
        )
    }

    // ---- 核心块 & 召回 ----

    suspend fun buildCoreBlock(
        assistantId: String,
        workspaceId: String? = null,
        charBudget: Int = DEFAULT_CORE_CHAR_BUDGET,
    ): CoreBlock? = withContext(Dispatchers.Default) {
        val all = getMemories(assistantId).filter { it.status == MemoryStatus.ACTIVE }
        val core = all.filter { MemoryKind.isCore(it.kind) }
            .sortedWith(
                compareBy<AssistantMemory> { MemorySection.ALL.indexOf(MemorySection.normalize(it.section)) }
                    .thenByDescending { it.weight }
                    .thenByDescending { it.updatedAt }
            )
        val projects = all.filter { it.kind == MemoryKind.PROJECT }
            .filter { it.scope == MemoryScope.ASSISTANT || workspaceId == null || it.workspaceId == workspaceId }
            .sortedByDescending { it.updatedAt }
            .take(8)

        if (core.isEmpty() && projects.isEmpty()) return@withContext null

        val builder = StringBuilder()
        var truncated = false
        val sectionCounts = sortedMapOf<String, Int>()
        fun append(line: String): Boolean {
            if (builder.length + line.length > charBudget) {
                truncated = true
                return false
            }
            builder.appendLine(line)
            return true
        }
        val sectionLabels = mapOf(
            MemorySection.USER to "U:",
            MemorySection.RULES to "R:",
            MemorySection.AI to "A:",
        )
        MemorySection.ALL.forEach { section ->
            val entries = core.filter { MemorySection.normalize(it.section) == section }
            if (entries.isEmpty()) return@forEach
            sectionCounts[section] = entries.size
            if (!append(sectionLabels.getValue(section))) return@forEach
            for (entry in entries) {
                val line = "- ${entry.content.replace("\n", " ").trim()}"
                if (!append(line)) break
            }
        }
        if (projects.isNotEmpty()) {
            append("P:")
            append(projects.take(5).joinToString(" / ") { it.displayTitle.take(30) })
        }
        if (builder.isBlank()) return@withContext null
        val text = builder.toString().trimEnd()
        CoreBlock(
            text = text,
            charCount = text.length,
            hash = Integer.toHexString(text.hashCode()) + "-" + text.length,
            sectionCounts = sectionCounts,
            projectTitles = projects.map { it.displayTitle },
            truncated = truncated,
            ids = core.map { it.id },
        )
    }

    suspend fun selectHits(
        assistantId: String,
        conversationId: String,
        workspaceId: String? = null,
        query: String,
        topK: Int = 2,
        charBudget: Int = DEFAULT_HIT_CHAR_BUDGET,
    ): List<AssistantMemory> = withContext(Dispatchers.Default) {
        if (query.isBlank() || topK <= 0) return@withContext emptyList()
        val seen = seenIds(conversationId).toSet()
        val candidates = getMemories(assistantId).filter { memory ->
            memory.status == MemoryStatus.ACTIVE &&
                MemoryKind.isRetrievable(memory.kind) &&
                (memory.scope == MemoryScope.ASSISTANT || workspaceId == null || memory.workspaceId == workspaceId) &&
                memory.sourceConversationId != conversationId &&
                memory.id !in seen
        }
        val ranked = MemoryText.rankHits(
            query = query.take(500),
            candidates = candidates,
            workspaceId = workspaceId,
            topK = topK,
        )
        val selected = mutableListOf<AssistantMemory>()
        var used = 0
        for (memory in ranked) {
            val cost = memory.content.length + memory.displayTitle.length + 16
            if (selected.isNotEmpty() && used + cost > charBudget) continue
            selected += memory
            used += cost
        }
        selected
    }

    suspend fun searchMemories(
        assistantId: String,
        query: String,
        workspaceId: String? = null,
        topK: Int = 8,
    ): List<AssistantMemory> = withContext(Dispatchers.Default) {
        val candidates = getMemories(assistantId).filter {
            it.status == MemoryStatus.ACTIVE &&
                !MemoryKind.isCore(it.kind) &&
                (it.scope == MemoryScope.ASSISTANT || workspaceId == null || it.workspaceId == workspaceId)
        }
        MemoryText.rankHits(query, candidates, workspaceId = workspaceId, topK = topK)
    }

    // ---- 使用痕迹 / 会话状态 ----

    suspend fun recordUsage(
        assistantId: String,
        conversationId: String,
        memoryIds: List<Int>,
        source: String,
    ) {
        if (memoryIds.isEmpty()) return
        val now = System.currentTimeMillis()
        usageDAO.insertAll(
            memoryIds.distinct().map { id ->
                MemoryUsageEntity(
                    assistantId = assistantId,
                    conversationId = conversationId,
                    memoryId = id,
                    source = source,
                    createdAt = now,
                )
            }
        )
        if (source == "injected") memoryDAO.markInjected(memoryIds.distinct(), now)
    }

    suspend fun seenIds(conversationId: String): List<Int> =
        usageDAO.memoryIdsOf(conversationId, listOf("injected", "searched"))

    suspend fun writtenIds(conversationId: String): List<Int> =
        usageDAO.memoryIdsOf(conversationId, listOf("written"))

    suspend fun clearSeen(conversationId: String) {
        usageDAO.clearSeenOfConversation(conversationId)
    }

    suspend fun getSession(conversationId: String): MemorySessionEntity? =
        sessionDAO.get(conversationId)

    suspend fun setCoreHash(conversationId: String, hash: String) {
        val old = sessionDAO.get(conversationId)
        sessionDAO.upsert(
            (old ?: MemorySessionEntity(conversationId)).copy(
                lastCoreHash = hash,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun setReinjectPending(conversationId: String, pending: Boolean = true) {
        val old = sessionDAO.get(conversationId)
        sessionDAO.upsert(
            (old ?: MemorySessionEntity(conversationId)).copy(
                reinjectPending = pending,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun isReinjectPending(conversationId: String): Boolean =
        sessionDAO.get(conversationId)?.reinjectPending == true

    suspend fun clearReinjectPending(conversationId: String) {
        val old = sessionDAO.get(conversationId)
        if (old == null) {
            sessionDAO.upsert(MemorySessionEntity(conversationId, updatedAt = System.currentTimeMillis()))
        } else {
            sessionDAO.clearReinjectPending(conversationId, System.currentTimeMillis())
        }
    }

    // ---- 写入 ----

    suspend fun createMemory(
        assistantId: String,
        content: String,
        title: String = "",
        kind: String = MemoryKind.FACT,
        section: String = "",
        category: String = MemoryCategory.OTHER,
        keywords: List<String> = emptyList(),
        weight: Int = 2,
        status: String = MemoryStatus.ACTIVE,
        scope: String = MemoryScope.ASSISTANT,
        workspaceId: String? = null,
        sourceConversationId: String? = null,
    ): WriteResult {
        val cleanContent = content.trim()
        val normalizedKind = MemoryKind.normalize(kind)
        val normalizedScope = MemoryScope.normalize(scope)
        val normalizedStatus = MemoryStatus.normalize(status)
        val normalizedWeight = weight.coerceIn(1, 4)
        val cleanKeywords = keywords.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .ifEmpty { MemoryText.extractKeywords(title + " " + cleanContent) }

        val similar = getMemories(assistantId).firstOrNull { existing ->
            existing.status != MemoryStatus.ARCHIVED &&
                existing.kind == normalizedKind &&
                existing.scope == normalizedScope &&
                existing.workspaceId == workspaceId &&
                MemoryText.similarity(existing.title + " " + existing.content, title + " " + cleanContent) >= DEDUP_THRESHOLD
        }
        if (similar != null) {
            val mergedKeywords = (similar.keywords + cleanKeywords).distinct().take(12)
            val merged = similar.copy(
                title = title.ifBlank { similar.title },
                content = cleanContent,
                category = MemoryCategory.normalize(category),
                keywords = mergedKeywords,
                weight = maxOf(similar.weight, normalizedWeight),
                status = MemoryStatus.ACTIVE,
                section = if (normalizedKind == MemoryKind.CORE) {
                    MemorySection.normalize(section.ifBlank { similar.section })
                } else "",
                updatedAt = System.currentTimeMillis(),
                archivedAt = null,
            )
            updateMemory(merged)
            recordWritten(assistantId, sourceConversationId, merged.id)
            return WriteResult(merged, true)
        }

        val now = System.currentTimeMillis()
        val id = memoryDAO.insertMemory(
            MemoryEntity(
                assistantId = assistantId,
                content = cleanContent,
                createdAt = now,
                updatedAt = now,
                category = MemoryCategory.normalize(category),
                title = title.trim(),
                kind = normalizedKind,
                section = if (normalizedKind == MemoryKind.CORE) MemorySection.normalize(section) else "",
                keywords = json.encodeToString(ListSerializerHolder.serializer, cleanKeywords),
                weight = normalizedWeight,
                status = normalizedStatus,
                scope = normalizedScope,
                workspaceId = if (normalizedScope == MemoryScope.WORKSPACE) workspaceId else null,
                sourceConversationId = sourceConversationId,
            )
        ).toInt()
        recordWritten(assistantId, sourceConversationId, id)
        trimMemories(assistantId)
        val created = memoryDAO.getMemoryById(id)?.toModel() ?: AssistantMemory(
            id = id,
            title = title,
            content = cleanContent,
            createdAt = now,
            updatedAt = now,
            kind = normalizedKind,
            section = section,
            category = MemoryCategory.normalize(category),
            keywords = cleanKeywords,
            weight = normalizedWeight,
            status = normalizedStatus,
            scope = normalizedScope,
            workspaceId = workspaceId,
            sourceConversationId = sourceConversationId,
        )
        return WriteResult(created, false)
    }

    suspend fun updateMemory(memory: AssistantMemory): AssistantMemory? {
        val oldEntity = memoryDAO.getMemoryById(memory.id) ?: return null
        val updated = oldEntity.copy(
            title = memory.title.trim(),
            content = memory.content.trim(),
            category = MemoryCategory.normalize(memory.category),
            kind = MemoryKind.normalize(memory.kind),
            section = if (memory.kind == MemoryKind.CORE) MemorySection.normalize(memory.section) else "",
            keywords = json.encodeToString(ListSerializerHolder.serializer, memory.keywords),
            weight = memory.weight.coerceIn(1, 4),
            status = MemoryStatus.normalize(memory.status),
            scope = MemoryScope.normalize(memory.scope),
            workspaceId = if (memory.scope == MemoryScope.WORKSPACE) memory.workspaceId else null,
            archivedAt = if (memory.status == MemoryStatus.ARCHIVED) {
                memory.archivedAt ?: System.currentTimeMillis()
            } else null,
            updatedAt = System.currentTimeMillis(),
        )
        memoryDAO.updateMemory(updated)
        return updated.toModel()
    }

    suspend fun archiveMemory(ids: List<Int>) {
        if (ids.isEmpty()) return
        memoryDAO.setStatus(ids, MemoryStatus.ARCHIVED, System.currentTimeMillis(), System.currentTimeMillis())
    }

    suspend fun restoreMemory(ids: List<Int>) {
        if (ids.isEmpty()) return
        memoryDAO.setStatus(ids, MemoryStatus.ACTIVE, null, System.currentTimeMillis())
    }

    suspend fun setStatus(ids: List<Int>, status: String) {
        if (ids.isEmpty()) return
        val normalized = MemoryStatus.normalize(status)
        memoryDAO.setStatus(
            ids = ids,
            status = normalized,
            archivedAt = if (normalized == MemoryStatus.ARCHIVED) System.currentTimeMillis() else null,
            now = System.currentTimeMillis(),
        )
    }

    suspend fun promoteToCore(id: Int, section: String) {
        val memory = getMemory(id) ?: return
        updateMemory(
            memory.copy(
                kind = MemoryKind.CORE,
                section = MemorySection.normalize(section),
                weight = maxOf(memory.weight, 3),
                status = MemoryStatus.ACTIVE,
            )
        )
    }

    suspend fun demoteToFact(id: Int) {
        val memory = getMemory(id) ?: return
        updateMemory(memory.copy(kind = MemoryKind.FACT, section = ""))
    }

    suspend fun mergeMemories(ids: List<Int>, content: String, title: String = ""): AssistantMemory? {
        val sources = getMemoriesByIds(ids)
        if (sources.isEmpty()) return null
        val first = sources.first()
        val mergedKeywords = sources.flatMap { it.keywords }.distinct().take(12)
            .ifEmpty { MemoryText.extractKeywords(content) }
        val target = updateMemory(
            first.copy(
                title = title.ifBlank { first.title },
                content = content.trim(),
                keywords = mergedKeywords,
                weight = sources.maxOf { it.weight },
                status = MemoryStatus.ACTIVE,
                updatedAt = System.currentTimeMillis(),
            )
        ) ?: return null
        val rest = sources.map { it.id }.filter { it != first.id }
        archiveMemory(rest)
        return target
    }

    suspend fun deleteMemory(id: Int) {
        memoryDAO.deleteMemory(id)
    }

    suspend fun deleteArchived(assistantId: String) {
        memoryDAO.deleteArchivedOfAssistant(assistantId)
    }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
        eventDAO.deleteOfAssistant(assistantId)
        usageDAO.deleteOfAssistant(assistantId)
    }

    suspend fun copyMemories(fromAssistantId: String, toAssistantId: String) {
        val memories = memoryDAO.getMemoriesOfAssistant(fromAssistantId)
        if (memories.isEmpty()) return
        val now = System.currentTimeMillis()
        memoryDAO.insertMemories(
            memories.map { memory ->
                memory.copy(
                    id = 0,
                    assistantId = toAssistantId,
                    sourceConversationId = null,
                    createdAt = now,
                    updatedAt = now,
                )
            }
        )
    }

    suspend fun trimMemories(assistantId: String) {
        val all = getMemories(assistantId)
        if (all.size <= MAX_MEMORIES_PER_SCOPE) return
        val now = System.currentTimeMillis()
        val excess = all.size - MAX_MEMORIES_PER_SCOPE
        val victims = all
            .filter { it.status != MemoryStatus.ARCHIVED }
            .sortedBy { memoryScore(it, now) }
            .take(excess)
            .map { it.id }
        if (victims.isNotEmpty()) memoryDAO.deleteMemories(victims)
    }

    // ---- 事件 / 导出 ----

    suspend fun recordEvent(
        assistantId: String,
        type: String,
        summary: String,
        details: String = "",
    ) {
        eventDAO.insert(
            MemoryEventEntity(
                assistantId = assistantId,
                type = type,
                summary = summary,
                details = details,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun lastOrganizedAt(assistantId: String): Long =
        eventDAO.lastAt(assistantId, MemoryEventType.ORGANIZE) ?: 0L

    suspend fun exportMarkdown(assistantId: String): String = withContext(Dispatchers.Default) {
        val all = getMemories(assistantId)
        val builder = StringBuilder("# Memory\n\n")
        val core = all.filter { it.kind == MemoryKind.CORE && it.status == MemoryStatus.ACTIVE }
        if (core.isNotEmpty()) {
            builder.appendLine("## Core")
            MemorySection.ALL.forEach { section ->
                val entries = core.filter { MemorySection.normalize(it.section) == section }
                if (entries.isEmpty()) return@forEach
                builder.appendLine("### ${section.replaceFirstChar { it.uppercase() }}")
                entries.forEach { builder.appendLine("- ${it.content}") }
                builder.appendLine()
            }
        }
        MemoryKind.ALL.filter { it != MemoryKind.CORE }.forEach { kind ->
            val entries = all.filter { it.kind == kind && it.status == MemoryStatus.ACTIVE }
            if (entries.isEmpty()) return@forEach
            builder.appendLine("## ${kind.replaceFirstChar { it.uppercase() }}")
            entries.forEach { memory ->
                builder.appendLine("- **${memory.displayTitle}**")
                builder.appendLine("  ${memory.content.replace("\n", "\n  ")}")
                if (memory.keywords.isNotEmpty()) {
                    builder.appendLine("  ${memory.keywords.joinToString(" ") { "#$it" }}")
                }
            }
            builder.appendLine()
        }
        builder.toString()
    }

    private suspend fun recordWritten(assistantId: String, conversationId: String?, memoryId: Int) {
        if (conversationId == null) return
        recordUsage(assistantId, conversationId, listOf(memoryId), "written")
    }

    private fun memoryScore(memory: AssistantMemory, now: Long): Float {
        val lastActive = if (memory.updatedAt > 0) memory.updatedAt else memory.createdAt
        val age = (now - lastActive).coerceAtLeast(0L)
        val recency = exp(-ln(2.0) * age / RECENCY_HALF_LIFE_MS).toFloat()
        return memory.weight.coerceIn(1, 4) * (0.55f + 0.45f * recency) + memory.hitCount * 0.01f
    }
}

private object ListSerializerHolder {
    val serializer = ListSerializer(String.serializer())
}

private fun MemoryEntity.toModel(): AssistantMemory = AssistantMemory(
    id = id,
    title = title,
    content = content,
    createdAt = createdAt,
    updatedAt = updatedAt,
    kind = kind,
    section = section,
    category = category,
    keywords = runCatching {
        Json.decodeFromString(ListSerializerHolder.serializer, keywords)
    }.getOrDefault(emptyList()),
    weight = weight,
    status = status,
    scope = scope,
    workspaceId = workspaceId,
    sourceConversationId = sourceConversationId,
    hitCount = hitCount,
    lastInjectedAt = lastInjectedAt,
    archivedAt = archivedAt,
)
