package me.yui.yuihub.data.sync

import kotlinx.serialization.json.Json
import me.yui.yuihub.data.files.FileFolders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份完整性护栏：新增一类用户数据目录时必须同步进备份清单。
 *
 * 备份遗漏字段/目录会静默丢配置，用户很难自查；这里把「有哪些用户数据目录」
 * 与「实际被备份的目录」绑定成断言，目录一漂移测试就失败。
 */
class BackupCoverageTest {

    /** Object 声明的常量名 → 值，避免依赖反射 */
    private val declaredFolders: Map<String, String> = mapOf(
        "UPLOAD" to FileFolders.UPLOAD,
        "SKILLS" to FileFolders.SKILLS,
        "FONTS" to FileFolders.FONTS,
        "TOOL_OUTPUTS" to FileFolders.TOOL_OUTPUTS,
        "IMAGES" to FileFolders.IMAGES,
    )

    @Test
    fun `every user data folder is backed up`() {
        val backedUp = BACKED_UP_FILE_FOLDERS.toSet()
        val missing = declaredFolders.filterValues { it !in backedUp }.keys
        assertTrue(
            "FileFolders 中新增的用户数据目录未纳入备份（会静默丢数据）：$missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `no stale folder names in backup list`() {
        val declared = declaredFolders.values.toSet()
        val unknown = BACKED_UP_FILE_FOLDERS.filter { it !in declared }
        assertTrue(
            "备份清单里存在 FileFolders 中不存在的目录名（拼写错误或已重命名）：$unknown",
            unknown.isEmpty(),
        )
    }

    @Test
    fun `backup list has no duplicates`() {
        assertEquals(BACKED_UP_FILE_FOLDERS.size, BACKED_UP_FILE_FOLDERS.toSet().size)
    }

    @Test
    fun `every backed up folder maps to a selectable item`() {
        assertEquals(
            BACKED_UP_FILE_FOLDERS.toSet(),
            BackupItem.FILE_ITEMS.mapNotNull { it.folder }.toSet(),
        )
    }

    @Test
    fun `manifest round trips and carries format version`() {
        val manifest = BackupManifest(
            appVersion = "2.5.8",
            databaseVersion = 41,
            items = BackupItem.ALL.map { it.name },
            counts = mapOf("conversations" to 3),
        )
        val decoded = Json.decodeFromString<BackupManifest>(Json.encodeToString(manifest))
        assertEquals(BackupManifest.FORMAT_VERSION, decoded.formatVersion)
        assertEquals(41, decoded.databaseVersion)
        assertEquals(3, decoded.counts["conversations"])
        assertTrue(BackupItem.ALL.none { it.folder?.contains("workspace") == true })
    }
}
