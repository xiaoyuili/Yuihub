package me.yui.yuihub.data.db.migrations

import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration_28_29 : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        // 从现有消息节点回填账本：删除对话后这些行仍会保留
        db.execSQL(
            """
            INSERT OR IGNORE INTO token_ledger (
                message_id, conversation_id, day, prompt_tokens, completion_tokens, cached_tokens, created_at
            )
            SELECT
                json_extract(j.value, '$.id') AS message_id,
                mn.conversation_id,
                substr(json_extract(j.value, '$.createdAt'), 1, 10) AS day,
                COALESCE(CAST(json_extract(j.value, '$.usage.promptTokens') AS INTEGER), 0),
                COALESCE(CAST(json_extract(j.value, '$.usage.completionTokens') AS INTEGER), 0),
                COALESCE(CAST(json_extract(j.value, '$.usage.cachedTokens') AS INTEGER), 0),
                CAST(strftime('%s','now') AS INTEGER) * 1000
            FROM message_node mn, json_each(mn.messages) j
            WHERE json_extract(j.value, '$.usage') IS NOT NULL
              AND json_extract(j.value, '$.id') IS NOT NULL
            """.trimIndent()
        )
    }
}
