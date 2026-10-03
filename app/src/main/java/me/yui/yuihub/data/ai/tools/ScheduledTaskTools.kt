package me.yui.yuihub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.yui.yuihub.data.db.entity.ScheduleType
import me.yui.yuihub.data.db.entity.ScheduledTaskEntity
import me.yui.yuihub.data.repository.ScheduledTaskRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.uuid.Uuid

/** scheduled_task 工具的 name，子代理过滤与 persona 白名单按名引用 */
const val SCHEDULED_TASK_TOOL_NAME = "scheduled_task"

/**
 * 让模型在对话中管理**当前助手自己的**定时任务（创建/编辑/删除/启用停用/立即试跑）。
 *
 * 与 UI 的约束对齐：
 * - 所有操作强制绑定 [assistantId]，模型无法读到也无法改到别的助手的任务；
 *   试图传别的助手 id 只会得到 “not found” 而不是越权成功。
 * - 新建任务的触发时刻与 UI 一致：DAILY 用 time_of_day（HH:mm），INTERVAL 用 interval_minutes，
 *   ONCE 用 trigger_at（"yyyy-MM-dd HH:mm"）。
 * - 写操作走 ScheduledTaskRepository，WorkManager 调度自动同步。
 *
 * 工具的 systemPrompt 会把「没有任务时也要知道可以建」以及字段约定告诉模型，
 * 避免它因为列表为空就以为功能不可用。
 */
