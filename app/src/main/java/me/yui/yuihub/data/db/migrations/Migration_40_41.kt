package me.yui.yuihub.data.db.migrations

import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 40→41：记忆分层（kind/status/scope/keywords/weight）+ 事件/引用/会话状态表。
 * 列由 AutoMigration 添加，这里只回填旧 `importance` 到 1..4 的 `weight`。
 */
class Migration_40_41 : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            UPDATE MemoryEntity
            SET weight = CASE
                WHEN importance >= 0.9 THEN 4
                WHEN importance >= 0.6 THEN 3
                WHEN importance >= 0.3 THEN 2
                ELSE 1
            END
            WHERE weight = 2
            """.trimIndent()
        )
    }
}
