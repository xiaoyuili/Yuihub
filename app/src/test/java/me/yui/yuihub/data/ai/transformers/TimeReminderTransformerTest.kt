package me.yui.yuihub.data.ai.transformers

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.rerere.ai.ui.UIMessagePart
import me.yui.yuihub.data.ai.tools.local.buildTimeInfoTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZonedDateTime

class TimeInfoToolTest {
    @Test
    fun `time fields use one timestamp and matching timezone`() = runBlocking {
        val before = System.currentTimeMillis()
        val tool = buildTimeInfoTool()
        val result = tool.execute(JsonObject(emptyMap()))
        val after = System.currentTimeMillis()
        val payload = Json.parseToJsonElement((result.single() as UIMessagePart.Text).text) as JsonObject
        val timestamp = payload.getValue("timestamp_ms").jsonPrimitive.long
        val dateTime = ZonedDateTime.parse(payload.getValue("datetime").jsonPrimitive.contentOrNull)

        assertTrue(timestamp in before..after)
        assertEquals(Instant.ofEpochMilli(timestamp).epochSecond, payload.getValue("timestamp_s").jsonPrimitive.long)
        assertEquals(Instant.ofEpochMilli(timestamp).epochSecond, dateTime.toEpochSecond())
        assertEquals(dateTime.zone.id, payload.getValue("timezone").jsonPrimitive.contentOrNull)
        assertEquals(dateTime.toLocalDate().toString(), payload.getValue("date").jsonPrimitive.contentOrNull)
        assertEquals(dateTime.dayOfWeek.value.toLong(), payload.getValue("weekday_index").jsonPrimitive.long)
    }

    @Test
    fun `time tool requires no input properties`() {
        val tool = buildTimeInfoTool()
        assertEquals("get_time_info", tool.name)
        val schema = tool.parameters() as me.rerere.ai.core.InputSchema.Obj
        assertTrue(schema.properties.isEmpty())
    }
}
