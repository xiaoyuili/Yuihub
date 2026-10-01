package me.yui.yuihub.ui.pages.automation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.yui.yuihub.data.db.entity.ScheduleType
import me.yui.yuihub.data.db.entity.ScheduledTaskEntity
import me.yui.yuihub.data.repository.ScheduledTaskRepository
import java.util.Calendar
import kotlin.uuid.Uuid

/**
 * 定时任务页 ViewModel。
 *
 * 列表来自 Room Flow（实时）；写操作全部经 Repository（内部同步刷新 WorkManager 调度）。
 */
class ScheduledTasksVM(
    private val repository: ScheduledTaskRepository,
) : ViewModel() {

    val tasks: StateFlow<List<ScheduledTaskEntity>> = repository.tasksFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 幂等保护：连点保存时只允许一次写入落地（UI 禁用可能因重组时机而漏网）
    @Volatile
    private var creating = false

    fun create(
        name: String,
        prompt: String,
        assistantId: Uuid,
        scheduleType: ScheduleType,
        triggerAt: Long,
        intervalMinutes: Int,
        timeOfDayMinutes: Int,
        onDone: () -> Unit = {},
    ) {
        if (creating) return
        creating = true
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                repository.upsert(
                    ScheduledTaskEntity(
                        id = Uuid.random().toString(),
                        name = name.trim(),
                        prompt = prompt.trim(),
                        assistantId = assistantId.toString(),
                        scheduleType = scheduleType.name,
                        triggerAt = triggerAt,
                        intervalMinutes = intervalMinutes,
                        timeOfDayMinutes = timeOfDayMinutes,
                        enabled = true,
                        createdAt = now,
                        updatedAt = now,
                    )
                )
                onDone()
            } finally {
                creating = false
            }
        }
    }

    fun update(task: ScheduledTaskEntity, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repository.upsert(task.copy(updatedAt = System.currentTimeMillis()))
            onDone()
        }
    }

    fun delete(task: ScheduledTaskEntity) {
        viewModelScope.launch { repository.delete(task) }
    }

    fun setEnabled(task: ScheduledTaskEntity, enabled: Boolean) {
        viewModelScope.launch { repository.setEnabled(task.id, enabled, System.currentTimeMillis()) }
    }

    /** 立即执行一次（不改变既定调度；用于调试与「先试跑一下」） */
    fun runNow(task: ScheduledTaskEntity) {
        repository.runNow(task)
    }

    /** 下一次触发时间的可读文本（含校验） */
    fun nextTriggerText(task: ScheduledTaskEntity): String {
        val next = repository.nextTriggerAt(task) ?: return ""
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        return fmt.format(java.util.Date(next))
    }

    companion object {
        /** 组装每日触发时间对应的分钟数（本地时区） */
        fun timeOfDayToMinutes(hour: Int, minute: Int): Int =
            (hour.coerceIn(0, 23) * 60 + minute.coerceIn(0, 59))

        fun minutesToHour(minutes: Int): Int = (minutes / 60).coerceIn(0, 23)

        fun minutesToMinute(minutes: Int): Int = (minutes % 60).coerceIn(0, 59)

        /** 默认每日 09:00 */
        const val DEFAULT_TIME_OF_DAY_MINUTES = 9 * 60

        /** 默认周期 24 小时 */
        const val DEFAULT_INTERVAL_MINUTES = 24 * 60

        fun defaultTriggerAt(): Long = Calendar.getInstance().apply {
            add(Calendar.HOUR_OF_DAY, 1)
        }.timeInMillis
    }
}
