package me.yui.yuihub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.yui.yuihub.utils.JsonInstantPretty

const val SPAWN_AGENT_TOOL_NAME = "spawn_agent"

fun createSubagentTool(
    onSpawn: suspend (description: String, prompt: String) -> String,
): Tool = Tool(
    name = SPAWN_AGENT_TOOL_NAME,
    description = """
        Delegate a self-contained subtask to a fresh child agent that shares this workspace, model, and tools
        but starts with an empty conversation (no parent history). Use it to parallelize independent work
        such as research, file inspection, or a focused implementation, then synthesize the child's final answer.
        The prompt must stand alone: include all files, constraints, and expected output format.
        Do not spawn a child for trivial one-step lookups you can do yourself.
        Children cannot spawn further children.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "Short label for the subtask (shown in the UI)")
                })
                put("prompt", buildJsonObject {
                    put("type", "string")
                    put("description", "Self-contained instructions for the child agent")
                })
            },
            required = listOf("description", "prompt"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "subtask" }
        val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (prompt.isBlank()) {
            error("spawn_agent requires a non-empty prompt")
        }
        val result = onSpawn(description, prompt)
        listOf(
            UIMessagePart.Text(
                JsonInstantPretty.encodeToString(
                    buildJsonObject {
                        put("description", description)
                        put("result", result)
                    }
                )
            )
        )
    },
)
