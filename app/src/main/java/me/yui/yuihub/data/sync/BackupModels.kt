package me.yui.yuihub.data.sync

import kotlinx.serialization.Serializable
import me.yui.yuihub.data.files.FileFolders
import java.io.File

/**
 * 备份分类。工作区（rootfs 与 /workspace）有意不在其中：体积大且可重建。
 * 数据库仍是原子单元：对话、助手、记忆、收藏、定时任务、用量统计一起备份/恢复。
 */
enum class BackupItem(val folder: String? = null) {
    DATABASE,
    SETTINGS,
    UPLOAD(FileFolders.UPLOAD),
    IMAGES(FileFolders.IMAGES),
    SKILLS(FileFolders.SKILLS),
    FONTS(FileFolders.FONTS),
    TOOL_OUTPUTS(FileFolders.TOOL_OUTPUTS),
    ;

    val isFile: Boolean get() = folder != null

    companion object {
        val FILE_ITEMS: List<BackupItem> = entries.filter { it.isFile }
        val ALL: List<BackupItem> = entries.toList()

        fun forFolder(folder: String): BackupItem? =
            entries.firstOrNull { it.folder == folder }
    }
}

@Serializable
data class BackupManifest(
    val formatVersion: Int = FORMAT_VERSION,
    val appVersion: String = "",
    val databaseVersion: Int = 0,
    val createdAt: Long = 0L,
    val items: List<String> = emptyList(),
    val databaseBytes: Long = 0L,
    val settingsBytes: Long = 0L,
    val fileCount: Int = 0,
    val fileBytes: Long = 0L,
    val counts: Map<String, Int> = emptyMap(),
    val databaseSha256: String = "",
    val settingsSha256: String = "",
) {
    companion object {
        const val FORMAT_VERSION = 2
        const val ENTRY_NAME = "manifest.json"
    }
}

data class BackupInspection(
    val file: File,
    val archiveBytes: Long,
    val manifest: BackupManifest?,
    val presentItems: Set<BackupItem>,
    val fileCount: Int,
    val fileBytes: Long,
    val createdAt: Long,
    val counts: Map<String, Int>,
    val itemBytes: Map<BackupItem, Long> = emptyMap(),
) {
    val isLegacy: Boolean get() = manifest == null
}

data class BackupSizeSummary(
    val databaseBytes: Long = 0L,
    val settingsBytes: Long = 0L,
    val fileBytes: Map<BackupItem, Long> = emptyMap(),
) {
    fun bytesOf(item: BackupItem): Long = when (item) {
        BackupItem.DATABASE -> databaseBytes
        BackupItem.SETTINGS -> settingsBytes
        else -> fileBytes[item] ?: 0L
    }

    fun total(items: Collection<BackupItem>): Long = items.sumOf { bytesOf(it) }
}

data class LocalBackupFile(
    val file: File,
    val kind: Kind,
    val createdAt: Long,
) {
    enum class Kind { AUTO, SAFETY }
}
