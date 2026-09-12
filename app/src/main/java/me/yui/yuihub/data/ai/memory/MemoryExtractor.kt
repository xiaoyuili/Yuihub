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
import me.yui.yuihub.data.ai.prompts.isMemorySnapshot
import me.yui.yuihub.data.datastore.Settings
import me.yui.yuihub.data.datastore.SettingsStore
import me.yui.yuihub.data.datastore.findProvider
import me.yui.yuihub.data.datastore.getAssistantById
import me.yui.yuihub.data.datastore.getCurrentChatModel
import me.yui.yuihub.data.model.Assistant
import me.yui.yuihub.data.model.AssistantMemory
import me.yui.yuihub.data.model.MemoryCategory
import me.yui.yuihub.data.repository.ConversationRepository
import me.yui.yuihub.data.repository.MemoryRepository
import me.yui.yuihub.service.backgroundTextGenerationParams
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "MemoryExtractor"

/**
 * 长期记忆提取管道：每次回复生成完成后异步运行。
 *
 * 与「模型自觉调用 memory_tool」互补：本管道被动、可靠地提取事实，
 * 并在提示词中携带现有记忆索引，由 LLM 输出 add/update/delete 操作，
 * 天然完成合并与冲突消解。提取完成后按阈值触发自动整理（MemoryConsolidator）。
 */
