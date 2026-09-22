package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

data class WorkspaceBindMount(
    val source: File,
    val target: String,
    val readOnly: Boolean = false,
) {
    init {
        require(target.startsWith("/")) { "Bind mount target must be absolute: $target" }
    }

    /** PRoot `-b` 参数形式：`<宿主路径>:<Rootfs 路径>[:ro]` */
    fun prootBindSpec(): String = buildString {
        append(source.absolutePath)
        append(':')
        append(target.trimEnd('/'))
        if (readOnly) append(":ro")
    }
}

class ProotShellRunner(
    private val nativeLibraryDir: File,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner {

    // 每个 workspace root 一个常驻会话; rootfs 挂载表是启动参数, 不能跨 root 复用
    private val sessions = ConcurrentHashMap<String, PersistentProotSession>()

    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        if (!context.linuxDir.hasUsableRootfs()) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "Rootfs is not installed",
            )
        }

        context.tempDir.mkdirs()
        // /etc/hosts、/etc/group 等补丁是读-改-写, 并发命令交错执行会写坏文件, 串行化。
        // patch 内部有 marker 幂等短路, 已打过补丁时只是几次文件 stat, 开销可忽略。
        synchronized(patcher) {
            patcher.patch(context.linuxDir)
        }

        val proot = File(nativeLibraryDir, PROOT_EXEC)
        val loader = File(nativeLibraryDir, PROOT_LOADER)
        if (!proot.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot executable not found: ${proot.absolutePath}",
            )
        }
        if (!loader.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot loader not found: ${loader.absolutePath}",
            )
        }

        if (context.shellCompatibilityMode) {
            // 兼容模式要求 PROOT_NO_SECCOMP 环境变量, 与常驻会话的启动环境互斥,
            // 该模式退回一次性执行
            return executeOneShot(context, proot, loader)
        }

        val maxAge = SESSION_IDLE_TIMEOUT_MS
        while (true) {
            val reused = sessions[context.root]
            if (reused != null && !reused.isAlive()) {
                sessions.remove(context.root, reused)
                reused.destroy()
                continue
            }
            if (reused != null && System.currentTimeMillis() - reused.lastUsedAtMs.get() > maxAge) {
                sessions.remove(context.root, reused)
                reused.destroy()
                continue
            }
            val session = reused ?: run {
                val created = launchSession(context, proot, loader)
                val existing = sessions.putIfAbsent(context.root, created)
                if (existing != null) {
                    // 并发下另一个线程先建了会话, 用它的, 销毁自己的多余实例
                    created.destroy()
                    existing
                } else {
                    created
                }
            }
            return try {
                session.execute(context)
            } catch (e: IOException) {
                // 会话死亡(进程被系统杀/流断裂): 重建一次再试; 再失败则透出错误
                if (sessions.remove(context.root, session)) {
                    session.destroy()
                }
                val fresh = launchSession(context, proot, loader)
                val raced = sessions.putIfAbsent(context.root, fresh)
                if (raced != null) {
                    fresh.destroy()
                    raced.execute(context)
                } else {
                    fresh.execute(context)
                }
            }
        }
    }

    private fun launchSession(
        context: WorkspaceShellContext,
        proot: File,
        loader: File,
    ): PersistentProotSession {
        val process = ProcessBuilder(buildCommand(context, proot, PERSISTENT_MAIN_COMMAND))
            .directory(context.filesDir)
            .redirectErrorStream(false)
            .apply {
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = context.tempDir.absolutePath
                environment()["TMPDIR"] = context.tempDir.absolutePath
            }
            .start()
        return try {
            PersistentProotSession.launch(process, initTimeoutMs = SESSION_INIT_TIMEOUT_MS)
        } catch (e: Exception) {
            process.destroyForcibly()
            throw e
        }
    }

    /** 一次性执行, 语义与历史行为一致: 每条命令独立 proot 进程, 跑完即退 */
    private fun executeOneShot(context: WorkspaceShellContext, proot: File, loader: File): WorkspaceCommandResult {
        val process = ProcessBuilder(buildCommand(context, proot, context.command))
            .directory(context.filesDir)
            .redirectErrorStream(false)
            .apply {
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = context.tempDir.absolutePath
                environment()["TMPDIR"] = context.tempDir.absolutePath
                // 兼容模式的用户显式选择了非 seccomp 路径
                environment()["PROOT_NO_SECCOMP"] = "1"
            }
            .start()
        return process.readResult(context.timeoutMillis, context.stdin)
    }

    private fun buildCommand(
        context: WorkspaceShellContext,
        proot: File,
        mainCommand: String,
    ): List<String> {
        val command = mutableListOf(
            proot.absolutePath,
            "--root-id",
            "--link2symlink",
            // 不加 --kill-on-exit: 它在进程退出时杀死整个进程树,
            // nohup/setsid 起的后台服务也会被一并杀掉, 常驻服务无法跨 tool call 存活。
            "-r",
            context.linuxDir.absolutePath,
            "-w",
            context.prootCwd(),
            "-b",
            "${context.filesDir.absolutePath}:$WORKSPACE_DIR",
        )

        context.bindMounts.forEach { mount ->
            if (mount.source.exists()) {
                command += "-b"
                command += mount.prootBindSpec()
            }
        }

        WorkspaceManager.KERNEL_FS_MOUNTS.forEach { path ->
            if (File(path).exists()) {
                command += "-b"
                command += path
            }
        }

        command += listOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            // 非交互执行约定, 抑制各类 CLI 的交互行为 (确认提示/分页器/颜色转义)
            "CI=true",
            "NO_COLOR=1",
            "PAGER=cat",
            "/bin/bash",
            "-l",
            "-c",
            // 命令通过位置参数传入, 避免任何转义; eval "$2" 对命令文本只求值一次, 等价于 bash -c "$cmd"
            "cd -- \"\$1\" && eval \"\$2\"",
            "yuihub",
            context.prootCwd(),
            mainCommand,
        )
        return command
    }

    private fun WorkspaceShellContext.prootCwd(): String {
        val normalized = cwd.trim().trim('/')
        return if (normalized.isBlank()) {
            WORKSPACE_DIR
        } else {
            "$WORKSPACE_DIR/$normalized"
        }
    }

    private fun File.hasUsableRootfs(): Boolean =
        isDirectory && File(this, "bin/sh").isFile

    fun destroySession(root: String) {
        sessions.remove(root)?.destroy()
    }

    fun destroyAllSessions() {
        sessions.values.forEach { it.destroy() }
        sessions.clear()
    }

    companion object {
        private const val PROOT_EXEC = "libproot_exec.so"
        private const val PROOT_LOADER = "libproot_loader.so"
        private val WORKSPACE_DIR = WorkspaceManager.ROOTFS_WORKSPACE_DIR

        /** 会话空闲多久后驱逐: 设备内存紧张时 proot 常驻进程是主要占用方之一 */
        private const val SESSION_IDLE_TIMEOUT_MS = 30 * 60 * 1000L
        private const val SESSION_INIT_TIMEOUT_MS = 20_000L

        /** 常驻主壳的读-求值循环: 每行完整命令经 stdin 投递进来, 登录 profile 只跑一次。
         *  循环负责把每行的退出码存进 __YUIHUB_RC —— 下一行的 $? 已被 read 覆盖, 不能用 */
        private const val PERSISTENT_MAIN_COMMAND =
            "while IFS= read -r __YUIHUB_LINE; do eval \"\$__YUIHUB_LINE\"; __YUIHUB_RC=\$?; done"
    }
}
