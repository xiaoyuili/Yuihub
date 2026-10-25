package me.yui.yuihub.data.ai.memory

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessage
import me.yui.yuihub.data.ai.prompts.PromptId
import me.yui.yuihub.data.ai.prompts.isMemorySnapshot
import me.yui.yuihub.data.ai.prompts.isPromptEnabled
import me.yui.yuihub.data.datastore.Settings
import me.yui.yuihub.data.datastore.SettingsStore
import me.yui.yuihub.data.datastore.findModelById
import me.yui.yuihub.data.datastore.findProvider
import me.yui.yuihub.data.datastore.getAssistantById
import me.yui.yuihub.data.datastore.getCurrentChatModel
import me.yui.yuihub.data.model.Assistant
import me.yui.yuihub.data.model.AssistantMemory
import me.yui.yuihub.data.model.MemoryCategory
import me.yui.yuihub.data.model.MemoryEventType
import me.yui.yuihub.data.model.MemoryKind
import me.yui.yuihub.data.model.MemorySection
import me.yui.yuihub.data.model.MemoryStatus
import me.yui.yuihub.data.repository.ConversationRepository
import me.yui.yuihub.data.repository.MemoryRepository
import me.yui.yuihub.data.repository.TokenUsageRepository
import me.yui.yuihub.data.repository.TokenUsageSource
import me.yui.yuihub.service.backgroundTextGenerationParams
import me.yui.yuihub.utils.applyPlaceholders
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "MemoryExtractor"

/**
 * 长期记忆提取管道：每次回复生成完成后异步运行。
 * 与模型主动调用 memory_tool 互补：本管道被动、可靠地提取事实，
 * 在提示词中携带紧凑记忆索引，由 LLM 输出 create/update/delete 操作。
 */
