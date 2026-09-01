package me.yui.yuihub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.yui.yuihub.data.db.entity.TokenLedgerEntity

@Dao
interface TokenLedgerDAO {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(row: TokenLedgerEntity): Long

    @Query(
        "SELECT COALESCE(SUM(prompt_tokens), 0) AS promptTokens, " +
            "COALESCE(SUM(completion_tokens), 0) AS completionTokens, " +
            "COALESCE(SUM(cached_tokens), 0) AS cachedTokens, " +
            "COUNT(*) AS assistantMessages, " +
            "COUNT(DISTINCT conversation_id) AS conversations " +
            "FROM token_ledger"
    )
    suspend fun getLifetimeStats(): TokenLedgerStats

    @Query(
        "SELECT day, COALESCE(SUM(prompt_tokens + completion_tokens + cached_tokens), 0) AS tokens " +
            "FROM token_ledger WHERE day >= :startDate GROUP BY day"
    )
    suspend fun getTokensPerDay(startDate: String): List<MessageDayTokens>
}

data class TokenLedgerStats(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
    val assistantMessages: Int = 0,
    val conversations: Int = 0,
)
