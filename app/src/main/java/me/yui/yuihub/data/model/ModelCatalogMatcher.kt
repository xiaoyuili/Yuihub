package me.yui.yuihub.data.model

/**
 * models.dev 模型 ID 的归一化与相似度匹配（无 Android 依赖，便于 JVM 单测）。
 *
 * 目标：把用户填写的各种形态（供应商前缀、日期后缀、大小写、点号版本）
 * 映射到 models.dev 的规范化 key 上，并给出足够保守的匹配，
 * 避免把元数据填到不相干的模型上（填错比留空更糟）。
 */
object ModelCatalogMatcher {

    /**
     * 归一化模型 ID：
     * - 转小写，`_` 与 `.` 视为 `-`
     * - 去掉 `:free` 之类的冒号后缀
     * - 去掉多级路径前缀，只保留最后一段（anthropic/claude-x -> claude-x）
     */
    fun normalize(modelId: String): String {
        var s = modelId.trim().lowercase()
        if (s.isEmpty()) return ""
        s = s.replace('_', '-').replace('.', '-')
        // 先取路径最后一段（hf:moonshotai/Kimi-K3 -> kimi-k3），再去掉冒号后缀（llama:free -> llama）
        val lastSlash = s.lastIndexOf('/')
        if (lastSlash >= 0) {
            s = s.substring(lastSlash + 1)
        }
        s = s.substringBefore(':')
        return s.trim('-').replace(Regex("-+"), "-")
    }

    /** 去掉可重复的纯日期后缀（-20251001），用于「带日期」与「不带日期」互相命中 */
    fun deStem(key: String): String? {
        val cached = STEM_CACHE[key]
        if (cached != null) return cached.ifEmpty { null }
        var cur = key
        while (true) {
            val next = cur.replaceFirst(DATE_SUFFIX, "")
            if (next == cur) break
            cur = next
        }
        val result = if (cur != key) cur.trim('-').takeIf { it.isNotEmpty() } else null
        STEM_CACHE[key] = result ?: NO_STEM
        return result
    }

    fun tokenize(key: String): List<String> = TOKEN_CACHE.computeIfAbsent(key) {
        TOKEN_REGEX.findAll(key).map { it.value }.toList()
    }

    /**
     * 打分（null 表示不匹配）。从强到弱：
     * 1000 完全一致；850 去日期后一致；700 连字符边界的前缀；
     * 400 token 后缀关系（供应商段前缀，如 meta-llama-...）；
     * 250 token 子序列（间隔 <= 4 且 token 数 >= 3）。
     */
    fun score(queryKey: String, candidateKey: String): Int? {
        if (queryKey == candidateKey) return 1000

        val queryStem = deStem(queryKey)
        val candidateStem = deStem(candidateKey)
        if (queryStem != null && queryStem == candidateKey) return 850
        if (candidateStem != null && candidateStem == queryKey) return 850
        if (queryStem != null && candidateStem != null && queryStem == candidateStem) return 840

        if (queryKey.length >= 3 && candidateKey.length >= 3) {
            if (candidateKey.startsWith(queryKey) && candidateKey.length > queryKey.length &&
                candidateKey[queryKey.length] == '-'
            ) return 700
            if (queryKey.startsWith(candidateKey) && queryKey.length > candidateKey.length &&
                queryKey[candidateKey.length] == '-'
            ) return 700
        }

        val queryTokens = tokenize(queryKey)
        val candidateTokens = tokenize(candidateKey)
        if (candidateTokens.size >= 3 && queryTokens.size > candidateTokens.size &&
            queryTokens.takeLast(candidateTokens.size) == candidateTokens
        ) return 400
        if (queryTokens.size >= 3 && candidateTokens.size > queryTokens.size &&
            candidateTokens.takeLast(queryTokens.size) == queryTokens
        ) return 400

        if (queryTokens.size >= 3 && candidateTokens.size >= queryTokens.size &&
            candidateTokens.size - queryTokens.size <= SEQ_GAP && isSubsequence(queryTokens, candidateTokens)
        ) return 250
        if (candidateTokens.size >= 3 && queryTokens.size >= candidateTokens.size &&
            queryTokens.size - candidateTokens.size <= SEQ_GAP && isSubsequence(candidateTokens, queryTokens)
        ) return 250

        return null
    }

    private fun isSubsequence(needle: List<String>, haystack: List<String>): Boolean {
        var index = 0
        for (token in haystack) {
            if (index < needle.size && token == needle[index]) {
                index += 1
            }
        }
        return index == needle.size
    }

    private val DATE_SUFFIX = Regex("-(?:\\d{8})$")
    private val TOKEN_REGEX = Regex("[a-z]+|\\d+")
    private const val SEQ_GAP = 4

    // 批量填充（选择器全选可达数百个模型）会对同一批候选键重复调用 score，
    // 记忆化避免热路径上的重复分词与正则
    private val TOKEN_CACHE = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    private val STEM_CACHE = java.util.concurrent.ConcurrentHashMap<String, String>()
    private const val NO_STEM = ""
}
