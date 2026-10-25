package me.yui.yuihub.data.ai

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSchemaCompactorTest {

    private fun tool(
        description: String,
        parameterDescription: String,
    ) = Tool(
        name = "demo_tool",
        description = description,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("description", parameterDescription)
                        put("enum", kotlinx.serialization.json.buildJsonArray {
                            add(JsonPrimitive("read"))
                            add(JsonPrimitive("write"))
                        })
                    })
                },
                required = listOf("action"),
            )
        },
        execute = { emptyList() },
    )

    @Test
    fun `balanced compaction trims long text but keeps schema structure`() {
        val compacted = ToolSchemaCompactor.compact(
            listOf(
                tool(
                    description = "A very long tool description. " + "Detail. ".repeat(100),
                    parameterDescription = "A long parameter explanation. " + "More. ".repeat(80),
                )
            ),
            ToolSchemaCompactor.Level.BALANCED,
        ).single()

        assertTrue(compacted.description.length <= 322)
        assertTrue(compacted.description.endsWith("…"))
        val schema = compacted.parameters() as InputSchema.Obj
        val action = schema.properties["action"]!!.jsonObject
        assertTrue(action["description"]!!.jsonPrimitive.content.length <= 122)
        assertEquals(2, action["enum"]!!.let { (it as kotlinx.serialization.json.JsonArray).size })
        assertEquals(listOf("action"), schema.required)
        assertEquals("demo_tool", compacted.name)
    }

    @Test
    fun `off returns the original tools untouched`() {
        val original = tool("short", "short")
        val result = ToolSchemaCompactor.compact(listOf(original), ToolSchemaCompactor.Level.OFF)
        assertEquals(original, result.single())
    }

    @Test
    fun `aggressive compaction drops examples and title but keeps defaults`() {
        val schemaTool = Tool(
            name = "demo",
            description = "x",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("value", buildJsonObject {
                            put("type", "string")
                            put("title", "Value")
                            put("description", "Long description ".repeat(20))
                            put("default", JsonPrimitive("auto"))
                            put("examples", kotlinx.serialization.json.buildJsonArray {
                                add(JsonPrimitive("a"))
                            })
                        })
                    },
                    required = null,
                )
            },
            execute = { emptyList() },
        )
        val property = (ToolSchemaCompactor.compact(listOf(schemaTool), ToolSchemaCompactor.Level.AGGRESSIVE)
            .single().parameters() as InputSchema.Obj)
            .properties["value"]!!.jsonObject
        assertEquals("auto", property["default"]!!.jsonPrimitive.content)
        assertTrue("title" !in property)
        assertTrue("examples" !in property)
    }
}
