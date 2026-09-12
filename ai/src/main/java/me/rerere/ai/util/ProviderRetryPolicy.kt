package me.rerere.ai.util

import java.io.IOException
import kotlin.random.Random

/**
 * Provider 请求重试策略（中间件）。
 *
 * 把「是否重试 / 等多久 / 为什么」的决策从生成循环中解耦出来：
 * - IOException（连接/超时/断流）→ 指数退避 + 抖动
 * - HTTP 429 → Retry-After 优先，否则指数退避 + 抖动
 * - HTTP 5xx → 指数退避 + 抖动（供应商瞬时故障常见）
 * - 其它（4xx 参数类错误、取消、流处理异常）→ 直接失败，重试无意义
 *
 * 重试幂等性由调用方保证：请求从消息快照重建，响应覆盖写入同一助手消息。
 */
class ProviderRetryPolicy(
    private val maxRetries: Int = 3,
    private val initialDelayMs: Long = 1_000L,
) {
    enum class RetryReason { NETWORK, RATE_LIMITED, SERVER_ERROR }

    sealed interface Decision {
        data class Retry(val delayMs: Long, val attempt: Int, val reason: RetryReason) : Decision
        data object Fail : Decision
    }

    fun decide(error: Throwable, retryCount: Int): Decision {
        if (retryCount >= maxRetries) return Decision.Fail
        val reason = when {
            error is IOException -> RetryReason.NETWORK
            error is HttpException && error.code == 429 -> RetryReason.RATE_LIMITED
            // 部分代理对 429 返回其它 4xx 变体（如 403 quota），结构化错误消息里常带 rate limit 字样；
            // 只有明确 5xx 或 429 才重试，避免把参数错误放大成风暴
            error is HttpException && error.code != null && error.code in 500..599 -> RetryReason.SERVER_ERROR
            else -> return Decision.Fail
        }
        val backoff = initialDelayMs shl retryCount
        // 抖动 ±30%：多客户端同时恢复时错峰，避免踩踏
        val jittered = (backoff * (0.7 + 0.6 * Random.nextDouble())).toLong()
        val rateLimitRetryAfter = (error as? HttpException)?.retryAfterMs ?: 0L
        val delay = if (reason == RetryReason.RATE_LIMITED) {
            maxOf(rateLimitRetryAfter, jittered)
        } else {
            jittered
        }
        return Decision.Retry(delayMs = delay, attempt = retryCount + 1, reason = reason)
    }
}
