package me.yui.yuihub.ui.pages.setting

import android.os.Build
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * 设置页搜索索引项：只承载可匹配的文本，[payload] 由调用方持有（通常是设置入口）。
 *
 * 匹配顺序即权重：标题 > 关键词 > 分组 > 描述 > 拼音（全拼 / 首字母）。
 * 拼音按需计算（lazy），首帧不做全量音译；音译结果按字符串缓存。
 */
internal class SettingsSearchItem<T>(
    val title: String,
    val description: String?,
    val keywords: List<String>,
    val group: String,
    val order: Int,
    val payload: T,
) {
    private val titleLower = title.lowercase()
    private val descriptionLower = description.orEmpty().lowercase()
    private val keywordsLower = keywords.map { it.lowercase() }
    private val groupLower = group.lowercase()

    private val titlePinyin: String by lazy { pinyinCompact(title) }
    private val titlePinyinInitials: String by lazy { pinyinInitials(title) }
    private val keywordsPinyin: List<String> by lazy { keywords.map { pinyinCompact(it) } }

    internal fun matchToken(rawToken: String): TokenMatch? {
        val token = rawToken.lowercase()
        if (token.isBlank()) return null

        if (titleLower == token) return TokenMatch(100)
        if (titleLower.startsWith(token)) return TokenMatch(92)
        if (titleLower.contains(token)) return TokenMatch(84)

        keywordsLower.firstOrNull { it == token }?.let { return TokenMatch(72, it) }
        keywordsLower.firstOrNull { it.startsWith(token) }?.let { return TokenMatch(62, it) }
        keywordsLower.firstOrNull { it.contains(token) }?.let { return TokenMatch(52, it) }

        if (groupLower.contains(token)) return TokenMatch(40)
        if (descriptionLower.contains(token)) return TokenMatch(30)

        if (titlePinyinInitials.isNotEmpty() && titlePinyinInitials.startsWith(token)) {
            return TokenMatch(46)
        }
        if (titlePinyin.isNotEmpty()) {
            if (titlePinyin.startsWith(token)) return TokenMatch(48)
            if (titlePinyin.contains(token)) return TokenMatch(44)
        }
        keywordsPinyin.firstOrNull { it.isNotEmpty() && it.startsWith(token) }
            ?.let { return TokenMatch(26) }
        keywordsPinyin.firstOrNull { it.isNotEmpty() && it.contains(token) }
            ?.let { return TokenMatch(22) }

        return null
    }
}

internal class TokenMatch(val score: Int, val keyword: String? = null)

internal data class SettingsSearchHit<T>(
    val item: SettingsSearchItem<T>,
    val score: Int,
    /** 命中来自关键词时给出原词，用于结果里显示「匹配：xxx」 */
    val keyword: String?,
)

/**
 * 多关键词 AND 搜索：空格分隔的每个词都要命中（标题/关键词/分组/描述/拼音），
 * 累加得分后从高到低排序，同分保持索引里的原始顺序（人工排布的优先级）。
 */
internal fun <T> searchSettings(
    items: List<SettingsSearchItem<T>>,
    query: String,
): List<SettingsSearchHit<T>> {
    val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (tokens.isEmpty()) return emptyList()

    return items.mapNotNull { item ->
        var score = 0
        var keyword: String? = null
        for (token in tokens) {
            val hit = item.matchToken(token) ?: return@mapNotNull null
            score += hit.score
            if (hit.keyword != null) keyword = hit.keyword
        }
        SettingsSearchHit(item = item, score = score, keyword = keyword)
    }.sortedWith(
        compareByDescending<SettingsSearchHit<T>> { it.score }
            .thenBy { it.item.order }
    )
}

/** 把命中的关键词在标题里高亮（只高亮直接子串命中，拼音命中不高亮） */
internal fun highlightSettingsText(
    text: String,
    tokens: List<String>,
    style: SpanStyle,
): AnnotatedString {
    if (tokens.isEmpty() || text.isEmpty()) return AnnotatedString(text)
    val lower = text.lowercase()
    val ranges = mutableListOf<IntRange>()
    tokens.forEach { token ->
        if (token.isBlank()) return@forEach
        var start = lower.indexOf(token)
        while (start >= 0) {
            ranges += start until (start + token.length)
            val next = start + token.length
            start = if (next >= lower.length) -1 else lower.indexOf(token, next)
        }
    }
    if (ranges.isEmpty()) return AnnotatedString(text)

    val merged = ranges.sortedBy { it.first }.fold(mutableListOf<IntRange>()) { acc, range ->
        val last = acc.lastOrNull()
        if (last != null && range.first <= last.last + 1) {
            acc[acc.lastIndex] = last.first..maxOf(last.last, range.last)
        } else {
            acc += range
        }
        acc
    }

    return buildAnnotatedString {
        append(text)
        merged.forEach { range ->
            addStyle(style, range.first, minOf(range.last + 1, text.length))
        }
    }
}

private val TONE_MARKS = Regex("\\p{Mn}+")
private val NON_WORD = Regex("[^a-z0-9\\s]+")
private val pinyinCache = ConcurrentHashMap<String, String>()

/**
 * 汉字转无声调拼音（"供应商" -> "gong ying shang"）。
 * android.icu 的 Han-Latin 需要 API 29；更老的设备返回空串，退化为标题/关键词匹配。
 */
private fun transliterate(text: String): String {
    if (text.isBlank() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ""
    return pinyinCache.getOrPut(text) {
        runCatching {
            val latin = android.icu.text.Transliterator.getInstance("Han-Latin").transliterate(text)
            Normalizer.normalize(latin, Normalizer.Form.NFD)
                .replace(TONE_MARKS, "")
                .lowercase()
                .replace(NON_WORD, " ")
                .trim()
        }.getOrDefault("")
    }
}

internal fun pinyinCompact(text: String): String = transliterate(text).replace(" ", "")

internal fun pinyinInitials(text: String): String =
    transliterate(text)
        .split(' ')
        .filter { it.isNotBlank() }
        .map { it.first() }
        .joinToString("")
