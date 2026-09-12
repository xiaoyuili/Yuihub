package me.yui.yuihub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.yui.yuihub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_32_33"

/**
 * 32 -> 33：删除自进化表；记忆表重建——去掉 embedding 与访问统计列，
 * 新增 category 列（默认 other），并清理旧全局记忆（__global__）。
 */
val Migration_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 32 to 33 (drop evolution, rebuild memory with category)")
        DatabaseMigrationTracker.onMigrationStart(32, 33)
        db.beginTransaction()
        try {
            db.execSQL("DROP TABLE IF EXISTS `evolution_lesson`")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `MemoryEntity_new` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `assistant_id` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `created_at` INTEGER NOT NULL DEFAULT 0,
                    `updated_at` INTEGER NOT NULL DEFAULT 0,
                    `importance` REAL NOT NULL DEFAULT 0.5,
                    `category` TEXT NOT NULL DEFAULT 'other'
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO `MemoryEntity_new` (`id`, `assistant_id`, `content`, `created_at`, `updated_at`, `importance`, `category`)
                SELECT `id`, `assistant_id`, `content`, `created_at`, `updated_at`, `importance`, 'other'
                FROM `MemoryEntity`
                WHERE `assistant_id` != '__global__'
                """.trimIndent()
            )
            db.execSQL("DROP TABLE `MemoryEntity`")
            db.execSQL("ALTER TABLE `MemoryEntity_new` RENAME TO `MemoryEntity`")

            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 32 to 33 success")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
