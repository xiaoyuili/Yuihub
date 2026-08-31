package me.yui.yuihub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class MemoryEntity(
    @PrimaryKey(true)
    val id: Int = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("content")
    val content: String = "",
    @ColumnInfo("created_at", defaultValue = "0")
    val createdAt: Long = 0L,
    @ColumnInfo("updated_at", defaultValue = "0")
    val updatedAt: Long = 0L,
    @ColumnInfo("last_accessed_at", defaultValue = "0")
    val lastAccessedAt: Long = 0L,
    @ColumnInfo("access_count", defaultValue = "0")
    val accessCount: Int = 0,
    @ColumnInfo("importance", defaultValue = "0.5")
    val importance: Float = 0.5f,
    @ColumnInfo("embedding")
    val embedding: String? = null,
)
