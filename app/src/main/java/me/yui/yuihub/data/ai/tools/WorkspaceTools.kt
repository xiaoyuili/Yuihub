package me.yui.yuihub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.yui.yuihub.data.files.FilesManager
import me.yui.yuihub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceStorageArea
import me.yui.yuihub.utils.generateUnifiedDiff
import me.rerere.workspace.MAX_OUTPUT_CHARS
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceMountDir
import org.koin.java.KoinJavaComponent.getKoin
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
internal const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024
private const val READ_DEFAULT_LIMIT = 200
private const val READ_MAX_LIMIT = 2000
private const val READ_FILES_MAX = 16

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_present_file" to false,
    "workspace_shell" to true,
)

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

internal suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
): AgentToolSet {
    if (workspaceId.isNullOrBlank()) return AgentToolSet.Empty
    val workspace = workspaceRepository.getById(workspaceId)
    val approvalOverrides = workspace?.toolApprovalOverrides().orEmpty()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)
    // 用户自己挂载的目录：读写挂载算可写安全区（不再强制审批），只读挂载在工具层拒绝写入
    val mountDirs = workspace?.mountDirList().orEmpty()
    fun WorkspaceMountDir.normalizedTarget(): String = target.trimEnd('/').ifBlank { "/" }
    val writableRoots = (
        WRITABLE_ROOT_PREFIXES + mountDirs.filterNot { it.readOnly }.map { it.normalizedTarget() }
        ).distinct()
    val readOnlyRoots = (
        READ_ONLY_ROOT_PREFIXES + mountDirs.filter { it.readOnly }.map { it.normalizedTarget() }
        ).distinct()

    val shellCwd = cwd?.removePrefix("/workspace/")?.removePrefix("/workspace")
    val readCache = WorkspaceReadCache()
    val readFiles = createReadFilesTool(workspaceId, ::needsApproval, workspaceRepository, readCache)
    val glob = createWorkspaceGlobTool(workspaceId, workspaceRepository)
    val grep = createWorkspaceGrepTool(workspaceId, workspaceRepository)
    val stat = createWorkspaceStatTool(workspaceId, workspaceRepository)
    val readResult = createWorkspaceReadResultTool(workspaceId, workspaceRepository, readCache)
    val activation = WorkspaceToolActivation(
        mapOf(
            "workspace_read_files" to { readFiles },
            "workspace_glob" to { glob },
            "workspace_grep" to { grep },
            "workspace_stat" to { stat },
            "workspace_read_result" to { readResult },
        )
    )

    return AgentToolSet(
        tools = listOf(
            createReadFileTool(workspaceId, ::needsApproval, workspaceRepository, readCache),
            createWorkspaceLoadToolsTool(activation),
            createWriteFileTool(workspaceId, ::needsApproval, workspaceRepository, readCache, writableRoots, readOnlyRoots),
            createEditFileTool(workspaceId, ::needsApproval, workspaceRepository, readCache, writableRoots, readOnlyRoots),
            createPresentFileTool(workspaceId, ::needsApproval, workspaceRepository),
            createShellTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd, readCache),
        ),
        extraTools = { activation.activeTools() },
    )
}

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun createReadFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    readCache: WorkspaceReadCache,
) = Tool(
    name = "workspace_read_file",
    description = """
        Read a bounded range of a UTF-8 file from the workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area. offset is 1-based; limit defaults to $READ_DEFAULT_LIMIT and is capped at $READ_MAX_LIMIT.
        Text output is numbered as `N|content`; do not copy the `N|` prefix back when editing.
        Returns revision, totalLines, complete, and nextOffset; use nextOffset to continue reading.
        Image files are returned as image parts. Non-UTF-8 binary files are rejected; use workspace_shell (od/hexdump/base64).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("offset", buildJsonObject {
                    put("type", "integer")
                    put("description", "1-based start line. Defaults to 1.")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum lines to return. Defaults to $READ_DEFAULT_LIMIT, max $READ_MAX_LIMIT.")
                })
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_read_file") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        // P2-3: /upload 无上传时挂载源目录可能为空（RepositoryModule 已 mkdirs，但旧版残留可能缺失），
        // 给出明确语义而不是裸 NOT_FOUND，避免模型把它当异常反复排查
        if (path == "/upload" || path.startsWith("/upload/")) {
            val size = runCatching { workspaceRepository.rootfsFileSize(workspaceId, path) }.getOrNull()
            if (size == null) {
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("path", path)
                            put("text", "(no file at this path — /upload holds user-uploaded files and may be empty when nothing has been uploaded yet; this is normal, not an error)")
                        }.toString()
                    )
                )
            }
        }
        if (path.isImagePath()) {
            workspaceRepository.readImageInRootfs(workspaceId, path)
        } else {
            val offset = params.int("offset")?.coerceAtLeast(1) ?: 1
            val limit = (params.int("limit") ?: READ_DEFAULT_LIMIT).coerceIn(1, READ_MAX_LIMIT)
            val payload = workspaceRepository.readTextPayload(
                workspaceId = workspaceId,
                path = path,
                offset = offset,
                limit = limit,
                cache = readCache,
            )
            listOf(UIMessagePart.Text(payload.toString()))
        }
    },
)

private fun createReadFilesTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    readCache: WorkspaceReadCache,
) = Tool(
    name = "workspace_read_files",
    description = """
        Read bounded ranges from up to $READ_FILES_MAX UTF-8 files in one call.
        Each item accepts path, offset (1-based, default 1), and limit (default $READ_DEFAULT_LIMIT, max $READ_MAX_LIMIT).
        One failed item does not fail the others. Returns {items:[...]} with the same fields as workspace_read_file.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("files", buildJsonObject {
                    put("type", "array")
                    put("maxItems", READ_FILES_MAX)
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            putPathProperty(required = true)
                            put("offset", buildJsonObject {
                                put("type", "integer")
                                put("description", "1-based start line. Defaults to 1.")
                            })
                            put("limit", buildJsonObject {
                                put("type", "integer")
                                put("description", "Maximum lines. Defaults to $READ_DEFAULT_LIMIT, max $READ_MAX_LIMIT.")
                            })
                        })
                        put("required", buildJsonArray { add(JsonPrimitive("path")) })
                    })
                })
            },
            required = listOf("files"),
        )
    },
    needsApproval = { needsApproval("workspace_read_file") },
    execute = {
        val files = it.jsonObject["files"]?.jsonArray
            ?: error("files is required")
        require(files.size in 1..READ_FILES_MAX) {
            "files must contain 1..$READ_FILES_MAX items"
        }
        val items = buildJsonArray {
            files.forEach { element ->
                val item = runCatching {
                    val obj = element.jsonObject
                    val path = obj.absolutePath("path")
                    require(!path.isImagePath()) {
                        "Image files are not supported by workspace_read_files; use workspace_read_file"
                    }
                    workspaceRepository.readTextPayload(
                        workspaceId = workspaceId,
                        path = path,
                        offset = obj.int("offset")?.coerceAtLeast(1) ?: 1,
                        limit = (obj.int("limit") ?: READ_DEFAULT_LIMIT).coerceIn(1, READ_MAX_LIMIT),
                        cache = readCache,
                        recordCache = false,
                    )
                }.getOrElse { error ->
                    buildJsonObject {
                        put("path", runCatching { element.jsonObject.string("path") }.getOrNull() ?: "")
                        put("error", error.message ?: error::class.simpleName ?: "read failed")
                    }
                }
                add(item)
            }
        }
        listOf(UIMessagePart.Text(buildJsonObject { put("items", items) }.toString()))
    },
)

