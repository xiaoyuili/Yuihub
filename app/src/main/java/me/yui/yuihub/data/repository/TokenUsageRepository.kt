package me.yui.yuihub.data.repository

import kotlinx.coroutines.flow.Flow
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.yui.yuihub.data.db.dao.TokenUsageDaily
import me.yui.yuihub.data.db.dao.TokenUsageEventDAO
import me.yui.yuihub.data.db.dao.TokenUsageHour
import me.yui.yuihub.data.db.dao.TokenUsageModel
import me.yui.yuihub.data.db.dao.TokenUsageProvider
import me.yui.yuihub.data.db.dao.TokenUsageSourceTotals
import me.yui.yuihub.data.db.dao.TokenUsageTotals
import me.yui.yuihub.data.db.entity.TokenUsageEventEntity
import java.time.Instant
import java.time.ZoneId
import kotlin.uuid.Uuid

object TokenUsageSource {
    const val CHAT = "chat"
    const val TITLE = "title"
    const val COMPRESS = "compress"
    const val MEMORY = "memory"
    const val VISION = "vision"
}

/**
 * 每个成功的模型请求落一行。旧 token_ledger 仍参与查询，保证升级前的历史不丢。
 */
class TokenUsageRepository(
    private val dao: TokenUsageEventDAO,
) {
    suspend fun record(
        conversationId: String?,
        provider: ProviderSetting,
        model: Model,
        source: String,
        usage: TokenUsage,
        atMillis: Long = System.currentTimeMillis(),
    ) {
        if (usage.promptTokens == 0 && usage.completionTokens == 0 && usage.cachedTokens == 0) return
        val day = Instant.ofEpochMilli(atMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()
        dao.insertIgnore(
            TokenUsageEventEntity(
                eventId = Uuid.random().toString(),
                conversationId = conversationId.orEmpty(),
                providerId = provider.id.toString(),
                providerName = provider.name.ifBlank { provider.id.toString() },
                modelId = model.id.toString(),
                modelName = model.displayName.ifBlank { model.modelId },
                source = source,
                day = day,
                promptTokens = usage.promptTokens.toLong(),
                completionTokens = usage.completionTokens.toLong(),
                cachedTokens = usage.cachedTokens.toLong(),
                createdAt = atMillis,
            )
        )
    }

    fun conversationTotals(conversationId: String): Flow<TokenUsageTotals> =
        dao.getConversationTotalsFlow(conversationId)

    suspend fun totals(
        startDay: String,
        endDay: String,
        providerId: String?,
        modelId: String?,
    ): TokenUsageTotals = dao.getTotals(startDay, endDay, providerId, modelId)

    suspend fun daily(
        startDay: String,
        endDay: String,
        providerId: String?,
        modelId: String?,
    ): List<TokenUsageDaily> = dao.getDaily(startDay, endDay, providerId, modelId)

    suspend fun hourly(
        startMillis: Long,
        endMillis: Long,
        providerId: String?,
        modelId: String?,
    ): List<TokenUsageHour> = dao.getHourly(startMillis, endMillis, providerId, modelId)

    suspend fun lifetimeTotals(): TokenUsageTotals = dao.getLifetimeTotals()

    suspend fun sourceTotals(
        startDay: String,
        endDay: String,
        providerId: String?,
        modelId: String?,
    ): List<TokenUsageSourceTotals> = dao.getSourceTotals(startDay, endDay, providerId, modelId)

    suspend fun providers(): List<TokenUsageProvider> = dao.getProviders()

    suspend fun models(providerId: String?): List<TokenUsageModel> = dao.getModels(providerId)
}
