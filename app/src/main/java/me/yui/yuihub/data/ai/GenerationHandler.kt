package me.yui.yuihub.data.ai

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.ProviderRetryPolicy
import me.rerere.common.android.Logging
import me.rerere.ai.ui.handleTextGenerationResult
import me.rerere.ai.ui.limitContext
import me.yui.yuihub.R
import me.yui.yuihub.data.ai.transformers.InputMessageTransformer
import me.yui.yuihub.data.ai.transformers.MessageTransformer
import me.yui.yuihub.data.ai.transformers.OutputMessageTransformer
import me.yui.yuihub.data.files.FileFolders
import me.yui.yuihub.data.ai.transformers.onGenerationFinish
import me.yui.yuihub.data.ai.transformers.transforms
import me.yui.yuihub.data.ai.transformers.visualTransforms
import me.yui.yuihub.data.ai.tools.buildMemoryTools
import me.yui.yuihub.data.datastore.Settings
import me.yui.yuihub.data.datastore.findProvider
import me.yui.yuihub.data.model.Assistant
import me.yui.yuihub.data.repository.MemoryRepository
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GenerationHandler"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024
private const val MAX_PROVIDER_NETWORK_RETRIES = 3
private const val INITIAL_PROVIDER_RETRY_DELAY_MS = 1_000L

private class StreamChunkHandlingException(cause: Throwable) : RuntimeException(cause)

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk
}

