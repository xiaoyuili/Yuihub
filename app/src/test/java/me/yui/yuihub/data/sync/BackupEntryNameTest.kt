package me.yui.yuihub.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份 zip 条目名匹配测试。
 *
 * 背景：上游 RikkaHub 的 DatabaseBackup.ARCHIVE_DATABASE = "rikka_hub.db"（带 .db 后缀），
 * 本 fork 备份条目名为 "rikka_hub"。此前只匹配后者，上游备份的数据库被整体跳过，
 * 表现为「导入 RikkaHub 备份 0 条记录」。
 */
class BackupEntryNameTest {

    @Test
    fun `matches fork backup entry without extension`() {
        assertTrue(RikkaHubImporter.isMainDatabaseEntry("rikka_hub"))
    }

    @Test
    fun `matches upstream backup entry with db extension`() {
        assertTrue(RikkaHubImporter.isMainDatabaseEntry("rikka_hub.db"))
    }

    @Test
    fun `matches entries inside directories`() {
        // 上游 PendingRestore 用的 payload 路径形如 database/rikka_hub.db
        assertTrue(RikkaHubImporter.isMainDatabaseEntry("database/rikka_hub.db"))
        assertTrue(RikkaHubImporter.isMainDatabaseEntry("backup/rikka_hub"))
    }

    @Test
    fun `rejects wal and shm sidecars`() {
        assertFalse(RikkaHubImporter.isMainDatabaseEntry("rikka_hub-wal"))
        assertFalse(RikkaHubImporter.isMainDatabaseEntry("rikka_hub-shm"))
        assertFalse(RikkaHubImporter.isMainDatabaseEntry("rikka_hub.db-wal"))
    }

    @Test
    fun `rejects unrelated entries`() {
        assertFalse(RikkaHubImporter.isMainDatabaseEntry("settings.json"))
        assertFalse(RikkaHubImporter.isMainDatabaseEntry("upload/avatar.png"))
        assertFalse(RikkaHubImporter.isMainDatabaseEntry("rikka_hub_data.db"))
        assertFalse(RikkaHubImporter.isMainDatabaseEntry("another_hub.db"))
        assertFalse(RikkaHubImporter.isMainDatabaseEntry("rikka_hub_backup.db"))
    }
}
