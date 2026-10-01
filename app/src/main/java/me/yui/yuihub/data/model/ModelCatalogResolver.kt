package me.yui.yuihub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.abs

/**
 * 目录条目：同一模型会被多个供应商收录，因此一个 key 对应一组条目。
 * 字段名取短以减少缓存体积（约 5MB 原始数据压缩到 500KB 左右）。
 */
@Serializable
data class ModelCatalogEntry(
    /** 供应商 id，如 anthropic / openrouter */
    @SerialName("p") val provider: String,
    /** 上下文长度（token） */
    @SerialName("c") val context: Int? = null,
    /** 输入支持图像 */
    @SerialName("fi") val inputHasImage: Boolean = false,
    /** 输出支持图像 */
    @SerialName("fo") val outputHasImage: Boolean = false,
    @SerialName("t") val toolCall: Boolean = false,
    @SerialName("r") val reasoning: Boolean = false,
    /** canonical_model_id 的供应商部分（与 provider 相同则省略） */
    @SerialName("cp") val canonicalProvider: String? = null,
    /** 原始 id 中 `/` 前的第一方供应商名，如 moonshotai/kimi-k2 -> moonshotai */
    @SerialName("v") val rawVendor: String? = null,
)

/** 解析后的模型元数据 */
data class ModelInfo(
    val contextLength: Int?,
    val inputHasImage: Boolean,
    val outputHasImage: Boolean,
    val toolCall: Boolean,
    val reasoning: Boolean,
)

/**
 * models.dev 目录的解析与查询（无 Android 依赖，便于 JVM 单测）。
 */
object ModelCatalogResolver {