fun createScheduledTaskTools(
    repository: ScheduledTaskRepository,
    assistantId: Uuid,
    /** 立即试跑回调：交给 ChatService 走它自己的调度入口（与 UI 的「立即运行」同一条路径） */
    onRunNow: (ScheduledTaskEntity) -> Unit,
): List<Tool> = listOf(
    Tool(
        name = SCHEDULED_TASK_TOOL_NAME,
        description = """
            Manage scheduled tasks that belong to THIS assistant only (tasks of other assistants are not accessible).
            `action`: list | create | update | delete | set_enabled | run_now.
            Schedule types: DAILY (`time_of_day` "HH:mm"), INTERVAL (`interval_minutes` >= 15), ONCE (`trigger_at` "yyyy-MM-dd HH:mm").
            create needs `name` + `prompt` (+ one schedule spec); update only needs the fields to change.
            Tasks run in a fresh conversation of this assistant and the result shows up in the chat list.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("list")
                                add("create")
                                add("update")
                                add("delete")
                                add("set_enabled")
                                add("run_now")
                            },
                        )
                        put("description", "Operation to perform")
                    })
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "Task id (required for update/delete/set_enabled/run_now, or pass `name`)")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Task name (creates with this name; also used to locate a task when `id` is omitted)")
                    })
                    put("prompt", buildJsonObject {
                        put("type", "string")
                        put("description", "The prompt to send to the assistant on each run")
                    })
                    put("schedule_type", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("DAILY")
                                add("INTERVAL")
                                add("ONCE")
                            },
                        )
                        put("description", "Schedule type, defaults to DAILY")
                    })
                    put("time_of_day", buildJsonObject {
                        put("type", "string")
                        put("description", "For DAILY: time of day in HH:mm (local time)")
                    })
                    put("interval_minutes", buildJsonObject {
                        put("type", "integer")
                        put("description", "For INTERVAL: minutes between runs, minimum 15")
                    })
                    put("trigger_at", buildJsonObject {
                        put("type", "string")
                        put("description", "For ONCE: trigger moment in \"yyyy-MM-dd HH:mm\" (local time)")
                    })
                    put("enabled", buildJsonObject {
                        put("type", "boolean")
                        put("description", "For set_enabled: whether the task is active")
                    })
                },
                required = listOf("action"),
            )
        },
        systemPrompt = { _, _ ->
            """
            You can manage this assistant's scheduled tasks with `$SCHEDULED_TASK_TOOL_NAME`
            (list / create / update / delete / set_enabled / run_now). Only this assistant's tasks are visible and editable.
            """.trimIndent()
        },
        execute = { args ->
            val obj = args.jsonObject
            val action = obj["action"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val tasks = repository.getTasksForAssistant(assistantId.toString())
            when (action) {
                "list" -> renderTasks(tasks)

                "create" -> createTask(repository, assistantId, obj, tasks)

                "update" -> updateTask(repository, obj, tasks)

                "delete" -> deleteTask(repository, obj, tasks)

                "set_enabled" -> setEnabled(repository, obj, tasks)

                "run_now" -> runNow(obj, tasks, onRunNow)

                else -> toolErrorResult("Unknown action '$action' (expected list / create / update / delete / set_enabled / run_now)")
            }
        },
    ),
)

private suspend fun createTask(
    repository: ScheduledTaskRepository,
    assistantId: Uuid,
    obj: JsonObject,
    existing: List<ScheduledTaskEntity>,
): List<UIMessagePart> {
    val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    require(name.isNotEmpty()) { "name is required for action=create" }
    val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    require(prompt.isNotEmpty()) { "prompt is required for action=create" }
    require(existing.none { it.name == name }) {
        "A task named '$name' already exists. Use action=update to modify it."
    }

    val schedule = parseSchedule(obj)
    val now = System.currentTimeMillis()
    val task = ScheduledTaskEntity(
        id = Uuid.random().toString(),
        name = name,
        prompt = prompt,
        assistantId = assistantId.toString(),
        scheduleType = schedule.type.name,
        triggerAt = schedule.triggerAt,
        intervalMinutes = schedule.intervalMinutes,
        timeOfDayMinutes = schedule.timeOfDayMinutes,
        enabled = true,
        createdAt = now,
        updatedAt = now,
    )
    repository.upsert(task)
    return toolJson {
        put("action", "created")
        put("id", task.id)
        put("name", task.name)
        put("schedule", describe(task))
        put("message", "Created task '${task.name}'")
    }
}

private suspend fun updateTask(
    repository: ScheduledTaskRepository,
    obj: JsonObject,
    tasks: List<ScheduledTaskEntity>,
): List<UIMessagePart> {
    val target = findTask(obj, tasks) ?: return toolErrorResult("No matching task found")

    var updated = target
    obj["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { newName ->
        require(tasks.none { it.id != target.id && it.name == newName }) {
            "A task named '$newName' already exists."
        }
        updated = updated.copy(name = newName)
    }
    obj["prompt"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { newPrompt ->
        updated = updated.copy(prompt = newPrompt)
    }
    // schedule 字段按需覆盖：给了任意一项就整体解析一次（parseSchedule 会用现有值兜底未给的项）
    if (obj.keys.any { it in SCHEDULE_KEYS }) {
        val schedule = parseSchedule(obj, fallback = target)
        updated = updated.copy(
            scheduleType = schedule.type.name,
            triggerAt = schedule.triggerAt,
            intervalMinutes = schedule.intervalMinutes,
            timeOfDayMinutes = schedule.timeOfDayMinutes,
        )
    }

    if (updated == target) {
        // 非错误：没有可更新的字段，用 ok=false 与 error 区分
        return toolJson {
            put("action", "update")
            put("ok", false)
            put("message", "Nothing to update: no recognized fields were provided.")
        }
    }
    repository.upsert(updated.copy(updatedAt = System.currentTimeMillis()))
    return toolJson {
        put("action", "updated")
        put("id", updated.id)
        put("name", updated.name)
        put("schedule", describe(updated))
        put("message", "Updated task '${updated.name}'")
    }
}

private suspend fun deleteTask(
    repository: ScheduledTaskRepository,
    obj: JsonObject,
    tasks: List<ScheduledTaskEntity>,
): List<UIMessagePart> {
    val target = findTask(obj, tasks) ?: return toolErrorResult("No matching task found")
    repository.delete(target)
    return toolJson {
        put("action", "deleted")
        put("id", target.id)
        put("name", target.name)
        put("message", "Deleted task '${target.name}'")
    }
}

private suspend fun setEnabled(
    repository: ScheduledTaskRepository,
    obj: JsonObject,
    tasks: List<ScheduledTaskEntity>,
): List<UIMessagePart> {
    val target = findTask(obj, tasks) ?: return toolErrorResult("No matching task found")
    val enabled = obj["enabled"]?.jsonPrimitive?.contentOrNull?.trim()?.toBooleanStrictOrNull()
        ?: return toolErrorResult("enabled (true/false) is required for action=set_enabled")
    repository.setEnabled(target.id, enabled, System.currentTimeMillis())
    return toolJson {
        put("action", "set_enabled")
        put("id", target.id)
        put("name", target.name)
        put("enabled", enabled)
        put("message", "${if (enabled) "Enabled" else "Disabled"} task '${target.name}'")
    }
}

private fun runNow(
    obj: JsonObject,
    tasks: List<ScheduledTaskEntity>,
    onRunNow: (ScheduledTaskEntity) -> Unit,
): List<UIMessagePart> {
    val target = findTask(obj, tasks) ?: return toolErrorResult("No matching task found")
    onRunNow(target)
    return toolJson {
        put("action", "run_now")
        put("id", target.id)
        put("name", target.name)
        put("message", "Triggered '${target.name}' to run now; the result will appear in a new conversation.")
    }
}

/** 定位任务：优先 id，其次唯一 name；只在传入的（已按助手限定的）列表内查找 */
internal fun findTask(obj: JsonObject, tasks: List<ScheduledTaskEntity>): ScheduledTaskEntity? {
    val id = obj["id"]?.jsonPrimitive?.contentOrNull?.trim()
    if (!id.isNullOrEmpty()) {
        return tasks.firstOrNull { it.id == id }
    }
    val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim()
    if (!name.isNullOrEmpty()) {
        return tasks.firstOrNull { it.name == name }
    }
    return null
}

internal data class ParsedSchedule(
    val type: ScheduleType,
    val triggerAt: Long,
    val intervalMinutes: Int,
    val timeOfDayMinutes: Int,
)

private val SCHEDULE_KEYS = setOf("schedule_type", "time_of_day", "interval_minutes", "trigger_at")

/**
 * 解析调度参数。[fallback] 为更新场景下的原任务（未给的字段沿用原值）；
 * 新建时兜底为 DAILY 09:00 / 24h。
 */
internal fun parseSchedule(obj: JsonObject, fallback: ScheduledTaskEntity? = null): ParsedSchedule {
    val type = obj["schedule_type"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
        ScheduleType.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw IllegalArgumentException("Invalid schedule_type '$raw' (expected DAILY / INTERVAL / ONCE)")
    } ?: fallback?.let { runCatching { ScheduleType.valueOf(it.scheduleType) }.getOrDefault(ScheduleType.DAILY) }
        ?: ScheduleType.DAILY

    var triggerAt = fallback?.triggerAt ?: 0L
    var intervalMinutes = fallback?.intervalMinutes ?: DEFAULT_INTERVAL_MINUTES
    var timeOfDayMinutes = fallback?.timeOfDayMinutes ?: DEFAULT_TIME_OF_DAY_MINUTES

    when (type) {
        ScheduleType.DAILY -> {
            obj["time_of_day"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                timeOfDayMinutes = parseTimeOfDay(text).coerceIn(0, 1439)
            }
        }

        ScheduleType.INTERVAL -> {
            obj["interval_minutes"]?.jsonPrimitive?.intOrNull?.let { minutes ->
                require(minutes >= 15) { "interval_minutes must be at least 15" }
                intervalMinutes = minutes
            }
        }

        ScheduleType.ONCE -> {
            val text = obj["trigger_at"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: error("trigger_at (\"yyyy-MM-dd HH:mm\") is required for schedule_type=ONCE")
            triggerAt = parseTriggerAt(text)
        }
    }

    return ParsedSchedule(
        type = type,
        triggerAt = triggerAt,
        intervalMinutes = intervalMinutes,
        timeOfDayMinutes = timeOfDayMinutes,
    )
}

internal fun parseTimeOfDay(text: String): Int {
    val match = Regex("^(\\d{1,2}):(\\d{1,2})$").find(text)
        ?: throw IllegalArgumentException("Invalid time_of_day '$text' (expected HH:mm)")
    val hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].toInt()
    require(hour in 0..23 && minute in 0..59) { "Invalid time_of_day '$text' (expected HH:mm)" }
    return hour * 60 + minute
}

internal fun parseTriggerAt(text: String): Long {
    val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).apply { isLenient = false }
    return runCatching { format.parse(text)?.time }
        .getOrNull()
        ?: throw IllegalArgumentException("Invalid trigger_at '$text' (expected \"yyyy-MM-dd HH:mm\")")
}

internal fun describe(task: ScheduledTaskEntity): String = when (
    runCatching { ScheduleType.valueOf(task.scheduleType) }.getOrDefault(ScheduleType.DAILY)
) {
    ScheduleType.DAILY -> "daily at %02d:%02d".format(task.timeOfDayMinutes / 60, task.timeOfDayMinutes % 60)

    ScheduleType.INTERVAL -> "every ${task.intervalMinutes.coerceAtLeast(15)} min"

    ScheduleType.ONCE -> "once at " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        .format(Date(task.triggerAt))
}

private fun renderTasks(tasks: List<ScheduledTaskEntity>): List<UIMessagePart> = toolJson {
    put("action", "list")
    put("count", tasks.size)
    put("tasks", buildJsonArray {
        tasks.forEach { task ->
            add(buildJsonObject {
                put("id", task.id)
                put("name", task.name)
                put("prompt", task.prompt)
                put("schedule", describe(task))
                put("enabled", task.enabled)
                put("last_run_status", task.lastRunStatus.ifBlank { "NEVER" })
            })
        }
    })
    if (tasks.isEmpty()) {
        put("hint", "No scheduled tasks for this assistant yet. Use action=create to add one.")
    }
}

/** 与 ScheduledTasksVM 保持一致的默认值（此处不能依赖 UI 层，故独立声明） */
private const val DEFAULT_TIME_OF_DAY_MINUTES = 9 * 60
private const val DEFAULT_INTERVAL_MINUTES = 24 * 60