private fun createWriteFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    readCache: WorkspaceReadCache,
    writableRoots: List<String> = WRITABLE_ROOT_PREFIXES,
    readOnlyRoots: List<String> = READ_ONLY_ROOT_PREFIXES,
) = Tool(
    name = "workspace_write_file",
    description = """
        Write a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Concurrency guard: pass expectedRevision from a prior read (preferred) and/or expectedMtimeMs.
        If the file changed meanwhile, the write is rejected with REVISION_CONFLICT / WRITE_CONFLICT and nothing is written.
        force=true deliberately bypasses these checks.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
                put("expectedRevision", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional SHA-256 revision returned by workspace_read_file. The write is rejected if the current file revision differs.")
                })
                put("expectedMtimeMs", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional: mtime (ms) you believe the file currently has (from a previous read/write result). Write is rejected if it changed meanwhile.")
                })
                put("force", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Deliberately overwrite despite a detected conflict. Defaults to false.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    needsApproval = {
        // 只读挂载必然报错，不必先让用户审批
        !it.pathInReadOnlyRoots("path", readOnlyRoots) &&
            (needsApproval("workspace_write_file") || it.pathOutsideWritableRoots("path", writableRoots))
    },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        rejectReadOnlyPath(path, readOnlyRoots)
        val text = params.string("text")
            ?: error("text is required (parameters: path, text, overwrite, expectedMtimeMs, force; the content parameter is named 'text')")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val expectedRevision = params.string("expectedRevision")
        val expectedMtime = params["expectedMtimeMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val force = params["force"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

        val entry = readCache.mutex(path).withLock {
            if (!force && expectedRevision != null) {
                val currentRevision = workspaceRepository.currentRevisionOrNull(workspaceId, path)
                if (currentRevision != expectedRevision) {
                    error(
                        "REVISION_CONFLICT: $path was modified by another session " +
                            "(current=${currentRevision ?: "missing"}, expected=$expectedRevision). " +
                            "Re-read the file and merge, or pass force=true to overwrite deliberately."
                    )
                }
            }
            // P1-3 写冲突检测：调用方给了 expectedMtimeMs 且磁盘文件比它新 → 说明有其它会话写过；
            // 未显式 force 时拒绝并给出双方时间戳，避免并发双写静默 last-writer-wins
            if (!force && expectedMtime != null) {
                val currentMtime = runCatching {
                    workspaceRepository.rootfsFileMtime(workspaceId, path)
                }.getOrNull()
                if (currentMtime != null && currentMtime > expectedMtime) {
                    error(
                        "WRITE_CONFLICT: $path was modified by another session (disk mtime=$currentMtime, your expectedMtimeMs=$expectedMtime). " +
                            "Re-read the file and merge, or pass force=true to overwrite deliberately."
                    )
                }
            }
            workspaceRepository.writeTextInRootfs(workspaceId, path, text, overwrite)
        }
        readCache.invalidate(path)
        val revision = text.toByteArray(Charsets.UTF_8).sha256Hex()
        val payload = buildJsonObject {
            entry.toJson().forEach { (key, value) -> put(key, value) }
            put("revision", revision)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

private fun createEditFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    readCache: WorkspaceReadCache,
    writableRoots: List<String> = WRITABLE_ROOT_PREFIXES,
    readOnlyRoots: List<String> = READ_ONLY_ROOT_PREFIXES,
) = Tool(
    name = "workspace_edit_file",
    description = """
        Edit a UTF-8 file in the workspace Rootfs with one atomic write. Paths must be absolute inside Rootfs.
        Prefer `edits: [{old_text,new_text}]` for multiple independent changes; they are applied in order to one snapshot.
        Legacy `old_text`/`new_text`/`replace_all` is still accepted. Each old_text must match exactly once unless replace_all=true.
        If no exact match is found, whitespace-tolerant line matching is attempted and may re-indent the replacement.
        Pass expectedRevision from workspace_read_file to reject concurrent edits (REVISION_CONFLICT, no file changes).
        Do not include the `N|` line-number prefix from read output in old_text.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("edits", buildJsonObject {
                    put("type", "array")
                    put("maxItems", 32)
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("old_text", buildJsonObject {
                                put("type", "string")
                                put("description", "Exact text to replace; must be unique in the current file")
                            })
                            put("new_text", buildJsonObject {
                                put("type", "string")
                                put("description", "Replacement text; empty string deletes")
                            })
                        })
                        put("required", buildJsonArray { add(JsonPrimitive("old_text")); add(JsonPrimitive("new_text")) })
                    })
                })
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Legacy single edit: exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Legacy single edit: replacement text")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Legacy single edit: replace every occurrence. Defaults to false.")
                })
                put("expectedRevision", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional SHA-256 revision from workspace_read_file")
                })
            },
            required = listOf("path"),
        )
    },
    needsApproval = {
        // 只读挂载必然报错，不必先让用户审批
        !it.pathInReadOnlyRoots("path", readOnlyRoots) &&
            (needsApproval("workspace_edit_file") || it.pathOutsideWritableRoots("path", writableRoots))
    },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        rejectReadOnlyPath(path, readOnlyRoots)
        val legacyOld = params.string("old_text")
        val legacyNew = params.string("new_text")
        val edits = params["edits"]?.jsonArray?.map { element ->
            val obj = element.jsonObject
            val oldText = obj.string("old_text") ?: error("edits[].old_text is required")
            val newText = obj.string("new_text") ?: error("edits[].new_text is required")
            require(oldText.isNotEmpty()) { "edits[].old_text must not be empty" }
            oldText to newText
        }.orEmpty()
        val editList = when {
            edits.isNotEmpty() && (legacyOld != null || legacyNew != null) ->
                error("Use either edits[] or legacy old_text/new_text, not both")
            edits.isNotEmpty() -> edits
            legacyOld != null || legacyNew != null -> {
                val oldText = legacyOld ?: error("old_text is required with new_text")
                val newText = legacyNew ?: error("new_text is required with old_text")
                require(oldText.isNotEmpty()) { "old_text must not be empty" }
                listOf(oldText to newText)
            }
            else -> error("edits[] or old_text/new_text is required")
        }
        require(editList.size <= 32) { "edits supports at most 32 entries" }
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val expectedRevision = params.string("expectedRevision")

        val written = readCache.mutex(path).withLock {
            val snapshot = workspaceRepository.readTextSnapshot(workspaceId, path)
            if (expectedRevision != null && snapshot.revision != expectedRevision) {
                error(
                    "REVISION_CONFLICT: $path was modified by another session " +
                        "(current=${snapshot.revision}, expected=$expectedRevision). Re-read and merge."
                )
            }
            var current = snapshot.text
            val strategies = linkedSetOf<String>()
            var replacements = 0
            var indentationAdjusted = false
            editList.forEach { (oldText, newText) ->
                val result = try {
                    replaceText(current, oldText, newText, replaceAll && editList.size == 1)
                } catch (e: IllegalArgumentException) {
                    error("${e.message} (path: $path)")
                }
                current = result.updated
                strategies += result.strategy
                replacements += result.replacements
                indentationAdjusted = indentationAdjusted || result.indentationAdjusted
            }
            val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, current, overwrite = true)
            EditWritten(
                original = snapshot.text,
                updated = current,
                entry = entry,
                replacements = replacements,
                strategies = strategies,
                indentationAdjusted = indentationAdjusted,
            )
        }
        readCache.invalidate(path)
        val revision = written.updated.toByteArray(Charsets.UTF_8).sha256Hex()
        val diff = generateUnifiedDiff(written.original, written.updated, written.entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("path", written.entry.path)
                    put("edits", editList.size)
                    put("replacements", written.replacements)
                    if (written.strategies != setOf(ExactReplacer.name)) {
                        put("matchStrategies", buildJsonArray { written.strategies.forEach { add(JsonPrimitive(it)) } })
                    }
                    if (written.indentationAdjusted) {
                        put(
                            "indentationWarning",
                            "old_text indentation did not match the file, so the replacement was re-indented " +
                                "to the matched block's original leading whitespace. Re-read the file to verify indentation."
                        )
                    }
                    put("sizeBytes", written.entry.sizeBytes)
                    put("updatedAt", written.entry.updatedAt)
                    put("revision", revision)
                }.toString(),
                // diff 存入 metadata 供 UI 渲染 diff view, 不会随工具结果发送给 API
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

