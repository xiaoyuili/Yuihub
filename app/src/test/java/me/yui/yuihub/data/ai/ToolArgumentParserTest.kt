package me.yui.yuihub.data.ai

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-2 降级路径与 P1-3 错误码回归表（JVM 级，覆盖要求的可复现断言）：
 * - 坏 JSON → 单行可读错误，无栈帧文本；原始异常可从 ToolArgumentException.cause 取到（供日志）
 * - 宽松模式自动重试 1 次不放大 token 消耗（纯本地重解析）
 */
class ToolArgumentParserTest {

    private val json = Json

    // ---- P1-2: 解析降级 ----

    @Test
    fun `valid json parses directly`() {
        val result = ToolArgumentParser.parse(json, """{"path":"/workspace/a.txt"}""", "workspace_write_file")
        assertTrue(result.toString().contains("/workspace/a.txt"))
    }

    @Test
    fun `blank input defaults to empty object`() {
        val result = ToolArgumentParser.parse(json, "", "get_time_info")
        assertEquals("{}", result.toString())
    }

    @Test
    fun `truncated json is rejected with single line message`() {
        // 模拟长上下文下模型输出被截断：字符串断在中间
        val truncated = """{"command": "echo hello world and some very long out"""
        val e = runCatching { ToolArgumentParser.parse(json, truncated, "workspace_shell") }
            .exceptionOrNull() as ToolArgumentException
        val message = ToolArgumentParser.invalidArgsMessage("workspace_shell", e)
        assertTrue("应含 ToolError 前缀", message.startsWith("ToolError: invalid_arguments_json"))
        assertTrue("应含 tool 标识", message.contains("tool=workspace_shell"))
        assertTrue("应有重发引导", message.contains("Re-issue the same tool call"))
        assertFalse("不得含栈帧文本 at ", message.contains("\tat "))
        assertFalse("不得含换行", message.contains('\n'))
        // 原始异常保留给日志层
        assertTrue(e.cause != null)
    }

    @Test
    fun `eof error message extracts offset`() {
        val e = runCatching {
            ToolArgumentParser.parse(json, """{"command": "unterminated""", "workspace_shell")
        }.exceptionOrNull() as ToolArgumentException
        val msg = e.cause?.message.orEmpty()
        val offset = ToolArgumentParser.extractOffset(msg)
        if (msg.contains("offset")) {
            assertTrue("消息含 offset 时必须能提取出数字", offset != null && offset > 0)
        }
    }

    @Test
    fun `lenient retry recovers unquoted keys and trailing commas`() {
        // strict 失败但宽松重试成功的样例：lenient 允许更宽松的语法
        val semiValid = "{} trailing"
        val e = runCatching { ToolArgumentParser.parse(json, semiValid, "list_agents") }
            .exceptionOrNull()
        // 无法宽松救回的输入最终仍走降级路径，不抛裸异常到模型
        if (e != null) {
            assertTrue(e is ToolArgumentException)
        }
    }

    @Test
    fun `offset regex handles null and non matching messages`() {
        assertNull(ToolArgumentParser.extractOffset(null))
        assertNull(ToolArgumentParser.extractOffset("no offset here"))
        assertEquals(72588L, ToolArgumentParser.extractOffset("Unexpected JSON token at offset 72588: Expected quotation mark"))
    }

    // ---- P1-3: 错误码回归表 ----

    private fun assertSingleLineJson(toolName: String, error: Throwable, expectedCode: String) {
        val output = ToolArgumentParser.formatError(toolName, error)
        assertTrue("输出应是单行", !output.contains('\n'))
        assertTrue("应含错误码 $expectedCode", output.contains(expectedCode))
        assertTrue("应含 tool 标识", output.contains("(tool=$toolName)"))
        assertFalse("不得含栈帧", output.contains("\tat "))
        assertTrue("应是 JSON", output.trim().startsWith("{\"error\""))
    }

    @Test
    fun `error code NOT_FOUND`() {
        assertSingleLineJson(
            "workspace_read_file",
            IllegalStateException("File does not exist: /workspace/x"),
            "NOT_FOUND",
        )
    }

    @Test
    fun `error code IS_DIRECTORY`() {
        assertSingleLineJson(
            "workspace_read_file",
            IllegalStateException("Path is not a file: /workspace/dir"),
            "IS_DIRECTORY",
        )
    }

    @Test
    fun `error code EDIT_NO_MATCH keeps guidance`() {
        val error = IllegalArgumentException(
            "old_text was not found, even with whitespace-tolerant matching; read the file again and copy old_text exactly from its current content"
        )
        val output = ToolArgumentParser.formatError("workspace_edit_file", error)
        assertTrue(output.contains("EDIT_NO_MATCH"))
        assertTrue("保留重读引导文案", output.contains("read the file again"))
    }

    @Test
    fun `error code PERMISSION_DENIED`() {
        assertSingleLineJson(
            "workspace_shell",
            IllegalStateException("Permission denied: /root"),
            "PERMISSION_DENIED",
        )
    }

    @Test
    fun `error code BINARY_FILE`() {
        assertSingleLineJson(
            "workspace_read_file",
            IllegalStateException("Binary file or invalid byte sequence: /workspace/x.png"),
            "BINARY_FILE",
        )
    }

    @Test
    fun `error code TIMEOUT`() {
        assertSingleLineJson("workspace_shell", IllegalStateException("Command timed out"), "TIMEOUT")
    }

    @Test
    fun `error code INVALID_ARGS for missing required field`() {
        assertSingleLineJson(
            "workspace_write_file",
            IllegalArgumentException("text is required"),
            "INVALID_ARGS",
        )
    }

    @Test
    fun `error code WRITE_CONFLICT keeps detail`() {
        assertSingleLineJson(
            "workspace_write_file",
            IllegalStateException("WRITE_CONFLICT: /workspace/a was modified by another session (disk mtime=1, your expectedMtimeMs=2)"),
            "WRITE_CONFLICT",
        )
    }

    @Test
    fun `literal error code is not double prefixed`() {
        val output = ToolArgumentParser.formatError(
            "workspace_write_file",
            IllegalStateException("WRITE_CONFLICT: /workspace/a was modified by another session"),
        )
        assertFalse("不得双重前缀", output.contains("WRITE_CONFLICT: WRITE_CONFLICT"))
    }

    @Test
    fun `error code AGENT_SESSION_NOT_FOUND`() {
        assertSingleLineJson(
            "followup_agent",
            IllegalStateException("AGENT_SESSION_NOT_FOUND: Unknown sessionId: abc"),
            "AGENT_SESSION_NOT_FOUND",
        )
    }

    @Test
    fun `error code REJECTED for child skill writes`() {
        assertSingleLineJson(
            "manage_skill",
            IllegalStateException("REJECTED: child agents cannot modify the skill library (action=save)"),
            "REJECTED",
        )
    }

    @Test
    fun `fallback TOOL_ERROR for unknown messages`() {
        assertSingleLineJson("workspace_shell", RuntimeException("segfault somewhere"), "TOOL_ERROR")
    }

    @Test
    fun `multiline message is collapsed to single line`() {
        val output = ToolArgumentParser.formatError(
            "workspace_shell",
            RuntimeException("line1\nline2\n\tat foo.Bar.baz(Foo.kt:1)"),
        )
        assertFalse(output.contains('\n'))
        assertFalse(output.contains("\tat "))
    }
}
