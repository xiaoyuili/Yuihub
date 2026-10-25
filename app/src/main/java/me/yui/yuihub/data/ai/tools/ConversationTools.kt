package me.yui.yuihub.data.ai.tools

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.yui.yuihub.data.db.fts.MessageSearchSort
import me.yui.yuihub.data.repository.ConversationRepository
import me.yui.yuihub.utils.JsonInstantPretty
import me.yui.yuihub.utils.toLocalDate
import kotlin.uuid.Uuid

/**
 * Tools that let the assistant query the user's past conversations on demand, instead of
 * statically injecting recent chats into the system prompt (which would break prompt caching).
 */
fun createConversationTools(
    conversationRepo: ConversationRepository,
    assistantId: Uuid,
): List<Tool> = listOf(
    Tool(
        name = "recent_chats",
        description = """
            List the user's recent conversations with THIS assistant only (not other assistants).
            Returns conversation titles and the date of last activity, ordered by pinned first then most recently updated.
            Use this when you need quick context about what the user has been discussing lately.
            Only titles and dates are returned; use `conversation_search` to look up the actual content.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Maximum number of recent conversations to return (default: 10, max: 30)"
                        )
                    })
                }
            )
        },
        execute = {
            val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 30)
            val recent = conversationRepo.getRecentConversations(
                assistantId = assistantId,
                limit = limit,
            )
            val payload = buildJsonArray {
                recent.forEach { conversation ->
                    add(buildJsonObject {
                        put("id", conversation.id.toString())
                        put("title", conversation.title.ifBlank { "Untitled" })
                        put("last_chat", conversation.updateAt.toLocalDate())
                    })
                }
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    ),
    Tool(
        name = "conversation_search",
        description = """
            Full-text search across THIS assistant's past conversations only (not other assistants).
            Use focused keywords. Run multiple searches with different keywords if needed.
            Each result includes the conversation title, a snippet with matched keywords wrapped in [brackets], and the date.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Keywords to search for in past conversation messages")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Maximum number of results to return (default: 15, max: 50)"
                        )
                    })
                },
                required = listOf("query")
            )
        },
        execute = {
            val query = it.jsonObject["query"]?.jsonPrimitive?.contentOrNull
                ?: error("query is required")
            val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 15).coerceIn(1, 50)
            val results = conversationRepo
                .searchMessages(query, MessageSearchSort.RELEVANCE, assistantId)
                .take(limit)
            val payload = buildJsonArray {
                results.forEach { result ->
                    add(buildJsonObject {
                        put("conversation_id", result.conversationId)
                        put("title", result.title.ifBlank { "Untitled" })
                        put("snippet", result.snippet)
                        put("date", result.updateAt.toLocalDate())
                    })
                }
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    )
)

/**
 * 压缩后的历史仍在本地节点中；这两个只读工具让模型按需查回精确内容，
 * 避免只能依赖有损摘要。仅在当前对话存在压缩摘要时注册。
 */
fun createHistoryTools(
    conversationRepo: ConversationRepository,
    conversationId: Uuid,
): List<Tool> = listOf(
    Tool(
        name = "history_search",
        description = """
            Search THIS conversation's history, including archived messages hidden behind context compression.
            Use precise keywords. Results contain node_id values for history_read.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Keywords to search for in this conversation's history")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum results (default 12, max 30)")
                    })
                },
                required = listOf("query")
            )
        },
        execute = {
            val query = it.jsonObject["query"]?.jsonPrimitive?.contentOrNull
                ?: error("query is required")
            val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 12).coerceIn(1, 30)
            val results = conversationRepo.searchMessages(
                keyword = query,
                sort = MessageSearchSort.RELEVANCE,
                conversationId = conversationId,
            ).take(limit)
            val payload = buildJsonArray {
                results.forEach { result ->
                    add(buildJsonObject {
                        put("node_id", result.nodeId)
                        put("message_id", result.messageId)
                        put("snippet", result.snippet)
                        put("date", result.updateAt.toLocalDate())
                    })
                }
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    ),
    Tool(
        name = "history_read",
        description = """
            Read one archived message from THIS conversation by node_id returned by history_search.
            Returns line-numbered original content; use offset/limit to page through large tool outputs.
            Do not include the `N|` line prefix when quoting or editing.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("node_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Message node id from history_search")
                    })
                    put("offset", buildJsonObject {
                        put("type", "integer")
                        put("description", "1-based start line (default 1)")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum lines (default 120, max 400)")
                    })
                },
                required = listOf("node_id")
            )
        },
        execute = {
            val nodeId = it.jsonObject["node_id"]?.jsonPrimitive?.contentOrNull
                ?: error("node_id is required")
            val offset = (it.jsonObject["offset"]?.jsonPrimitive?.intOrNull ?: 1).coerceAtLeast(1)
            val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 120).coerceIn(1, 400)
            val conversation = conversationRepo.getConversationById(conversationId)
                ?: error("Conversation not found")
            val node = conversation.messageNodes.firstOrNull { it.id.toString() == nodeId }
                ?: error("History node not found: $nodeId")
            val lines = node.currentMessage.historyText().lines()
            val startIndex = (offset - 1).coerceAtMost(lines.size)
            val selected = lines.drop(startIndex).take(limit)
            val nextOffset = startIndex + selected.size + 1
            val complete = startIndex + selected.size >= lines.size
            val text = buildString {
                selected.forEachIndexed { index, line ->
                    append(startIndex + index + 1)
                    append('|')
                    append(line)
                    if (index != selected.lastIndex) append('\n')
                }
            }
            val payload = buildJsonObject {
                put("node_id", nodeId)
                put("role", node.currentMessage.role.name.lowercase())
                put("start_line", startIndex + 1)
                put("end_line", startIndex + selected.size)
                put("total_lines", lines.size)
                put("complete", complete)
                if (!complete) put("next_offset", nextOffset)
                put("text", text)
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    )
)

private fun UIMessage.historyText(): String = buildString {
    parts.forEachIndexed { index, part ->
        if (index > 0) append("\n\n")
        when (part) {
            is UIMessagePart.Text -> append(part.text)
            is UIMessagePart.Tool -> {
                append("[tool: ${part.toolName}]\n")
                append(part.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text })
            }
            else -> append("[${part::class.simpleName}]")
        }
    }
}