private data class EditWritten(
    val original: String,
    val updated: String,
    val entry: WorkspaceFileEntry,
    val replacements: Int,
    val strategies: Set<String>,
    val indentationAdjusted: Boolean,
)

private fun createPresentFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_present_file",
    description = """
        Send a file to the user as a file card in the chat (they can open, export or share it).
        Use ONLY when the user asks to receive a file or asks for a deliverable to be handed over.
        Do not send files on your own initiative; if the user only needs to know where a file is, mention its path in your reply instead.
        Paths must be absolute inside Rootfs; use /workspace for the workspace files area.
        Call it after the file is fully written; `note` is a short description. Do not paste the file content in your reply.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("note", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional short note shown with the file, e.g. what it is or how to use it")
                })
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_present_file") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val note = params.string("note").orEmpty()
        val size = workspaceRepository.rootfsFileSize(workspaceId, path)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("path", path)
                    put("name", path.rootfsName())
                    put("sizeBytes", size)
                    if (note.isNotBlank()) put("note", note)
                }.toString()
            )
        )
    },
)

private fun createShellTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
    readCache: WorkspaceReadCache? = null,
) = Tool(
    name = "workspace_shell",
    description = buildString {
        append("Run a shell command in the assistant's bound workspace Rootfs; the workspace files area is mounted at /workspace. ")
        append("Use cwd for a path relative to the workspace files root. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("The default working directory is stated in the <workspace> prompt. ")
        }
        append("Each call runs the command in a subshell: no state (variables, functions, cd) persists between calls — pass absolute paths or the `cwd` parameter instead of relying on `cd` or exported variables. ")
        append("For long or quote-heavy scripts, write the script to a file first and pass script_path instead of command; this avoids JSON escaping failures. ")
        append("Output is capped: if truncated, re-run with head/tail/grep to fetch only the needed part; large installs (apt/npm/pip) need a larger timeout (300-600). ")
        append("Requires Rootfs to be installed and ready.")
        appendLine()
        append("Background services are supported: start detached with 'nohup <cmd> >/workspace/svc.log 2>&1 &', verify with a short sleep + curl, stop with 'pkill -f <pattern>'; never wait on the service command itself. ")
        append("Bind web services to 127.0.0.1 only — never 0.0.0.0 (the device shares its network stack, binding all interfaces would expose the service to the local network); tell the user the full URL as http://127.0.0.1:<port>.")
        appendLine()
        append("Proot notes: prefer 'pgrep -f <pattern>' over ps; check a port with 'curl -s -m 2 http://127.0.0.1:<port>' (/proc/net/tcp may be empty); curl may be missing - install with 'apt-get install -y curl'.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("script_path", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional path to a script file; executed as bash <script_path>. Use instead of command for long scripts.")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Working directory relative to the workspace files root. Defaults to the workspace's current working directory stated in the <workspace> prompt, or root if none."
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Command timeout in seconds. Defaults to 30, max $SHELL_TIMEOUT_MAX_SECONDS. Use 300-600 for package installs and builds."
                    )
                })
            },
            required = emptyList(),
        )
    },
    needsApproval = { needsApproval("workspace_shell") },
    execute = {
        val params = it.jsonObject
        val command = params.string("command")?.takeIf { it.isNotBlank() }
        val scriptPath = params.string("script_path")?.takeIf { it.isNotBlank() }
        require((command == null) xor (scriptPath == null)) {
            "Provide exactly one of command or script_path"
        }
        val effectiveCommand = command ?: "bash -- ${scriptPath!!.shellQuote()}"
        val cwd = (params.string("cwd") ?: defaultCwd.orEmpty())
            .removePrefix("/workspace/").removePrefix("/workspace")
        val timeoutMillis = params.string("timeout")?.toLongOrNull()
            ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
            ?.times(1_000L)
            ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
        val result = workspaceRepository.executeCommand(workspaceId, effectiveCommand, cwd, timeoutMillis)
        // shell 可能改任意文件，直接放弃本生成内的读取去重证据，避免返回已过期的“未变化”
        readCache?.clearAll()
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    // 截断提示字段放在 stdout 之前：完整 JSON 可能再次被调用链截断为前 4K,
                    // 提示若排在输出后面就随尾部一起看不到
                    if (result.truncated) {
                        put("truncated", true)
                        put(
                            "truncatedMarker",
                            "[truncated: showing first ${result.stdout.length} of more chars (cap ${MAX_OUTPUT_CHARS / 1024}K)]",
                        )
                        val spill = result.spillFile
                        if (spill != null) {
                            val resultId = spill.name.substringBeforeLast('.')
                            put("resultId", resultId)
                            put("fullOutputPath", "/tool_outputs/${spill.name}")
                            put(
                                "truncatedHint",
                                "Output exceeded ${MAX_OUTPUT_CHARS / 1024}K chars; the COMPLETE output is saved to " +
                                    "/tool_outputs/${spill.name} — use workspace_read_result(resultId=\"$resultId\") " +
                                    "(call workspace_load_tools first if needed) or read the path in ranges. " +
                                    "For future commands prefer pipes like `cmd 2>&1 | tail -c 4000`, `| head -50` or `| grep keyword`."
                            )
                        } else {
                            put(
                                "truncatedHint",
                                "Output exceeded ${MAX_OUTPUT_CHARS / 1024}K chars and was cut off mid-stream. " +
                                    "Use pipes like `cmd 2>&1 | tail -c 4000`, `| head -50`, `| grep keyword`, " +
                                    "or redirect to a file and read parts of it."
                            )
                        }
                    }
                    if (result.timedOut) {
                        put(
                            "timeoutHint",
                            "Command was killed after the ${timeoutMillis / 1000}s timeout. " +
                                "Retry with a larger timeout parameter (e.g. 300) or run it in the background with nohup."
                        )
                    }
                    put("exitCode", result.exitCode)
                    put("stdout", result.stdout)
                    put("stderr", result.stderr)
                    put("timedOut", result.timedOut)
                }.toString()
            )
        )
    },
)

internal fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

/**
 * 按 Rootfs 内绝对路径读入内存。路径映射交给 WorkspaceManager, 由它统一处理
 * /workspace、bind mount 与 Rootfs 内部路径。
 */
internal suspend fun WorkspaceRepository.readRootfsBuffer(
    workspaceId: String,
    path: String,
): ByteArrayOutputStream {
    val size = rootfsFileSize(workspaceId, path)
    require(size <= MAX_READ_FILE_BYTES) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it."
    }
    // /workspace 即宿主侧 filesDir, 直连文件 IO 免去 proot 进程启动
    workspaceRelativePathInFilesArea(path)?.let { relative ->
        return ByteArrayOutputStream(size.toInt()).also { out ->
            exportFile(workspaceId, WorkspaceStorageArea.FILES, relative, out)
        }
    }
    return ByteArrayOutputStream(size.toInt()).also { exportRootfsFile(workspaceId, path, it) }
}

private suspend fun WorkspaceRepository.readImageInRootfs(
    workspaceId: String,
    path: String,
): List<UIMessagePart> {
    val bytes = readRootfsBuffer(workspaceId, path).toByteArray()

    val filesManager = getKoin().get<FilesManager>()
    val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
    return listOf(
        UIMessagePart.Image(url = uris.first().toString()),
        UIMessagePart.Text(
            buildJsonObject {
                put("path", path)
                put("description", "Image file read successfully")
            }.toString()
        ),
    )
}

private suspend fun WorkspaceRepository.writeTextInRootfs(
    workspaceId: String,
    path: String,
    text: String,
    overwrite: Boolean,
): WorkspaceFileEntry {
    // /workspace 即宿主侧 filesDir, 直连文件 IO 免去 proot 进程启动与 stdin 传输
    workspaceRelativePathInFilesArea(path)?.let { relative ->
        require(relative.isNotBlank()) { "Path must point to a file inside /workspace" }
        val entry = writeText(workspaceId, relative, text, overwrite)
        return entry.toRootfsEntry()
    }
    val pathArg = path.shellQuote()
    val result = runRootfsCommand(
        workspaceId = workspaceId,
        action = "Write file",
        command = """
            overwrote=0
            if [ -e $pathArg ]; then overwrote=1; fi
            if [ -e $pathArg ] && [ ${(!overwrite).shellFlag()} = 1 ]; then
              printf '%s\n' ${"File already exists: $path".shellQuote()} >&2
              exit 1
            fi
            if [ -e $pathArg ] && [ ! -f $pathArg ]; then
              printf '%s\n' ${"Path is not a file: $path".shellQuote()} >&2
              exit 1
            fi
            parent=${'$'}(dirname -- $pathArg) || exit 1
            mkdir -p -- "${'$'}parent" || exit 1
            cat > $pathArg || exit 1
            ${statEntryCommand(path)}
            printf '%s\0' "${'$'}overwrote"
        """.trimIndent(),
        stdin = text.toByteArray(Charsets.UTF_8),
    )
    return result.stdout.parseRootfsEntry()
}

private suspend fun WorkspaceRepository.runRootfsCommand(
    workspaceId: String,
    action: String,
    command: String,
    stdin: ByteArray? = null,
): WorkspaceCommandResult {
    val result = executeCommand(
        id = workspaceId,
        command = command,
        timeoutMillis = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin = stdin,
    )
    if (result.timedOut) {
        error("$action timed out")
    }
    if (result.exitCode != 0) {
        val message = result.stderr.ifBlank { result.stdout }.trim()
        error(if (message.isBlank()) "$action failed with exit code ${result.exitCode}" else message)
    }
    if (result.truncated) {
        error("$action output is too large")
    }
    return result
}

private fun statEntryCommand(path: String): String {
    val pathArg = path.shellQuote()
    return """
        if [ -d $pathArg ]; then entry_type=d; else entry_type=f; fi
        entry_size=${'$'}(stat -c '%s' -- $pathArg) || exit 1
        entry_mtime=${'$'}(stat -c '%Y' -- $pathArg) || exit 1
        printf '%s\0%s\0%s\0%s\0' "${'$'}entry_type" "${'$'}entry_size" "${'$'}entry_mtime" $pathArg
    """.trimIndent()
}

private fun String.parseRootfsEntry(): WorkspaceFileEntry {
    val fields = split('\u0000').dropLastWhile { it.isEmpty() }
    // 4 字段 = stat 输出; 5 字段 = stat + overwrote 标记（writeTextInRootfs 附加）
    require(fields.size == 4 || fields.size == 5) { "Invalid file metadata output" }
    val chunk = fields.take(4)
    val type = chunk[0]
    val size = chunk[1].toLongOrNull() ?: error("Invalid file size: ${chunk[1]}")
    val updatedAt = (chunk[2].toLongOrNull() ?: error("Invalid file mtime: ${chunk[2]}")) * 1_000L
    val path = chunk[3]
    val overwrote = fields.getOrNull(4) == "1"
    return WorkspaceFileEntry(
        path = path,
        name = path.rootfsName(),
        isDirectory = type == "d",
        sizeBytes = size,
        updatedAt = updatedAt,
        overwrote = overwrote,
    )
}

private fun String.parseRootfsEntries(): List<WorkspaceFileEntry> {
    val fields = split('\u0000').dropLastWhile { it.isEmpty() }
    require(fields.size % 4 == 0) { "Invalid file metadata output" }
    return fields.chunked(4).map { chunk ->
        val type = chunk[0]
        val size = chunk[1].toLongOrNull() ?: error("Invalid file size: ${chunk[1]}")
        val updatedAt = (chunk[2].toLongOrNull() ?: error("Invalid file mtime: ${chunk[2]}")) * 1_000L
        val path = chunk[3]
        WorkspaceFileEntry(
            path = path,
            name = path.rootfsName(),
            isDirectory = type == "d",
            sizeBytes = size,
            updatedAt = updatedAt,
        )
    }
}

internal suspend fun WorkspaceRepository.readTextPayload(
    workspaceId: String,
    path: String,
    offset: Int,
    limit: Int,
    cache: WorkspaceReadCache,
    recordCache: Boolean = true,
): JsonObject {
    val snapshot = readTextSnapshot(workspaceId, path)
    val total = snapshot.lines.size
    val startIndex = (offset - 1).coerceIn(0, total)
    val requestedEnd = (startIndex + limit).coerceAtMost(total)
    val textBuilder = StringBuilder()
    var renderedEnd = startIndex
    while (renderedEnd < requestedEnd) {
        val line = "${renderedEnd + 1}|${snapshot.lines[renderedEnd]}"
        if (textBuilder.isNotEmpty() &&
            textBuilder.length + 1 + line.length > WORKSPACE_READ_CACHE_MAX_CHARS
        ) {
            break
        }
        if (textBuilder.isNotEmpty()) textBuilder.append('\n')
        textBuilder.append(line)
        renderedEnd++
    }
    val numbered = textBuilder.toString()
    val truncatedByBudget = renderedEnd < requestedEnd
    val key = readCacheKey(path, offset, limit, snapshot.revision)
    val unchanged = cache.isUnchanged(key)
    if (recordCache && !unchanged && numbered.length <= WORKSPACE_READ_CACHE_MAX_CHARS) {
        cache.record(key)
    }
    return buildJsonObject {
        put("path", path)
        put("startLine", if (startIndex >= total) total else startIndex + 1)
        put("endLine", renderedEnd)
        put("totalLines", total)
        put("sizeBytes", snapshot.sizeBytes)
        put("revision", snapshot.revision)
        put("format", "numbered-lines")
        put("complete", renderedEnd >= total)
        put("pageComplete", !truncatedByBudget)
        put("scanComplete", true)
        put("truncated", truncatedByBudget)
        if (renderedEnd < total) put("nextOffset", renderedEnd + 1)
        if (unchanged) {
            put("unchanged", true)
            put("note", "Content for this exact path/range/revision was already returned in this generation; no body repeated.")
        } else {
            put("text", numbered)
        }
    }
}

internal fun kotlinx.serialization.json.JsonObject.absolutePath(name: String): String {
    val path = string(name)?.replace('\\', '/')?.trim() ?: error("$name is required")
    require(path.isNotBlank()) { "$name is required" }
    require(path.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
    require(!path.contains('\u0000')) { "$name contains invalid character" }
    return path
}

// 免强制审批的可写安全区: 工作区文件目录、临时目录和技能目录
private val WRITABLE_ROOT_PREFIXES = listOf("/workspace", "/tmp", "/skills")

// 平台只读区（PRoot 的 -b 无只读能力，靠工具层拒绝写入；见 WorkspaceBindMount.readOnly 注释）
private val READ_ONLY_ROOT_PREFIXES = listOf("/upload")

internal fun String.isInReadOnlyRoot(readOnlyRoots: List<String> = READ_ONLY_ROOT_PREFIXES): Boolean {
    val normalized = trimEnd('/').ifBlank { "/" }
    return readOnlyRoots.any { prefix ->
        normalized == prefix || normalized.startsWith("$prefix/")
    }
}

internal fun rejectReadOnlyPath(path: String, readOnlyRoots: List<String> = READ_ONLY_ROOT_PREFIXES) {
    if (path.isInReadOnlyRoot(readOnlyRoots)) {
        error(
            "READ_ONLY: $path is under a read-only mount. " +
                "Copy it into /workspace first, then modify the copy."
        )
    }
}

private fun kotlinx.serialization.json.JsonElement.pathOutsideWritableRoots(
    name: String,
    writableRoots: List<String> = WRITABLE_ROOT_PREFIXES,
): Boolean =
    runCatching {
        jsonObject.absolutePath(name).isOutsideWritableRoots(writableRoots)
    }.getOrDefault(true)

private fun kotlinx.serialization.json.JsonElement.pathInReadOnlyRoots(
    name: String,
    readOnlyRoots: List<String>,
): Boolean =
    runCatching {
        jsonObject.absolutePath(name).isInReadOnlyRoot(readOnlyRoots)
    }.getOrDefault(false)

internal fun String.isOutsideWritableRoots(
    writableRoots: List<String> = WRITABLE_ROOT_PREFIXES,
): Boolean {
    val normalized = trimEnd('/').ifBlank { "/" }
    return writableRoots.none { prefix ->
        normalized == prefix || normalized.startsWith("$prefix/")
    }
}

private fun String.rootfsName(): String =
    trimEnd('/').substringAfterLast('/').ifBlank { "/" }

private fun String.shellQuote(): String =
    "'" + replace("'", "'\"'\"'") + "'"

private fun Boolean.shellFlag(): Int = if (this) 1 else 0

internal fun JsonObjectBuilder.putPathProperty(required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Absolute path inside Rootfs. Use /workspace for the workspace files area."
            } else {
                "Optional absolute path inside Rootfs. Use /workspace for the workspace files area."
            }
        )
    })
}

private fun WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
    // P1-3: 可读时间与覆盖标记（毫秒时间戳 updatedAt 保留，向后兼容）。
    // 时区与 get_time_info 对齐：本地时区带偏移（如 2026-10-03T11:48:16.805+08:00），
    // 此前输出 UTC 的 `...Z`，与 get_time_info 的 +08:00 两套表示容易误读。
    put(
        "updatedAtIso",
        java.time.Instant.ofEpochMilli(updatedAt)
            .atZone(java.time.ZoneId.systemDefault())
            .toString(),
    )
    put("overwrote", overwrote)
}

/**
 * /workspace/<rest> → 相对 filesDir 的路径; 非 /workspace 路径返回 null。
 * filesDir 与 Rootfs 内 /workspace 是同一目录 (bind mount), 可直连文件 IO。
 */
private fun workspaceRelativePathInFilesArea(path: String): String? {
    val normalized = path.replace('\\', '/').trim().trimEnd('/')
    if (normalized == WorkspaceManager.ROOTFS_WORKSPACE_DIR) return ""
    val prefix = WorkspaceManager.ROOTFS_WORKSPACE_DIR + "/"
    return normalized.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
}

/** filesDir 条目的 path 是相对 filesDir 的, 还原成 Rootfs 内绝对路径, 与 shell 返回格式一致 */
private fun WorkspaceFileEntry.toRootfsEntry() = copy(
    path = if (path.isBlank()) WorkspaceManager.ROOTFS_WORKSPACE_DIR
    else WorkspaceManager.ROOTFS_WORKSPACE_DIR + "/" + path.trimStart('/'),
)