class MemoryExtractor(
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val scope: CoroutineScope,
    private val consolidator: MemoryConsolidator,
    private val tokenUsageRepository: TokenUsageRepository,
) {
    // 每个助手同一时间只跑一次提取；运行期间再次触发则记录最后触发的会话，结束后重跑一次
    private val runningScopes = ConcurrentHashMap.newKeySet<String>()
    private val pendingScopes = ConcurrentHashMap<String, Uuid>()

    fun launchExtraction(conversationId: Uuid, onFinished: (() -> Unit)? = null) {
        scope.launch(Dispatchers.IO) {
            try {
                runCatching {
                    extract(conversationId)
                }.onFailure {
                    Log.w(TAG, "memory extraction failed", it)
                }
            } finally {
                onFinished?.invoke()
            }
        }
    }

    private suspend fun extract(conversationId: Uuid) {
        val settings = settingsStore.settingsFlow.value
        if (!settings.isPromptEnabled(PromptId.MEMORY_EXTRACT)) return
        val conversation = conversationRepository.getConversationById(conversationId) ?: return
        val assistant = settings.getAssistantById(conversation.assistantId) ?: return
        if (!assistant.enableMemory || !assistant.memoryAutoExtract) return

        val assistantId = assistant.id.toString()
        if (!runningScopes.add(assistantId)) {
            pendingScopes[assistantId] = conversationId
            return
        }
        try {
            do {
                val nextConversation = pendingScopes.remove(assistantId) ?: conversationId
                extractOnce(nextConversation, assistantId, settings, assistant)
            } while (pendingScopes.containsKey(assistantId))
        } finally {
            runningScopes.remove(assistantId)
            pendingScopes.remove(assistantId)
        }
    }

    private suspend fun extractOnce(
        conversationId: Uuid,
        assistantId: String,
        settings: Settings,
        assistant: Assistant,
    ) {
        val conversation = conversationRepository.getConversationById(conversationId) ?: return
        val model = settings.findModelById(settings.memoryMaintenanceModelId)
            ?: settings.getCurrentChatModel()
            ?: return
        val provider = model.findProvider(settings.providers) ?: return

        val recentMessages = conversation.currentMessages
            .filterNot { it.isMemorySnapshot() }
            .takeLast(6)
        if (recentMessages.isEmpty()) return

        val existing = memoryRepository.getMemories(assistantId)
        val prompt = buildExtractionPrompt(existing, recentMessages, settings.memoryExtractPrompt)

        val handler = providerManager.getProviderByType(provider)
        val result = handler.generateText(
            providerSetting = provider,
            messages = listOf(UIMessage.user(prompt = prompt)),
            params = backgroundTextGenerationParams(model, Uuid.random(), ReasoningLevel.AUTO),
        )
        result.usage?.let { usage ->
            tokenUsageRepository.record(
                conversationId = conversationId.toString(),
                provider = provider,
                model = model,
                source = TokenUsageSource.MEMORY,
                usage = usage,
            )
        }
        val raw = result.message.toText().trim()
        val operations = parseOperations(raw) ?: return
        if (operations.operations.isEmpty()) return

        val stats = applyOperations(
            assistantId = assistantId,
            conversationId = conversationId.toString(),
            workspaceId = assistant.workspaceId?.toString(),
            existing = existing,
            operations = operations,
        )
        if (stats.created == 0 && stats.updated == 0 && stats.archived == 0) return
        memoryRepository.trimMemories(assistantId)
        memoryRepository.recordEvent(
            assistantId = assistantId,
            type = MemoryEventType.EXTRACT,
            summary = "提取 ${stats.created} 新增 · ${stats.updated} 更新 · ${stats.archived} 归档",
            details = """{"created":${stats.created},"updated":${stats.updated},"archived":${stats.archived},"merged":${stats.merged}}""",
        )
        Log.i(TAG, "memory extraction for assistant=$assistantId: $stats")

        if (assistant.memoryAutoOrganize) {
            runCatching {
                consolidator.autoOrganizeIfNeeded(
                    assistantId = assistantId,
                    settings = settings,
                    coreBudget = settings.memoryCoreCharBudget,
                    growthThreshold = settings.memoryOrganizeGrowth,
                )
            }.onFailure { Log.w(TAG, "auto organize failed", it) }
        }
    }

    private fun buildExtractionPrompt(
        existing: List<AssistantMemory>,
        recentMessages: List<UIMessage>,
        template: String,
    ): String {
        val date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val index = existing
            .filter { it.status != MemoryStatus.ARCHIVED }
            .sortedWith(
                compareByDescending<AssistantMemory> { it.weight }
                    .thenByDescending { it.updatedAt }
            )
            .take(100)
            .joinToString("\n") { memory ->
                buildString {
                    append("id=${memory.id} | ${memory.kind} | weight=${memory.weight}")
                    append(" | ${memory.status} | ${memory.category}")
                    if (memory.title.isNotBlank()) append(" | ${memory.title.take(60)}")
                    if (memory.keywords.isNotEmpty()) append(" | kw: ${memory.keywords.take(6).joinToString(",")}")
                    append("\n  ${memory.content.take(300)}")
                }
            }
            .ifBlank { "(none)" }

        val conversationText = recentMessages.joinToString("\n\n") { message ->
            val role = if (message.role == MessageRole.USER) "用户" else "助手"
            "$role: ${message.summaryAsText(maxLength = 800)}"
        }

        return template.applyPlaceholders(
            "date" to date,
            "existing_memories" to index,
            "recent_conversation" to conversationText,
        )
    }

    private fun parseOperations(raw: String): MemoryOperations? {
        val cleaned = raw
            .substringAfter("```json", raw)
            .substringBefore("```", raw)
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val jsonText = cleaned.substring(start, end + 1)
        return runCatching { json.decodeFromString<MemoryOperations>(jsonText) }.getOrNull()
    }

    private suspend fun applyOperations(
        assistantId: String,
        conversationId: String,
        workspaceId: String?,
        existing: List<AssistantMemory>,
        operations: MemoryOperations,
    ): OperationStats {
        var created = 0
        var updated = 0
        var archived = 0
        var merged = 0
        for (op in operations.operations.take(8)) {
            when (op.action) {
                "create", "add" -> {
                    val content = op.content?.trim()?.takeIf { it.isNotBlank() } ?: continue
                    val result = memoryRepository.createMemory(
                        assistantId = assistantId,
                        content = content,
                        title = op.title.orEmpty(),
                        kind = MemoryKind.normalize(op.kind),
                        section = MemorySection.normalize(op.section),
                        category = MemoryCategory.normalize(op.category),
                        keywords = op.keywords.orEmpty(),
                        weight = op.weight ?: 2,
                        status = MemoryStatus.normalize(op.status),
                        sourceConversationId = conversationId,
                        workspaceId = workspaceId,
                    )
                    if (result.merged) merged++ else created++
                }

                "update" -> {
                    val id = op.id ?: continue
                    val current = existing.firstOrNull { it.id == id } ?: continue
                    val content = op.content?.trim()?.takeIf { it.isNotBlank() } ?: current.content
                    val keywords = op.keywords?.takeIf { it.isNotEmpty() } ?: current.keywords
                    val title = op.title?.takeIf { it.isNotBlank() } ?: current.title
                    val kind = op.kind?.let { MemoryKind.normalize(it) } ?: current.kind
                    val section = if (kind == MemoryKind.CORE) {
                        MemorySection.normalize(op.section ?: current.section)
                    } else ""
                    val updatedMemory = memoryRepository.updateMemory(
                        current.copy(
                            title = title,
                            content = content,
                            kind = kind,
                            section = section,
                            category = MemoryCategory.normalize(op.category ?: current.category),
                            keywords = keywords,
                            weight = (op.weight ?: current.weight).coerceIn(1, 4),
                            status = MemoryStatus.normalize(op.status ?: current.status),
                        )
                    )
                    if (updatedMemory != null) {
                        updated++
                        memoryRepository.recordUsage(assistantId, conversationId, listOf(id), "written")
                    }
                }

                "delete", "archive" -> {
                    val id = op.id ?: continue
                    if (existing.none { it.id == id }) continue
                    memoryRepository.archiveMemory(listOf(id))
                    archived++
                }
            }
        }
        return OperationStats(created, updated, archived, merged)
    }

    private data class OperationStats(
        val created: Int,
        val updated: Int,
        val archived: Int,
        val merged: Int,
    )
}

@Serializable
data class MemoryOperations(
    val operations: List<MemoryOperation> = emptyList(),
)

@Serializable
data class MemoryOperation(
    val action: String = "",
    val id: Int? = null,
    val title: String? = null,
    val content: String? = null,
    val kind: String? = null,
    val section: String? = null,
    val category: String? = null,
    val keywords: List<String>? = null,
    val weight: Int? = null,
    val status: String? = null,
    val corrected: Boolean? = null,
)
