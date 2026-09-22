package me.yui.yuihub.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.yui.yuihub.data.datastore.SettingsStore
import me.yui.yuihub.data.db.dao.WorkspaceDAO
import me.yui.yuihub.data.db.entity.WorkspaceEntity
import me.yui.yuihub.utils.JsonInstant
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.PackageMirrorConfigurator
import me.rerere.workspace.PackageMirrorOutcome
import me.rerere.workspace.PackageMirrorSetup
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.yui.yuihub.AppScope
import me.rerere.workspace.WorkspaceMountDir
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.uuid.Uuid

class WorkspaceRepository(
    private val dao: WorkspaceDAO,
    private val manager: WorkspaceManager,
    private val rootfsInstaller: RootfsInstaller,
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
) {
    private val mirrorConfigurator = PackageMirrorConfigurator()

    fun listFlow(): Flow<List<WorkspaceEntity>> = dao.listFlow()

    suspend fun checkIntegrity() = withContext(Dispatchers.IO) {
        val workspaces = dao.getAll()
        for (workspace in workspaces) {
            val dir = manager.workspaceDir(workspace.root)
            if (!dir.exists()) {
                // 目录缺失时不删除记录(例如恢复备份后工作区文件未随数据库一起恢复),
                // 仅标记为 BROKEN 以保留记录与助手绑定, 避免误删用户工作区
                Log.w(TAG, "Workspace directory missing, marking as broken: id=${workspace.id}, root=${workspace.root}")
                if (workspace.shellStatus != WorkspaceShellStatus.BROKEN.name) {
                    updateShellState(workspace.id, WorkspaceShellStatus.BROKEN.name)
                }
                continue
            }
            val statusName = workspace.shellStatus
            if ((statusName == WorkspaceShellStatus.READY.name || statusName == WorkspaceShellStatus.INSTALLING.name)
                && !manager.hasRootfs(workspace.root)
            ) {
                Log.w(TAG, "Rootfs missing, resetting shell status: id=${workspace.id}")
                updateShellState(workspace.id, WorkspaceShellStatus.DISABLED.name)
            }
        }
    }

    suspend fun getById(id: String): WorkspaceEntity? = dao.getById(id)

    suspend fun getByRoot(root: String): WorkspaceEntity? = dao.getByRoot(root)

    suspend fun create(name: String): WorkspaceEntity {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val finalName = name.trim().ifBlank { "Workspace" }
        require(!isNameTaken(finalName, excludeId = null)) {
            "Workspace name already exists: $finalName"
        }
        val workspace = WorkspaceEntity(
            id = id,
            name = finalName,
            root = id,
            createdAt = now,
            updatedAt = now,
            lastAccessAt = null,
        )
        manager.ensureWorkspace(workspace.root)
        dao.upsert(workspace)
        return workspace
    }

    suspend fun rename(id: String, name: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        val finalName = name.trim().ifBlank { workspace.name }
        require(!isNameTaken(finalName, excludeId = id)) {
            "Workspace name already exists: $finalName"
        }
        dao.upsert(
            workspace.copy(
                name = finalName,
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    /** 名字是否已被其他 workspace 占用（trim 后精确匹配，排除 [excludeId] 自身） */
    suspend fun isNameTaken(name: String, excludeId: String?): Boolean {
        val target = name.trim()
        return dao.getAll().any { it.id != excludeId && it.name.trim() == target }
    }

    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean {
        val workspace = dao.getById(id) ?: return false
        val overrides = workspace.toolApprovalOverrides() + (toolName to needsApproval)
        dao.upsert(
            workspace.copy(
                toolApprovals = JsonInstant.encodeToString(overrides),
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    /** 挂载目录的校验规则（由 [WorkspaceManager] 统一持有），返回错误码或 null */
    suspend fun validateMountDir(id: String, mountDir: WorkspaceMountDir): String? {
        val workspace = dao.getById(id) ?: return "workspace_missing"
        return manager.validateMountDir(workspace.root, mountDir, workspace.mountDirList())
    }

    suspend fun addMountDir(id: String, mountDir: WorkspaceMountDir): Boolean {
        val workspace = dao.getById(id) ?: return false
        val normalized = mountDir.copy(
            sourcePath = mountDir.sourcePath.trim(),
            target = mountDir.target.trim().trimEnd('/'),
        )
        val error = manager.validateMountDir(workspace.root, normalized, workspace.mountDirList())
        require(error == null) { "Invalid mount: $error" }
        val mounts = workspace.mountDirList() + normalized
        dao.upsert(
            workspace.copy(
                mountDirs = JsonInstant.encodeToString(mounts),
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    suspend fun removeMountDir(id: String, target: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        val mounts = workspace.mountDirList().filterNot { it.target.trimEnd('/') == target.trimEnd('/') }
        dao.upsert(
            workspace.copy(
                mountDirs = JsonInstant.encodeToString(mounts),
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    suspend fun setMountDirReadOnly(id: String, target: String, readOnly: Boolean): Boolean {
        val workspace = dao.getById(id) ?: return false
        val mounts = workspace.mountDirList().map {
            if (it.target.trimEnd('/') == target.trimEnd('/')) it.copy(readOnly = readOnly) else it
        }
        dao.upsert(
            workspace.copy(
                mountDirs = JsonInstant.encodeToString(mounts),
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    /** 该工作区当前生效的自定义挂载（已转成宿主路径的 bind 描述） */
    suspend fun bindMountsFor(id: String): List<WorkspaceBindMount> {
        val workspace = dao.getById(id) ?: return emptyList()
        return workspace.mountDirList().map { manager.bindMountFor(it) }
    }

    suspend fun installRootfs(
        id: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean {
        val workspace = dao.getById(id) ?: return false
        updateShellState(workspace, WorkspaceShellStatus.INSTALLING.name)
        try {
            // runInterruptible 让协程取消转成线程中断, 打断 install 内阻塞的下载/解压循环
            runInterruptible(Dispatchers.IO) {
                rootfsInstaller.install(workspace.root, url, onProgress)
            }
            // 旧 rootfs 已被替换, 引用它的常驻 shell 会话必须丢弃, 下条命令重建
            manager.invalidateShellSession(workspace.root)
            updateShellState(workspace, WorkspaceShellStatus.READY.name)
            // 重新安装会换掉整个 rootfs，之前写进去的镜像配置随之消失，
            // 摘要不清空会让系统提示词继续告诉 AI 「源已经配好」。
            clearPackageMirrors(workspace.id)
            installCommonNetworkToolsAsync(workspace.id)
            return true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw e
        } catch (e: InterruptedException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw CancellationException("Rootfs install cancelled").also { it.initCause(e) }
        } catch (e: Throwable) {
            Log.e(TAG, "installRootfs failed: workspace=${workspace.id}, root=${workspace.root}, url=$url", e)
            updateShellState(workspace, WorkspaceShellStatus.BROKEN.name)
            throw e
        }
    }

    suspend fun listFiles(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        manager.ensureWorkspace(workspace.root)
        manager.listFiles(workspace.root, path, area)
    }

    suspend fun readText(
        id: String,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.readText(workspace.root, path)
    }

    suspend fun writeText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.writeText(workspace.root, path, text, overwrite)
    }

    /**
     * 读取文本用于应用内预览/编辑, 支持两个存储区.
     * FILES 区走 [WorkspaceManager.readText] (自带大小保护); LINUX 区通过 exportFile 读入内存,
     * 因此这里对 LINUX 区显式做大小限制, 避免大文件撑爆内存.
     */
    suspend fun resolveFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.resolveFile(workspace.root, path, area)
    }

    suspend fun readTextForPreview(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        when (area) {
            WorkspaceStorageArea.FILES -> manager.readText(workspace.root, path)
            WorkspaceStorageArea.LINUX -> {
                val size = manager.fileSize(workspace.root, path, area)
                require(size <= MAX_PREVIEW_BYTES) {
                    "文件过大, 无法预览 (${size} bytes)"
                }
                ByteArrayOutputStream().use { out ->
                    manager.exportFile(workspace.root, path, area, out)
                    out.toString(Charsets.UTF_8.name())
                }
            }
        }
    }

    suspend fun importFile(
        id: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.importFile(workspace.root, destinationPath, area, fileName, inputStream)
    }

    suspend fun fileSize(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.fileSize(workspace.root, path, area)
    }

    suspend fun exportFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        outputStream: OutputStream,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.exportFile(workspace.root, path, area, outputStream)
    }

    /** 按 Rootfs 内绝对路径读取文件大小, 支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun rootfsFileSize(
        id: String,
        path: String,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.rootfsFileSize(workspace.root, path, workspace.bindMounts())
    }

    /** 按 Rootfs 内绝对路径导出文件内容, 支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun exportRootfsFile(
        id: String,
        path: String,
        outputStream: OutputStream,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.exportRootfsFile(workspace.root, path, outputStream, workspace.bindMounts())
    }

    suspend fun deleteFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        recursive: Boolean,
    ): Boolean {
        val deleted = withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext false
            manager.deleteFile(workspace.root, path, recursive, area)
        }
        return deleted
    }

    suspend fun moveFile(
        id: String,
        source: String,
        target: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.moveFile(workspace.root, source, target, overwrite)
    }

    /**
     * 按文件名（子串、忽略大小写）递归搜索 FILES 区文件。
     * 广度优先、限制结果数，跳过隐藏目录（.git 等）保证速度。
     */
    suspend fun searchFilesByName(
        id: String,
        query: String,
        limit: Int = 80,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return@withContext emptyList()
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        manager.ensureWorkspace(workspace.root)
        val results = mutableListOf<WorkspaceFileEntry>()
        val pending = ArrayDeque<String>()
        pending.add("")
        while (pending.isNotEmpty() && results.size < limit) {
            val dir = pending.removeFirst()
            val entries = runCatching {
                manager.listFiles(workspace.root, dir, WorkspaceStorageArea.FILES)
            }.getOrDefault(emptyList())
            for (entry in entries) {
                if (entry.isDirectory) {
                    if (!entry.name.startsWith(".")) pending.add(entry.path)
                } else if (entry.name.lowercase().contains(needle)) {
                    results.add(entry)
                    if (results.size >= limit) break
                }
            }
        }
        results
    }

    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        val mounts = workspace.bindMounts()
        // runInterruptible 让协程取消转化为线程中断，从而打断阻塞的 Process.waitFor 并杀掉进程
        return runInterruptible(Dispatchers.IO) {
            manager.ensureWorkspace(workspace.root)
            manager.executeCommand(
                root = workspace.root,
                command = command,
                cwd = cwd,
                timeoutMillis = timeoutMillis,
                stdin = stdin,
                extraBindMounts = mounts,
                shellCompatibilityMode = workspace.shellCompatibilityMode,
            )
        }
    }

    /**
     * 串行执行 apt 系命令（update/install）。
     *
     * rootfs 内 apt 全局只有一把文件锁（/var/lib/apt/lists、dpkg lock），并发必抦：
     * 后到者直接 rc=100（E: Unable to lock directory），而不是等待。已知的真实场景：
     * 装完 rootfs 后台自动装 curl 的 update 与用户点「一键配置镜像」的 update 相撞，
     * 报「镜像源已写入，但刷新 apt 包列表失败」，实际重进就好了——就是锁冲突。
     * 同一 workspace 的 apt 命令在应用侧排队，彻底消除这个窗口。
     */
    private val aptMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private fun aptMutex(root: String): Mutex = aptMutexes.getOrPut(root) { Mutex() }

    suspend fun executeAptCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
    ): WorkspaceCommandResult {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        return aptMutex(workspace.root).withLock {
            executeCommand(id, command, cwd, timeoutMillis)
        }
    }

    /** 暴露给交互终端: 与 AI 工具共用同一份 PRoot 参数组装 */
    fun prootArgs(root: String, cwd: String, mounts: List<WorkspaceMountDir>): List<String> =
        manager.buildProotArgs(root, cwd, mounts.map { manager.bindMountFor(it) })

    fun globalBindMounts(): List<WorkspaceBindMount> = manager.globalBindMounts

    private fun WorkspaceEntity.bindMounts(): List<WorkspaceBindMount> =
        mountDirList().map { manager.bindMountFor(it) }

    suspend fun delete(id: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        dao.deleteById(id)
        withContext(Dispatchers.IO) {
            manager.invalidateShellSession(workspace.root)
            manager.deleteWorkspace(workspace.root)
        }
        cleanupAssistantReferences(id)
        return true
    }

    private suspend fun cleanupAssistantReferences(workspaceId: String) {
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.workspaceId?.toString() == workspaceId) {
                        assistant.copy(workspaceId = null)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    private suspend fun restoreShellState(workspace: WorkspaceEntity) {
        updateShellState(workspace.id, workspace.shellStatus)
    }

    suspend fun setShellCompatibilityMode(id: String, enabled: Boolean) {
        dao.setShellCompatibilityMode(id, enabled, System.currentTimeMillis())
    }

    /**
     * 安装常用网络工具 (curl)。Ubuntu base 默认不带 curl, 而工具描述让 AI 用 curl 验证服务。
     * 用 rootfs 内 apt (已配国内镜像) 安装, 失败静默 —— 缺失时 AI 可用 node fetch 兼容。
     */
    private fun installCommonNetworkToolsAsync(id: String) {
        appScope.launch(Dispatchers.IO) {
            runCatching {
                executeAptCommand(
                    id = id,
                    command = "command -v curl >/dev/null 2>&1 || { apt-get update -qq && apt-get install -y --no-install-recommends curl; }",
                    timeoutMillis = 300_000L,
                )
            }.onFailure {
                Log.w(TAG, "install network tools failed (non-fatal)", it)
            }
        }
    }

    private suspend fun updateShellState(
        workspace: WorkspaceEntity,
        shellStatus: String,
    ) = updateShellState(workspace.id, shellStatus)

    private suspend fun updateShellState(
        workspaceId: String,
        shellStatus: String,
    ) {
        dao.updateShellStatus(
            id = workspaceId,
            shellStatus = shellStatus,
            updatedAt = System.currentTimeMillis(),
        )
    }

    /**
     * 一键配置国内镜像源：并行测速→每个包管理器挑最快的一家→写进 rootfs。
     *
     * 写完后额外刷一次 apt 索引：新装的 rootfs 里 apt 连包列表都没有，只改源不 update，
     * AI 第一次 `apt-get install` 仍会失败。刷索引失败（比如离线）不抽销已写入的配置，
     * 因此结果里单独回传状态让 UI 区分。
     */
    suspend fun configurePackageMirrors(id: String): PackageMirrorApplyResult {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        require(manager.hasRootfs(workspace.root)) { "Rootfs is not installed" }
        val outcome = mirrorConfigurator.configure(manager.linuxDir(workspace.root))
        val (aptIndex, aptIndexDetail) = if (outcome.setup.aptRepoUrl.isNotBlank()) {
            refreshAptIndex(id)
        } else {
            AptIndexRefresh.SKIPPED to null
        }
        dao.upsert(
            workspace.copy(
                packageMirrors = JsonInstant.encodeToString(outcome.setup),
                updatedAt = System.currentTimeMillis(),
            )
        )
        return PackageMirrorApplyResult(outcome, aptIndex, aptIndexDetail)
    }

    /** 清除记录里的镜像源摘要（rootfs 里的配置文件保留，重新安装时会回到默认源）。 */
    suspend fun clearPackageMirrors(id: String) {
        val workspace = dao.getById(id) ?: return
        dao.upsert(
            workspace.copy(
                packageMirrors = JsonInstant.encodeToString(PackageMirrorSetup()),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /** @return 索引刷新状态与失败原因文本 */
    private suspend fun refreshAptIndex(id: String): Pair<AptIndexRefresh, String?> = try {
        // 走 apt 互斥通道：避免与后台 curl 安装（installCommonNetworkToolsAsync）撞锁。
        // AI 手动跑的 apt 命令不经过该通道，snippet 内再做锁冲突重试兑底
        val result = executeAptCommand(
            id = id,
            command = APT_LOCK_RETRY_SNIPPET,
            timeoutMillis = APT_INDEX_TIMEOUT_MS,
        )
        when {
            result.timedOut -> AptIndexRefresh.TIMEOUT to null
            result.exitCode != 0 -> {
                val detail = (result.stderr.ifBlank { result.stdout }).lines().lastOrNull()?.take(MAX_ERROR_DETAIL_CHARS)
                AptIndexRefresh.FAILED to (detail ?: "exit ${result.exitCode}")
            }

            else -> AptIndexRefresh.SUCCESS to null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AptIndexRefresh.FAILED to e.message
    }

    companion object {
        private const val TAG = "WorkspaceRepository"
        private const val MAX_PREVIEW_BYTES = 512L * 1024
        private const val APT_INDEX_TIMEOUT_MS = 300_000L

        /**
         * apt 锁冲突重试：锁被其它 apt 进程持有时 update 会立即 rc=100（E: Unable to lock），
         * 重试最多 6 次 x 2s。只对锁类错误重试，网络/源错误照常立即失败上报。
         * 用 apt 自身的报错做判断，不依赖 fuser/lsof 等 base 镜像没有的工具。
         */
        private const val APT_LOCK_RETRY_SNIPPET =
            "try=0; while :; do " +
                "err=\$(apt-get update -qq 2>&1); rc=\$?; " +
                "if [ \$rc -eq 0 ]; then break; fi; " +
                "try=\$((try+1)); " +
                "if [ \$try -ge 6 ] || ! printf '%s' \"\$err\" | grep -Eiq 'unable to lock|locked by another'; then " +
                "printf '%s\\n' \"\$err\" >&2; exit \$rc; fi; sleep 2; done"
        private const val MAX_ERROR_DETAIL_CHARS = 300
    }
}

/** apt 索引刷新结果，UI 据此提示「配置已写入但当前网络刷不动索引」。 */
enum class AptIndexRefresh { SUCCESS, FAILED, TIMEOUT, SKIPPED }

data class PackageMirrorApplyResult(
    val outcome: PackageMirrorOutcome,
    val aptIndex: AptIndexRefresh,
    val aptIndexDetail: String? = null,
)
