package me.yui.yuihub.data.ai.memory

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.yui.yuihub.data.datastore.EmbeddingConfig

/**
 * OpenAI 兼容的 embedding 服务。
 *
 * 用户只需配置 base URL（到 /v1 即可）、API Key 与模型名；
 * 调用 POST {base}/embeddings，与 OpenAI Embeddings API 协议一致。
 */
class EmbeddingService(
    private val client: HttpClient,
    private val json: Json,
) {
    suspend fun isConfigured(config: EmbeddingConfig): Boolean =
        config.url.isNotBlank() && config.apiKey.isNotBlank() && config.model.isNotBlank()

    suspend fun embedTexts(config: EmbeddingConfig, texts: List<String>): List<FloatArray>? {
        if (!isConfigured(config) || texts.isEmpty()) return null
        return runCatching {
            val base = config.url.trimEnd('/')
            val endpoint = if (base.endsWith("/embeddings")) base else "$base/embeddings"
            val body = buildJsonObject {
                put("model", config.model)
                put("input", buildJsonArray { texts.forEach { add(it) } })
            }
            val response = client.post(endpoint) {
                timeout {
                    requestTimeoutMillis = 10_000
                    connectTimeoutMillis = 5_000
                }
                header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            if (!response.status.isSuccess()) return null
            val element = json.parseToJsonElement(response.bodyAsText())
            val data = element.jsonObject["data"]?.jsonArray ?: return null
            data.mapNotNull { item ->
                item.jsonObject["embedding"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull?.toFloatOrNull() }
                    ?.toFloatArray()
                    ?.takeIf { it.isNotEmpty() }
            }.takeIf { it.size == texts.size }
        }.getOrNull()
    }

    suspend fun embedText(config: EmbeddingConfig, text: String): FloatArray? =
        embedTexts(config, listOf(text))?.firstOrNull()

    // 测试连接：返回向量维度
    suspend fun testConnection(config: EmbeddingConfig): Result<Int> = runCatching {
        val vector = embedText(config, "test")
            ?: throw IllegalStateException("No embedding returned, check URL/key/model")
        vector.size
    }
}
