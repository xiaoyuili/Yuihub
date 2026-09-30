package me.yui.yuihub.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import me.yui.yuihub.data.repository.ScheduledTaskRepository

/**
 * 定时任务调度：每个任务一个唯一的 [OneTimeWorkRequest]（workName = task.id），
 * 由 Worker 自己跑完后计算下一个触发点并重新入队。
 *
 * 选择 OneTime 而非 PeriodicWorkRequest 的原因：PeriodicWorkRequest 的周期下限是 15 分钟
 * 且无法指定「每日固定时刻」；OneTime + 自行重排可以同时支持 ONCE / DAILY / INTERVAL 三种模式，
 * 且触发时间精确到分钟。
 */
object ScheduledTaskScheduler {
    private const val TAG = "ScheduledTaskScheduler"

    /** 任务触发时 Worker 接收的数据 key */
    const val KEY_TASK_ID = "task_id"

    private fun workName(taskId: String) = "scheduled_task_$taskId"

    /** 重新计算并应用所有启用任务的调度（新增/编辑/启停后调用） */
    suspend fun rescheduleAll(context: Context, repository: ScheduledTaskRepository) {
        val tasks = repository.getAllEnabled()
        tasks.forEach { task ->
            val next = repository.nextTriggerAt(task) ?: run {
                Log.i(TAG, "rescheduleAll: task ${task.id} has no next trigger, skip")
                return@forEach
            }
            enqueueAt(context, task.id, next)
        }
    }

    /** 按指定时刻入队（同任务重复调用会覆盖旧请求） */
    fun enqueueAt(context: Context, taskId: String, triggerAtMillis: Long) {
        val delayMs = (triggerAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<ScheduledTaskWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(KEY_TASK_ID, taskId).build())
            .setConstraints(
                // 联网是常见需求（搜索/抓取），但不做硬约束：任务也可能纯本地计算
                Constraints.Builder().build()
            )
            .build()
        runCatching {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName(taskId), ExistingWorkPolicy.REPLACE, request)
        }.onFailure {
            Log.e(TAG, "enqueueAt failed for task $taskId", it)
        }
    }

    /** 取消任务调度 */
    fun cancel(context: Context, taskId: String) {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(workName(taskId))
        }.onFailure {
            Log.e(TAG, "cancel failed for task $taskId", it)
        }
    }
}
