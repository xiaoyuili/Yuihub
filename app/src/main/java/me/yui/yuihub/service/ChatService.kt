package me.yui.yuihub.service

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.yui.yuihub.data.ai.prompts.buildMemorySnapshotText
import me.yui.yuihub.data.ai.prompts.isMemorySnapshot
import me.yui.yuihub.data.ai.prompts.withMemorySnapshot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.yui.yuihub.AppScope
import me.yui.yuihub.R
import me.yui.yuihub.data.ai.GenerationChunk
import me.yui.yuihub.data.ai.GenerationHandler
import me.yui.yuihub.data.ai.mcp.McpManager
import me.yui.yuihub.data.ai.memory.MemoryExtractor
import me.yui.yuihub.data.ai.tools.createConversationTools
import me.yui.yuihub.data.ai.tools.local.ASK_USER_TOOL_NAME
import me.yui.yuihub.data.ai.tools.local.LocalTools
import me.yui.yuihub.data.ai.tools.createSearchTools
import me.yui.yuihub.data.ai.tools.createMcpManageTools
import me.yui.yuihub.data.ai.tools.createSkillManageTools
import me.yui.yuihub.data.ai.tools.createTodoTool
import me.yui.yuihub.data.ai.tools.MCP_MANAGE_TOOL_NAME
import me.yui.yuihub.data.ai.tools.SCHEDULED_TASK_TOOL_NAME
import me.yui.yuihub.data.ai.tools.TODO_TOOL_NAME
import me.yui.yuihub.data.ai.tools.createScheduledTaskTools
import me.yui.yuihub.data.ai.tools.createSkillTools
import me.yui.yuihub.data.ai.tools.createWorkspaceTools
import me.yui.yuihub.data.ai.tools.SPAWN_AGENT_TOOL_NAME
import me.yui.yuihub.data.ai.tools.FOLLOWUP_AGENT_TOOL_NAME
import me.yui.yuihub.data.ai.tools.AGENT_SESSION_NOT_FOUND
import me.yui.yuihub.data.ai.tools.SUBAGENT_RESULT_INLINE_CHARS
import me.yui.yuihub.data.ai.tools.createSubagentTool
import me.yui.yuihub.data.ai.tools.createFollowupAgentTool
import me.yui.yuihub.data.ai.tools.createPollAgentTool
import me.yui.yuihub.data.ai.tools.createCancelAgentTool
import me.yui.yuihub.data.ai.tools.createListAgentsTool
import me.yui.yuihub.data.ai.tools.createVisionTool
import me.yui.yuihub.data.files.SkillManager
import me.yui.yuihub.data.files.FileFolders
import me.yui.yuihub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.yui.yuihub.data.ai.transformers.DocumentAsPromptTransformer
import me.yui.yuihub.data.ai.transformers.PlaceholderTransformer
import me.yui.yuihub.data.ai.transformers.PromptInjectionTransformer
import me.yui.yuihub.data.ai.transformers.RegexOutputTransformer
import me.yui.yuihub.data.ai.transformers.TemplateTransformer
import me.yui.yuihub.data.ai.transformers.ThinkTagTransformer
import me.yui.yuihub.data.ai.transformers.TimeReminderTransformer
import me.yui.yuihub.data.ai.transformers.WorkspaceReminderTransformer
import me.yui.yuihub.data.event.AppEvent
import me.yui.yuihub.data.event.AppEventBus
import me.yui.yuihub.data.datastore.Settings
import me.yui.yuihub.data.datastore.SettingsStore
import me.yui.yuihub.data.datastore.findModelById
import me.yui.yuihub.data.datastore.findProvider
import me.yui.yuihub.data.datastore.getAssistantById
import me.yui.yuihub.data.datastore.getCurrentAssistant
import me.yui.yuihub.data.datastore.getCurrentChatModel
import me.yui.yuihub.data.datastore.getTitleModelOrDefault
import me.yui.yuihub.data.files.FilesManager
import me.yui.yuihub.data.model.Assistant
import me.yui.yuihub.data.model.AssistantAffectScope
import me.yui.yuihub.data.model.CompressionSummary
import me.yui.yuihub.data.model.Conversation
import me.yui.yuihub.data.model.localFileUrls
import me.yui.yuihub.data.model.MessageNode
import me.yui.yuihub.data.model.SubagentPersona
import me.yui.yuihub.data.model.replaceRegexes
import me.yui.yuihub.data.model.toMessageNode
import me.yui.yuihub.data.repository.ConversationRepository
import me.yui.yuihub.data.repository.FolderRepository
import me.yui.yuihub.data.repository.MemoryRepository
import me.yui.yuihub.data.repository.ScheduledTaskRepository
import me.yui.yuihub.data.repository.WorkspaceRepository
import me.yui.yuihub.utils.AUTO_COMPRESS_RETAIN_RATIO
import me.yui.yuihub.utils.AUTO_COMPRESS_TARGET_TOKENS
import me.yui.yuihub.utils.AUTO_COMPRESS_THRESHOLD_RATIO
import me.yui.yuihub.utils.JsonInstant
import me.yui.yuihub.utils.applyPlaceholders
import me.yui.yuihub.utils.effectiveContextLength
import me.yui.yuihub.utils.estimateTokenCount
import me.yui.yuihub.utils.estimateWindowTokens
import me.rerere.workspace.WorkspaceShellStatus
import java.time.Instant
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.selects.select

private const val TAG = "ChatService"

// 子代理工具循环步数上限: 严于主代理默认值, 防止失控子任务无限制消耗 token
private const val CHILD_AGENT_MAX_STEPS = 32

/**
 * ISSUE-04: 仅限主代理的管理类工具。子代理无人监督批量运行，改共享配置
 * （MCP 注册表、技能库）或覆盖主会话待办/定时任务属于越权面，直接从子代理工具集剔除。
 */
private val PARENT_ONLY_TOOL_NAMES = setOf(
    "manage_skill",
    MCP_MANAGE_TOOL_NAME,
    SCHEDULED_TASK_TOOL_NAME,
    TODO_TOOL_NAME,
)

// 子代理平台限制声明 (P1-2): 经工具 systemPrompt 注入子代理系统提示,
// 把「工具不存在」的隐式限制变成平台明确声明, 避免子代理把缺工具理解成环境缺失而自行排查。
// P2-5: 同时声明沙箱预置缺口（无 python3 / shell 为 UTC / 无 tzdata / 产物目录约定），
// 避免子代理浪费轮次自查环境；产物统一写 /workspace/subagents/<sessionId>/ 便于清理与归属
private const val CHILD_AGENT_PLATFORM_DECLARATION =
    "<child_agent_constraints>\n" +
        "You are a CHILD agent spawned by a parent agent. Platform restrictions:\n" +
        "- You CANNOT spawn further child agents (spawn_agent) or follow up on other agents (followup_agent). These tools do not exist in your environment by design — do not search for them or work around them.\n" +
        "- You have NO management tools: manage_skill, manage_mcp_server, scheduled_task and todo_write exist only for the parent agent. Report any desired change to them (including skill library edits) to the parent agent.\n" +
        "- Tools requiring user approval are unavailable to you; report them to the parent agent instead.\n" +
        "- ask_user is unavailable: there is no interactive user in your session.\n" +
        "Sandbox environment facts (do NOT waste turns re-checking these):\n" +
        "- python3 and node are NOT installed; if a task needs scripting, use bash/sh with Perl or awk (both preinstalled), or install a runtime with apt-get.\n" +
        "- The shell clock is UTC (no tzdata). TZ=Asia/Shanghai date will show a wrong label; use get_time_info for the user's local time (+08:00).\n" +
        "- /upload may not exist when there are no user uploads; treat ls errors there as empty, not broken.\n" +
        "- Write your deliverables under /workspace/subagents/<your sessionId>/ (create it) so the parent and user can find them.\n" +
        "</child_agent_constraints>"

internal fun backgroundTextGenerationParams(
    model: Model,
    conversationId: Uuid,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
    sessionId = conversationId.toString(),
)

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}

