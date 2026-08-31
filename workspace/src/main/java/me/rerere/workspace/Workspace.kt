package me.rerere.workspace

import kotlinx.serialization.Serializable

data class Workspace(
    val id: String,
    val name: String,
    val root: String,
    val shellStatus: WorkspaceShellStatus = WorkspaceShellStatus.DISABLED,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessAt: Long? = null,
    /** 用户自定义的宿主机目录挂载（已校验过的才会出现在这里） */
    val mountDirs: List<WorkspaceMountDir> = emptyList(),
)

/**
 * 用户把宿主机（Android 侧）的一个真实目录挂进 Rootfs 的配置。
 *
 * [sourcePath] 必须是绝对路径：PRoot 的 `-b` 只认普通文件路径，SAF 的 content:// URI
 * 无法直接挂载。[target] 是 Rootfs 内的挂载点。
 */
@Serializable
data class WorkspaceMountDir(
    val sourcePath: String,
    val target: String,
    val readOnly: Boolean = false,
)

enum class WorkspaceShellStatus {
    DISABLED,
    INSTALLING,
    READY,
    BROKEN,
}

enum class WorkspaceStorageArea {
    FILES,
    LINUX,
}

enum class RootfsInstallStage {
    DOWNLOADING,
    EXTRACTING,
    INSTALLED,
}

data class RootfsInstallProgress(
    val stage: RootfsInstallStage,
    val bytesRead: Long = 0,
    val totalBytes: Long? = null,
    val entriesExtracted: Int = 0,
    val currentEntry: String? = null,
)

data class WorkspaceConfig(
    val maxReadBytes: Long = 512 * 1024,
    val maxWriteBytes: Long = 2 * 1024 * 1024,
    val maxListEntries: Int = 500,
    val maxSearchResults: Int = 100,
)

data class WorkspaceFileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val updatedAt: Long,
)

data class WorkspaceSearchMatch(
    val path: String,
    val line: Int,
    val text: String,
)

data class WorkspaceCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
)
