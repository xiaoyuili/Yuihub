package me.yui.yuihub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.yui.yuihub.data.model.SubagentPersona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子代理工具的参数契约单测（不需要真实子代理运行）。
 * 重点覆盖输入校验分支：这些分支直接决定模型拿到的是可执行的调用还是可读的错误。
 */
class SubagentToolsTest {

    private fun persona(name: String, tools: List<String> = emptyList()) =
        SubagentPersona(name = name, allowedTools = tools.toSet())

    @Test
    fun `spawn rejects blank prompt`() {
        val tool = createSubagentTool { _, _, _, _, _, _ -> "unused" }
        val e = assertThrows(IllegalStateException::class.java) {
            runBlocking { tool.execute(buildJsonObject { put("description", "x"); put("prompt", "  ") }) }
        }
        assertTrue(e.message.orEmpty().contains("non-empty prompt"))
    }

    @Test
    fun `spawn forwards parsed options to callback`() = runBlocking {
        var captured: List<Any?>? = null
        val tool = createSubagentTool { description, prompt, async, timeoutMs, maxToolCalls, personaName ->
            captured = listOf(description, prompt, async, timeoutMs, maxToolCalls, personaName)
            """{"status":"ok","result":"done"}"""
        }
        val result = tool.execute(
            buildJsonObject {
                put("description", "research")
                put("prompt", "do it")
                put("async", true)
                put("timeoutMs", 5000)
                put("maxToolCalls", 3)
            }
        )
        assertEquals(listOf<Any?>("research", "do it", true, 5000L, 3, null), captured)
        assertTrue((result.single() as UIMessagePart.Text).text.contains("done"))
    }

    @Test
    fun `spawn rejects unknown persona`() {
        val tool = createSubagentTool(personas = listOf(persona("researcher"))) { _, _, _, _, _, _ -> "unused" }
        val e = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                tool.execute(buildJsonObject { put("description", "x"); put("prompt", "y"); put("agent", "nope") })
            }
        }
        assertTrue(e.message.orEmpty().contains("unknown agent role"))
    }

    @Test
    fun `spawn accepts a known persona`() = runBlocking {
        var capturedPersona: String? = null
        val tool = createSubagentTool(personas = listOf(persona("researcher"))) { _, _, _, _, _, name ->
            capturedPersona = name
            """{"status":"ok"}"""
        }
        tool.execute(buildJsonObject { put("description", "x"); put("prompt", "y"); put("agent", "researcher") })
        assertEquals("researcher", capturedPersona)
    }

    @Test
    fun `followup rejects blank sessionId or message with structured error`() = runBlocking {
        val tool = createFollowupAgentTool { _, _ -> "should-not-run" }
        val blankSession = tool.execute(buildJsonObject { put("sessionId", " "); put("message", "hi") })
        val blankMessage = tool.execute(buildJsonObject { put("sessionId", "abc"); put("message", "") })
        listOf(blankSession, blankMessage).forEach { result ->
            val json = result.single().let { (it as UIMessagePart.Text).text }
            assertTrue(json.contains("\"status\":\"error\""))
        }
    }

    @Test
    fun `poll rejects blank taskId`() = runBlocking {
        val tool = createPollAgentTool { "should-not-run" }
        val result = tool.execute(buildJsonObject { put("taskId", "") })
        assertTrue((result.single() as UIMessagePart.Text).text.contains("poll_agent requires a non-empty taskId"))
    }

    @Test
    fun `cancel rejects blank taskId`() = runBlocking {
        val tool = createCancelAgentTool { "should-not-run" }
        val result = tool.execute(buildJsonObject { put("taskId", "  ") })
        assertTrue((result.single() as UIMessagePart.Text).text.contains("cancel_agent requires a non-empty taskId"))
    }

    @Test
    fun `persona tool list is advertised in description`() {
        val tool = createSubagentTool(personas = listOf(persona("researcher", listOf("search_web", "read_file")))) { _, _, _, _, _, _ -> "" }
        assertTrue(tool.description.contains("researcher"))
        assertTrue("工具集应出现在描述里供模型选择", tool.description.contains("read_file"))
    }
}