internal fun createForkConversation(
    source: Conversation,
    messageNodes: List<MessageNode>,
    existingTitles: Set<String> = emptySet(),
): Conversation = Conversation(
    id = Uuid.random(),
    assistantId = source.assistantId,
    title = generateSequence(1) { it + 1 }
        .map { "${source.title}($it)" }
        .first { it !in existingTitles },
    messageNodes = messageNodes,
    customSystemPrompt = source.customSystemPrompt,
    modeInjectionIds = source.modeInjectionIds,
    lorebookIds = source.lorebookIds,
    workspaceCwd = source.workspaceCwd,
    folderId = source.folderId,
)

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckModelSettings,
}

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val memoryExtractor: MemoryExtractor,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val folderRepository: FolderRepository,
    private val subagentManager: SubagentManager,
    private val scheduledTaskRepository: ScheduledTaskRepository,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)

    // 定时任务会话标记：会话 id → (任务 id, 任务名)。生成结束时据此发悬浮通知并回写运行结果。
    // 仅进程内存即可：定时任务由 WorkManager 拉起，通知发生在同一次进程存续期内。
    private data class ScheduledRef(val taskId: String, val taskName: String)
    private val scheduledConversations = ConcurrentHashMap<Uuid, ScheduledRef>()

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    fun cleanup() = runCatching {
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = settings.getCurrentAssistant().id
                ),
                scope = appScope,
                onIdle = { removeSession(it) },
                onGenerationFinished = { id, cause ->
                    if (cause != null) sessions[id]?.messageQueue?.pause()
                    appScope.launch { dispatchNextQueuedMessage(id) }
                },
            ).also {
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            session.cleanup()
            _sessionsVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        return getOrCreateSession(conversationId).processingStatus
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    private fun launchGenerationJob(
        conversationId: Uuid,
        keepAliveInBackground: Boolean = true,
        block: suspend () -> Unit,
    ): Job {
        if (!keepAliveInBackground) return appScope.launch(start = CoroutineStart.LAZY) { block() }

        return appScope.launch(start = CoroutineStart.LAZY) {
            val generationId = Uuid.random()
            val foregroundStarted = ChatGenerationForegroundService.acquire(
                context = context,
                generationId = generationId,
                conversationId = conversationId,
            )
            try {
                block()
            } finally {
                if (foregroundStarted) {
                    ChatGenerationForegroundService.release(context, generationId)
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId) // 确保 session 存在
        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            // 正在生成中的会话以内存态为权威：生成中的流式消息只在节点收尾时落库，
            // 数据库里只有用户消息。切回该对话时若用 DB 快照覆盖内存态，
            // 正在流式输出的思考/工具调用会被抹掉，UI 退化为「用户消息+加载中」直到下一个 chunk。
            val memoryState = session.state.value
            val hasLiveGeneration = session.isGenerating || memoryState.currentMessages.any { message ->
                message.parts.any { it is UIMessagePart.Tool && (it.isPending || !it.isExecuted) }
            }
            if (hasLiveGeneration) {
                settingsStore.updateAssistant(memoryState.assistantId)
                return
            }
            session.mutationLock.withLock {
                updateConversation(conversationId, conversation)
            }
            settingsStore.updateAssistant(conversation.assistantId)
        } else {
            // 新建对话, 并添加预设消息
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true
            ).updateCurrentMessages(assistant.presetMessages)
            session.mutationLock.withLock {
                updateConversation(conversationId, newConversation)
            }
        }
    }

    // ---- 发送消息 ----

    /**
     * 定时任务入口：在指定助手下新建会话，写入 prompt 并立即开始生成。
     *
     * 不复用 [sendMessage] 的队列路径：队列走的是「当前会话」的 session 与当前助手，
     * 而定时任务需要指定助手且不依赖 UI 是否打开该会话。这里直接落库 + 复用同一会话 session。
     *
     * @return 新建会话的 id
     */
    suspend fun startScheduledConversation(
        assistantId: Uuid,
        taskId: String,
        title: String,
        prompt: String,
    ): Uuid {
        val conversationId = Uuid.random()
        val settings = settingsStore.settingsFlowRaw.first()
        val assistant = settings.getAssistantById(assistantId) ?: settings.getCurrentAssistant()
        val session = getOrCreateSession(conversationId)
        // 标记为定时任务会话：生成完成后发悬浮通知并回写任务运行结果
        scheduledConversations[conversationId] = ScheduledRef(taskId, title)
        session.mutationLock.withLock {
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true,
            ).copy(title = title)
                .updateCurrentMessages(assistant.presetMessages)
            updateConversation(conversationId, newConversation)
            conversationRepo.insertConversation(newConversation)
        }
        // 进入正常发送链路（会经过 autoCompress / 工具 / 通知等全部流程）
        sendMessage(conversationId, listOf(UIMessagePart.Text(prompt)), answer = true)
        return conversationId
    }

    fun getMessageQueueFlow(conversationId: Uuid): StateFlow<MessageQueueState> =
        getOrCreateSession(conversationId).messageQueue.state

    fun removeQueuedMessage(conversationId: Uuid, messageId: Uuid) {
        sessions[conversationId]?.messageQueue?.remove(messageId)?.let(::cleanupQueuedAttachments)
        dispatchNextQueuedMessage(conversationId)
    }

    fun beginEditQueuedMessage(conversationId: Uuid, messageId: Uuid): QueuedMessage? =
        sessions[conversationId]?.messageQueue?.beginEdit(messageId)

    fun finishEditQueuedMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>? = null
    ) {
        sessions[conversationId]?.messageQueue?.finishEdit(messageId, parts)
            ?.let(::cleanupQueuedAttachments)
        dispatchNextQueuedMessage(conversationId)
    }

    private fun cleanupQueuedAttachments(previous: QueuedMessage) {
        val candidates = previous.parts.localFileUrls()
        if (candidates.isEmpty()) return
        appScope.launch {
            try {
                // 未打开的会话及未选中的分支也可能引用同一附件。
                val persistedReferences =
                    candidates.filter { conversationRepo.hasFileReference(it) }.toSet()
                // 数据库查询挂起期间队列可能已推进，删除前重新读取内存引用。
                val currentSessions = sessions.values.toList()
                val unusedFiles = unreferencedQueuedAttachmentUrls(
                    previous = previous,
                    conversations = currentSessions.map { it.state.value },
                    pendingMessages = currentSessions.flatMap {
                        it.messageQueue.state.value.messages + listOfNotNull(it.submittingMessage)
                    },
                ) - persistedReferences
                if (unusedFiles.isNotEmpty()) {
                    filesManager.deleteChatFiles(unusedFiles.map { it.toUri() })
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // 无法确认引用时保留文件，避免误删。
                Log.w(TAG, "Failed to clean queued attachments", e)
            }
        }
    }

    fun resumeMessageQueue(conversationId: Uuid) {
        sessions[conversationId]?.messageQueue?.resume()
        dispatchNextQueuedMessage(conversationId)
    }

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        val session = getOrCreateSession(conversationId)
        synchronized(session) {
            if (session.messageQueue.state.value.messages.isEmpty()) session.messageQueue.resume()
            session.messageQueue.enqueue(content, answer)
            dispatchNextQueuedMessage(conversationId)
        }
    }

    private fun dispatchNextQueuedMessage(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        synchronized(session) {
            // A pending tool approval is still part of the current turn.
            if (session.getJob() != null || session.state.value.currentMessages.any { message ->
                    message.parts.any { it is UIMessagePart.Tool && it.isPending }
                }) return
            val next = session.messageQueue.takeNext() ?: return
            session.submittingMessage = next
            sendQueuedMessage(session, next)
        }
    }

    private fun sendQueuedMessage(session: ConversationSession, queued: QueuedMessage) {
        val conversationId = session.id
        val content = queued.parts
        val answer = queued.answer
        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = answer,
        ) {
            try {
                finishInterruptedPendingTools(conversationId)

                val currentConversation = session.state.value
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: settings.getCurrentAssistant()
                val processedContent = preprocessUserInputParts(content, assistant)

                // 自动压缩：发送前检查上下文占用，超过模型窗口 80% 时强制压缩历史（摘要由模型生成）
                if (answer) {
                    autoCompressIfNeeded(conversationId, assistant)
                }

                // 添加消息到列表
                session.mutationLock.withLock {
                    val newConversation = session.state.value.copy(
                        messageNodes = session.state.value.messageNodes + UIMessage(
                            role = MessageRole.USER,
                            parts = processedContent,
                        ).toMessageNode(),
                    )
                    saveConversation(conversationId, newConversation)
                }
                session.submittingMessage = null

                // 开始补全
                if (answer) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
                if (answer) {
                    memoryExtractor.launchExtraction(conversationId)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (e is CancellationException) throw e
                session.messageQueue.pause()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        job.invokeOnCompletion {
            synchronized(session) {
                if (session.submittingMessage?.id == queued.id) session.submittingMessage = null
            }
        }
        session.setJob(job)
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        previousJob?.cancel()

        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = message.role == MessageRole.USER || regenerateAssistantMsg,
        ) {
            try {
                // 等被取消的旧 job 结束后再改状态, 避免两个协程并发 saveConversation 丢更新
                runCatching { previousJob?.join() }

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息。按 id 定位；定位失败必须中止，
                    // 否则 indexOf 得 -1 会让 subList(0,0) 把整个会话历史清空并落库。
                    session.mutationLock.withLock {
                        val conversation = session.state.value
                        val node = conversation.getMessageNodeByMessageId(message.id)
                            ?: return@launchGenerationJob
                        val indexAt = conversation.messageNodes.indexOf(node)
                        saveConversation(
                            conversationId,
                            conversation.copy(messageNodes = conversation.messageNodes.subList(0, indexAt + 1))
                        )
                    }
                    handleMessageComplete(conversationId)
                } else {
                    if (regenerateAssistantMsg) {
                        val conversation = session.state.value
                        val node = conversation.getMessageNodeByMessageId(message.id)
                            ?: return@launchGenerationJob
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)
                    } else {
                        session.mutationLock.withLock {
                            saveConversation(conversationId, session.state.value)
                        }
                    }
                }

                _generationDoneFlow.emit(conversationId)
                memoryExtractor.launchExtraction(conversationId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                session.messageQueue.pause()
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        session.setJob(job)
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()

        val hasOtherPendingTools = session.state.value.messageNodes.any { node ->
            node.currentMessage.parts.any { part ->
                part is UIMessagePart.Tool && part.isPending && part.toolCallId != toolCallId
            }
        }

        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = !hasOtherPendingTools,
        ) {
            try {
                afterPreviousGeneration(previousJob) {
                    val hasPendingTools = session.mutationLock.withLock {
                        val conversation = session.state.value
                        // Ignore double taps and stale approvals for completed or inactive tools.
                        if (conversation.currentMessages.none { message ->
                                message.getTools().any { it.toolCallId == toolCallId && it.isPending }
                            }) return@afterPreviousGeneration
                        val newApprovalState = when {
                            answer != null -> ToolApprovalState.Answered(answer)
                            approved -> ToolApprovalState.Approved
                            else -> ToolApprovalState.Denied(reason)
                        }

                        // Update the tool approval state
                        val updatedNodes = conversation.messageNodes.map { node ->
                            node.copy(
                                messages = node.messages.map { msg ->
                                    msg.copy(
                                        parts = msg.parts.map { part ->
                                            when {
                                                part is UIMessagePart.Tool && part.toolCallId == toolCallId -> {
                                                    part.copy(approvalState = newApprovalState)
                                                }

                                                else -> part
                                            }
                                        }
                                    )
                                }
                            )
                        }
                        val updatedConversation = conversation.copy(messageNodes = updatedNodes)
                        saveConversation(conversationId, updatedConversation)

                        // Check if there are still pending tools
                        updatedNodes.any { node ->
                            node.currentMessage.parts.any { part ->
                                part is UIMessagePart.Tool && part.isPending
                            }
                        }
                    }

                    // Only continue generation when all pending tools are handled
                    if (!hasPendingTools) {
                        handleMessageComplete(conversationId)
                    }

                    _generationDoneFlow.emit(conversationId)
                    if (!hasPendingTools) {
                        memoryExtractor.launchExtraction(conversationId)
                    }
                }
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job, cancelPrevious = false)
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null
    ) {
        val settings = settingsStore.settingsFlow.first()
        val initialConversation = getConversationFlow(conversationId).value
        val assistant = settings.getAssistantById(initialConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: return

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }
        val useExternalWebSearch = shouldUseExternalWebSearch(assistant, model)

        runCatching {

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (useExternalWebSearch || mcpManager.getAllAvailableTools().isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            // 记忆快照：内容变化时追加落库（append-only），保持请求前缀跨轮稳定；
            // 快照准备属缓存优化，失败不应阻断本轮生成
            runCatching { prepareMemorySnapshot(conversationId, assistant) }
                .onFailure { Log.w(TAG, "prepareMemorySnapshot failed", it) }
            val conversation = getConversationFlow(conversationId).value

            // start generating
            val session = getOrCreateSession(conversationId)
            generationHandler.generateText(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                messages = if (messageRange != null) {
                    // 重生成历史消息：保持原始轨迹（被压缩的原始消息仍在，上下文完整）
                    conversation.currentMessages.let {
                        it.subList(messageRange.start, messageRange.endInclusive + 1)
                    }
                } else {
                    // 发送窗口：活跃压缩检查点作为新段起点，边界前旧前缀不发送（DSH 分段轨迹）
                    conversation.requestWindowMessages()
                },
                assistant = assistant,
                conversationId = conversationId,
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                workspaceCwd = conversation.workspaceCwd,
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(workspaceReminderTransformer)
                },
                outputTransformers = outputTransformers,
                tools = buildAgentTools(
                    settings = settings,
                    assistant = assistant,
                    conversation = conversation,
                    useExternalWebSearch = useExternalWebSearch,
                    parentConversationId = conversationId,
                ),
            ).onCompletion {
                // 可能被取消了，或者意外结束，兜底更新；NonCancellable 保证取消场景下兜底也执行
                val updatedConversation = withContext(NonCancellable) {
                    session.mutationLock.withLock {
                        val current = session.state.value
                        val updated = current.copy(
                            messageNodes = current.messageNodes.map { node ->
                                node.copy(messages = node.messages.map { it.finishReasoning() })
                            },
                            updateAt = Instant.now()
                        )
                        updateConversation(conversationId, updated)
                        updated
                    }
                }

                // 生成结束：取消 Live Update 通知，后台时发送完成通知
                appEventBus.emit(
                    AppEvent.ChatGenerationEnded(
                        conversationId = conversationId,
                        senderName = senderName,
                        contentPreview = updatedConversation.currentMessages.lastOrNull()
                            ?.toText()?.take(50)?.trim() ?: "",
                        scheduledTaskId = scheduledConversations[conversationId]?.taskId,
                        scheduledTaskName = scheduledConversations.remove(conversationId)?.taskName,
                    )
                )
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        // 与用户操作（切分支/编辑等）串行写回，避免读-改-写互相覆盖
                        session.mutationLock.withLock {
                            updateConversation(
                                conversationId,
                                session.state.value.updateCurrentMessages(chunk.messages)
                            )
                        }

                        // 通知等边缘副作用由 ChatNotificationManager 消费；
                        // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
                        chunk.messages.lastOrNull()?.let { lastMessage ->
                            appEventBus.tryEmit(
                                AppEvent.ChatGenerationUpdate(conversationId, lastMessage, senderName)
                            )
                        }
                    }
                }
            }
        }.onFailure {
            // 兜底取消 Live Update 通知（生成开始前失败时 onCompletion 不会执行）；
            // 定时任务会话在这里发一条失败提醒，并回写任务状态
            val scheduledRef = scheduledConversations.remove(conversationId)
            appEventBus.tryEmit(
                AppEvent.ChatGenerationEnded(
                    conversationId = conversationId,
                    senderName = senderName,
                    contentPreview = null,
                    scheduledTaskId = scheduledRef?.taskId,
                    scheduledTaskName = scheduledRef?.taskName,
                )
            )

            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            val finalSession = getOrCreateSession(conversationId)
            val finalConversation = finalSession.mutationLock.withLock {
                val current = finalSession.state.value
                saveConversation(conversationId, current)
                current
            }

            // 空回复可见化：「只有思考、没有正文」过去会静默结束，用户只看到思考框后无下文
            notifyIfEmptyReply(finalConversation)

            launchWithConversationReference(conversationId) {
                generateTitle(conversationId, finalConversation)
            }
        }
    }

    private suspend fun buildAgentTools(
        settings: Settings,
        assistant: Assistant,
        conversation: Conversation,
        useExternalWebSearch: Boolean,
        parentConversationId: Uuid,
    ): List<Tool> {
        val tools = buildList {
        if (useExternalWebSearch) {
            addAll(createSearchTools(settings))
        }
        addAll(localTools.getTools(assistant.localTools))
        if (assistant.enableRecentChatsReference) {
            addAll(createConversationTools(conversationRepo, assistant.id))
        }
        addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), conversation.workspaceCwd))
        if (assistant.enabledSkills.isNotEmpty()) {
            addAll(
                createSkillTools(
                    enabledSkills = assistant.enabledSkills,
                    allSkills = skillManager.listSkills(),
                )
            )
        }
        addAll(createSkillManageTools(skillManager))
        addAll(createMcpManageTools(mcpManager, settingsStore))
        add(createTodoTool())
        addAll(
            createScheduledTaskTools(
                repository = scheduledTaskRepository,
                assistantId = assistant.id,
                onRunNow = { task -> scheduledTaskRepository.runNow(task) },
            )
        )
        mcpManager.getAllAvailableTools().forEach { (serverId, serverName, tool) ->
            add(
                Tool(
                    name = "mcp__${serverName}__${tool.name}",
                    description = tool.description ?: "",
                    parameters = { tool.inputSchema },
                    needsApproval = { tool.needsApproval },
                    execute = {
                        mcpManager.callTool(serverId, tool.name, it.jsonObject)
                    },
                )
            )
        }
        add(
            createSubagentTool(
                personas = settings.subagentPersonas,
            ) { description, prompt, async, timeoutMs, maxToolCalls, personaName ->
                runChildAgent(
                    parentConversationId = parentConversationId,
                    settings = settings,
                    assistant = assistant,
                    conversation = conversation,
                    useExternalWebSearch = useExternalWebSearch,
                    description = description,
                    prompt = prompt,
                    async = async,
                    timeoutMs = timeoutMs,
                    maxToolCalls = maxToolCalls,
                    personaName = personaName,
                )
            }
        )
        add(
            createPollAgentTool { taskId -> pollChildAgent(taskId) }
        )
        add(
            createCancelAgentTool { taskId -> cancelChildAgent(taskId) }
        )
        add(
            createListAgentsTool { listChildAgents(parentConversationId) }
        )
        add(
            createFollowupAgentTool { sessionId, message ->
                followUpChildAgent(
                    sessionId = sessionId,
                    message = message,
                    settings = settings,
                    assistant = assistant,
                    conversation = conversation,
                    useExternalWebSearch = useExternalWebSearch,
                )
            }
        )
        createVisionToolIfReady(settings, assistant, conversation)?.let(::add)
        }
        return tools
    }

    // harness spawn-in-process：子 agent 继承 workspace/model/tools，禁止再派生子 agent；
    // 子代理以真实子会话落库（parent_conversation_id 归属父会话），抽屉树实时可见、点开可看完整轨迹。
    // 返回结构化 JSON（P0-1）：status/result/toolCalls/时间/files/sessionId，供父代理区分失败类型与追问。
    // P0-2: async=true 立即返回 taskId（poll_agent 取结果）；timeoutMs/maxToolCalls 控制面；默认同步阻塞（向后兼容）
    private suspend fun runChildAgent(
        parentConversationId: Uuid,
        settings: Settings,
        assistant: Assistant,
        conversation: Conversation,
        useExternalWebSearch: Boolean,
        description: String,
        prompt: String,
        async: Boolean = false,
        timeoutMs: Long? = null,
        maxToolCalls: Int? = null,
        personaName: String? = null,
    ): String {
        val persona = personaName?.let { name ->
            settings.subagentPersonas.find { it.name == name }
        }
        val childId = Uuid.random()
        if (async) {
            // 异步派发：在应用作用域起独立协程，立即返回 taskId；父代理后续 poll/cancel/followup
            subagentManager.registerAsyncTask(childId, parentConversationId, description)
            appScope.launch {
                var taskStatus = SubagentTaskStatus.COMPLETED
                var taskResult: String? = null
                try {
                    taskResult = executeChildAgent(
                        childId = childId,
                        description = description,
                        userMessages = listOf(UIMessage.user(prompt)),
                        parentConversationId = parentConversationId,
                        settings = settings,
                        assistant = assistant,
                        conversation = conversation,
                        useExternalWebSearch = useExternalWebSearch,
                        timeoutMs = timeoutMs,
                        maxToolCalls = maxToolCalls,
                        cancelSignal = subagentManager.cancelSignal(childId),
                        persona = persona,
                    )
                } catch (e: ChildAgentTimeoutException) {
                    // 超时的部分结果在异常里，转 TIMEOUT 终态供 poll 取回
                    taskStatus = SubagentTaskStatus.TIMEOUT
                    taskResult = e.partialResultJson
                } catch (e: ChildAgentCancelledException) {
                    taskStatus = SubagentTaskStatus.CANCELLED
                    taskResult = e.partialResultJson
                } catch (e: CancellationException) {
                    taskStatus = SubagentTaskStatus.CANCELLED
                } catch (e: Throwable) {
                    taskStatus = SubagentTaskStatus.FAILED
                    taskResult = agentErrorJson("error", "${e.javaClass.simpleName}: ${e.message.orEmpty()}")
                }
                subagentManager.completeAsyncTask(childId, taskStatus, taskResult)
            }
            return buildJsonObject {
                put("status", "running")
                put("taskId", childId.toString())
                put("description", description)
                put("hint", "Poll with poll_agent(taskId) to fetch progress/result; cancel with cancel_agent(taskId).")
            }.toString()
        }
        val syncStartedAtMs = System.currentTimeMillis()
        val syncResult = executeChildAgent(
            childId = childId,
            description = description,
            userMessages = listOf(UIMessage.user(prompt)),
            parentConversationId = parentConversationId,
            settings = settings,
            assistant = assistant,
            conversation = conversation,
            useExternalWebSearch = useExternalWebSearch,
            timeoutMs = timeoutMs,
            maxToolCalls = maxToolCalls,
            persona = persona,
        )
        // ISSUE-02: 同步任务补登记终态，让 list_agents 可见；返回值丢失时也能找回 sessionId
        subagentManager.recordSyncTask(
            taskId = childId,
            parentConversationId = parentConversationId,
            description = description,
            status = SubagentManager.statusFromResult(
                runCatching {
                    JsonInstant.parseToJsonElement(syncResult).jsonObject["status"]
                        ?.jsonPrimitive?.contentOrNull.orEmpty()
                }.getOrDefault("")
            ),
            resultJson = subagentManager.summarizeResultJson(syncResult),
            startedAtMs = syncStartedAtMs,
        )
        return syncResult
    }

    /** P0-2 b) poll_agent：运行中返回进度（工具调用数/最近动作），终态返回完整结果 */
    private suspend fun pollChildAgent(taskId: String): String {
        // GC 顺带清理，保证 poll 到的任务都在 TTL 内
        subagentManager.collectExpired()
        val id = runCatching { Uuid.parse(taskId.trim()) }.getOrNull()
            ?: return agentErrorJson("AGENT_TASK_NOT_FOUND", "Invalid taskId: $taskId")
        val task = subagentManager.getTask(id)
            ?: return agentErrorJson(
                "AGENT_TASK_NOT_FOUND",
                "Unknown taskId: $taskId (only async-spawned agents are pollable; sync results are returned inline)",
            )
        return if (task.status == SubagentTaskStatus.RUNNING) {
            val run = subagentManager.runningOf(task.parentConversationId).firstOrNull { it.childId == id }
            buildJsonObject {
                put("status", "running")
                put("taskId", taskId)
                put("description", task.description)
                put("durationMs", System.currentTimeMillis() - task.startedAt)
                // ISSUE-05: 轮询活性信号，调用方可据此区分「正在生成」与「卡死」
                put("lastProgressAt", System.currentTimeMillis())
                put("toolCalls", run?.messages?.sumOf { m -> m.parts.count { it is UIMessagePart.Tool } } ?: 0)
                run?.messages?.lastOrNull()?.parts?.lastOrNull()?.let { lastPart ->
                    val preview = when (lastPart) {
                        is UIMessagePart.Text -> lastPart.text.take(120)
                        is UIMessagePart.Tool -> "→ ${lastPart.toolName}"
                        is UIMessagePart.Reasoning -> lastPart.reasoning.take(120)
                        else -> null
                    }
                    if (!preview.isNullOrBlank()) put("latestAction", preview)
                }
            }.toString()
        } else {
            buildJsonObject {
                put("status", task.status.name.lowercase())
                put("taskId", taskId)
                put("description", task.description)
                put("durationMs", (task.endedAt ?: System.currentTimeMillis()) - task.startedAt)
                if (task.resultJson != null) {
                    put("result", task.resultJson)
                }
                put("sessionId", task.taskId.toString())
            }.toString()
        }
    }

    /** P0-2 c) cancel_agent：置取消信号；执行协程收到后停止并落 CANCELLED 终态 */
    private suspend fun cancelChildAgent(taskId: String): String {
        val id = runCatching { Uuid.parse(taskId.trim()) }.getOrNull()
            ?: return agentErrorJson("AGENT_TASK_NOT_FOUND", "Invalid taskId: $taskId")
        val cancelled = subagentManager.cancel(id)
        return if (cancelled) {
            buildJsonObject {
                put("status", "cancelling")
                put("taskId", taskId)
                put("hint", "The child agent will stop at its next cancellation checkpoint; poll for final state.")
            }.toString()
        } else {
            agentErrorJson("AGENT_TASK_NOT_FOUND", "Task $taskId is not running (already finished or unknown)")
        }
    }

    /** P0-2 c) list_agents：本会话的 async 任务（含 RUNNING 与终态） */
    private suspend fun listChildAgents(parentConversationId: Uuid): String {
        subagentManager.collectExpired()
        val tasks = subagentManager.listTasks(parentConversationId)
        return buildJsonObject {
            put("count", tasks.size)
            put("agents", buildJsonArray {
                tasks.forEach { task ->
                    add(buildJsonObject {
                        put("taskId", task.taskId.toString())
                        put("description", task.description)
                        put("status", task.status.name.lowercase())
                        put("mode", if (task.async) "async" else "sync")
                        put("durationMs", (task.endedAt ?: System.currentTimeMillis()) - task.startedAt)
                        if (task.expiresAt != null) put("sessionExpiresAt", task.expiresAt)
                    })
                }
            })
        }.toString()
    }

    /**
     * 追问既有子代理（P0-4）：复用同一子会话上下文继续生成。
     * 会话不存在（被删/从未派发）返回 AGENT_SESSION_NOT_FOUND，不抛异常，让父代理能明确感知。
     */
    private suspend fun followUpChildAgent(
        sessionId: String,
        message: String,
        settings: Settings,
        assistant: Assistant,
        conversation: Conversation,
        useExternalWebSearch: Boolean,
    ): String {
        val childId = runCatching { Uuid.parse(sessionId.trim()) }.getOrNull()
            ?: return agentErrorJson(AGENT_SESSION_NOT_FOUND, "Unknown sessionId: $sessionId")
        val existing = conversationRepo.getConversationById(childId)
        if (existing == null || existing.parentConversationId == null) {
            return agentErrorJson(
                AGENT_SESSION_NOT_FOUND,
                "No child-agent session for sessionId $sessionId (it may have been deleted, or it is not a child-agent session)",
            )
        }
        // 用库里的最新归属链，防止主会话迁移助手后追问时串环境
        return executeChildAgent(
            childId = childId,
            description = existing.title,
            userMessages = listOf(UIMessage.user(message)),
            parentConversationId = existing.parentConversationId!!, // 已在上方判空
            settings = settings,
            assistant = assistant,
            conversation = existing,
            useExternalWebSearch = useExternalWebSearch,
            isFollowUp = true,
        )
    }

    private suspend fun executeChildAgent(
        childId: Uuid,
        description: String,
        userMessages: List<UIMessage>,
        parentConversationId: Uuid,
        settings: Settings,
        assistant: Assistant,
        conversation: Conversation,
        useExternalWebSearch: Boolean,
        isFollowUp: Boolean = false,
        timeoutMs: Long? = null,
        maxToolCalls: Int? = null,
        cancelSignal: CompletableDeferred<Unit>? = null,
        persona: SubagentPersona? = null,
    ): String {
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: error("Model not found for child agent")
        // 追问时把新消息追加到既有子会话；首轮则建新子会话（title=description，继承父配置）
        val baseConversation = if (isFollowUp) {
            conversation.copy(
                messageNodes = conversation.messageNodes + userMessages.map { it.toMessageNode() },
            )
        } else {
            Conversation(
                id = childId,
                assistantId = conversation.assistantId,
                title = description,
                messageNodes = emptyList(),
                customSystemPrompt = conversation.customSystemPrompt,
                modeInjectionIds = conversation.modeInjectionIds,
                lorebookIds = conversation.lorebookIds,
                workspaceCwd = conversation.workspaceCwd,
                parentConversationId = parentConversationId,
            ).updateCurrentMessages(userMessages)
        }
        // 生成开始前落库：抽屉树立即出现子代理行；失败/取消也保留已产生的轨迹
        saveConversation(childId, baseConversation)
        // 同步 session 初始态：session 默认用「当前设置助手」建空会话，若用户此时切换过助手，
        // 后续整对象保存会把错误的 assistantId 落库，先对齐一次
        getOrCreateSession(childId).mutationLock.withLock {
            updateConversation(childId, baseConversation)
        }

        val childTools = buildChildAgentTools(
            settings = settings,
            assistant = assistant,
            conversation = baseConversation,
            useExternalWebSearch = useExternalWebSearch,
            parentConversationId = parentConversationId,
            persona = persona,
        )
        val startedAtMs = System.currentTimeMillis()
        // 角色提示词追加到系统提示尾部（不依赖 allowConversationSystemPrompt 开关，子代理一定会生效）
        val childAssistant = if (persona != null && persona.systemPrompt.isNotBlank()) {
            assistant.copy(
                systemPrompt = buildString {
                    if (assistant.systemPrompt.isNotBlank()) {
                        append(assistant.systemPrompt)
                        append("\n\n")
                    }
                    append("<agent_role name=\"")
                    append(persona.name)
                    append("\">\n")
                    append(persona.systemPrompt)
                    append("\n</agent_role>")
                }
            )
        } else {
            assistant
        }
        // P1-1 方案A: 生成前扫一次 /workspace，结束时 diff 出 shell 等非工具写入
        val workspaceSnapshotBefore = withContext(Dispatchers.IO) { snapshotWorkspaceFiles() }
        var latest: List<UIMessage> = baseConversation.currentMessages
        var failed: Throwable? = null
        subagentManager.start(
            childId = childId,
            parentConversationId = parentConversationId,
            description = description,
        )
        try {
            // P0-2 c): timeout / cancel 任意一个触发即终止生成；用 select 竞速，胜出方决定终止类型。
            // timeoutMs 不设且无 cancelSignal 时直接收集（零开销路径）
            val collectBlock: suspend () -> Unit = {
                generationHandler.generateText(
                    settings = settings,
                    model = model,
                    messages = baseConversation.currentMessages,
                    assistant = childAssistant.copy(streamOutput = false),
                    conversationId = childId,
                    conversationSystemPrompt = baseConversation.customSystemPrompt,
                    conversationModeInjectionIds = baseConversation.modeInjectionIds,
                    conversationLorebookIds = baseConversation.lorebookIds,
                    workspaceCwd = baseConversation.workspaceCwd,
                    inputTransformers = buildList {
                        addAll(inputTransformers)
                        add(templateTransformer)
                        add(workspaceReminderTransformer)
                    },
                    outputTransformers = outputTransformers,
                    tools = childTools,
                    maxSteps = maxToolCalls?.plus(1)?.coerceAtMost(CHILD_AGENT_MAX_STEPS) ?: CHILD_AGENT_MAX_STEPS,
                ).collect { chunk ->
                    when (chunk) {
                        is GenerationChunk.Messages -> {
                            latest = chunk.messages
                            // 流式写回子会话 session（抽屉树/子会话页实时可见），与其它写者串行
                            val childSession = getOrCreateSession(childId)
                            childSession.mutationLock.withLock {
                                updateConversation(
                                    childId,
                                    childSession.state.value.updateCurrentMessages(chunk.messages)
                                )
                            }
                            // 实时上报子代理进度, 供聊天页过程演示
                            subagentManager.updateMessages(childId, chunk.messages)
                        }
                    }
                }
            }
            when {
                timeoutMs == null && cancelSignal == null -> collectBlock()
                else -> withTimeoutOrNull(timeoutMs ?: Long.MAX_VALUE) {
                    if (cancelSignal == null) {
                        collectBlock()
                    } else {
                        // cancel 与正常结束竞速：select 任一方胜出立即返回，无悬空子协程。
                        // P1 缺陷修复：原实现 scope 内的 cancel 监听协程挂在 await() 上，
                        // collect 结束后 scope 仍要等它 → executeChildAgent 永久挂起，
                        // 异步任务终态永远落不了地（症状：工作已完成但 poll 一直 running，
                        // 直到 cancel_agent 才解锁终态）。
                        // 语义：collect 已完成时 onJoin 优先（首个就绪子句胜出），
                        // 完成后才到达的 cancel 不会把已完成的任务标成 CANCELLED。
                        try {
                            coroutineScope {
                                val collectJob = launch { collectBlock() }
                                val cancelled = select<Boolean> {
                                    collectJob.onJoin { false }
                                    cancelSignal.onAwait { true }
                                }
                                if (cancelled) {
                                    collectJob.cancel()
                                    throw ChildAgentCancelledException(null)
                                }
                            }
                        } catch (e: ChildAgentCancelledException) {
                            throw e
                        }
                    }
                } ?: run {
                    // withTimeoutOrNull 返回 null = 超时
                    failed = ChildAgentTimeoutException(null)
                }
            }
        } catch (e: ChildAgentTimeoutException) {
            failed = e
        } catch (e: ChildAgentCancelledException) {
            failed = e
        } catch (e: Throwable) {
            // 取消与失败都要注销, 否则僵尸 run 泄漏内存且 UI 提示会误显示。
            // NonCancellable 收尾：取消路径下父协程已不可挂起，但仍要把子会话部分轨迹落库。
            // 取消向上传播（CME 语义不变），其它异常转为结构化 error 返回给父代理（P0-1）
            failed = e
        } finally {
            subagentManager.finish(childId)
            withContext(NonCancellable) { saveChildAgentFinal(childId) }
        }
        val failure = failed
        if (failure is CancellationException) throw failure
        conversationRepo.recordTokenUsage(parentConversationId.toString(), latest)
        return buildChildAgentResultJson(
            childId = childId,
            description = description,
            latest = latest,
            startedAtMs = startedAtMs,
            failure = failure,
            workspaceSnapshotBefore = workspaceSnapshotBefore,
        )
    }

    /**
     * 子代理结果结构化封装（P0-1/P0-2）：
     * - status: ok / empty_output / error / timeout / cancelled
     * - result 截断到 [SUBAGENT_RESULT_INLINE_CHARS] 并加显式尾标记，全文落盘 /tool_outputs/<childId>.md（fullResultPath）
     * - empty_output 时附 toolCalls 摘要（工具名+次数），父代理可判断子代理做过什么
     * - P1-1 选型方案A: files = 工具写入路径 + /workspace 快照 diff（shell 产物）。
     *   性能开销：两次 O(会话期写入量) 的目录遍历，深度限制 6 层、上限 5000 条目，
     *   适用规模：工作区文件在数千级以内；超大工作区建议靠 WRITE_CONFLICT 而非全量 diff。
     */
    private suspend fun buildChildAgentResultJson(
        childId: Uuid,
        description: String,
        latest: List<UIMessage>,
        startedAtMs: Long,
        failure: Throwable?,
        workspaceSnapshotBefore: Map<String, FileSnapshot>? = null,
    ): String {
        val endedAtMs = System.currentTimeMillis()
        val answer = latest.lastOrNull { it.role == MessageRole.ASSISTANT }?.toText()?.trim().orEmpty()
        val toolCalls = latest.flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>()
        val filesWritten = toolCalls
            .filter { it.toolName == "workspace_write_file" || it.toolName == "workspace_edit_file" }
            .mapNotNull { part ->
                runCatching { part.inputAsJson().jsonObject["path"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            }
            .toMutableSet()
        // P1-1 方案A: 合并 shell 重定向等非工具写入（新增或 mtime/size 变化的 /workspace 文件）
        if (workspaceSnapshotBefore != null) {
            runCatching {
                val after = snapshotWorkspaceFiles()
                after.forEach { (path, snap) ->
                    val before = workspaceSnapshotBefore[path]
                    if (before == null || before != snap) filesWritten += path
                }
            }.onFailure { Log.w(TAG, "workspace snapshot diff failed", it) }
        }
        val toolSummary = toolCalls
            .groupBy { it.toolName }
            .map { (name, calls) -> "$name x${calls.size}" }
            .joinToString(", ")

        val status = when {
            failure is ChildAgentTimeoutException -> "timeout"
            failure is ChildAgentCancelledException -> "cancelled"
            failure != null -> "error"
            answer.isBlank() -> "empty_output"
            else -> "ok"
        }
        return buildJsonObject {
            put("status", status)
            put("description", description)
            put("sessionId", childId.toString())
            if (failure != null) {
                put("error", "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}".trim())
            }
            // 选型说明（P0-1）：empty_output 时选方案 a（自动附工具摘要），不补发总结轮——
            // 补发会加倍 token 与延迟，且子代理无文本输出往往本就是「只干活」型任务，摘要已足够。
            // P2-1: 保留字符串 toolCalls 兼容，新增结构化 toolCallsDetail
            if (toolCalls.isNotEmpty()) {
                if (status == "empty_output") {
                    put("toolCalls", toolSummary)
                }
                put("toolCallsDetail", buildJsonArray {
                    toolCalls.groupBy { it.toolName }
                        .forEach { (name, calls) ->
                            add(buildJsonObject {
                                put("name", name)
                                put("count", calls.size)
                            })
                        }
                })
            }
            put("startedAt", startedAtMs)
            put("endedAt", endedAtMs)
            put("durationMs", endedAtMs - startedAtMs)
            if (filesWritten.isNotEmpty()) {
                put("files", buildJsonArray { filesWritten.forEach { add(it) } })
            }
            if (failure == null) {
                put("result", truncateChildResult(childId, answer))
            }
        }.toString()
    }

    /** /workspace 文件快照条目（P1-1: mtime+size 足以识别外部写入） */
    private data class FileSnapshot(val sizeBytes: Long, val mtimeMs: Long)

    /**
     * 扫描 /workspace（宿主侧 filesDir 对应目录）。
     * 深度 6 / 上限 5000 条目防失控；/tool_outputs 是平台产物区不纳入 diff。
     */
    private suspend fun snapshotWorkspaceFiles(): Map<String, FileSnapshot> {
        val workspaceRoot = findWorkspaceFilesDir() ?: return emptyMap()
        val result = HashMap<String, FileSnapshot>()
        val maxDepth = 6
        val maxEntries = 5000
        fun walk(dir: File, relative: String, depth: Int) {
            if (result.size >= maxEntries || depth > maxDepth) return
            val files = dir.listFiles() ?: return
            for (file in files) {
                if (result.size >= maxEntries) return
                if (file.name.startsWith(".")) continue
                val rel = if (relative.isEmpty()) file.name else "$relative/${file.name}"
                if (file.isDirectory) {
                    walk(file, rel, depth + 1)
                } else {
                    result["/workspace/$rel"] = FileSnapshot(file.length(), file.lastModified())
                }
            }
        }
        walk(workspaceRoot, "", 0)
        return result
    }

    /** 找到任一已就绪 workspace 的宿主侧 files 目录（/workspace 即它） */
    private suspend fun findWorkspaceFilesDir(): File? {
        return runCatching {
            val workspace = workspaceRepository.getReadyWorkspaceAny()
                ?: return@runCatching null
            File(context.filesDir, "workspaces/${workspace.root}/files")
        }.getOrNull()?.takeIf { it.isDirectory }
    }

    /** result 截断 + 尾标记 + 全文落盘 /tool_outputs（P0-2）；工具层与模型侧共享同一常量 */
    private fun truncateChildResult(childId: Uuid, answer: String): String {
        if (answer.length <= SUBAGENT_RESULT_INLINE_CHARS) return answer
        runCatching {
            val dir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
            File(dir, "$childId.md").writeText(answer)
        }
        return buildString {
            append(answer.take(SUBAGENT_RESULT_INLINE_CHARS))
            append("\n\n[TRUNCATED: showing ${SUBAGENT_RESULT_INLINE_CHARS} of ${answer.length} chars. ")
            append("Full result saved to /tool_outputs/$childId.md — read it with workspace_read_file or shell if you need the rest.]")
        }
    }

private fun agentErrorJson(code: String, message: String): String =
    buildJsonObject {
        put("status", "error")
        put("error", "$code: $message")
    }.toString()

/** P0-2 c): 子代理超时（携带部分结果的 JSON，供 async 路径转 TIMEOUT 终态） */
internal class ChildAgentTimeoutException(val partialResultJson: String?) :
    RuntimeException("Child agent timed out")

/** P0-2 c): 子代理被 cancel_agent 取消（携带部分结果） */
internal class ChildAgentCancelledException(val partialResultJson: String?) :
    RuntimeException("Child agent cancelled")

    /** 子代理结束后把内存态落库（收尾 reasoning、刷新 updateAt），与主会话收尾一致 */
    private suspend fun saveChildAgentFinal(childId: Uuid) {
        val session = sessions[childId] ?: return
        session.mutationLock.withLock {
            val current = session.state.value
            val finished = current.copy(
                messageNodes = current.messageNodes.map { node ->
                    node.copy(messages = node.messages.map { it.finishReasoning() })
                },
                updateAt = Instant.now(),
            )
            saveConversation(childId, finished)
        }
    }

    /**
     * 删除会话及其全部子代理子会话。正在生成的会话先停止：
     * 父会话停止会级联取消运行中的子代理工具，其 NonCancellable 收尾可能把子会话重新落库，
     * 因此必须在停止完成之后再读取一次子会话列表并删除，避免删后复活成孤儿。
     * 返回被删除的子会话 + 本会话，供 UI 判断当前页跳转。
     */
    suspend fun deleteConversationTree(conversation: Conversation): List<Conversation> {
        if (sessions[conversation.id]?.isGenerating == true) {
            stopGeneration(conversation.id)
        }
        conversationRepo.getSubconversationsOfParentOnce(conversation.id).forEach { sub ->
            if (sessions[sub.id]?.isGenerating == true) {
                stopGeneration(sub.id)
            }
        }
        val subs = conversationRepo.getSubconversationsOfParentOnce(conversation.id)
        subs.forEach { conversationRepo.deleteConversation(it) }
        conversationRepo.deleteConversation(conversation)
        return subs + conversation
    }

    /** 父会话迁移助手时，子代理子会话跟随：先同步活跃 session 内存态，再批量改库 */
    suspend fun moveSubconversationsToAssistant(parentId: Uuid, targetAssistantId: Uuid) {
        sessions.values
            .filter { it.state.value.parentConversationId == parentId }
            .forEach { updateConversationState(it.id) { c -> c.copy(assistantId = targetAssistantId) } }
        conversationRepo.updateSubconversationsAssistant(parentId, targetAssistantId)
    }

    /**
     * 子代理工具集: 继承主代理的全部工具, 但重新收敛安全边界:
     * - 移除 spawn_agent 与 followup_agent (子代理不得再派生/追问) 与 ask_user (没有可交互的用户)
     * - 移除 manage_skill 的写能力 (P1-5): 子代理不得静默改技能库, save/delete 返回 rejected
     * - 需要用户审批的工具不静默放行 (子代理没有审批 UI, 放行等于绕过用户配置):
     *   按真实入参判定, 需要审批时返回明确错误, 由子代理转告主代理自行处理
     *
     * 同时通过 systemPrompt 给子代理下发明确的平台限制声明 (P1-2):
     * 把「工具不存在」升级为「平台明确拒绝」, 避免子代理自行排查浪费轮次
     */
    private suspend fun buildChildAgentTools(
        settings: Settings,
        assistant: Assistant,
        conversation: Conversation,
        useExternalWebSearch: Boolean,
        parentConversationId: Uuid,
        persona: SubagentPersona? = null,
    ): List<Tool> =
        buildAgentTools(
            settings = settings,
            assistant = assistant,
            conversation = conversation,
            useExternalWebSearch = useExternalWebSearch,
            parentConversationId = parentConversationId,
        )
            // ISSUE-04: 管理类工具仅限主代理。子代理往往无人监督批量运行，改共享配置
            // （MCP 注册表、技能库、定时任务）或覆盖主会话待办属于越权面；
            // 直接从工具集中剔除，而不是保留后拒绝——避免模型误以为自己拥有这些能力。
            .filter { tool ->
                tool.name !in PARENT_ONLY_TOOL_NAMES &&
                    tool.name != SPAWN_AGENT_TOOL_NAME &&
                    tool.name != FOLLOWUP_AGENT_TOOL_NAME &&
                    tool.name != ASK_USER_TOOL_NAME
            }
            .map { tool ->
                when {
                    // 空 JSON 预判: 无入参就需审批的工具 (如 shell/审批类 MCP) 直接包装拦截
                    !tool.needsApproval(JsonObject(emptyMap())) -> tool

                    else -> tool.copy(
                        execute = { args ->
                            if (tool.needsApproval(args)) {
                                error(
                                    "Tool '${tool.name}' requires user approval, which is unavailable in child agents. " +
                                        "Report this to the main agent so it can run the tool itself (with approval) instead."
                                )
                            }
                            tool.execute(args)
                        }
                    )
                }
            }
            .map { tool ->
                // P1-2: 给子代理的第一个工具附加平台限制声明, 经 tool.systemPrompt 注入系统提示。
                // 选型说明: 选「声明式拒绝」而非「下发 spawn_agent 再返回 rejected」——
                // 声明在会话开始即可见, 不浪费一次注定失败的调用轮
                if (tool.name == "workspace_read_file") {
                    tool.copy(systemPrompt = { _, _ -> CHILD_AGENT_PLATFORM_DECLARATION })
                } else {
                    tool
                }
            }
            // 角色白名单：只保留允许的工具（空集 = 不限制），在平台声明之后执行以免误删声明载体
            .let { tools ->
                if (persona == null || persona.allowedTools.isEmpty()) {
                    tools
                } else {
                    tools.filter { it.name in persona.allowedTools }
                }
            }

    /**
     * 聊天模型无视觉能力且用户配置了视觉模型时，提供 vision_analyze 工具。
     * 主 agent 与子 agent 共用 buildAgentTools，子代理自动继承。
     */
    private suspend fun createVisionToolIfReady(
        settings: Settings,
        assistant: Assistant,
        conversation: Conversation,
    ): Tool? {
        val chatModel = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: return null
        if (Modality.IMAGE in chatModel.inputModalities) return null
        val visionModel = settings.findModelById(settings.visionModelId) ?: return null
        if (Modality.IMAGE !in visionModel.inputModalities) return null
        val provider = visionModel.findProvider(settings.providers) ?: return null
        return createVisionTool(
            visionModel = visionModel,
            provider = provider,
            providerManager = providerManager,
            workspaceId = assistant.workspaceId?.toString(),
            workspaceRepository = workspaceRepository,
            filesManager = filesManager,
            context = context,
        )
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String? = null): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
    }

    // ---- 检查无效消息 ----

    private suspend fun checkInvalidMessages(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        session.mutationLock.withLock {
        val conversation = session.state.value
        // 快速路径：没有任何待修复节点时直接返回，避免每次发送前全量重建（长会话下是纯浪费）
        val needsFix = conversation.messageNodes.any { node ->
            node.messages.isEmpty() ||
                node.selectIndex !in node.messages.indices ||
                node.messages.getOrNull(node.selectIndex)?.getTools()?.any { !it.isExecuted } == true
        }
        if (!needsFix) return@withLock
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool = node.currentMessage.getTools().any {
                    !it.isExecuted && it.approvalState.canResumeToolExecution()
                }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // 中断遗留的未执行工具（如进程被杀）：补错误输出而不是整条消息移除，
                // 避免那条回复在历史里静默消失
                val fixedMessage = node.currentMessage.finishPendingTools { tool ->
                    tool.copy(
                        output = listOf(
                            UIMessagePart.Text(
                                """{"status":"error","error":"Tool execution was interrupted before finishing."}"""
                            )
                        )
                    )
                }
                return@mapIndexed node.copy(
                    messages = node.messages.map { message ->
                        if (message.id == fixedMessage.id) fixedMessage else message
                    }
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
        }
    }

    /**
     * 生成正常结束但没有任何可见正文时，把原因告诉用户。
     *
     * 典型场景：思考模型把输出预算耗在思考里（finish_reason=length）、供应商内容过滤、
     * 或模型只回了思考。过去这些均静默结束，表现为「思考完就不回复」。
     */
    private fun notifyIfEmptyReply(conversation: Conversation) {
        val last = conversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT } ?: return
        // 工具调用轮（含等待审批）本轮本就无正文，不是空回复
        if (last.getTools().isNotEmpty()) return
        if (last.parts.any { it is UIMessagePart.ServerTool }) return
        val hasVisibleText = last.parts.any { it is UIMessagePart.Text && it.text.isNotBlank() }
        if (hasVisibleText) return
        val titleRes = when (last.finishReason) {
            "length" -> R.string.error_title_reply_truncated
            "content_filter" -> R.string.error_title_reply_filtered
            else -> R.string.error_title_reply_empty
        }
        addError(
            error = IllegalStateException(
                "model returned no visible answer (finishReason=${last.finishReason ?: "n/a"})"
            ),
            conversationId = conversation.id,
            title = context.getString(titleRes),
            solution = ChatErrorSolution.CheckModelSettings,
        )
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            )
        )
    }

    private suspend fun finishInterruptedPendingTools(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        session.mutationLock.withLock {
            val currentConversation = session.state.value
            val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
            val lastMessage = lastNode.currentMessage
            val updatedMessage = lastMessage.finishPendingTools(::cancelToolByUser)
            if (updatedMessage == lastMessage) {
                return
            }

            val updatedConversation = currentConversation.copy(
                messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                    messages = lastNode.messages.map { message ->
                        if (message.id == lastMessage.id) updatedMessage else message
                    }
                )
            )
            saveConversation(conversationId, updatedConversation)
        }
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return@withContext

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.getTitleModelOrDefault()
                ?: throw IllegalStateException(context.getString(R.string.error_title_model_not_found))
            val provider = model.findProvider(settings.providers)
                ?: throw IllegalStateException(context.getString(R.string.error_title_model_provider_not_found))

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .filterNot { it.isMemorySnapshot() }
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) })
                    ),
                ),
                params = backgroundTextGenerationParams(model, conversationId, settings.titleModelReasoningLevel),
            )

            // 生成完，conversation可能不是最新了，因此需要重新获取
            getOrCreateSession(conversationId).mutationLock.withLock {
                conversationRepo.getConversationById(conversation.id)?.let {
                    saveConversation(
                        conversationId,
                        it.copy(title = result.message.toText().trim())
                    )
                }
            }
        }.onFailure {
            it.printStackTrace()
            addError(
                error = it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckModelSettings,
            )
        }
    }

    // ---- 记忆快照 ----

    /**
     * 准备记忆快照：内容有变化时作为合成 USER 消息落库（插入在最新用户消息之前），
     * 保证请求前缀跨轮字节级稳定、持续命中供应商前缀缓存；内容未变化时不产生任何写入。
     */
    private suspend fun prepareMemorySnapshot(conversationId: Uuid, assistant: Assistant) {
        if (!assistant.enableMemory) return
        val session = getOrCreateSession(conversationId)
        val memories = memoryRepository.selectForPrompt(assistant.id.toString())
        session.mutationLock.withLock {
            val conversation = session.state.value
            if (memories.isEmpty() && conversation.messageNodes.none { it.currentMessage.isMemorySnapshot() }) return
            val updatedNodes = conversation.messageNodes.withMemorySnapshot(buildMemorySnapshotText(memories)) ?: return
            saveConversation(conversationId, conversation.copy(messageNodes = updatedNodes))
        }
    }

    // ---- 自动压缩对话历史 ----

    /**
     * agent/pre-step：发送前用占用估算（真实 usage 优先，缺失时本地估算）对比模型窗口，
     * 达到 AUTO_COMPRESS_THRESHOLD_RATIO 时把早期历史压缩为检查点摘要（对齐 deepseek-harness
     * compaction-basic）：摘要以 <compressed-summary> user 消息形式替换早期历史（是替换不是追加），
     * 保留尾部 AUTO_COMPRESS_RETAIN_RATIO 窗口原文；新摘要与旧检查点合并为单一摘要（harness 合并规则）。
     * 失败静默（不影响发送）。
     */
    private suspend fun autoCompressIfNeeded(conversationId: Uuid, assistant: Assistant) {
        val conversation = conversationRepo.getConversationById(conversationId) ?: return
        // 只看发送窗口：边界前的旧历史已压缩，不再参与占用判断
        if (conversation.windowNodes().size <= 2) return

        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(assistant.chatModelId)
            ?: settings.getCurrentChatModel()
            ?: return
        val window = model.effectiveContextLength()
        val threshold = (window * AUTO_COMPRESS_THRESHOLD_RATIO).toInt()
        val usedTokens = conversation.estimateWindowTokens(model)
        if (usedTokens < threshold) return

        Log.i(TAG, "autoCompressIfNeeded: $usedTokens / $window tokens >= threshold $threshold, compacting conversation $conversationId")
        compressToSummary(conversationId, conversation, settings, model, window)
    }

    /**
     * 压缩实现（对齐 harness compaction-basic + DSH 分段轨迹）：
     * 1. 保留策略 token 驱动：从尾部往前累加，预算 = 窗口 × retainRatio（至少留 2 条）；
     * 2. 其余发送窗口历史分块并行摘要，与旧摘要（prior checkpoint）合并为单一新摘要；
     * 3. **轨迹只追加**：不删除/改写任何消息节点，只落库新摘要与其压缩边界（boundaryNodeId）；
     *    请求组装时（requestWindowMessages）以检查点合成消息为新段起点，边界前节点保留在
     *    轨迹中但不发送 —— 旧前缀字节可回溯，检查点之后的前缀保持稳定。
     * 压缩使用对话模型（model 参数）。
     */
    private suspend fun compressToSummary(
        conversationId: Uuid,
        conversation: Conversation,
        settings: Settings,
        model: Model,
        windowTokens: Int,
    ): String {
        return runCatching {
            val provider = model.findProvider(settings.providers) ?: return@runCatching ""
            val providerHandler = providerManager.getProviderByType(provider)

            val maxMessagesPerChunk = 256
            // 只压缩发送窗口内的历史（prior checkpoint 已包含更早内容，参与合并）
            val allNodes = conversation.windowNodes()
            val allMessages = allNodes.map { it.currentMessage }

            // 保留策略（harness retainRatio）：从尾部往前累加，预算 = 窗口 × retainRatio，至少留 2 条
            val keepBudget = (windowTokens * AUTO_COMPRESS_RETAIN_RATIO).toInt()
            var keepCount = 0
            var keepTokens = 0
            for (message in allMessages.asReversed()) {
                val msgTokens = estimateTokenCount(listOf(message))
                if (keepCount >= 2 && keepTokens + msgTokens > keepBudget) break
                keepTokens += msgTokens
                keepCount++
            }
            val nodesToKeep = allNodes.takeLast(keepCount)
            val nodesToCompress = allNodes.drop(nodesToKeep.size)
            if (nodesToCompress.isEmpty()) return@runCatching ""
            val messagesToCompress = nodesToCompress.map { it.currentMessage }

            // 旧检查点作为 prior checkpoint 参与合并（harness：合并重写而非追加）
            val priorSummary = conversation.compressionSummaries.lastOrNull()?.content.orEmpty()
            val priorContext = if (priorSummary.isNotBlank()) {
                "PRIOR CHECKPOINT (merge this with the new conversation into one consolidated checkpoint):\n$priorSummary"
            } else ""

            fun splitMessages(messages: List<UIMessage>): List<List<UIMessage>> {
                if (messages.size <= maxMessagesPerChunk) return listOf(messages)
                val mid = messages.size / 2
                val left = splitMessages(messages.subList(0, mid))
                val right = splitMessages(messages.subList(mid, messages.size))
                return left + right
            }

            suspend fun compressMessages(messages: List<UIMessage>): String {
                val contentToCompress = messages.joinToString("\n\n") { it.summaryAsText(maxLength = 2000) }
                val prompt = settings.compressPrompt.applyPlaceholders(
                    "content" to contentToCompress,
                    "target_tokens" to AUTO_COMPRESS_TARGET_TOKENS.toString(),
                    "additional_context" to priorContext,
                    "locale" to Locale.getDefault().displayName
                )
                val result = providerHandler.generateText(
                    providerSetting = provider,
                    messages = listOf(UIMessage.user(prompt)),
                    params = backgroundTextGenerationParams(model, conversationId),
                )
                return result.message.toText().trim().takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Failed to generate compressed summary")
            }

            val summaries = coroutineScope {
                splitMessages(messagesToCompress)
                    .map { chunk -> async { compressMessages(chunk) } }
                    .awaitAll()
            }
            val combined = summaries.joinToString("\n\n")

            // 检查点行：user 角色 <compressed-summary> 消息（模型侧），UI 渲染为可见的压缩流程行
            // 轨迹只追加：不替换 messageNodes，只落库新摘要 + 压缩边界；
            // 保留窗口消息的 usage 不改写（轨迹不可变），占用估算在 estimateWindowTokens 里
            // 按「检查点之后是否有新回复」判定 usage 是否可信。
            val boundaryNodeId = nodesToCompress.lastOrNull()?.id
                ?: return@runCatching ""
            val newCompression = CompressionSummary(
                content = combined,
                messageCount = messagesToCompress.size + conversation.compressionSummaries.sumOf { it.messageCount },
                boundaryNodeId = boundaryNodeId,
            )
            getOrCreateSession(conversationId).mutationLock.withLock {
                saveConversation(conversationId, conversation.copy(compressionSummaries = listOf(newCompression)))
            }
            Log.i(
                TAG,
                "compressToSummary: compacted ${messagesToCompress.size} messages into a checkpoint " +
                    "(retained ${nodesToKeep.size} messages verbatim, trajectory append-only)"
            )
            // 压缩是会话内最大的一次缓存断裂事件（新段起点），双写应用内日志供观测
            Logging.log(
                TAG,
                "compressToSummary: compacted ${messagesToCompress.size} messages, retained ${nodesToKeep.size}"
            )
            combined
        }.onFailure { error ->
            Log.w(TAG, "compressToSummary: auto compaction failed", error)
            addError(
                error = error,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_compress_context),
                solution = ChatErrorSolution.CheckModelSettings,
            )
        }.getOrDefault("")
    }

    // ---- 对话状态更新 ----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        val previous = session.state.value
        // 文件引用检查开销与消息总量成正比：流式每 chunk 都会触发状态更新，
        // 只在消息规模缩小（可能删除了内容）时才做全量扫描。
        if (contentSize(conversation) < contentSize(previous)) {
            checkFilesDelete(conversation, previous)
        }
        session.state.value = conversation
    }

    /** 粗略衡量会话内容体量（节点/消息/part 计数），仅用于判断是否可能发生了内容删除 */
    private fun contentSize(conversation: Conversation): Int =
        conversation.messageNodes.sumOf { node ->
            node.messages.sumOf { message -> message.parts.size + 1 } + 1
        }

    suspend fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val session = getOrCreateSession(conversationId)
        session.mutationLock.withLock {
            updateConversation(conversationId, update(session.state.value))
        }
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 若该会话当前有活跃 session（正在查看或后台生成），先同步内存态再落库：
     * 否则仅改数据库 folder_id，而内存里那份 Conversation 仍是旧 folderId，
     * 后续任意 saveConversation(id, state.value) 会用整对象把 folder_id 覆盖回旧值，导致移动丢失。
     * 先改内存可确保这段窗口内的整对象保存也带上新 folderId。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        if (sessions.containsKey(conversationId)) {
            updateConversationState(conversationId) { it.copy(folderId = folderId) }
        }
        conversationRepo.updateConversationFolderId(conversationId, folderId)
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessions.values.any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 先把内存中归属该文件夹的活跃 session folderId 置空，再删库：
     * 否则 clearFolder 只改了数据库，而活跃 session 内存态仍指向该文件夹，
     * 后续整对象保存会写回一个已被删除的 folder_id，导致会话在列表中悬空。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        sessions.values
            .filter { it.state.value.folderId == folderId }
            .forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
        folderRepository.deleteFolder(folderId)
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val newFiles = newConversation.files
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }

        val updatedConversation = conversation.copy()
        updateConversation(conversationId, updatedConversation)

        if (!exists) {
            conversationRepo.insertConversation(updatedConversation)
        } else {
            conversationRepo.updateConversation(updatedConversation)
        }
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return

        val session = getOrCreateSession(conversationId)
        val settings = settingsStore.settingsFlow.first()
        session.mutationLock.withLock {
            val currentConversation = session.state.value
            val assistant = settings.getAssistantById(currentConversation.assistantId)
                ?: settings.getCurrentAssistant()
            val processedParts = preprocessUserInputParts(parts, assistant)
            var edited = false

            val updatedNodes = currentConversation.messageNodes.map { node ->
                if (!node.messages.any { it.id == messageId }) {
                    return@map node
                }
                edited = true

                node.copy(
                    messages = node.messages + UIMessage(
                        role = node.role,
                        parts = processedParts,
                    ),
                    selectIndex = node.messages.size
                )
            }

            if (!edited) return

            saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
        }
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw IllegalArgumentException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        val existingTitles = conversationRepo
            .getConversationsOfAssistant(currentConversation.assistantId)
            .first()
            .mapTo(mutableSetOf()) { it.title }
        val forkConversation = createForkConversation(currentConversation, copiedNodes, existingTitles)

        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val session = getOrCreateSession(conversationId)
        session.mutationLock.withLock {
            val currentConversation = session.state.value
            val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
                ?: throw IllegalArgumentException("Message node not found")

            if (selectIndex !in targetNode.messages.indices) {
                throw IllegalArgumentException("Invalid selectIndex")
            }

            if (targetNode.selectIndex == selectIndex) {
                return
            }

            val updatedNodes = currentConversation.messageNodes.map { node ->
                if (node.id == nodeId) {
                    node.copy(selectIndex = selectIndex)
                } else {
                    node
                }
            }

            saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
        }
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val session = getOrCreateSession(conversationId)
        session.mutationLock.withLock {
            val updatedConversation = buildConversationAfterMessageDelete(session.state.value, messageId)

            if (updatedConversation == null) {
                if (failIfMissing) {
                    throw IllegalArgumentException("Message not found")
                }
                return
            }

            saveConversation(conversationId, updatedConversation)
        }
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private suspend fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        suspend fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        val jobs = synchronized(session) {
            session.cancelJobs()
        }
        if (jobs.isEmpty()) return
        jobs.forEach { runCatching { it.join() } }
        finishInterruptedPendingTools(conversationId)
    }
}
