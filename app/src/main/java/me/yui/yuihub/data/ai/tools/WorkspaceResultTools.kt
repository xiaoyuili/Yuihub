package me.yui.yuihub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.yui.yuihub.data.repository.WorkspaceRepository

private const val RESULT_DEFAULT_LIMIT = 200
private const val RESULT_MAX_LIMIT = 2000

internal fun createWorkspaceReadResultTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
    readCache: WorkspaceReadCache,
) = Tool(
    name = "workspace_read_result",
    description = """
        Read a bounded range from a stored tool result by resultId (the value shown when output was truncated).
        Results live under /tool_outputs. offset is 1-based; limit defaults to $RESULT_DEFAULT_LIMIT, max $RESULT_MAX_LIMIT.
        Use this instead of re-running a command that already produced a large output.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("resultId", buildJsonObject {
                    put("type", "string")
                    put("description", "resultId from a truncated tool result; .txt/.md is appended if omitted")
                })
                put("offset", buildJsonObject {
                    put("type", "integer")
                    put("description", "1-based start line. Defaults to 1.")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum lines. Defaults to $RESULT_DEFAULT_LIMIT, max $RESULT_MAX_LIMIT.")
                })
            },
            required = listOf("resultId"),
        )
    },
    execute = {
        val params = it.jsonObject()
        val rawId = params.string("resultId")?.trim().orEmpty()
        require(rawId.matches(Regex("[A-Za-z0-9._-]+"))) {
            "resultId may only contain letters, digits, dot, underscore, and hyphen"
        }
        val path = when {
            rawId.endsWith(".txt") || rawId.endsWith(".md") -> "/tool_outputs/$rawId"
            else -> "/tool_outputs/$rawId.txt"
        }
        val offset = (params.int("offset") ?: 1).coerceAtLeast(1)
        val limit = (params.int("limit") ?: RESULT_DEFAULT_LIMIT).coerceIn(1, RESULT_MAX_LIMIT)
        val payload = runCatching {
            workspaceRepository.readTextPayload(workspaceId, path, offset, limit, readCache)
        }.getOrElse {
            // 子代理结果使用 .md，兼容同一 resultId 的两种落盘扩展名
            if (!rawId.contains('.')) {
                workspaceRepository.readTextPayload(
                    workspaceId,
                    "/tool_outputs/$rawId.md",
                    offset,
                    limit,
                    readCache,
                )
            } else {
                throw it
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)
