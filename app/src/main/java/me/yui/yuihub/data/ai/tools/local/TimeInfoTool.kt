package me.yui.yuihub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.time.format.TextStyle
import java.util.Locale

internal fun buildTimeInfoTool(): Tool = Tool(
    name = "get_time_info",
    description = """
        Get the current local date and time info from the device.
        Returns year/month/day, weekday, ISO date/time strings, timezone, and timestamp.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { }
        )
    },
    execute = {
        // 所有字段从同一个 Instant 派生：old 版本里 datetime 与 timestamp_ms 分别读
        // 两次系统时钟，秒边界与时钟源差异会造成两者不一致（曾出现首次调用偏差）
        val now = java.time.Instant.now()
        val zoned = now.atZone(java.time.ZoneId.systemDefault())
        val date = zoned.toLocalDate()
        val time = zoned.toLocalTime().withNano(0)
        val weekday = zoned.dayOfWeek
        val payload = buildJsonObject {
            put("year", date.year)
            put("month", date.monthValue)
            put("day", date.dayOfMonth)
            put("weekday", weekday.getDisplayName(TextStyle.FULL, Locale.getDefault()))
            put("weekday_en", weekday.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
            put("weekday_index", weekday.value)
            put("date", date.toString())
            put("time", time.toString())
            put("datetime", zoned.withNano(0).toString())
            put("timezone", zoned.zone.id)
            put("utc_offset", zoned.offset.id)
            put("timestamp_ms", now.toEpochMilli())
            // 与 timestamp_ms 同源的秒值：避免调用方自己从 datetime 字符串解析时
            // 因时区处理差异得出错误值（时间戳字段与展示字段天然一致）
            put("timestamp_s", now.epochSecond)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
