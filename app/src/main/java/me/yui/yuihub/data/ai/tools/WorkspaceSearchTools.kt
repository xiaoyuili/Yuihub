package me.yui.yuihub.data.ai.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.yui.yuihub.data.repository.WorkspaceRepository

private const val GLOB_DEFAULT_LIMIT = 200
private const val GLOB_MAX_LIMIT = 500
private const val GREP_DEFAULT_LIMIT = 100
private const val GREP_MAX_LIMIT = 100

internal fun createWorkspaceGlobTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_glob",
    description = """
        Find files by glob pattern under a Rootfs directory. Pattern examples: "**/*.kt", "*.md".
        Returns paths, sizes, and modified times. Use offset/nextOffset to page; truncated=true means this page is incomplete.
        scanComplete=false means the underlying 500-entry scan hit its cap, so do not claim a global "no more files" result.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("pattern", buildJsonObject {
                    put("type", "string")
                    put("description", "Glob pattern relative to path, e.g. **/*.kt")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Directory to search. Defaults to /workspace.")
                })
                put("offset", buildJsonObject {
                    put("type", "integer")
                    put("description", "0-based result offset. Defaults to 0.")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum entries. Defaults to $GLOB_DEFAULT_LIMIT, max $GLOB_MAX_LIMIT.")
                })
            },
            required = listOf("pattern"),
        )
    },
    execute = {
        val params = it.jsonObject()
        val pattern = params.string("pattern")?.trim()
            ?: error("pattern is required")
        require(pattern.isNotEmpty()) { "pattern must not be empty" }
        val base = params.string("path")?.trim().takeUnless { it.isNullOrBlank() } ?: "/workspace"
        val offset = (params.int("offset") ?: 0).coerceAtLeast(0)
        val limit = (params.int("limit") ?: GLOB_DEFAULT_LIMIT).coerceIn(1, GLOB_MAX_LIMIT)
        val raw = workspaceRepository.globRootfs(workspaceId, pattern, base)
        val page = raw.drop(offset).take(limit)
        val pageComplete = offset + page.size >= raw.size
        val scanComplete = raw.size < GLOB_MAX_LIMIT
        val items = buildJsonArray {
            page.forEach { entry ->
                add(buildJsonObject {
                    put("path", rootfsChildPath(base, entry.path))
                    put("isDirectory", entry.isDirectory)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                })
            }
        }
        val result = buildJsonObject {
            put("path", base)
            put("items", items)
            put("offset", offset)
            put("pageComplete", pageComplete)
            put("scanComplete", scanComplete)
            put("truncated", !pageComplete)
            put("complete", pageComplete && scanComplete)
            if (!pageComplete) put("nextOffset", offset + page.size)
        }
        listOf(UIMessagePart.Text(result.toString()))
    },
)

internal fun createWorkspaceGrepTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_grep",
    description = """
        Search UTF-8 files under a Rootfs directory and return matching lines with line numbers.
        query is a literal string unless regex=true. includeGlob optionally limits files, e.g. "**/*.kt".
        Results are capped at $GREP_MAX_LIMIT matches; truncated=true means more may exist. Use offset/nextOffset to page.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Literal text or regular expression to find")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Directory or file to search. Defaults to /workspace.")
                })
                put("regex", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Treat query as a regex. Defaults to false.")
                })
                put("ignoreCase", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Case-insensitive search. Defaults to true.")
                })
                put("includeGlob", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional glob filter relative to path, e.g. **/*.kt")
                })
                put("offset", buildJsonObject {
                    put("type", "integer")
                    put("description", "0-based result offset. Defaults to 0.")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum matches. Defaults to $GREP_DEFAULT_LIMIT, max $GREP_MAX_LIMIT.")
                })
            },
            required = listOf("query"),
        )
    },
    execute = {
        val params = it.jsonObject()
        val query = params.string("query") ?: error("query is required")
        require(query.isNotEmpty()) { "query must not be empty" }
        val base = params.string("path")?.trim().takeUnless { it.isNullOrBlank() } ?: "/workspace"
        val regex = params.boolean("regex") ?: false
        val ignoreCase = params.boolean("ignoreCase") ?: true
        val includeGlob = params.string("includeGlob")?.trim().takeUnless { it.isNullOrBlank() }
        val offset = (params.int("offset") ?: 0).coerceAtLeast(0)
        val limit = (params.int("limit") ?: GREP_DEFAULT_LIMIT).coerceIn(1, GREP_MAX_LIMIT)
        val raw = workspaceRepository.grepRootfs(
            id = workspaceId,
            query = query,
            path = base,
            regex = regex,
            ignoreCase = ignoreCase,
            includeGlob = includeGlob,
        )
        val page = raw.drop(offset).take(limit)
        val pageComplete = offset + page.size >= raw.size
        val scanComplete = raw.size < GREP_MAX_LIMIT
        val items = buildJsonArray {
            page.forEach { match ->
                add(buildJsonObject {
                    put("path", rootfsChildPath(base, match.path))
                    put("line", match.line)
                    put("text", match.text)
                })
            }
        }
        val result = buildJsonObject {
            put("query", query)
            put("path", base)
            put("items", items)
            put("offset", offset)
            put("pageComplete", pageComplete)
            put("scanComplete", scanComplete)
            put("truncated", !pageComplete)
            put("complete", pageComplete && scanComplete)
            if (!pageComplete) put("nextOffset", offset + page.size)
        }
        listOf(UIMessagePart.Text(result.toString()))
    },
)

internal fun createWorkspaceStatTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_stat",
    description = """
        Return metadata for a Rootfs path: exists, type, sizeBytes, modifiedAt, and revision for UTF-8 files.
        revision can be passed to workspace_edit_file or workspace_write_file as expectedRevision.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
            },
            required = listOf("path"),
        )
    },
    execute = {
        val path = it.jsonObject().absolutePath("path")
        val entry = workspaceRepository.rootfsEntry(workspaceId, path)
        val payload = buildJsonObject {
            put("path", path)
            if (entry == null) {
                put("exists", false)
            } else {
                put("exists", true)
                put("type", if (entry.isDirectory) "directory" else "file")
                put("sizeBytes", entry.sizeBytes)
                put("modifiedAt", entry.updatedAt)
                if (!entry.isDirectory) {
                    runCatching {
                        workspaceRepository.currentRevisionOrNull(workspaceId, path)
                    }.getOrNull()?.let { put("revision", it) }
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

private fun rootfsChildPath(base: String, child: String): String {
    val normalizedBase = base.trimEnd('/')
    val normalizedChild = child.trimStart('/')
    return if (normalizedBase.isBlank() || normalizedBase == "/") {
        "/$normalizedChild"
    } else {
        "$normalizedBase/$normalizedChild"
    }
}

internal fun kotlinx.serialization.json.JsonElement.jsonObject(): kotlinx.serialization.json.JsonObject =
    this as kotlinx.serialization.json.JsonObject

private fun kotlinx.serialization.json.JsonObject.boolean(name: String): Boolean? =
    this[name]?.jsonPrimitive?.content?.toBooleanStrictOrNull()

internal fun kotlinx.serialization.json.JsonObject.int(name: String): Int? =
    this[name]?.jsonPrimitive?.content?.toIntOrNull()
