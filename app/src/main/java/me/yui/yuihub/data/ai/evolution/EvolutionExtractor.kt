package me.yui.yuihub.data.ai.evolution

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
import me.rerere.ai.ui.UIMessagePart
import me.yui.yuihub.data.datastore.Settings
import me.yui.yuihub.data.datastore.SettingsStore
import me.yui.yuihub.data.datastore.findProvider
import me.yui.yuihub.data.datastore.getAssistantById
import me.yui.yuihub.data.datastore.getFastModelOrDefault
import me.yui.yuihub.data.model.Assistant
import me.yui.yuihub.data.model.EvolutionLesson
import me.yui.yuihub.data.repository.ConversationRepository
import me.yui.yuihub.data.repository.EvolutionRepository
import me.yui.yuihub.service.backgroundTextGenerationParams
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "EvolutionExtractor"

class EvolutionExtractor(
    private val evolutionRepository: EvolutionRepository,
    private val conversationRepository: ConversationRepository,
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val scope: CoroutineScope,
    private val consolidator: EvolutionConsolidator? = null,
) {
    private val runningAssistants = ConcurrentHashMap.newKeySet<String>()
    // 记录运行期间最后触发的会话，结束后用它重跑一次，避免连续对话丢事实
    private val pendingAssistants = ConcurrentHashMap<String, Uuid>()
    // 每个助手上次整理时的条目分布签名，避免无变化时重复调用模型
    private val lastConsolidatedSignature = ConcurrentHashMap<String, String>()

    fun launchExtraction(conversationId: Uuid) {
        scope.launch(Dispatchers.IO) {
            runCatching { extract(conversationId) }
                .onFailure { Log.w(TAG, "evolution extraction failed", it) }
        }
    }

    private suspend fun extract(conversationId: Uuid) {
        val settings = settingsStore.settingsFlow.value
        val conversation = conversationRepository.getConversationById(conversationId) ?: return
        val assistant = settings.getAssistantById(conversation.assistantId) ?: return
        if (!assistant.enableEvolution) return

        val assistantId = assistant.id.toString()
        if (!runningAssistants.add(assistantId)) {
            // 已有提取在跑：记下最后触发的会话（put 保证同助手后到者胜出），不直接丢弃
            pendingAssistants[assistantId] = conversationId
            return
        }
        try {
            do {
                // 取出并清掉标记；若循环期间又有新触发，会重新 put 进来，循环自然重跑
                val nextConversation = pendingAssistants.remove(assistantId) ?: conversationId
                extractOnce(nextConversation, assistantId, settings, assistant)
            } while (pendingAssistants.containsKey(assistantId))
        } finally {
            runningAssistants.remove(assistantId)
            pendingAssistants.remove(assistantId)
        }
    }

    private suspend fun extractOnce(
        conversationId: Uuid,
        assistantId: String,
        settings: Settings,
        assistant: Assistant,
    ) {
        val conversation = conversationRepository.getConversationById(conversationId) ?: return
        val model = settings.getFastModelOrDefault() ?: return
        val provider = model.findProvider(settings.providers) ?: return

        val recentMessages = conversation.currentMessages.takeLast(10)
        if (recentMessages.size < 2) return

        val existing = evolutionRepository.getLessons(assistantId)
        val prompt = buildExtractionPrompt(existing, recentMessages)
        val handler = providerManager.getProviderByType(provider)
        val result = handler.generateText(
            providerSetting = provider,
            messages = listOf(UIMessage.user(prompt = prompt)),
            params = backgroundTextGenerationParams(model, ReasoningLevel.AUTO),
        )
        val operations = parseOperations(result.message.toText().trim())
        if (operations != null && operations.operations.isNotEmpty()) {
            applyOperations(assistantId, existing, operations)
            Log.i(TAG, "extracted ${operations.operations.size} evolution ops for assistant=$assistantId")
        }

        // 自我整理全自动：条目分布有变化就尝试把同类方法合并成更通用的（1+1=2）
        // 并删除被吸收的旧条目；签名未变则跳过，不烧模型
        val signature = evolutionRepository.getLessons(assistantId)
            .let { lessons ->
                EvolutionLesson.KINDS.joinToString(",") { kind ->
                    lessons.count { it.kind == kind }.toString()
                }
            }
        if (signature != lastConsolidatedSignature[assistantId]) {
            lastConsolidatedSignature[assistantId] = signature
            runCatching { consolidator?.consolidate(assistantId, settings) }
                .onFailure { Log.w(TAG, "consolidate failed", it) }
        }
    }

    private fun buildExtractionPrompt(
        existing: List<EvolutionLesson>,
        recentMessages: List<UIMessage>,
    ): String {
        val index = existing.joinToString("\n") { lesson ->
            "id=${lesson.id} | ${lesson.kind} | ${lesson.title}: ${lesson.content.take(180)}"
        }.ifBlank { "(none)" }

        val conversationText = recentMessages.joinToString("\n\n") { message ->
            val role = if (message.role == MessageRole.USER) "User" else "Assistant"
            val hasTools = message.parts.any { it is UIMessagePart.Tool }
            val suffix = if (hasTools) " [used tools]" else ""
            "$role$suffix: ${message.summaryAsText(maxLength = 700)}"
        }

        return """
            You extract reusable METHODS this assistant should remember across future conversations.
            This is NOT factual memory about the user. Store how to work, how to talk, or how to stay in character.

            <existing lessons>
            $index
            </existing lessons>

            <recent conversation>
            $conversationText
            </recent conversation>

            Extract a lesson ONLY when one of these happened:
            - coding: a tool/error was diagnosed and then fixed, or a reusable setup/debug procedure appeared
            - chat: the user corrected tone, verbosity, language, or interaction style
            - roleplay: the user corrected persona, voice, boundaries, or in-character rules

            Do NOT extract: user profile facts, one-off task details, plot events, passwords, or restating the assistant's last answer.
            Keep each lesson as a short reusable rule (1-3 sentences). Write in the user's language.
            kind must be one of: coding, chat, roleplay.

            For every lesson choose one operation:
            - "add": new method with no equivalent existing lesson
            - "update": supersedes an existing lesson (include its id)
            - skip anything already covered

            Respond with ONLY a JSON object:
            {"operations":[{"action":"add","kind":"coding","title":"...","content":"..."},{"action":"update","id":3,"kind":"chat","title":"...","content":"..."}]}
            If nothing is worth keeping, respond with {"operations":[]}.
        """.trimIndent()
    }

    private fun parseOperations(raw: String): EvolutionOperations? {
        val cleaned = raw
            .substringAfter("```json", raw)
            .substringBefore("```", raw)
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            json.decodeFromString<EvolutionOperations>(cleaned.substring(start, end + 1))
        }.getOrNull()
    }

    private suspend fun applyOperations(
        assistantId: String,
        existing: List<EvolutionLesson>,
        operations: EvolutionOperations,
    ) {
        val allowed = setOf(
            EvolutionLesson.KIND_CODING,
            EvolutionLesson.KIND_CHAT,
            EvolutionLesson.KIND_ROLEPLAY,
        )
        for (op in operations.operations) {
            val kind = op.kind.trim().lowercase()
            if (kind !in allowed) continue
            val title = op.title?.trim().orEmpty().ifBlank { kind }
            val content = op.content?.trim().orEmpty()
            if (content.isBlank()) continue
            when (op.action) {
                "add" -> evolutionRepository.addLesson(assistantId, kind, title, content)
                "update" -> {
                    val id = op.id ?: continue
                    if (existing.none { it.id == id }) continue
                    evolutionRepository.updateLesson(id, title, content, kind)
                }
            }
        }
    }
}

@Serializable
private data class EvolutionOperations(
    val operations: List<EvolutionOperation> = emptyList(),
)

@Serializable
private data class EvolutionOperation(
    val action: String = "",
    val id: Int? = null,
    val kind: String = "",
    val title: String? = null,
    val content: String? = null,
)