    /**
     * 解析 api.json：`{ providerId: { models: { rawId: {...} } } }` -> `归一化 key -> 条目组`。
     * 容错：结构不符或字段缺失的条目直接跳过。
     */
    fun parse(json: Json, body: String): Map<String, List<ModelCatalogEntry>> {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyMap()
        val result = HashMap<String, MutableList<ModelCatalogEntry>>()
        for ((providerId, providerElement) in root) {
            val provider = providerElement as? JsonObject ?: continue
            val models = provider["models"] as? JsonObject ?: continue
            for ((rawId, modelElement) in models) {
                val model = modelElement as? JsonObject ?: continue
                val key = ModelCatalogMatcher.normalize(rawId)
                if (key.isEmpty()) continue

                val limit = model["limit"] as? JsonObject
                val context = limit?.get("context")
                    ?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }
                    ?.takeIf { it in 1..MAX_CONTEXT }
                    ?.toInt()

                val modalities = model["modalities"] as? JsonObject
                val inputModalities = modalities?.get("input")
                    ?.let { (it as? JsonArray)?.mapNotNull { p -> (p as? JsonPrimitive)?.contentOrNull } }
                    .orEmpty()
                val outputModalities = modalities?.get("output")
                    ?.let { (it as? JsonArray)?.mapNotNull { p -> (p as? JsonPrimitive)?.contentOrNull } }
                    .orEmpty()

                val canonicalProvider = (model["canonical_model_id"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.substringBefore('/')
                    ?.lowercase()
                    ?.takeIf { it.isNotEmpty() && it != providerId }

                val rawVendor = rawId.substringBefore('/').lowercase()
                    .takeIf { rawId.contains('/') && it in FIRST_PARTY_PROVIDERS }

                result.getOrPut(key) { mutableListOf() } += ModelCatalogEntry(
                    provider = providerId,
                    context = context,
                    inputHasImage = inputModalities.any { it.equals("image", ignoreCase = true) },
                    outputHasImage = outputModalities.any { it.equals("image", ignoreCase = true) },
                    toolCall = (model["tool_call"] as? JsonPrimitive)?.contentOrNull == "true",
                    reasoning = (model["reasoning"] as? JsonPrimitive)?.contentOrNull == "true",
                    canonicalProvider = canonicalProvider,
                    rawVendor = rawVendor,
                )
            }
        }
        return result.mapValues { (_, list) -> list.distinct() }
    }

    /**
     * 查询模型元数据。找不到返回 null（调用方回退内置注册表）。
     * [providerName]/[baseUrl] 用于在多个供应商收录同一模型时做消歧。
     */
    fun resolve(
        catalog: Map<String, List<ModelCatalogEntry>>,
        modelId: String,
        providerName: String?,
        baseUrl: String?,
    ): ModelInfo? {
        if (catalog.isEmpty()) return null
        val queryKey = ModelCatalogMatcher.normalize(modelId)
        if (queryKey.isEmpty()) return null

        val hints = providerHints(providerName, baseUrl)

        // 快路径：绝大多数供应商返回的 id 与目录键完全一致（含列表全选场景，
        // 模型可达数百个，逐键扫描会明显变慢）
        catalog[queryKey]?.let { return arbitrate(it, hints) }

        var bestScore: Int? = null
        var bestKey: String? = null
        var bestLengthDelta = Int.MAX_VALUE
        for (candidateKey in catalog.keys) {
            val score = ModelCatalogMatcher.score(queryKey, candidateKey) ?: continue
            val lengthDelta = abs(candidateKey.length - queryKey.length)
            val currentBest = bestScore
            if (currentBest == null || score > currentBest ||
                (score == currentBest && lengthDelta < bestLengthDelta)
            ) {
                bestScore = score
                bestKey = candidateKey
                bestLengthDelta = lengthDelta
            }
        }
        val group = bestKey?.let { catalog[it] } ?: return null
        if (group.isEmpty()) return null
        return arbitrate(group, hints)
    }

    /**
     * 多供应商条目的加权投票：供应商提示命中权重最高，其次 canonical 指向、
     * 第一方供应商、原始 id 中的供应商名。上下文取权重和最大的值（并列取更大者）；
     * 能力标记合计权重过半才置位，避免少数条目的宽松标注污染结果。
     */
    fun arbitrate(group: List<ModelCatalogEntry>, hints: List<String>): ModelInfo {
        val weights = group.map { weight(it, hints) }
        val total = weights.sum()

        val contextWeights = HashMap<Int, Int>()
        group.forEachIndexed { index, entry ->
            val c = entry.context ?: return@forEachIndexed
            contextWeights[c] = contextWeights.getOrDefault(c, 0) + weights[index]
        }
        val context = contextWeights.entries
            .maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            ?.key

        fun vote(selector: (ModelCatalogEntry) -> Boolean): Boolean {
            val yes = group.indices.sumOf { if (selector(group[it])) weights[it] else 0 }
            return yes * 2 >= total
        }

        return ModelInfo(
            contextLength = context,
            inputHasImage = vote { it.inputHasImage },
            outputHasImage = vote { it.outputHasImage },
            toolCall = vote { it.toolCall },
            reasoning = vote { it.reasoning },
        )
    }

    private fun weight(entry: ModelCatalogEntry, hints: List<String>): Int {
        var w = 1
        if (hints.any { it == entry.provider }) {
            w += 6
        } else if (hints.any { hint ->
                hint.length >= 3 && (entry.provider.contains(hint) || hint.contains(entry.provider))
            }
        ) {
            w += 3
        }
        if (entry.canonicalProvider != null && hints.any { it == entry.canonicalProvider }) w += 2
        if (entry.provider in FIRST_PARTY_PROVIDERS) w += 2
        if (entry.rawVendor != null) w += 1
        return w
    }

    /** 从供应商名与 baseUrl 提取关键词，用于跨供应商消歧 */
    fun providerHints(name: String?, baseUrl: String?): List<String> {
        val hints = mutableListOf<String>()
        baseUrl?.let { url ->
            val host = url.substringAfter("://", url).substringBefore('/').substringBefore(':').lowercase()
            val labels = host.split('.')
            val core = if (labels.size > 1) labels.dropLast(1) else labels
            core.forEach { label ->
                if (label.length >= 3 && label !in STOP_WORDS) hints += label
            }
        }
        name?.let { providerName ->
            providerName.lowercase()
                .split(Regex("[^a-z0-9]+"))
                .filter { it.length >= 3 && it !in STOP_WORDS }
                .forEach { if (it !in hints) hints += it }
        }
        return hints
    }

    private const val MAX_CONTEXT = 100_000_000L

    // 第一方供应商：同名模型的官方条目优先
    private val FIRST_PARTY_PROVIDERS = setOf(
        "anthropic", "openai", "google", "deepseek", "moonshotai", "moonshotai-cn",
        "zai", "zhipuai", "xai", "alibaba", "alibaba-cn", "minimax", "minimax-cn",
        "meta", "mistral", "cohere", "qwen", "google-vertex", "google-vertex-anthropic",
    )

    private val STOP_WORDS = setOf(
        "api", "www", "com", "net", "org", "cn", "io", "co", "ai", "v1", "v1beta", "v2",
        "https", "http", "localhost", "generativelanguage", "googleapis", "openai-compatible",
    )
}
