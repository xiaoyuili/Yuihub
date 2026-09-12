package me.yui.yuihub.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

// 已完成记录没有消费方，最多保留最近 10 条，避免每次子代理运行的消息常驻内存（运行中的不清理）
private const val MAX_FINISHED_RUNS = 10

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
    val finished: Boolean = false,
    val result: String? = null,
) {
    val elapsedMillis: Long get() = System.currentTimeMillis() - startedAt
}

/**
 * 进程内子代理注册表: childId → 运行状态。
 * 主会话与子代理共用同一个 ChatService 实例, 以 childId 隔离。
 */
class SubagentManager {
    private val _runs = MutableStateFlow<Map<Uuid, SubagentRun>>(emptyMap())
    val runs: StateFlow<Map<Uuid, SubagentRun>> = _runs.asStateFlow()

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

    fun finish(childId: Uuid, result: String) {
        _runs.update { map ->
            val run = map[childId] ?: return@update map
            pruneFinished(map + (childId to run.copy(finished = true, result = result)))
        }
    }

    /** 按开始时间保留最近的已完成记录，其余的整条移除 */
    private fun pruneFinished(runs: Map<Uuid, SubagentRun>): Map<Uuid, SubagentRun> {
        val finished = runs.values.filter { it.finished }
        if (finished.size <= MAX_FINISHED_RUNS) return runs
        val staleIds = finished
            .sortedByDescending { it.startedAt }
            .drop(MAX_FINISHED_RUNS)
            .map { it.childId }
            .toSet()
        return runs.filterKeys { it !in staleIds }
    }

    /** 运行中的子代理（按开始时间排序）, 用于会话页顶部提示 */
    fun runningOf(conversationId: Uuid): List<SubagentRun> =
        _runs.value.values
            .filter { it.parentConversationId == conversationId && !it.finished }
            .sortedBy { it.startedAt }

    fun historyOf(conversationId: Uuid): List<SubagentRun> =
        _runs.value.values
            .filter { it.parentConversationId == conversationId }
            .sortedByDescending { it.startedAt }
}
