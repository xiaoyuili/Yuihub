package me.yui.yuihub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.yui.yuihub.data.model.AssistantMemory
import me.yui.yuihub.data.model.MemoryCategory
import me.yui.yuihub.data.model.MemoryKind
import me.yui.yuihub.data.model.MemorySection
import me.yui.yuihub.data.model.MemoryStatus
import me.yui.yuihub.data.repository.MemoryRepository

/**
 * 模型侧记忆工具：create / edit / delete / search。
 * 写入自动去重合并，搜索返回关键词排序的摘要。
 */
fun buildMemoryTools(
    json: Json,
    repository: MemoryRepository,
    assistantId: String,
    conversationId: String?,
    workspaceId: String?,
): List<Tool> = listOf(
    Tool(
        name = "memory_tool",
        description = """
            Manage long-term memory for this assistant. `action`: create / edit / delete / search.
            Kinds: core (stable user profile/rules/AI self; section=user/rules/ai), project (workspace facts), fact (one atomic durable fact), lesson (correction or hard-won experience), topic (ongoing thread).
            keywords: 5-10 words the user would plausibly say when this memory should be recalled. Required for create.
            weight 1-4: 4 safety/red line, 3 user-emphasized or global rule, 2 ordinary preference/project decision, 1 minor.
            Prefer edit over create when a record already covers the topic. Delete archives (recoverable).
            Never store credentials, secrets, or one-off task state. Do not quote memory content in chat unless asked.
            Use action=search to recall details before answering; search returns ids for edit/delete.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("create"); add("edit"); add("delete"); add("search")
                        })
                        put("description", "Operation to perform")
                    })
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "Memory id (required for edit/delete)")
                    })
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Search keywords or sentence (required for search)")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "Memory content (required for create)")
                    })
                    put("title", buildJsonObject {
                        put("type", "string")
                        put("description", "Short title; used for display and retrieval")
                    })
                    put("kind", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { MemoryKind.ALL.forEach { add(it) } })
                        put("description", "Memory kind")
                    })
                    put("section", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { MemorySection.ALL.forEach { add(it) } })
                        put("description", "Core section (only when kind=core)")
                    })
                    put("category", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { MemoryCategory.ALL.forEach { add(it) } })
                        put("description", "Display category")
                    })
                    put("keywords", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "5-10 retrieval keywords")
                    })
                    put("weight", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", 4)
                        put("description", "Importance 1-4")
                    })
                    put("status", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { MemoryStatus.ALL.forEach { add(it) } })
                        put("description", "active / stale (finished) / archived (invalid)")
                    })
                },
                required = listOf("action")
            )
        },
        execute = {
            val params = it.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
            when (action) {
                "create" -> {
                    val content = params["content"]?.jsonPrimitive?.contentOrNull
                        ?: error("content is required")
                    val result = repository.createMemory(
                        assistantId = assistantId,
                        content = content,
                        title = params["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        kind = MemoryKind.normalize(params["kind"]?.jsonPrimitive?.contentOrNull),
                        section = MemorySection.normalize(params["section"]?.jsonPrimitive?.contentOrNull),
                        category = MemoryCategory.normalize(params["category"]?.jsonPrimitive?.contentOrNull),
                        keywords = params.stringList("keywords"),
                        weight = params["weight"]?.jsonPrimitive?.intOrNull ?: 2,
                        status = MemoryStatus.normalize(params["status"]?.jsonPrimitive?.contentOrNull),
                        workspaceId = workspaceId,
                        sourceConversationId = conversationId,
                    )
                    val payload = buildJsonObject {
                        put("ok", true)
                        put("id", result.memory.id)
                        put("kind", result.memory.kind)
                        put("weight", result.memory.weight)
                        put("merged", result.merged)
                        put("keywords", buildJsonArray { result.memory.keywords.forEach { add(it) } })
                    }
                    listOf(UIMessagePart.Text(payload.toString()))
                }

                "edit" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    val current = repository.getMemory(id)
                        ?: error("memory #$id not found")
                    val keywords = params.stringList("keywords")
                    val kind = params["kind"]?.jsonPrimitive?.contentOrNull
                    val updated = repository.updateMemory(
                        current.copy(
                            title = params["title"]?.jsonPrimitive?.contentOrNull ?: current.title,
                            content = params["content"]?.jsonPrimitive?.contentOrNull ?: current.content,
                            kind = kind?.let { MemoryKind.normalize(it) } ?: current.kind,
                            section = if ((kind ?: current.kind) == MemoryKind.CORE) {
                                MemorySection.normalize(
                                    params["section"]?.jsonPrimitive?.contentOrNull ?: current.section
                                )
                            } else "",
                            category = params["category"]?.jsonPrimitive?.contentOrNull
                                ?.let { MemoryCategory.normalize(it) } ?: current.category,
                            keywords = keywords.ifEmpty { current.keywords },
                            weight = params["weight"]?.jsonPrimitive?.intOrNull ?: current.weight,
                            status = params["status"]?.jsonPrimitive?.contentOrNull
                                ?.let { MemoryStatus.normalize(it) } ?: current.status,
                        )
                    ) ?: error("memory #$id not found")
                    conversationId?.let {
                        repository.recordUsage(assistantId, it, listOf(updated.id), "written")
                    }
                    val payload = json.encodeToJsonElement(AssistantMemory.serializer(), updated)
                    listOf(UIMessagePart.Text(payload.toString()))
                }

                "delete" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    repository.archiveMemory(listOf(id))
                    val payload = buildJsonObject {
                        put("ok", true)
                        put("id", id)
                        put("archived", true)
                    }
                    listOf(UIMessagePart.Text(payload.toString()))
                }

                "search" -> {
                    val query = params["query"]?.jsonPrimitive?.contentOrNull
                        ?: error("query is required")
                    val hits = repository.searchMemories(
                        assistantId = assistantId,
                        query = query,
                        workspaceId = workspaceId,
                        topK = 8,
                    )
                    conversationId?.let { key ->
                        repository.recordUsage(assistantId, key, hits.map { hit -> hit.id }, "searched")
                    }
                    val payload = buildJsonObject {
                        put("hits", buildJsonArray {
                            hits.forEach { memory ->
                                add(buildJsonObject {
                                    put("id", memory.id)
                                    put("title", memory.displayTitle)
                                    put("kind", memory.kind)
                                    put("weight", memory.weight)
                                    put("updatedAt", memory.updatedAt)
                                    put("keywords", buildJsonArray { memory.keywords.forEach { add(it) } })
                                    put("content", memory.content.take(400))
                                })
                            }
                        })
                    }
                    listOf(UIMessagePart.Text(payload.toString()))
                }

                else -> error("unknown action: $action")
            }
        }
    )
)

private fun kotlinx.serialization.json.JsonObject.stringList(key: String): List<String> {
    val element = this[key] ?: return emptyList()
    return (element as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?.filter { it.isNotBlank() }
        .orEmpty()
}
