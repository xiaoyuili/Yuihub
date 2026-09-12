package me.yui.yuihub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.yui.yuihub.data.model.AssistantMemory
import me.yui.yuihub.data.model.MemoryCategory

fun buildMemoryTools(
    json: Json,
    onCreation: suspend (content: String, category: String, importance: Float?) -> AssistantMemory,
    onUpdate: suspend (id: Int, content: String, category: String, importance: Float?) -> AssistantMemory,
    onDelete: suspend (Int) -> Unit
): List<Tool> = listOf(
    Tool(
        name = "memory_tool",
        description = """
            Store long-term info about the user across conversations. `action`: create / edit / delete.
            - New fact: create + content + category + importance
            - Existing record on the same topic: edit + id + content (add category/importance only when changed)
            - Stale record: delete + id
            Categories: profile (identity/stable facts), preference (how the user likes things), coding, roleplay, daily, temporary (short-lived), other.
            importance 0.0-1.0: 0.9+ identity/strong preference; 0.6-0.8 ordinary preference or ongoing project; 0.2-0.5 minor note.
            Merge similar memories; prefer updating over creating. Never store sensitive personal data. Don't quote memory content in chat unless asked.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("create")
                                add("edit")
                                add("delete")
                            }
                        )
                        put("description", "Operation to perform: create, edit, or delete")
                    })
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "The id of the memory record (required for edit/delete)")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "The content of the memory record (required for create/edit)")
                    })
                    put("category", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                MemoryCategory.ALL.forEach { add(it) }
                            }
                        )
                        put("description", "Memory category (required for create; optional for edit)")
                    })
                    put("importance", buildJsonObject {
                        put("type", "number")
                        put("description", "Importance 0.0-1.0 (0.9+ identity/strong preferences, 0.6-0.8 ordinary, 0.2-0.5 minor). Optional; omitted keeps the current value on edit.")
                    })
                },
                required = listOf("action")
            )
        },
        execute = {
            val params = it.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
            val payload = when (action) {
                "create" -> {
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    val category = params["category"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val importance = params["importance"]?.jsonPrimitive?.floatOrNull
                    json.encodeToJsonElement(AssistantMemory.serializer(), onCreation(content, category, importance))
                }

                "edit" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    val category = params["category"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val importance = params["importance"]?.jsonPrimitive?.floatOrNull
                    json.encodeToJsonElement(AssistantMemory.serializer(), onUpdate(id, content, category, importance))
                }

                "delete" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    onDelete(id)
                    buildJsonObject {
                        put("success", true)
                        put("id", id)
                    }
                }

                else -> error("unknown action: $action, must be one of [create, edit, delete]")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)
