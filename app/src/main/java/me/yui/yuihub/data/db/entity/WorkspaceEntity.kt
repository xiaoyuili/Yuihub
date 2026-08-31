package me.yui.yuihub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.yui.yuihub.utils.JsonInstant
import me.rerere.workspace.Workspace
import me.rerere.workspace.WorkspaceMountDir
import me.rerere.workspace.WorkspaceShellStatus

@Entity(
    tableName = "workspaces",
    indices = [
        Index(value = ["root"], unique = true),
        Index(value = ["updated_at"]),
    ],
)
data class WorkspaceEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("name")
    val name: String,
    @ColumnInfo("root")
    val root: String,
    @ColumnInfo("shell_status")
    val shellStatus: String = WorkspaceShellStatus.DISABLED.name,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("last_access_at")
    val lastAccessAt: Long? = null,
    // 工具审批的用户覆盖项 (toolName -> needsApproval)，未覆盖的工具沿用默认值
    @ColumnInfo("tool_approvals", defaultValue = "{}")
    val toolApprovals: String = "{}",
    // 用户自定义的宿主机目录挂载 (JSON 数组)，挂进 Rootfs 供 shell 与文件工具访问
    @ColumnInfo("mount_dirs", defaultValue = "[]")
    val mountDirs: String = "[]",
) {
    fun toolApprovalOverrides(): Map<String, Boolean> = runCatching {
        JsonInstant.decodeFromString<Map<String, Boolean>>(toolApprovals)
    }.getOrDefault(emptyMap())

    /** 解析挂载配置，并丢弃字段不全/路径非法的条目，避免手改数据库导致整个工作区不可用 */
    fun mountDirList(): List<WorkspaceMountDir> = runCatching {
        JsonInstant.decodeFromString<List<WorkspaceMountDir>>(mountDirs)
    }.getOrDefault(emptyList()).filter {
        it.sourcePath.startsWith("/") && it.target.startsWith("/") && it.target.trimEnd('/') != it.sourcePath.trimEnd('/')
    }

    fun toWorkspace(): Workspace = Workspace(
        id = id,
        name = name,
        root = root,
        shellStatus = runCatching { WorkspaceShellStatus.valueOf(shellStatus) }
            .getOrDefault(WorkspaceShellStatus.DISABLED),
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastAccessAt = lastAccessAt,
        mountDirs = mountDirList(),
    )
}
