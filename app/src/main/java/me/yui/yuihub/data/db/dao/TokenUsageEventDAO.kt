package me.yui.yuihub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.yui.yuihub.data.db.entity.TokenUsageEventEntity

private const val USAGE_UNION =
    "SELECT conversation_id, '' AS provider_id, '' AS provider_name, '' AS model_id, '' AS model_name, " +
        "'legacy' AS source, day, prompt_tokens, completion_tokens, cached_tokens, created_at " +
        "FROM token_ledger " +
        "UNION ALL " +
        "SELECT conversation_id, provider_id, provider_name, model_id, model_name, source, day, " +
        "prompt_tokens, completion_tokens, cached_tokens, created_at FROM token_usage_event"

@Dao
interface TokenUsageEventDAO {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(row: TokenUsageEventEntity)

    @Query(
        "SELECT COALESCE(SUM(prompt_tokens), 0) AS promptTokens, " +
            "COALESCE(SUM(completion_tokens), 0) AS completionTokens, " +
            "COALESCE(SUM(cached_tokens), 0) AS cachedTokens, " +
            "COUNT(*) AS requests, COUNT(DISTINCT NULLIF(conversation_id, '')) AS conversations " +
            "FROM ($USAGE_UNION) u WHERE u.day BETWEEN :startDay AND :endDay " +
            "AND (:providerId IS NULL OR u.provider_id = :providerId) " +
            "AND (:modelId IS NULL OR u.model_id = :modelId)"
    )
    suspend fun getTotals(
        startDay: String,
        endDay: String,
        providerId: String?,
        modelId: String?,
    ): TokenUsageTotals

    @Query(
        "SELECT source, COALESCE(SUM(prompt_tokens), 0) AS promptTokens, " +
            "COALESCE(SUM(completion_tokens), 0) AS completionTokens, " +
            "COALESCE(SUM(cached_tokens), 0) AS cachedTokens, COUNT(*) AS requests " +
            "FROM ($USAGE_UNION) u WHERE u.day BETWEEN :startDay AND :endDay " +
            "AND (:providerId IS NULL OR u.provider_id = :providerId) " +
            "AND (:modelId IS NULL OR u.model_id = :modelId) " +
            "GROUP BY source ORDER BY source ASC"
    )
    suspend fun getSourceTotals(
        startDay: String,
        endDay: String,
        providerId: String?,
        modelId: String?,
    ): List<TokenUsageSourceTotals>

    @Query(
        "SELECT u.day AS day, COALESCE(SUM(u.prompt_tokens), 0) AS promptTokens, " +
            "COALESCE(SUM(u.completion_tokens), 0) AS completionTokens, " +
            "COALESCE(SUM(u.cached_tokens), 0) AS cachedTokens " +
            "FROM ($USAGE_UNION) u WHERE u.day BETWEEN :startDay AND :endDay " +
            "AND (:providerId IS NULL OR u.provider_id = :providerId) " +
            "AND (:modelId IS NULL OR u.model_id = :modelId) " +
            "GROUP BY u.day ORDER BY u.day ASC"
    )
    suspend fun getDaily(
        startDay: String,
        endDay: String,
        providerId: String?,
        modelId: String?,
    ): List<TokenUsageDaily>

    @Query(
        "SELECT COALESCE(SUM(prompt_tokens), 0) AS promptTokens, " +
            "COALESCE(SUM(completion_tokens), 0) AS completionTokens, " +
            "COALESCE(SUM(cached_tokens), 0) AS cachedTokens, " +
            "COUNT(*) AS requests, COUNT(DISTINCT NULLIF(conversation_id, '')) AS conversations " +
            "FROM ($USAGE_UNION)"
    )
    suspend fun getLifetimeTotals(): TokenUsageTotals

    @Query(
        "SELECT CAST(strftime('%H', u.created_at / 1000, 'unixepoch', 'localtime') AS INTEGER) AS hour, " +
            "COALESCE(SUM(u.prompt_tokens), 0) AS promptTokens, " +
            "COALESCE(SUM(u.completion_tokens), 0) AS completionTokens, " +
            "COALESCE(SUM(u.cached_tokens), 0) AS cachedTokens " +
            "FROM ($USAGE_UNION) u WHERE u.created_at BETWEEN :startMillis AND :endMillis " +
            "AND (:providerId IS NULL OR u.provider_id = :providerId) " +
            "AND (:modelId IS NULL OR u.model_id = :modelId) " +
            "GROUP BY hour ORDER BY hour ASC"
    )
    suspend fun getHourly(
        startMillis: Long,
        endMillis: Long,
        providerId: String?,
        modelId: String?,
    ): List<TokenUsageHour>

    @Query(
        "SELECT COALESCE(SUM(prompt_tokens), 0) AS promptTokens, " +
            "COALESCE(SUM(completion_tokens), 0) AS completionTokens, " +
            "COALESCE(SUM(cached_tokens), 0) AS cachedTokens, " +
            "COUNT(*) AS requests, COUNT(DISTINCT NULLIF(conversation_id, '')) AS conversations " +
            "FROM ($USAGE_UNION) u WHERE u.conversation_id = :conversationId " +
            "OR u.conversation_id IN " +
            "(SELECT id FROM ConversationEntity WHERE parent_conversation_id = :conversationId)"
    )
    fun getConversationTotalsFlow(conversationId: String): Flow<TokenUsageTotals>

    @Query(
        "SELECT DISTINCT provider_id AS providerId, provider_name AS providerName " +
            "FROM ($USAGE_UNION) u WHERE u.provider_id != '' ORDER BY provider_name ASC"
    )
    suspend fun getProviders(): List<TokenUsageProvider>

    @Query(
        "SELECT DISTINCT provider_id AS providerId, provider_name AS providerName, " +
            "model_id AS modelId, model_name AS modelName FROM ($USAGE_UNION) u " +
            "WHERE u.model_id != '' AND (:providerId IS NULL OR u.provider_id = :providerId) " +
            "ORDER BY model_name ASC"
    )
    suspend fun getModels(providerId: String?): List<TokenUsageModel>
}

data class TokenUsageTotals(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
    val requests: Int = 0,
    val conversations: Int = 0,
) {
    val totalTokens: Long get() = promptTokens + completionTokens
}

data class TokenUsageDaily(
    val day: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
)

data class TokenUsageHour(
    val hour: Int,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
)

data class TokenUsageSourceTotals(
    val source: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
    val requests: Int,
) {
    val totalTokens: Long get() = promptTokens + completionTokens
}

data class TokenUsageProvider(
    val providerId: String,
    val providerName: String,
)

data class TokenUsageModel(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val modelName: String,
)
