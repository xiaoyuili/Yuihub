package me.yui.yuihub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// 索引：记忆查询一直按 assistant_id 过滤
@Entity(indices = [Index("assistant_id")])
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
    // 旧版 0..1 重要度：仅保留用于 40→41 迁移换算，业务不再读写
    @ColumnInfo("importance", defaultValue = "0.5")
    val importance: Float = 0.5f,
    @ColumnInfo("category", defaultValue = "other")
    val category: String = "other",
    @ColumnInfo("title", defaultValue = "")
    val title: String = "",
    @ColumnInfo("kind", defaultValue = "fact")
    val kind: String = "fact",
    @ColumnInfo("section", defaultValue = "")
    val section: String = "",
    @ColumnInfo("keywords", defaultValue = "[]")
    val keywords: String = "[]",
    @ColumnInfo("weight", defaultValue = "2")
    val weight: Int = 2,
    @ColumnInfo("status", defaultValue = "active")
    val status: String = "active",
    @ColumnInfo("scope", defaultValue = "assistant")
    val scope: String = "assistant",
    @ColumnInfo("workspace_id")
    val workspaceId: String? = null,
    @ColumnInfo("source_conversation_id")
    val sourceConversationId: String? = null,
    @ColumnInfo("hit_count", defaultValue = "0")
    val hitCount: Int = 0,
    @ColumnInfo("last_injected_at")
    val lastInjectedAt: Long? = null,
    @ColumnInfo("archived_at")
    val archivedAt: Long? = null,
)
