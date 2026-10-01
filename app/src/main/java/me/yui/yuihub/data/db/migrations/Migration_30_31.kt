package me.yui.yuihub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 30 -> 31：workspaces 增加 shell_compatibility_mode 列。
 *
 * 为什么不用 AutoMigration：上游 RikkaHub 的 v25 库已经含同名的 shell_compatibility_mode 列
 * （两边版本号交错，上游先加了同名列）。当上游库被文件级替换进来（旧版「导入 RikkaHub
 * 备份」的行为），迁移链从 25 起跑时会执行到本步，无条件 ADD COLUMN 会因 duplicate column
 * 抛错，整个库随之打不开——用户看到的就是「导入后工作区无法保存/创建」。
 *
 * 改为按列存在性判断，缺什么补什么，使迁移对两类库都成立：
 * - 本 fork 的库：补 shell_compatibility_mode（mount_dirs 在 24->25 已加）
 * - 上游替换库：shell_compatibility_mode 已存在则跳过；因跳过了 24->25 而缺的 mount_dirs 在此补齐
 */
val Migration_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val columns = db.query("PRAGMA table_info(`workspaces`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            if (nameIndex < 0) return
            buildList {
                while (cursor.moveToNext()) {
                    cursor.getString(nameIndex)?.let(::add)
                }
            }
        }
        migration30To31Sql(columns).forEach(db::execSQL)
    }
}

/**
 * 30→31 需要补的列（按当前列集合决定），拆出来便于单测：
 * - 本 fork 库：只缺 shell_compatibility_mode
 * - 上游替换库：shell_compatibility_mode 已有（跳过），但缺 mount_dirs
 * - 两者都在：无需执行任何语句
 */
internal fun migration30To31Sql(existingColumns: List<String>): List<String> = buildList {
    if ("shell_compatibility_mode" !in existingColumns) {
        add("ALTER TABLE `workspaces` ADD COLUMN `shell_compatibility_mode` INTEGER NOT NULL DEFAULT 0")
    }
    if ("mount_dirs" !in existingColumns) {
        add("ALTER TABLE `workspaces` ADD COLUMN `mount_dirs` TEXT NOT NULL DEFAULT '[]'")
    }
}
