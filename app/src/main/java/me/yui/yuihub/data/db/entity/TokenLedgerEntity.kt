package me.yui.yuihub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "token_ledger",
    indices = [Index(value = ["message_id"], unique = true), Index("day")],
)
data class TokenLedgerEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo("message_id")
    val messageId: String,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("day")
    val day: String,
    @ColumnInfo("prompt_tokens")
    val promptTokens: Long,
    @ColumnInfo("completion_tokens")
    val completionTokens: Long,
    @ColumnInfo("cached_tokens")
    val cachedTokens: Long,
    @ColumnInfo("created_at")
    val createdAt: Long,
)
