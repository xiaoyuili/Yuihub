package me.rerere.search

import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
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

/**
 * DeepSeek 官方搜索：没有独立搜索端点，通过 Anthropic 兼容的 Messages API
 * 声明 `web_search_20250305` server tool，由 DeepSeek 服务端执行搜索并返回
 * 结构化的 `web_search_tool_result` 块。一次搜索 = 一次完整模型调用的延迟与费用。
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
            if (serviceOptions.apiKey.isBlank()) {
                error("DeepSeek API key is required")
            }

            val query = params["query"]?.jsonPrimitive?.content
                ?: error("query is required")

            val body = buildJsonObject {
                put("model", JsonPrimitive(serviceOptions.model))
                put("max_tokens", JsonPrimitive(serviceOptions.maxTokens))
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive(query))
                            })
                        })
                    })
                })
                put("tools", buildJsonArray {
                    add(buildJsonObject {
                        put("type", JsonPrimitive("web_search_20250305"))
                        put("name", JsonPrimitive("web_search"))
                        put("max_uses", JsonPrimitive(serviceOptions.maxUses))
                    })
                })
            }

            Log.i(TAG, "search: $query")

            val request = Request.Builder()
                .url(serviceOptions.baseUrl.trimEnd('/') + "/messages")
                .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
                .addHeader("x-api-key", serviceOptions.apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).await()
            if (response.isSuccessful) {
                val responseBody = response.body.string().let {
                    json.decodeFromString<DeepSeekSearchResponse>(it)
                }

                // web_search_tool_result 块给结果列表; text 块的 citations[] 给摘要
                val resultBlock = responseBody.content.firstOrNull {
                    it.type == "web_search_tool_result"
                }
                val resultItems = resultBlock?.content.orEmpty()

                val snippets = HashMap<String, String>()
                responseBody.content.forEach { block ->
                    block.citations?.forEach { citation ->
                        val url = citation.url.orEmpty()
                        val text = citation.citedText.orEmpty()
                        if (url.isNotBlank() && text.isNotBlank() && url !in snippets) {
                            snippets[url] = text
                        }
                    }
                }

                val answerBlock = responseBody.content.lastOrNull {
                    it.type == "text" && !it.text.isNullOrBlank()
                }

                val items = resultItems
                    .filter { it.url.isNotBlank() }
                    .distinctBy { it.url }
                    .take(commonOptions.resultSize)
                    .map { item ->
                        SearchResultItem(
                            title = item.title ?: item.url,
                            url = item.url,
                            text = snippets[item.url].orEmpty()
                        )
                    }

                if (items.isEmpty()) {
                    error("No search results returned by DeepSeek")
                }

                return@withContext Result.success(
                    SearchResult(
                        answer = answerBlock?.text,
                        items = items
                    )
                )
            } else {
                error("response failed #${response.code}: ${response.body?.string()}")
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DeepSeekOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for DeepSeek"))
    }

    @Serializable
    private data class DeepSeekSearchResponse(
        val content: List<DeepSeekContentBlock> = emptyList()
    )

    @Serializable
    private data class DeepSeekContentBlock(
        val type: String,
        val text: String? = null,
        // web_search_tool_result 块: 命中的结果条目
        val content: List<DeepSeekSearchResultItem> = emptyList(),
        // text 块: 逐 URL 的引用摘录(snippet 来源)
        val citations: List<DeepSeekCitation> = emptyList(),
    )

    @Serializable
    private data class DeepSeekSearchResultItem(
        val url: String = "",
        val title: String? = null,
        @SerialName("page_age") val pageAge: String? = null,
    )

    @Serializable
    private data class DeepSeekCitation(
        val url: String? = null,
        @SerialName("cited_text") val citedText: String? = null,
    )}
