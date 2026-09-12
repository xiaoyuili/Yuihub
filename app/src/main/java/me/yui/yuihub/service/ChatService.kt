package me.yui.yuihub.service

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
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
import me.yui.yuihub.data.ai.prompts.buildMemorySnapshotText
import me.yui.yuihub.data.ai.prompts.isMemorySnapshot
import me.yui.yuihub.data.ai.prompts.withMemorySnapshot
import kotlinx.serialization.json.jsonObject
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
import me.yui.yuihub.data.ai.tools.local.LocalTools
import me.yui.yuihub.data.ai.tools.createSearchTools
import me.yui.yuihub.data.ai.tools.createMcpManageTools
import me.yui.yuihub.data.ai.tools.createSkillManageTools
import me.yui.yuihub.data.ai.tools.createSkillTools
import me.yui.yuihub.data.ai.tools.createWorkspaceTools
import me.yui.yuihub.data.ai.tools.createSubagentTool
import me.yui.yuihub.data.ai.tools.createVisionTool
import me.yui.yuihub.data.files.SkillManager
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
import me.yui.yuihub.data.model.replaceRegexes
import me.yui.yuihub.data.model.toMessageNode
import me.yui.yuihub.data.repository.ConversationRepository
import me.yui.yuihub.data.repository.FolderRepository
import me.yui.yuihub.data.repository.MemoryRepository
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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

internal fun backgroundTextGenerationParams(
    model: Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}

internal fun createForkConversation(
    source: Conversation,
    messageNodes: List<MessageNode>,
): Conversation = Conversation(
    id = Uuid.random(),
    assistantId = source.assistantId,
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
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)

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
                    allowSubagent = true,
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
            // 兜底取消 Live Update 通知（生成开始前失败时 onCompletion 不会执行）
            appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))

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
        allowSubagent: Boolean,
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
        if (allowSubagent) {
            add(
                createSubagentTool { description, prompt ->
                    runChildAgent(
                        parentConversationId = parentConversationId,
                        settings = settings,
                        assistant = assistant,
                        conversation = conversation,
                        useExternalWebSearch = useExternalWebSearch,
                        description = description,
                        prompt = prompt,
                    )
                }
            )
        }
        createVisionToolIfReady(settings, assistant, conversation)?.let(::add)
        }
        return tools
    }

    // harness spawn-in-process：子 agent 空会话、继承 workspace/model/tools，禁止再派生子 agent
    private suspend fun runChildAgent(
        parentConversationId: Uuid,
        settings: Settings,
        assistant: Assistant,
        conversation: Conversation,
        useExternalWebSearch: Boolean,
        description: String,
        prompt: String,
    ): String {
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: error("Model not found for child agent")
        val childId = Uuid.random()
        val childMessages = listOf(UIMessage.user(prompt))
        val childTools = buildAgentTools(
            settings = settings,
            assistant = assistant,
            conversation = conversation,
            useExternalWebSearch = useExternalWebSearch,
            allowSubagent = false,
            parentConversationId = parentConversationId,
        ).filter { it.name != "ask_user" }.map { it.copy(needsApproval = { false }) }
        var latest = childMessages
        subagentManager.start(
            childId = childId,
            parentConversationId = parentConversationId,
            description = description,
        )
        try {
            generationHandler.generateText(
                settings = settings,
                model = model,
                messages = childMessages,
                assistant = assistant.copy(streamOutput = false),
                conversationId = childId,
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
                tools = childTools,
                maxSteps = 32,
            ).collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        latest = chunk.messages
                        // 实时上报子代理进度, 供聊天页过程演示
                        subagentManager.updateMessages(childId, chunk.messages)
                    }
                }
            }
        } catch (e: CancellationException) {
            subagentManager.finish(
                childId = childId,
                result = "cancelled: ${e.message.orEmpty()}"
            )
            throw e
        }
        conversationRepo.recordTokenUsage(parentConversationId.toString(), latest)
        val answer = latest.lastOrNull { it.role == MessageRole.ASSISTANT }?.toText()?.trim().orEmpty()
        val finalAnswer = answer.ifBlank { "Child agent '$description' finished with no text output." }
        subagentManager.finish(childId, finalAnswer)
        return finalAnswer
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
            val model = settings.getTitleModelOrDefault() ?: return@runCatching
            val provider = model.findProvider(settings.providers) ?: return@runCatching

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
                params = backgroundTextGenerationParams(model, settings.titleModelReasoningLevel),
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
                    params = backgroundTextGenerationParams(model),
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

        val forkConversation = createForkConversation(currentConversation, copiedNodes)

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
