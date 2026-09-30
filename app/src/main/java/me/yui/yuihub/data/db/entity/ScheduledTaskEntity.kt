package me.yui.yuihub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 定时任务（自动化）。
 *
 * 到点后在所属助手下创建/复用一条会话，自动发送 [prompt]，由 AI 完成后正常走通知。
 * 调度基于 WorkManager PeriodicWorkRequest；[intervalMinutes] 为最小周期粒度，
 * [timeOfDayMinutes] 为每日固定触发点（0-1439，仅 DAILY 模式使用）。
 */
@Entity(
    tableName = "scheduled_task",
    indices = [
        Index(value = ["assistant_id"]),
        Index(value = ["enabled"]),
    ],
)
data class ScheduledTaskEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("name")
    val name: String,
    @ColumnInfo("prompt")
    val prompt: String,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    /** ONCE / DAILY / INTERVAL */
    @ColumnInfo("schedule_type", defaultValue = "'DAILY'")
    val scheduleType: String = ScheduleType.DAILY.name,
    /** ONCE: 触发时刻(epoch millis)；INTERVAL: 间隔分钟；DAILY: 不使用 */
    @ColumnInfo("trigger_at", defaultValue = "0")
    val triggerAt: Long = 0L,
    @ColumnInfo("interval_minutes", defaultValue = "1440")
    val intervalMinutes: Int = 1440,
    /** DAILY 模式的一天内触发时刻（分钟数 0-1439，本地时区） */
    @ColumnInfo("time_of_day_minutes", defaultValue = "540")
    val timeOfDayMinutes: Int = 540,
    @ColumnInfo("enabled", defaultValue = "1")
    val enabled: Boolean = true,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("last_run_at", defaultValue = "0")
    val lastRunAt: Long = 0L,
    @ColumnInfo("last_run_status", defaultValue = "''")
    val lastRunStatus: String = "",
    @ColumnInfo("last_conversation_id", defaultValue = "''")
    val lastConversationId: String = "",
)

enum class ScheduleType { ONCE, DAILY, INTERVAL }

enum class ScheduledTaskRunStatus { RUNNING, SUCCESS, SKIPPED, FAILED }
