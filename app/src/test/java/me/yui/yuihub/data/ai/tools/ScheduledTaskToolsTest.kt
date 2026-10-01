package me.yui.yuihub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.yui.yuihub.data.db.entity.ScheduleType
import me.yui.yuihub.data.db.entity.ScheduledTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class ScheduledTaskToolsTest {

    private fun task(
        id: String = "task-a",
        name: String = "Morning",
        assistantId: String = "assistant-1",
        type: ScheduleType = ScheduleType.DAILY,
        timeOfDayMinutes: Int = 9 * 60,
        intervalMinutes: Int = 1440,
        triggerAt: Long = 0L,
    ) = ScheduledTaskEntity(
        id = id,
        name = name,
        prompt = "report",
        assistantId = assistantId,
        scheduleType = type.name,
        triggerAt = triggerAt,
        intervalMinutes = intervalMinutes,
        timeOfDayMinutes = timeOfDayMinutes,
        createdAt = 1L,
        updatedAt = 1L,
    )

    // ---------- 调度解析 ----------

    @Test
    fun `daily parses time of day`() {
        val parsed = parseSchedule(
            buildJsonObject {
                put("schedule_type", "DAILY")
                put("time_of_day", "7:05")
            }
        )
        assertEquals(ScheduleType.DAILY, parsed.type)
        assertEquals(7 * 60 + 5, parsed.timeOfDayMinutes)
    }

    @Test
    fun `schedule type defaults to daily at 09 00 for create`() {
        val parsed = parseSchedule(buildJsonObject {})
        assertEquals(ScheduleType.DAILY, parsed.type)
        assertEquals(9 * 60, parsed.timeOfDayMinutes)
        assertEquals(1440, parsed.intervalMinutes)
    }

    @Test
    fun `interval requires at least 15 minutes`() {
        val parsed = parseSchedule(
            buildJsonObject {
                put("schedule_type", "INTERVAL")
                put("interval_minutes", 30)
            }
        )
        assertEquals(ScheduleType.INTERVAL, parsed.type)
        assertEquals(30, parsed.intervalMinutes)

        assertThrows(IllegalArgumentException::class.java) {
            parseSchedule(
                buildJsonObject {
                    put("schedule_type", "INTERVAL")
                    put("interval_minutes", 5)
                }
            )
        }
    }

    @Test
    fun `once needs trigger_at`() {
        val parsed = parseSchedule(
            buildJsonObject {
                put("schedule_type", "ONCE")
                put("trigger_at", "2026-05-01 08:30")
            }
        )
        assertEquals(ScheduleType.ONCE, parsed.type)
        val expected = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .parse("2026-05-01 08:30")!!.time
        assertEquals(expected, parsed.triggerAt)

        assertThrows(IllegalStateException::class.java) {
            parseSchedule(buildJsonObject { put("schedule_type", "ONCE") })
        }
    }

    @Test
    fun `invalid values are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseSchedule(buildJsonObject { put("time_of_day", "25:00") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseSchedule(buildJsonObject { put("time_of_day", "7h30") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseSchedule(buildJsonObject { put("schedule_type", "WEEKLY") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseSchedule(
                buildJsonObject {
                    put("schedule_type", "ONCE")
                    put("trigger_at", "not a date")
                }
            )
        }
    }

    @Test
    fun `update keeps unspecified schedule fields from fallback`() {
        val existing = task(type = ScheduleType.INTERVAL, intervalMinutes = 60)
        val parsed = parseSchedule(
            buildJsonObject { put("interval_minutes", 120) },
            fallback = existing,
        )
        assertEquals(ScheduleType.INTERVAL, parsed.type)
        assertEquals(120, parsed.intervalMinutes)
        // 时间字段沿用原值，不被重置
        assertEquals(existing.timeOfDayMinutes, parsed.timeOfDayMinutes)
    }

    // ---------- 任务定位 ----------

    @Test
    fun `find by id wins over name`() {
        val tasks = listOf(task(id = "1", name = "A"), task(id = "2", name = "B"))
        val found = findTask(
            buildJsonObject {
                put("id", "2")
                put("name", "A")
            },
            tasks,
        )
        assertEquals("2", found?.id)
    }

    @Test
    fun `find by name works`() {
        val tasks = listOf(task(id = "1", name = "A"))
        assertEquals("1", findTask(buildJsonObject { put("name", "A") }, tasks)?.id)
    }

    @Test
    fun `find returns null when nothing matches`() {
        val tasks = listOf(task(id = "1", name = "A"))
        assertNull(findTask(buildJsonObject { put("id", "missing") }, tasks))
        assertNull(findTask(buildJsonObject {}, tasks))
    }

    @Test
    fun `foreign assistant task id cannot be located`() {
        // 工具只会把「本助手」的任务传进 findTask；别的助手的 id 自然查不到
        val ownTasks = listOf(task(id = "own", assistantId = "assistant-1"))
        assertNull(findTask(buildJsonObject { put("id", "foreign") }, ownTasks))
        assertEquals("own", findTask(buildJsonObject { put("id", "own") }, ownTasks)?.id)
    }

    // ---------- 描述 ----------

    @Test
    fun `describe renders each schedule type`() {
        assertEquals("daily at 07:30", describe(task(timeOfDayMinutes = 7 * 60 + 30)))
        assertEquals("every 60 min", describe(task(type = ScheduleType.INTERVAL, intervalMinutes = 60)))
        val onceText = describe(
            task(
                type = ScheduleType.ONCE,
                triggerAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .parse("2026-05-01 08:30")!!.time,
            )
        )
        assertEquals("once at 2026-05-01 08:30", onceText)
    }

    @Test
    fun `describe clamps interval to the minimum`() {
        assertEquals("every 15 min", describe(task(type = ScheduleType.INTERVAL, intervalMinutes = 5)))
    }
}
