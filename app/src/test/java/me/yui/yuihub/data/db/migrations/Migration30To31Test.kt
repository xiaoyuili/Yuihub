package me.yui.yuihub.data.db.migrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration30To31Test {

    @Test
    fun `fork database without shell compatibility mode gets it added`() {
        // 本 fork 的库：mount_dirs 在 24->25 已加，这里只缺 shell_compatibility_mode
        val sql = migration30To31Sql(
            listOf("id", "name", "root", "shell_status", "created_at", "updated_at", "last_access_at", "tool_approvals", "mount_dirs")
        )
        assertEquals(1, sql.size)
        assertTrue(sql.single().contains("shell_compatibility_mode"))
    }

    @Test
    fun `upstream replaced database skips existing column and backfills mount dirs`() {
        // 上游 v25 库被文件级替换进来：已有 shell_compatibility_mode，但因为跳过了
        // 24->25 迁移而缺 mount_dirs
        val sql = migration30To31Sql(
            listOf("id", "name", "root", "shell_status", "created_at", "updated_at", "last_access_at", "tool_approvals", "shell_compatibility_mode")
        )
        assertEquals(1, sql.size)
        assertTrue(sql.single().contains("mount_dirs"))
    }

    @Test
    fun `database with both columns needs no statements`() {
        val sql = migration30To31Sql(
            listOf("id", "name", "root", "shell_status", "created_at", "updated_at", "last_access_at", "tool_approvals", "shell_compatibility_mode", "mount_dirs")
        )
        assertEquals(emptyList<String>(), sql)
    }

    @Test
    fun `empty column list adds both`() {
        val sql = migration30To31Sql(emptyList())
        assertEquals(2, sql.size)
        assertTrue(sql.any { it.contains("shell_compatibility_mode") })
        assertTrue(sql.any { it.contains("mount_dirs") })
    }
}
