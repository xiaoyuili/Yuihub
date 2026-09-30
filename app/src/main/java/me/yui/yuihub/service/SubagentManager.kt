package me.yui.yuihub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

/**
 * 子代理运行状态。消息列表随子代理生成实时更新, 供聊天 UI 展示「过程演示」,
 * 避免长时间运行时用户以为卡住。
 */
data class SubagentRun(
    val childId: Uuid,
    val parentConversationId: Uuid,
    val description: String,
    val startedAt: Long,
    val messages: List<UIMessage> = emptyList(),
) {
    val elapsedMillis: Long get() = System.currentTimeMillis() - startedAt
}

/**
 * P0-2: async 模式的子代理任务句柄。
 * spawn_agent(async=true) 创建后立即返回 taskId=childId，父代理经 poll_agent 轮询状态/取结果。
 */
data class SubagentTask(
    val taskId: Uuid,
    val parentConversationId: Uuid,
    val description: String,
    val status: SubagentTaskStatus,
    val startedAt: Long,
    val endedAt: Long? = null,
    // 完成后的结构化结果 JSON（与同步返回一致）；未完成为 null
    val resultJson: String? = null,
    // 会话回收时间（末次活动 + TTL），null 表示未过期
    val expiresAt: Long? = null,
)

enum class SubagentTaskStatus { RUNNING, COMPLETED, FAILED, CANCELLED, TIMEOUT }

/**
 * 进程内子代理注册表: childId → 运行状态。
 * 主会话与子代理共用同一个 ChatService 实例, 以 childId 隔离。
 * 只跟踪运行中的子代理, 完成或失败即注销 (结果已随工具输出留在会话消息里, 无需常驻内存);
 * 注销前的僵尸 run 会泄漏内存并被后续运行的 UI 提示误显示。
 *
 * P0-2: 另维护 async 任务的终态表（含结果 JSON 与 TTL），供 poll_agent 取回；
 * 超过 [SESSION_TTL_MS] 未活动的会话被 [collectExpired] 回收，followup 报 AGENT_SESSION_NOT_FOUND(expired)。
 */
class SubagentManager {
    private val _runs = MutableStateFlow<Map<Uuid, SubagentRun>>(emptyMap())
    val runs: StateFlow<Map<Uuid, SubagentRun>> = _runs.asStateFlow()

    /** async 任务终态表: taskId → task（完成后仍保留供 poll，由 TTL 清理） */
    private val _tasks = MutableStateFlow<Map<Uuid, SubagentTask>>(emptyMap())
    val tasks: StateFlow<Map<Uuid, SubagentTask>> = _tasks.asStateFlow()

    /** taskId → 取消信号（HashMap + mutex 保护：只在本类 mutex 内读写） */
    private val cancelSignals = HashMap<Uuid, CompletableDeferred<Unit>>()

    private val mutex = Mutex()

    fun start(childId: Uuid, parentConversationId: Uuid, description: String): SubagentRun {
        val run = SubagentRun(
            childId = childId,
            parentConversationId = parentConversationId,
            description = description,
            startedAt = System.currentTimeMillis(),
        )
        _runs.update { it + (childId to run) }
        return run
    }

    fun updateMessages(childId: Uuid, messages: List<UIMessage>) {
        _runs.update { map ->
            map[childId] ?: return@update map
            map + (childId to map.getValue(childId).copy(messages = messages))
        }
    }

    fun finish(childId: Uuid) {
        _runs.update { it - childId }
    }

    /** 某会话运行中的子代理（按开始时间排序）, 用于会话页过程提示, 按父会话隔离 */
    fun runningOf(conversationId: Uuid): List<SubagentRun> =
        _runs.value.values
            .filter { it.parentConversationId == conversationId }
            .sortedBy { it.startedAt }

    // ---- P0-2 async 任务面 ----

    /** async 派发登记：创建任务与取消信号 */
    suspend fun registerAsyncTask(taskId: Uuid, parentConversationId: Uuid, description: String) {
        mutex.withLock {
            _tasks.update {
                it + (taskId to SubagentTask(
                    taskId = taskId,
                    parentConversationId = parentConversationId,
                    description = description,
                    status = SubagentTaskStatus.RUNNING,
                    startedAt = System.currentTimeMillis(),
                ))
            }
            cancelSignals[taskId] = CompletableDeferred()
        }
    }

    /** async 任务完成（成功/失败/取消/超时）落终态，保留结果供 poll；并发完成时首写胜出 */
    suspend fun completeAsyncTask(
        taskId: Uuid,
        status: SubagentTaskStatus,
        resultJson: String?,
    ) {
        mutex.withLock {
            val existing = _tasks.value[taskId] ?: return
            if (existing.status != SubagentTaskStatus.RUNNING) return
            _tasks.update {
                it + (taskId to existing.copy(
                    status = status,
                    endedAt = System.currentTimeMillis(),
                    resultJson = resultJson,
                    // 会话 TTL：终态时间起 [SESSION_TTL_MS] 内可 followup
                    expiresAt = System.currentTimeMillis() + SESSION_TTL_MS,
                ))
            }
            cancelSignals.remove(taskId)
        }
    }

    /** poll_agent：取任务快照；不存在返回 null */
    fun getTask(taskId: Uuid): SubagentTask? = _tasks.value[taskId]

    /** list_agents：全部 async 任务（含 RUNNING 与终态），按启动时间排序 */
    fun listTasks(parentConversationId: Uuid? = null): List<SubagentTask> =
        _tasks.value.values
            .filter { parentConversationId == null || it.parentConversationId == parentConversationId }
            .sortedBy { it.startedAt }

    /**
     * cancel_agent：置取消信号（执行协程负责落 CANCELLED 终态并停止产生副作用）。
     * 任务不存在或已结束返回 false。
     */
    suspend fun cancel(taskId: Uuid): Boolean {
        val signal = mutex.withLock { cancelSignals[taskId] }
        return if (signal != null) {
            signal.complete(Unit)
            true
        } else {
            false
        }
    }

    /** 订阅某任务的取消信号；未登记返回 null */
    fun cancelSignal(taskId: Uuid): CompletableDeferred<Unit>? = synchronized(cancelSignals) { cancelSignals[taskId] }

    /** 会话 GC：清除超过 TTL 的 async 任务记录；返回被清除的 taskId 列表 */
    suspend fun collectExpired(now: Long = System.currentTimeMillis()): List<Uuid> {
        return mutex.withLock {
            val expired = _tasks.value.values
                .filter { it.status != SubagentTaskStatus.RUNNING && it.expiresAt != null && it.expiresAt < now }
                .map { it.taskId }
            if (expired.isNotEmpty()) {
                _tasks.update { map -> map - expired.toSet() }
            }
            expired
        }
    }

    companion object {
        /**
         * P0-2 d) 会话存活期：子代理终态后仍可 followup 的窗口。
         * 之后 collectExpired 回收记录，followup_agent 返回 AGENT_SESSION_NOT_FOUND(expired)。
         */
        const val SESSION_TTL_MS = 30L * 60 * 1000
    }
}
