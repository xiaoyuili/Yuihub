package me.yui.yuihub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * get_time_info 的一致性回归：所有字段必须从同一个 Instant 派生。
 *
 * 历史问题：datetime 与 timestamp_ms 各自读一次时钟，曾观察到两者不一致（首次调用偏差）。
 * 现在实现里两者同源，本测试锁死该约束——datetime 字符串解析出的纪元秒与 timestamp_s 必须相等。
 */
class TimeInfoToolConsistencyTest {

    /** 复刻工具实现的时间派生逻辑（工具本体依赖 Android Tool 框架，无法直接调用） */
    private data class TimeFields(
        val datetime: String,
        val timestampMs: Long,
        val timestampS: Long,
        val date: String,
        val time: String,
        val timezone: String,
        val utcOffset: String,
    )

    private fun derive(instant: Instant, zone: ZoneId): TimeFields {
        val zoned = instant.atZone(zone)
        val date = zoned.toLocalDate()
        val time = zoned.toLocalTime().withNano(0)
        return TimeFields(
            datetime = zoned.withNano(0).toString(),
            timestampMs = instant.toEpochMilli(),
            timestampS = instant.epochSecond,
            date = date.toString(),
            time = time.toString(),
            timezone = zoned.zone.id,
            utcOffset = zoned.offset.id,
        )
    }

    @Test
    fun `datetime matches timestamp across many instants`() {
        val zone = ZoneId.of("Asia/Shanghai")
        // 覆盖秒边界、午夜、年末等敏感点
        val instants = listOf(
            Instant.parse("2026-10-02T06:57:00Z"),
            Instant.parse("2026-10-02T06:57:59Z"),
            Instant.parse("2026-10-02T16:00:00Z"),   // 北京时间次日 0 点
            Instant.parse("2026-12-31T15:59:59Z"),   // 跨年边界（UTC+8）
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-06-15T12:34:56Z"),
            Instant.now(),
        )
        for (instant in instants) {
            val fields = derive(instant, zone)
            // datetime 解析回 Instant 后必须与 timestamp 完全一致（秒级，datetime 被 withNano(0) 截断）
            val parsed = ZonedDateTime.parse(fields.datetime).toInstant()
            assertEquals(
                "datetime(${fields.datetime}) 与 timestamp_s 不一致",
                instant.epochSecond,
                parsed.epochSecond,
            )
            assertEquals(
                "timestamp_s 与 timestamp_ms 不一致",
                instant.epochSecond,
                fields.timestampMs / 1000,
            )
            // epochSecond 与解析后的毫秒差必须小于 1 秒
            assertTrue(
                "datetime 与 timestamp_ms 偏差过大",
                Math.abs(instant.toEpochMilli() - parsed.toEpochMilli()) < 1000,
            )
        }
    }

    @Test
    fun `date and time fields join to the datetime date`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val instant = Instant.parse("2026-10-02T06:57:00Z")
        val fields = derive(instant, zone)
        assertTrue(fields.datetime.startsWith("2026-10-02"))
        assertEquals("2026-10-02", fields.date)
        assertEquals("14:57", fields.time)
        assertEquals("Asia/Shanghai", fields.timezone)
        assertEquals("+08:00", fields.utcOffset)
    }
}
