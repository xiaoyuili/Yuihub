package me.yui.yuihub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "MemoryEventEntity",
    indices = [Index("assistant_id"), Index("created_at")],
)
data class MemoryEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("type")
    val type: String,
    @ColumnInfo("summary")
    val summary: String = "",
    @ColumnInfo("details")
    val details: String = "",
    @ColumnInfo("created_at")
    val createdAt: Long = 0L,
)