class GenerationHandler(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryRepo: MemoryRepository,
) {
    private val retryPolicy = ProviderRetryPolicy(
        maxRetries = MAX_PROVIDER_NETWORK_RETRIES,
        initialDelayMs = INITIAL_PROVIDER_RETRY_DELAY_MS,
    )
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationId: Uuid? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        var messages: List<UIMessage> = messages

        for (stepIndex in 0 until maxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")

            val toolsInternal = buildList {
                Log.i(TAG, "generateInternal: build tools($assistant)")
                if (assistant.enableMemory) {
                    val memoryAssistantId = assistant.id.toString()
                    buildMemoryTools(
                        json = json,
                        onCreation = { content, category, importance ->
                            memoryRepo.addMemory(
                                memoryAssistantId,
                                content,
                                importance = importance ?: 0.6f,
                                category = category,
                            )
                        },
                        onUpdate = { id, content, category, importance ->
                            memoryRepo.updateMemory(id, content, importance = importance, category = category)
                                ?: throw IllegalStateException("Memory record #$id no longer exists")
                        },
                        onDelete = { id ->
                            memoryRepo.deleteMemory(id)
                        }
                    ).let(this::addAll)
                }
                addAll(tools)
            }

            // Check if we have tool calls ready to continue after user interaction.
            val pendingTools = messages.lastOrNull()?.getTools()?.filter {
                it.canResumeExecution
            } ?: emptyList()

            val toolsToProcess: List<UIMessagePart.Tool>

            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
                generateInternal(
                    assistant = assistant,
                    settings = settings,
                    messages = messages,
                    onUpdateMessages = {
                        messages = it.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings
                        )
                        emit(
                            GenerationChunk.Messages(
                                messages.visualTransforms(
                                    transformers = outputTransformers,
                                    context = context,
                                    model = model,
                                    assistant = assistant,
                                    settings = settings
                                )
                            )
                        )
                    },
                    transformers = inputTransformers,
                    model = model,
                    providerImpl = providerImpl,
                    provider = provider,
                    tools = toolsInternal,
                    stream = assistant.streamOutput,
                    processingStatus = processingStatus,
                    conversationSystemPrompt = conversationSystemPrompt,
                    conversationId = conversationId,
                    conversationModeInjectionIds = conversationModeInjectionIds,
                    conversationLorebookIds = conversationLorebookIds,
                    workspaceCwd = workspaceCwd,
                )
                messages = messages.visualTransforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                    finishedAt = Clock.System.now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                )
                emit(GenerationChunk.Messages(messages))

                val tools = messages.last().getTools().filter { !it.isExecuted }
                if (tools.isEmpty()) {
                    // no tool calls, break
                    break
                }

                // Check for tools that need approval
                var hasPendingApproval = false
                val updatedTools = tools.map { tool ->
                    val toolDef = toolsInternal.find { it.name == tool.toolName }
                    when {
                        // Tool needs approval and state is Auto -> set to Pending
                        toolDef?.needsApproval(tool.inputAsJson()) == true &&
                            tool.approvalState is ToolApprovalState.Auto -> {
                            hasPendingApproval = true
                            tool.copy(approvalState = ToolApprovalState.Pending)
                        }
                        // State is Pending -> keep waiting
                        tool.approvalState is ToolApprovalState.Pending -> {
                            hasPendingApproval = true
                            tool
                        }

                        else -> tool
                    }
                }

                // If any tools were updated to Pending, update the message and break
                if (updatedTools != tools) {
                    val lastMessage = messages.last()
                    val updatedParts = lastMessage.parts.map { part ->
                        if (part is UIMessagePart.Tool) {
                            updatedTools.find { it.toolCallId == part.toolCallId } ?: part
                        } else {
                            part
                        }
                    }
                    messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                    emit(GenerationChunk.Messages(messages))
                }

                // If there are pending approvals, break and wait for user
                if (hasPendingApproval) {
                    Log.i(TAG, "generateText: waiting for tool approval")
                    break
                }

                toolsToProcess = updatedTools
            } else {
                // Resuming after user interaction - use the resumable tools directly.
                Log.i(TAG, "generateText: resuming with ${pendingTools.size} resumable tools")
                toolsToProcess = messages.last().getTools().filter { it.canResumeExecution }
            }

            // Handle tools (execute approved tools, handle denied tools)
            // 每完成一个工具立即 emit, 避免整批执行期间 UI 无变化让用户误以为卡住
            val executedTools = arrayListOf<UIMessagePart.Tool>()
            suspend fun pushExecuted(updated: UIMessagePart.Tool) {
                executedTools += updated
                val lastMessage = messages.last()
                val parts = lastMessage.parts.map { part ->
                    if (part is UIMessagePart.Tool) {
                        (executedTools.find { it.toolCallId == part.toolCallId } ?: part)
                    } else part
                }
                messages = messages.dropLast(1) + lastMessage.copy(parts = parts)
                emit(GenerationChunk.Messages(messages))
            }
            toolsToProcess.forEach { tool ->
                when (tool.approvalState) {
                    is ToolApprovalState.Denied -> {
                        // Tool was denied by user
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        pushExecuted(
                            tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put(
                                                    "error",
                                                    JsonPrimitive("Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}")
                                                )
                                            }
                                        )
                                    )
                                )
                            )
                        )
                    }

                    is ToolApprovalState.Answered -> {
                        // Tool was answered by user (e.g., ask_user tool)
                        val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                        pushExecuted(
                            tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(answer)
                                )
                            )
                        )
                    }

                    is ToolApprovalState.Pending -> {
                        // Should not reach here, but just in case
                    }

                    else -> {
                        // Auto or Approved - execute the tool
                        runCatching {
                            val toolDef = toolsInternal.find { toolDef -> toolDef.name == tool.toolName }
                                ?: error("Tool ${tool.toolName} not found")
                            val args = runCatching {
                                json.parseToJsonElement(tool.input.ifBlank { "{}" })
                            }.getOrElse {
                                error("Invalid tool arguments JSON for ${tool.toolName}: ${it.message}")
                            }
                            Log.i(TAG, "generateText: executing tool ${toolDef.name} with args: $args")
                            val toolStartMs = SystemClock.elapsedRealtime()
                            val result = withToolProgress(processingStatus, toolDef.name) {
                                toolDef.execute(args)
                            }
                            Log.i(TAG, "generateText: tool ${toolDef.name} took ${SystemClock.elapsedRealtime() - toolStartMs}ms")
                            pushExecuted(
                                tool.copy(
                                    output = maybeTruncateToolOutput(tool.toolCallId, result)
                                )
                            )
                        }.onFailure {
                            // 取消必须向上传播，否则停止生成会被误报为工具执行错误
                            if (it is CancellationException) throw it
                            it.printStackTrace()
                            pushExecuted(
                                tool.copy(
                                    output = listOf(
                                        UIMessagePart.Text(
                                            json.encodeToString(
                                                buildJsonObject {
                                                    put(
                                                        "error",
                                                        JsonPrimitive(buildString {
                                                            append("[${it.javaClass.name}] ${it.message}")
                                                            // 堆栈全量进历史会白占上下文：截到 2K 保留关键帧
                                                            append("\n${it.stackTraceToString().take(2048)}")
                                                        })
                                                    )
                                                }
                                            )
                                        )
                                    )
                                )
                            )
                        }
                    }
                }
            }

            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
                break
            }

            // 工具结果已在 pushExecuted 中逐个 emit 并更新 messages, 这里只需做输出转换
            emit(
                GenerationChunk.Messages(
                    messages.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings
                    )
                )
            )
        }

    }.flowOn(Dispatchers.IO)

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        stream: Boolean,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationId: Uuid? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
    ) {
        // 分段计时（构建/首字/流续/工具），用于定位『一顿顿』的开销分布
        val buildStartMs = SystemClock.elapsedRealtime()
        val internalMessages = buildList {
            val system = buildString {
                val effectiveSystemPrompt =
                    if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                        conversationSystemPrompt
                    } else {
                        assistant.systemPrompt
                    }
                if (effectiveSystemPrompt.isNotBlank()) {
                    append(effectiveSystemPrompt)
                }

                // 工具prompt
                tools.forEach { tool ->
                    appendLine()
                    append(tool.systemPrompt(model, messages))
                }
                if (tools.isNotEmpty()) {
                    appendLine()
                    append(AGENT_TOOL_STYLE_PROMPT)
                }
            }
            if (system.isNotBlank()) {
                add(UIMessage.system(prompt = system).copy(isSynthetic = true))
            }
            addAll(messages.limitContext(assistant.contextMessageLimit))
        }.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            conversationModeInjectionIds = conversationModeInjectionIds,
            conversationLorebookIds = conversationLorebookIds,
            processingStatus = processingStatus,
            workspaceCwd = workspaceCwd,
        )

        var messages: List<UIMessage> = messages
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            },
            sessionId = conversationId?.toString(),
        )
        val buildMs = SystemClock.elapsedRealtime() - buildStartMs
        // 前缀指纹：对最终发送的 system 文本与工具 schema 做 SHA-256（DSH 字节级前缀稳定的观测手段）；
        // 同一会话连续轮次不变 → 供应商端前缀缓存命中；变化 → 前缀断裂，可在日志中直接定位
        val prefixFp = prefixFingerprint(internalMessages, tools)
        try {
            if (stream) {
                // 每次重试都从本次模型调用开始前的消息快照重新合并，避免将重试响应
                // 追加到已经展示的半截回复后面。预先创建助手消息可让所有尝试复用同一 ID，
                // ChatService 因而会覆盖当前分支，而不是创建新的候选消息。
                val responseBaseMessages =
                    if (messages.lastOrNull()?.role == MessageRole.ASSISTANT) {
                        messages
                    } else {
                        messages + UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = emptyList(),
                            modelId = model.id,
                        )
                    }
                var retryCount = 0

                while (true) {
                    val streamChunkHandler = StreamChunkHandler(model)
                    var attemptMessages = responseBaseMessages
                    val attemptStartMs = SystemClock.elapsedRealtime()
                    var firstChunkMs = -1L
                    try {
                        providerImpl.streamText(
                            providerSetting = provider,
                            messages = internalMessages,
                            params = params
                        ).collect { chunk ->
                            try {
                                if (firstChunkMs < 0) {
                                    firstChunkMs = SystemClock.elapsedRealtime() - attemptStartMs
                                }
                                if (retryCount > 0) {
                                    processingStatus.value = null
                                }
                                attemptMessages = streamChunkHandler.handle(attemptMessages, chunk)
                                onUpdateMessages(attemptMessages)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                // 下游消息转换或 UI 更新失败不属于网络故障，不能重放模型请求。
                                throw StreamChunkHandlingException(error)
                            }
                        }
                        messages = attemptMessages
                        // 速度统计口径（vc220）：
                        // 分子 = 本次请求的输出 tokens（usage 在同一条消息上被后续请求覆盖，
                        //   工具循环必须逐次累加才完整）；
                        // 分母 = 首字之后的纯生成时长，不含排队/连接/prefill ——
                        //   这些是「等待模型开始输出」的时间，不该算进输出速度。
                        val attemptDurationMs = SystemClock.elapsedRealtime() - attemptStartMs
                        val pureGenerationMs = (attemptDurationMs - firstChunkMs).coerceAtLeast(0L)
                        val attemptTokens = streamChunkHandler.attemptUsage?.completionTokens ?: 0
                        val lastMessage = messages.lastOrNull()
                        if (lastMessage?.role == MessageRole.ASSISTANT) {
                            messages = messages.dropLast(1) + lastMessage.copy(
                                generationDurationMs = (lastMessage.generationDurationMs ?: 0L) + pureGenerationMs,
                                generationOutputTokens = (lastMessage.generationOutputTokens ?: 0) + attemptTokens,
                            )
                            onUpdateMessages(messages)
                        }
                        val perfLine = "generateInternal: build=${buildMs}ms prefix=$prefixFp ttft=${firstChunkMs}ms " +
                            "gen=${pureGenerationMs}ms total=${attemptDurationMs}ms out=$attemptTokens retries=$retryCount"
                        Log.i(TAG, perfLine)
                        Log.i(TAG, "generateInternal: msgs=${messageFingerprints(internalMessages)}")
                        // 双写应用内日志缓冲：手机上无需 adb 即可对比连续两轮的 prefix/msgs 定位缓存断裂点
                        Logging.log(TAG, "$perfLine msgs=${messageFingerprints(internalMessages)}")
                        break
                    } catch (error: Throwable) {
                        if (error is StreamChunkHandlingException) {
                            throw error.cause ?: error
                        }
                        retryCount = awaitNetworkRetryOrThrow(
                            error = error,
                            retryCount = retryCount,
                            processingStatus = processingStatus,
                            enabled = settings.networkSetting.enableAutoRetry,
                        )
                    }
                }
            } else {
                val attemptStartMs = SystemClock.elapsedRealtime()
                val result = executeProviderRequestWithRetry(
                    processingStatus = processingStatus,
                    enabled = settings.networkSetting.enableAutoRetry,
                ) {
                    providerImpl.generateText(
                        providerSetting = provider,
                        messages = internalMessages,
                        params = params,
                    )
                }
                val attemptDurationMs = SystemClock.elapsedRealtime() - attemptStartMs
                messages = messages.handleTextGenerationResult(result = result, model = model)
                val lastMessage = messages.lastOrNull()
                if (lastMessage?.role == MessageRole.ASSISTANT) {
                    // 非流式无法拆分 TTFT，分母用总时长（速度偏低是口径限制，不做假修正）
                    messages = messages.dropLast(1) + lastMessage.copy(
                        generationDurationMs = (lastMessage.generationDurationMs ?: 0L) + attemptDurationMs,
                        generationOutputTokens = (lastMessage.generationOutputTokens ?: 0) +
                            (result.usage?.completionTokens ?: 0),
                    )
                }
                onUpdateMessages(messages)
            }
        } finally {
            processingStatus.value = null
        }
    }

    private suspend fun <T> executeProviderRequestWithRetry(
        processingStatus: MutableStateFlow<String?>,
        enabled: Boolean,
        block: suspend () -> T,
    ): T {
        var retryCount = 0
        while (true) {
            try {
                return block()
            } catch (error: Throwable) {
                retryCount = awaitNetworkRetryOrThrow(
                    error = error,
                    retryCount = retryCount,
                    processingStatus = processingStatus,
                    enabled = enabled,
                )
            }
        }
    }

    private suspend fun awaitNetworkRetryOrThrow(
        error: Throwable,
        retryCount: Int,
        processingStatus: MutableStateFlow<String?>,
        enabled: Boolean,
    ): Int {
        // 用户主动停止生成时，底层连接也可能以 IOException("canceled") 收尾；
        // 先检查协程状态，确保取消不会被当作网络波动重新拉起。
        currentCoroutineContext().ensureActive()
        if (!enabled) throw error
        // 重试决策（错误分类/退避/抖动/Retry-After）收敛在 ProviderRetryPolicy 中间件，
        // 生成循环只负责执行与展示
        val (retryDelay, nextRetryCount, reason) = when (val decision = retryPolicy.decide(error, retryCount)) {
            is ProviderRetryPolicy.Decision.Retry -> Triple(decision.delayMs, decision.attempt, decision.reason)
            ProviderRetryPolicy.Decision.Fail -> throw error
        }
        processingStatus.value = context.getString(
            R.string.chat_generation_network_retrying,
            getNetworkErrorMessage(error),
            nextRetryCount,
            MAX_PROVIDER_NETWORK_RETRIES,
        )
        Log.w(
            TAG,
            "Provider request failed ($reason), retrying in ${retryDelay}ms " +
                    "($nextRetryCount/$MAX_PROVIDER_NETWORK_RETRIES)",
            error,
        )
        delay(retryDelay)
        return nextRetryCount
    }

    private fun getNetworkErrorMessage(error: Throwable): String {
        if (error is HttpException) {
            // 限流/服务端错误展示供应商返回的摘要，比分类文案更有诊断价值
            return error.message?.take(160) ?: "HTTP ${error.code ?: "?"}"
        }
        val messageRes = when (error) {
            is UnknownHostException -> R.string.chat_generation_network_unknown_host
            is SocketTimeoutException -> R.string.chat_generation_network_timeout
            is ConnectException, is NoRouteToHostException -> R.string.chat_generation_network_unreachable
            else -> R.string.chat_generation_network_disconnected
        }
        return context.getString(messageRes)
    }

    /**
     * 请求前缀指纹：对最终发送的 system 文本与工具定义（名称+描述+schema）做 SHA-256。
     * DSH 字节级前缀稳定的观测手段：同一会话连续轮次该值不变 → 前缀缓存命中；
     * 变化 → 从 token 0 断裂，结合日志能直接定位是哪次改动破坏了缓存。
     */
    private fun prefixFingerprint(messages: List<UIMessage>, tools: List<Tool>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        messages.firstOrNull { it.role == MessageRole.SYSTEM }?.let { system ->
            system.parts.forEach { part ->
                if (part is UIMessagePart.Text) digest.update(part.text.toByteArray())
            }
        }
        tools.forEach { tool ->
            digest.update(tool.name.toByteArray())
            digest.update(tool.description.toByteArray())
            tool.parameters()?.let { schema ->
                digest.update(json.encodeToString(InputSchema.serializer(), schema).toByteArray())
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(8)
    }

    /**
     * 逐消息指纹：每条消息 role+文本 的 SHA-256 前 4 位。
     * 对比连续两轮的序列，从右往左第一个不同处即缓存断裂点（用于定位 48% 类命中率问题）。
     */
    private fun messageFingerprints(messages: List<UIMessage>): String =
        messages.joinToString(",", "[", "]") { message ->
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(message.role.name.toByteArray())
            message.parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> digest.update(part.text.toByteArray())
                    is UIMessagePart.Reasoning -> digest.update(part.reasoning.toByteArray())
                    is UIMessagePart.Tool -> {
                        digest.update(part.toolName.toByteArray())
                        digest.update(part.input.toByteArray())
                        digest.update(part.output.hashCode().toString().toByteArray())
                    }
                    else -> digest.update(part.hashCode().toString().toByteArray())
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }.take(4)
        }

    /**
     * 工具执行期间展示进度状态: 「执行 <工具名>…」并每秒刷新已用时,
     * 长耗时工具不再让用户误以为卡住。结束后恢复原状态值。
     */
    private suspend fun <T> withToolProgress(
        processingStatus: MutableStateFlow<String?>,
        toolName: String,
        block: suspend () -> T,
    ): T {
        if (processingStatus.value != null) {
            return try {
                block()
            } finally {
                // 保留调用方原有的重试提示, 不强行清空
            }
        }
        val startedAt = System.currentTimeMillis()
        return try {
            coroutineScope {
                val ticker = launch {
                    while (true) {
                        val elapsed = (System.currentTimeMillis() - startedAt) / 1_000
                        processingStatus.value = context.getString(
                            R.string.chat_generation_tool_running,
                            toolName,
                            elapsed,
                        )
                        delay(1_000)
                    }
                }
                try {
                    block()
                } finally {
                    ticker.cancel()
                    processingStatus.value = null
                }
            }
        } catch (e: CancellationException) {
            processingStatus.value = null
            throw e
        }
    }

    private fun maybeTruncateToolOutput(
        toolCallId: String,
        output: List<UIMessagePart>,
    ): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val nonTextParts = output.filter { it !is UIMessagePart.Text }
        val totalChars = textParts.sumOf { it.text.length }

        // 截断始终生效：超大输出原样进历史会撑爆窗口并抖动缓存；
        // 与是否绑定 workspace 无关（MCP/搜索工具同样受限）
        if (totalChars <= MAX_TOOL_OUTPUT_CHARS) return output

        Log.i(TAG, "maybeTruncateToolOutput: truncating tool $toolCallId output ($totalChars chars)")

        val fullText = textParts.joinToString("\n") { it.text }
        val preview = fullText.take(TOOL_OUTPUT_PREVIEW_CHARS)

        val fileName = "${toolCallId}.txt"
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    append("If you have shell access, read it in chunks: `cat /tool_outputs/$fileName` or `grep \"pattern\" /tool_outputs/$fileName`.")
                    appendLine()
                    appendLine()
                    append(preview)
                }
            )
        ) + nonTextParts
    }

}
