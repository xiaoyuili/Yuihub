package me.yui.yuihub.data.ai

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具结果契约（P1-4 结果侧）单测：
 * - 结构化错误补稳定 errorCode
 * - 畸形 JSON（被截断/编码损坏）被包装为 MALFORMED_TOOL_RESULT，不再原样进入上下文
 * - 普通文本与非 JSON 结果必须原样透传，不改写正常输出
 */
class ToolResultContractTest {

    private fun errorCodeOf(text: String): String? =
        runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject["errorCode"]?.jsonPrimitive?.contentOrNull }
            .getOrNull()

    @Test
    fun `plain text result passes through unchanged`() {
        val text = "Command finished with exit code 0"
        assertEquals(text, ToolResultContract.normalize("workspace_shell", text))
    }

    @Test
    fun `json result without error passes through unchanged`() {
        val text = """{"path":"/workspace/a.txt","sizeBytes":12}"""
        assertEquals(text, ToolResultContract.normalize("workspace_read_file", text))
    }

    @Test
    fun `json array result passes through unchanged`() {
        val text = """[1,2,3]"""
        assertEquals(text, ToolResultContract.normalize("some_tool", text))
    }

    @Test
    fun `truncated json is wrapped as malformed`() {
        val truncated = """{"path":"/workspace/a.txt","text":"hello wor"""
        val normalized = ToolResultContract.normalize("workspace_read_file", truncated)
        assertTrue(normalized.contains("MALFORMED_TOOL_RESULT"))
        assertEquals("MALFORMED_TOOL_RESULT", errorCodeOf(normalized))
        assertTrue("应保留原始片段供排查", normalized.contains("hello wor"))
    }

    @Test
    fun `error field gains stable code`() {
        val result = """{"error":"File does not exist: /workspace/x"}"""
        val normalized = ToolResultContract.normalize("workspace_read_file", result)
        assertEquals("NOT_FOUND", errorCodeOf(normalized))
        assertTrue("原始 error 文案保留", normalized.contains("File does not exist"))
    }

    @Test
    fun `literal error code is reused instead of recomputed`() {
        val result = """{"error":"WRITE_CONFLICT: /workspace/a was modified"}"""
        val normalized = ToolResultContract.normalize("workspace_write_file", result)
        assertEquals("WRITE_CONFLICT", errorCodeOf(normalized))
        assertFalse("不得双重前缀", normalized.contains("errorCode\":\"TOOL_ERROR"))
    }

    @Test
    fun `timeout message classifies as timeout`() {
        val normalized = ToolResultContract.normalize("workspace_shell", """{"error":"Command timed out after 30s"}""")
        assertEquals("TIMEOUT", errorCodeOf(normalized))
    }

    @Test
    fun `unknown error falls back to TOOL_ERROR`() {
        val normalized = ToolResultContract.normalize("workspace_shell", """{"error":"segfault somewhere"}""")
        assertEquals("TOOL_ERROR", errorCodeOf(normalized))
    }

    @Test
    fun `blank error message gets a usable replacement`() {
        val normalized = ToolResultContract.normalize("workspace_shell", """{"error":""}""")
        assertTrue(normalized.contains("unspecified error"))
        assertEquals("TOOL_ERROR", errorCodeOf(normalized))
    }

    @Test
    fun `normalizeToolResultParts only rewrites text parts`() {
        val image = UIMessagePart.Image(url = "file:///tmp/a.png")
        val text = UIMessagePart.Text("""{"error":"not found"}""")
        val result = normalizeToolResultParts("workspace_read_file", listOf(text, image))
        assertEquals(2, result.size)
        assertSame(image, result[1])
        assertTrue((result[0] as UIMessagePart.Text).text.contains("NOT_FOUND"))
    }

    @Test
    fun `management list payload passes through and stays parseable`() {
        // 管理类工具改造后的预期形态：带 action 的 JSON 数组结果，不契约为错误
        val listed = """{"action":"list","count":0,"tasks":[],"hint":"No scheduled tasks for this assistant yet. Use action=create to add one."}"""
        val normalized = ToolResultContract.normalize("scheduled_task", listed)
        assertEquals(listed, normalized)
    }

    @Test
    fun `management error envelope gains a code`() {
        // 工具层只写 {"error": "..."}，错误码由契约层统一补，两层不重复定义
        val error = """{"error":"No matching task found"}"""
        val normalized = ToolResultContract.normalize("scheduled_task", error)
        assertEquals("NOT_FOUND", errorCodeOf(normalized))
    }
}
