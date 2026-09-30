package me.yui.yuihub.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlin.uuid.Uuid
import me.yui.yuihub.data.db.entity.ScheduleType
import me.yui.yuihub.data.db.entity.ScheduledTaskRunStatus
import me.yui.yuihub.data.repository.ScheduledTaskRepository
import me.yui.yuihub.service.ChatService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 定时任务执行 Worker。
 *
 * 到点后读取任务配置，在指定助手下新建会话并发送 prompt；完成后写回运行状态并重排下一次触发。
 * 同一任务用 [activeRun] 防止并发重复执行（WorkManager 理论上不会，但重排时可能有竞态）。
 */
class ScheduledTaskWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    private val chatService: ChatService by inject()
    private val repository: ScheduledTaskRepository by inject()

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(ScheduledTaskScheduler.KEY_TASK_ID)
        if (taskId.isNullOrBlank()) {
            Log.e(TAG, "doWork: missing task id")
            return Result.failure()
        }
        val task = repository.getById(taskId)
        if (task == null) {
            Log.i(TAG, "doWork: task $taskId no longer exists, skip")
            return Result.success()
        }
        if (!task.enabled) {
            Log.i(TAG, "doWork: task $taskId is disabled, skip")
            return Result.success()
        }
        if (!activeRuns.add(taskId)) {
            Log.i(TAG, "doWork: task $taskId already running, skip")
            return Result.success()
        }

        val startedAt = System.currentTimeMillis()
        return try {
            repository.updateRunState(taskId, startedAt, ScheduledTaskRunStatus.RUNNING.name, "")
            val assistantId = Uuid.parse(task.assistantId)
            val conversationId = chatService.startScheduledConversation(
                assistantId = assistantId,
                title = task.name,
                prompt = task.prompt,
            )
            Log.i(TAG, "doWork: task $taskId started conversation $conversationId")
            repository.updateRunState(
                taskId,
                startedAt,
                ScheduledTaskRunStatus.SUCCESS.name,
                conversationId.toString(),
            )
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "doWork: task $taskId failed", e)
            repository.updateRunState(taskId, startedAt, ScheduledTaskRunStatus.FAILED.name, "")
            Result.retry()
        } finally {
            // 单次任务执行后自动停用；周期任务重排下一次
            runCatching {
                if (task.scheduleType == ScheduleType.ONCE.name) {
                    repository.disableAfterRun(taskId)
                } else {
                    repository.getById(taskId)?.let { latest ->
                        repository.nextTriggerAt(latest)?.let { next ->
                            ScheduledTaskScheduler.enqueueAt(applicationContext, taskId, next)
                        }
                    }
                }
            }.onFailure {
                Log.e(TAG, "doWork: reschedule failed for task $taskId", it)
            }
            activeRuns.remove(taskId)
        }
    }

    companion object {
        private const val TAG = "ScheduledTaskWorker"
        private val activeRuns = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    }
}
