package me.yui.yuihub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 每个会话的记忆注入状态：核心块指纹与压缩后的重注入待办。 */
@Entity(tableName = "MemorySessionEntity")
data class MemorySessionEntity(
    @PrimaryKey
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("last_core_hash", defaultValue = "")
    val lastCoreHash: String = "",
    @ColumnInfo("reinject_pending", defaultValue = "0")
    val reinjectPending: Boolean = false,
    @ColumnInfo("updated_at", defaultValue = "0")
    val updatedAt: Long = 0L,
)
