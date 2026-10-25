package me.rerere.search

import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "DeepSeekSearchService"
private const val DEFAULT_BASE_URL = "https://api.deepseek.com"
private const val MAX_QUERY_LENGTH = 128

/**
 * DeepSeek 官方搜索接口：POST /v1/tools/search。
 * 使用与 DeepSeek 模型相同的 Bearer API Key，返回结构化网页结果。
 */
object DeepSeekSearchService : SearchService<SearchServiceOptions.DeepSeekOptions> {
    override val name: String = "DeepSeek"

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                urlHandler.openUri("https://platform.deepseek.com/api_keys")
            }
        ) {
            Text(stringResource(R.string.click_to_get_api_key))
        }
    }

    override fun parameters(options: SearchServiceOptions.DeepSeekOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "The search query")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.DeepSeekOptions): InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DeepSeekOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = serviceOptions.apiKey.trim()
            if (apiKey.isEmpty()) {
                error("DeepSeek API key is required")
            }

            val query = params["query"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (query.isEmpty()) {
                error("query is required")
            }
            if (query.length > MAX_QUERY_LENGTH) {
                error("query must be at most $MAX_QUERY_LENGTH characters")
            }

            val count = commonOptions.resultSize.coerceIn(1, 50)
            val body = buildJsonObject {
                put("query", query)
                put("count", count)
                put("need_content", false)
            }

            val endpoint = searchEndpoint(serviceOptions.baseUrl)
            Log.i(TAG, "search: $query -> $endpoint")

            val request = Request.Builder()
                .url(endpoint)
                .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).await()
            val payload = response.body.string()
            if (!response.isSuccessful) {
                error("response failed #${response.code}: $payload")
            }

            parseResponse(payload, count)
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DeepSeekOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for DeepSeek"))
    }

    internal fun parseResponse(payload: String, limit: Int): SearchResult {
        val response = json.decodeFromString<DeepSeekSearchResponse>(payload)
        val items = response.results
            .asSequence()
            .filter { it.url.isNotBlank() }
            .distinctBy { it.url }
            .take(limit.coerceIn(1, 50))
            .map { item ->
                SearchResultItem(
                    title = item.title.ifBlank { item.url },
                    url = item.url,
                    text = item.summary.ifBlank { item.snippet }.ifBlank { item.content },
                )
            }
            .toList()
        return SearchResult(items = items)
    }

    internal fun searchEndpoint(baseUrl: String): String {
        val configured = baseUrl.trim().ifBlank { DEFAULT_BASE_URL }
        val root = configured.substringBefore("/anthropic").ifBlank { DEFAULT_BASE_URL }
            .trimEnd('/')
            .removeSuffix("/v1/tools/search")
            .removeSuffix("/tools/search")
            .removeSuffix("/v1")
        return "$root/v1/tools/search"
    }

    @Serializable
    private data class DeepSeekSearchResponse(
        val results: List<DeepSeekSearchResultItem> = emptyList(),
    )

    @Serializable
    private data class DeepSeekSearchResultItem(
        val title: String = "",
        val url: String = "",
        val snippet: String = "",
        val summary: String = "",
        val content: String = "",
    )
}
