package me.yui.yuihub.data.db.migrations

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.yui.yuihub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DB 迁移的消息 JSON 归一化单测：历史库里的 part 类型是全限定类名，迁移到缩写标识；
 * 嵌套在 Tool.output 里的 part 也必须递归处理，否则升级后旧工具结果渲染不出来。
 */
class MigrationUtilsTest {

    private fun typeOf(messagesJson: String, messageIndex: Int = 0, partIndex: Int = 0): String? =
        JsonInstant.parseToJsonElement(messagesJson).jsonArray[messageIndex].jsonObject["parts"]!!
            .jsonArray[partIndex].jsonObject["type"]!!.jsonPrimitive.contentOrNull

    @Test
    fun `fully qualified part types are mapped`() {
        val messages = """
            [{"role":"ASSISTANT","parts":[{"type":"me.rerere.ai.ui.UIMessagePart.Text","text":"hi"}]}]
        """.trimIndent()
        assertEquals("text", typeOf(migrateMessagesJson(messages)))
    }

    @Test
    fun `short legacy names are mapped`() {
        val messages = """
            [{"role":"ASSISTANT","parts":[{"type":"UIMessagePart.Reasoning","reasoning":"think"}]}]
        """.trimIndent()
        assertEquals("reasoning", typeOf(migrateMessagesJson(messages)))
    }

    @Test
    fun `nested tool output parts are migrated recursively`() {
        val messages = """
            [{"role":"ASSISTANT","parts":[{"type":"UIMessagePart.Tool","toolName":"t",
              "output":[{"type":"me.rerere.ai.ui.UIMessagePart.Text","text":"result"}]}]}]
        """.trimIndent()
        val migrated = migrateMessagesJson(messages)
        val outputType = JsonInstant.parseToJsonElement(migrated).jsonArray[0].jsonObject["parts"]!!
            .jsonArray[0].jsonObject["output"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.contentOrNull
        assertEquals("text", outputType)
    }

    @Test
    fun `unknown part type is preserved`() {
        val messages = """
            [{"role":"ASSISTANT","parts":[{"type":"something_custom","x":1}]}]
        """.trimIndent()
        assertEquals("something_custom", typeOf(migrateMessagesJson(messages)))
    }

    @Test
    fun `already migrated json is returned unchanged`() {
        val messages = """
            [{"role":"ASSISTANT","parts":[{"type":"text","text":"hi"}]}]
        """.trimIndent()
        assertEquals(messages, migrateMessagesJson(messages))
    }

    @Test
    fun `invalid json is returned unchanged`() {
        val broken = "[{\"role\":\"ASSISTANT\",\"parts\":["
        assertEquals(broken, migrateMessagesJson(broken))
    }

    @Test
    fun `missing parts field is left untouched`() {
        val messages = """[{"role":"USER"}]"""
        assertEquals(messages, migrateMessagesJson(messages))
    }

    @Test
    fun `type mapping covers every current part kind`() {
        // 任一 part 类型必须有可映射的目标，未覆盖时这条断言会先失败
        listOf("Text", "Image", "Video", "Audio", "Document", "Reasoning", "Search", "ToolCall", "ToolResult", "Tool")
            .forEach { kind ->
                assertTrue("缺失类型映射: $kind", partTypeMapping.containsKey("UIMessagePart.$kind"))
                assertTrue("缺失全限定映射: $kind", partTypeMapping.containsKey("me.rerere.ai.ui.UIMessagePart.$kind"))
            }
    }
}
