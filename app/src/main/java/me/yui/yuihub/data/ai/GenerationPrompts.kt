package me.yui.yuihub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.yui.yuihub.data.model.AssistantMemory
import me.yui.yuihub.data.model.EvolutionLesson
import me.yui.yuihub.utils.JsonInstantPretty

// Agent 工具循环的输出风格约束：只在最终回答轮写面向用户的文本，
// 工具轮不碎碎念（对齐 harness/Claude Code 的 思考→工具→…→最终输出 结构）
internal val AGENT_TOOL_STYLE_PROMPT = """
# Tool-use style
- While you still need to call tools, do NOT write user-facing prose between tool calls. Emit tool calls back-to-back; put all deliberation in your thinking channel, and if you have no thinking channel keep intermediate turns to tool calls only.
- Never narrate steps to the user ("Now I will check...", "Let me look at...", "The output shows...") in turns that contain tool calls.
- Write your complete user-facing answer only in the FINAL turn, after all tool work is done. A brief one-line status before a long tool run is the only allowed exception.
""".trimIndent()

internal fun buildMemoryPrompt(memories: List<AssistantMemory>) =
    buildString {
        appendLine("<memories>")
        append("Injected context: memories stored via the memory_tool, selected as relevant to the current task. Use them as background reference.")
        appendLine()
        val json = buildJsonArray {
            memories.forEach { memory ->
                add(buildJsonObject {
                    put("id", memory.id)
                    put("content", memory.content)
                })
            }
        }
        append(JsonInstantPretty.encodeToString(json))
        appendLine()
        append("</memories>")
        appendLine()
    }

internal fun buildEvolutionPrompt(lessons: List<EvolutionLesson>) =
    buildString {
        appendLine("<learned-methods>")
        append("Methods this assistant previously learned. Follow them. They are not user profile facts.")
        appendLine()
        lessons.forEach { lesson ->
            append("- [")
            append(lesson.kind)
            append("] ")
            append(lesson.title)
            append(": ")
            appendLine(lesson.content)
        }
        append("</learned-methods>")
        appendLine()
    }

