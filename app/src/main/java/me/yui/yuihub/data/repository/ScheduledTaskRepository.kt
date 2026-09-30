package me.yui.yuihub.data.repository

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.yui.yuihub.data.db.dao.ScheduledTaskDAO
import me.yui.yuihub.data.db.entity.ScheduledTaskEntity
import me.yui.yuihub.data.db.entity.ScheduleType
import me.yui.yuihub.worker.ScheduledTaskScheduler

/**
 * 定时任务（自动化）仓储。
 *
 * 每次写操作后同步刷新 WorkManager 调度（[ScheduledTaskScheduler.rescheduleAll]），
 * 保证数据库状态与系统调度一致；App 启动与备份恢复后也会全量重建。
 */
class ScheduledTaskRepository(
    private val context: Context,
    private val dao: ScheduledTaskDAO,
) {
    val tasksFlow: Flow<List<ScheduledTaskEntity>> = dao.getAllFlow()

    suspend fun getById(id: String): ScheduledTaskEntity? = dao.getById(id)

    suspend fun getAllEnabled(): List<ScheduledTaskEntity> = dao.getAllEnabled()

    suspend fun upsert(task: ScheduledTaskEntity) {
        dao.upsert(task)
        ScheduledTaskScheduler.rescheduleAll(context, this)
    }

    suspend fun delete(task: ScheduledTaskEntity) {
        dao.delete(task)
        ScheduledTaskScheduler.cancel(context, task.id)
    }

    suspend fun setEnabled(id: String, enabled: Boolean, updatedAt: Long) {
        dao.setEnabled(id, enabled, updatedAt)
        ScheduledTaskScheduler.rescheduleAll(context, this)
    }

    suspend fun updateRunState(
        id: String,
        runAt: Long,
        status: String,
        conversationId: String,
    ) {
        dao.updateRunState(id, runAt, status, conversationId)
    }

    /** 立即触发一次（不影响既定调度；仅调试/试跑用） */
    fun runNow(task: ScheduledTaskEntity) {
        ScheduledTaskScheduler.enqueueAt(context, task.id, System.currentTimeMillis())
    }

    /** 单次任务完成后自动停用（不再重复触发） */
    suspend fun disableAfterRun(id: String) {
        dao.setEnabled(id, enabled = false, updatedAt = System.currentTimeMillis())
    }

    /**
     * 计算下一次应触发的时间（epoch millis），DAILY / INTERVAL / ONCE 分别处理。
     * 返回 null 表示该任务不应再触发（如已过期的 ONCE）。
     */
    fun nextTriggerAt(task: ScheduledTaskEntity, now: Long = System.currentTimeMillis()): Long? =
        when (runCatching { ScheduleType.valueOf(task.scheduleType) }.getOrDefault(ScheduleType.DAILY)) {
            ScheduleType.ONCE ->
                if (task.triggerAt > now) task.triggerAt else null

            ScheduleType.INTERVAL -> {
                val intervalMs = task.intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES) * 60_000L
                val base = if (task.lastRunAt > 0) task.lastRunAt + intervalMs else now
                // 补偿：若已过期（错过多次），顺延到下一个未来时刻
                var candidate = base
                while (candidate <= now) candidate += intervalMs
                candidate
            }

            ScheduleType.DAILY -> {
                val minutes = task.timeOfDayMinutes.coerceIn(0, 24 * 60 - 1)
                val localNow = java.util.Calendar.getInstance().apply { timeInMillis = now }
                val target = java.util.Calendar.getInstance().apply {
                    timeInMillis = now
                    set(java.util.Calendar.HOUR_OF_DAY, minutes / 60)
                    set(java.util.Calendar.MINUTE, minutes % 60)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                if (target.timeInMillis <= localNow.timeInMillis) {
                    target.add(java.util.Calendar.DAY_OF_YEAR, 1)
                }
                target.timeInMillis
            }
        }

    companion object {
        /** WorkManager 周期任务最小间隔（分钟） */
        const val MIN_INTERVAL_MINUTES = 15
    }
}
