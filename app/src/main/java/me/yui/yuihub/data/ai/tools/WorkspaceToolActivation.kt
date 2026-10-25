package me.yui.yuihub.data.ai.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

internal data class AgentToolSet(
    val tools: List<Tool>,
    val extraTools: () -> List<Tool> = { emptyList() },
) {
    companion object {
        val Empty = AgentToolSet(emptyList())
    }
}

internal class WorkspaceToolActivation(
    private val catalog: Map<String, () -> Tool>,
) {
    private val active = linkedSetOf<String>()

    fun availableNames(): List<String> = catalog.keys.sorted()

    @Synchronized
    fun activeTools(): List<Tool> = active.mapNotNull { name -> catalog[name]?.invoke() }

    @Synchronized
    fun activate(names: List<String>): List<String> {
        val unknown = names.filter { it !in catalog }
        require(unknown.isEmpty()) {
            "Unknown workspace tools: ${unknown.joinToString()}. Available: ${availableNames().joinToString()}"
        }
        val newlyActivated = names.filter { active.add(it) }
        return newlyActivated
    }
}

internal fun createWorkspaceLoadToolsTool(
    activation: WorkspaceToolActivation,
) = Tool(
    name = "workspace_load_tools",
    description = """
        Activate optional workspace tools for the next model request of this generation.
        Available: ${activation.availableNames().joinToString()}.
        Call this before using an optional tool. Activation is scoped to this agent/generation and is not shared with subagents.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("tools", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "Tool names to activate, e.g. workspace_glob")
                })
            },
            required = listOf("tools"),
        )
    },
    execute = {
        val requested = it.jsonObject["tools"]?.jsonArray
            ?.map { element -> element.jsonPrimitive.content }
            ?: error("tools is required")
        require(requested.isNotEmpty()) { "tools must not be empty" }
        val activated = activation.activate(requested)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("activated", buildJsonArray { activated.forEach { add(JsonPrimitive(it)) } })
                    put("active", buildJsonArray {
                        activation.activeTools().forEach { add(JsonPrimitive(it.name)) }
                    })
                    put("available", buildJsonArray {
                        activation.availableNames().forEach { add(JsonPrimitive(it)) }
                    })
                }.toString()
            )
        )
    },
)