class MemoryExtractor(
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val scope: CoroutineScope,
    private val consolidator: MemoryConsolidator,
) {
    // 每个助手同一时间只跑一次提取；
    // 运行期间再次触发则记录最后触发的会话，结束后用它重跑一次，避免连续对话丢事实
    private val runningScopes = ConcurrentHashMap.newKeySet<String>()
    private val pendingScopes = ConcurrentHashMap<String, Uuid>()

    fun launchExtraction(conversationId: Uuid) {
        // AppScope 默认主线程调度，全部提取工作在 IO 上执行，避免阻塞 UI
        scope.launch(Dispatchers.IO) {
            runCatching {
                extract(conversationId)
            }.onFailure {
                Log.w(TAG, "memory extraction failed", it)
            }
        }
    }

    private suspend fun extract(conversationId: Uuid) {
        val settings = settingsStore.settingsFlow.value
        val conversation = conversationRepository.getConversationById(conversationId) ?: return
        val assistant = settings.getAssistantById(conversation.assistantId) ?: return
        if (!assistant.enableMemory) return

        val assistantId = assistant.id.toString()
        if (!runningScopes.add(assistantId)) {
            // 已有提取在跑：记下最后触发的会话（put 保证同助手后到者胜出），不直接丢弃
            pendingScopes[assistantId] = conversationId
            return
        }
        try {
            do {
                // 取出并清掉标记；若循环期间又有新触发，会重新 put 进来，循环自然重跑
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
        // 提取模型：使用当前对话模型
        val model = settings.getCurrentChatModel() ?: return
        val provider = model.findProvider(settings.providers) ?: return

        val recentMessages = conversation.currentMessages
            .filterNot { it.isMemorySnapshot() }
            .takeLast(6)
        if (recentMessages.isEmpty()) return

        val existing = memoryRepository.getMemories(assistantId)
        val prompt = buildExtractionPrompt(existing, recentMessages)

        val handler = providerManager.getProviderByType(provider)
        val result = handler.generateText(
            providerSetting = provider,
            messages = listOf(UIMessage.user(prompt = prompt)),
            params = backgroundTextGenerationParams(model, ReasoningLevel.AUTO),
        )
        val raw = result.message.toText().trim()
        val operations = parseOperations(raw) ?: return
        if (operations.operations.isEmpty()) return

        applyOperations(assistantId, existing, operations)
        memoryRepository.trimMemories(assistantId)
        Log.i(TAG, "extracted ${operations.operations.size} memory operations for assistant=$assistantId")

        // 积累到阈值后自动整理：合并重复内容、修正类别，节约注入预算
        runCatching { consolidator.autoConsolidateIfNeeded(assistantId, settings) }
            .onFailure { Log.w(TAG, "auto consolidate failed", it) }
    }

    private fun buildExtractionPrompt(
        existing: List<AssistantMemory>,
        recentMessages: List<UIMessage>,
    ): String {
        val date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val index = existing
            .sortedByDescending { it.updatedAt }
            .take(100)
            .joinToString("\n") { m ->
                "id=${m.id} | [${m.category}] ${m.content.take(200)}"
            }
            .ifBlank { "(none)" }

        val conversationText = recentMessages.joinToString("\n\n") { m ->
            val role = if (m.role == MessageRole.USER) "用户" else "助手"
            "$role: ${m.summaryAsText(maxLength = 800)}"
        }

        return """
            You are the memory manager of a personal AI assistant. Extract durable facts about the user from the recent conversation and update the memory store accordingly.

            Today is $date.

            <existing memories>
            $index
            </existing memories>

            <recent conversation>
            $conversationText
            </recent conversation>

            Categories (choose exactly one per fact):
            - profile: identity and stable facts about the user (name, occupation, location, relationships)
            - preference: how the user likes things (communication style, tools, likes/dislikes)
            - coding: software development knowledge — projects, tech stack, codebase conventions, debugging context
            - roleplay: roleplay/persona settings, in-character rules, character sheets, worldbuilding
            - daily: everyday life — schedules, plans, errands, non-work routines
            - temporary: short-lived context likely to go stale soon (current task state, one-off plans, temporary setups)
            - other: anything that fits nowhere else

            Instructions:
            - Extract only facts that stay relevant across future conversations.
            - Do NOT extract: single-turn task details, chit-chat, the assistant's own replies, or sensitive information (ethnicity, religion, political views, sexual orientation, passwords, credentials).
            - Write each memory in the language the user mostly uses. Keep each memory concise (1-3 sentences). Time-sensitive facts must contain an explicit date.
            - "importance" is a number 0.0-1.0: 0.9+ for identity/strong preferences, 0.6-0.8 for ordinary preferences and ongoing projects, 0.2-0.5 for minor notes.
            - Merge related facts into a single memory instead of creating many tiny ones.

            For every extracted fact choose one operation against the existing memories:
            - "add": a new fact with no equivalent existing memory
            - "update": a fact that supersedes or contradicts an existing memory (include its id)
            - "delete": an existing memory proven false or obsolete by the conversation (include its id)
            - facts already fully covered by an existing memory should be omitted

            Respond with ONLY a JSON object, no markdown fences, no extra text:
            {"operations":[{"action":"add","content":"...","category":"profile","importance":0.8},{"action":"update","id":7,"content":"...","category":"preference","importance":0.7},{"action":"delete","id":3}]}
            If there is nothing worth remembering, respond with {"operations":[]}.
        """.trimIndent()
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
        existing: List<AssistantMemory>,
        operations: MemoryOperations,
    ) {
        for (op in operations.operations) {
            when (op.action) {
                "add" -> {
                    val content = op.content?.trim()?.takeIf { it.isNotBlank() } ?: continue
                    memoryRepository.addMemory(
                        assistantId = assistantId,
                        content = content,
                        importance = op.importance ?: 0.6f,
                        category = MemoryCategory.normalize(op.category),
                    )
                }

                "update" -> {
                    val id = op.id ?: continue
                    val content = op.content?.trim()?.takeIf { it.isNotBlank() } ?: continue
                    // 只操作当前助手的记忆，防止模型幻觉出的 id 改到其他助手
                    if (existing.none { it.id == id }) continue
                    runCatching {
                        memoryRepository.updateMemory(
                            id = id,
                            content = content,
                            importance = op.importance,
                            category = op.category,
                        )
                    }.onFailure { Log.w(TAG, "update memory #$id failed", it) }
                }

                "delete" -> {
                    val id = op.id ?: continue
                    if (existing.none { it.id == id }) continue
                    memoryRepository.deleteMemory(id)
                }
            }
        }
    }
}

@Serializable
data class MemoryOperations(
    val operations: List<MemoryOperation> = emptyList(),
)

@Serializable
data class MemoryOperation(
    val action: String = "",
    val id: Int? = null,
    val content: String? = null,
    val importance: Float? = null,
    val category: String? = null,
)
