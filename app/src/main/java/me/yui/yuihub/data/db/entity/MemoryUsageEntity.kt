package me.yui.yuihub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 记忆在会话中的使用痕迹：已注入 / 已检索 / 本会话写入。 */
@Entity(
    tableName = "MemoryUsageEntity",
    indices = [
        Index(value = ["conversation_id", "memory_id", "source"], unique = true),
        Index("assistant_id"),
        Index("memory_id"),
    ],
)
data class MemoryUsageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("memory_id")
    val memoryId: Int,
    @ColumnInfo("source")
    val source: String,
    @ColumnInfo("created_at")
    val createdAt: Long = 0L,
)
