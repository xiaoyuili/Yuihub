package me.yui.yuihub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 每一次成功的模型请求记一行。
 *
 * 不能按助手消息记：一次回复可能包含多轮工具调用，每轮都会单独计费；
 * message.usage 只保存最后一次请求，按消息记会系统性少算。
 */
@Entity(
    tableName = "token_usage_event",
    indices = [
        Index(value = ["event_id"], unique = true),
        Index("day"),
        Index("conversation_id"),
        Index("provider_id"),
        Index("model_id"),
    ],
)
data class TokenUsageEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo("event_id")
    val eventId: String,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("provider_id")
    val providerId: String,
    @ColumnInfo("provider_name")
    val providerName: String,
    @ColumnInfo("model_id")
    val modelId: String,
    @ColumnInfo("model_name")
    val modelName: String,
    /** chat / title / compress / memory / vision */
    @ColumnInfo("source")
    val source: String,
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
