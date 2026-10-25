package me.yui.yuihub.data.ai.memory

import me.yui.yuihub.data.model.AssistantMemory
import java.text.Normalizer
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * 本地记忆文本工具：语言无关的 CJK bigram 分词、关键词提取、相似度与召回打分。
 * 全部是纯函数，便于 JVM 单元测试；不依赖 Android。
 */
object MemoryText {
    private const val RECENCY_HALF_LIFE_DAYS = 45.0

    private val EN_STOPWORDS = setOf(
        "a", "an", "the", "and", "or", "but", "if", "then", "so", "of", "to", "in", "on",
        "at", "for", "from", "by", "with", "as", "is", "are", "was", "were", "be", "been",
        "being", "it", "its", "this", "that", "these", "those", "i", "you", "he", "she",
        "we", "they", "me", "my", "your", "our", "their", "do", "does", "did", "not",
        "no", "yes", "can", "could", "should", "would", "will", "just", "about", "into",
    )

    /** NFKC + 按 CJK / 字母数字分段；CJK 段取相邻 bigram，其他语言整词小写。 */
    fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val cps = normalized.codePoints().toArray()
        val out = ArrayList<String>(cps.size)
        var i = 0
        while (i < cps.size) {
            val cp = cps[i]
            when {
                isCjk(cp) -> {
                    var j = i + 1
                    while (j < cps.size && isCjk(cps[j])) j++
                    if (j - i >= 2) {
                        for (k in i until j - 1) {
                            out += String(Character.toChars(cps[k])) + String(Character.toChars(cps[k + 1]))
                        }
                    }
                    i = j
                }

                Character.isLetterOrDigit(cp) -> {
                    var j = i + 1
                    while (j < cps.size && Character.isLetterOrDigit(cps[j]) && !isCjk(cps[j])) j++
                    val word = String(cps, i, j - i).lowercase()
                    if (word !in EN_STOPWORDS) out += word
                    i = j
                }

                else -> i++
            }
        }
        return out
    }

    /** 从正文提取检索关键词：词频优先，过滤纯数字与单字噪声。 */
    fun extractKeywords(text: String, limit: Int = 8): List<String> {
        val counts = LinkedHashMap<String, Int>()
        for (token in tokenize(text)) {
            if (token.length < 2 || token.all { it.isDigit() }) continue
            counts[token] = (counts[token] ?: 0) + 1
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.key }
    }

    /** 关键词/内容的 Jaccard 相似度，用于写入去重与合并建议。 */
    fun similarity(a: String, b: String): Float {
        val sa = tokenize(a).toSet()
        val sb = tokenize(b).toSet()
        if (sa.isEmpty() || sb.isEmpty()) return 0f
        val inter = sa.count { it in sb }
        return inter.toFloat() / (sa.size + sb.size - inter).toFloat()
    }

    /** 召回排序：交集 IDF × 覆盖率 × 时效 × 权重 × 工作区/标题加成。
     * 返回按分数从高到低；分数低于本轮最高分 25% 的条目视为弱相关丢弃。
     */
    fun rankHits(
        query: String,
        candidates: List<AssistantMemory>,
        now: Long = System.currentTimeMillis(),
        workspaceId: String? = null,
        topK: Int = 2,
    ): List<AssistantMemory> {
        if (query.isBlank() || candidates.isEmpty()) return emptyList()
        val queryTokens = tokenize(query).toSet()
        if (queryTokens.isEmpty()) return emptyList()

        val docTokens = candidates.associateWith { memory ->
            val source = if (memory.keywords.isNotEmpty()) {
                memory.keywords.joinToString(" ") + " " + memory.title
            } else {
                memory.title + " " + memory.content
            }
            tokenize(source).toSet()
        }
        val docFreq = HashMap<String, Int>()
        docTokens.values.forEach { tokens ->
            tokens.forEach { docFreq[it] = (docFreq[it] ?: 0) + 1 }
        }
        val total = candidates.size

        fun idf(token: String): Double {
            val df = docFreq[token] ?: 0
            return ln(1.0 + (total - df + 0.5) / (df + 0.5))
        }

        val scored = candidates.mapNotNull { memory ->
            val tokens = docTokens.getValue(memory)
            if (tokens.isEmpty()) return@mapNotNull null
            val matched = queryTokens.count { it in tokens }
            if (matched == 0) return@mapNotNull null
            val raw = queryTokens.sumOf { if (it in tokens) idf(it) else 0.0 }
            val coverage = matched.toDouble() / max(1, tokens.size)
            val ageDays = ((now - (memory.updatedAt.takeIf { it > 0 } ?: memory.createdAt))
                .coerceAtLeast(0L)) / 86_400_000.0
            val recency = 0.5.pow(ageDays / RECENCY_HALF_LIFE_DAYS)
            val weightFactor = 1.0 + (memory.weight.coerceIn(1, 4) - 1) * 0.2
            val scopeBoost = if (workspaceId != null && memory.workspaceId == workspaceId) 1.25 else 1.0
            val titleHit = if (tokenize(memory.title).any { it in queryTokens }) 1.15 else 1.0
            val score = raw * coverage * (0.6 + 0.4 * recency) * weightFactor * scopeBoost * titleHit
            memory to score.toFloat()
        }.sortedByDescending { it.second }

        if (scored.isEmpty()) return emptyList()
        val floor = scored.first().second * 0.25f
        return scored.asSequence()
            .filter { it.second >= floor }
            .take(topK)
            .map { it.first }
            .toList()
    }

    private fun isCjk(cp: Int): Boolean =
        cp in 0x4E00..0x9FFF ||
            cp in 0x3400..0x4DBF ||
            cp in 0x3040..0x30FF ||
            cp in 0xF900..0xFAFF ||
            cp in 0x20000..0x2FA1F ||
            cp == 0x3005 ||
            cp == 0x30FC
}
