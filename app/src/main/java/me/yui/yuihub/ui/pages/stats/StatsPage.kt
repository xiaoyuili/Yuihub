package me.yui.yuihub.ui.pages.stats

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.hugeicons.stroke.TransactionHistory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.yui.yuihub.R
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.theme.CustomColors
import me.yui.yuihub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun StatsPage(
    chatId: String?,
    vm: StatsVM = koinViewModel(parameters = { parametersOf(chatId) }),
) {
    val stats by vm.stats.collectAsStateWithLifecycle()

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.stats_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        if (stats.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding + PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    StatsCard(
                        title = stringResource(R.string.stats_page_current_conversation),
                        icon = HugeIcons.AiBrain01,
                    ) {
                        StatGrid(
                            items = listOf(
                                stringResource(R.string.stats_page_input_tokens) to formatTokens(stats.currentPromptTokens),
                                stringResource(R.string.stats_page_output_tokens) to formatTokens(stats.currentCompletionTokens),
                                stringResource(R.string.stats_page_cached_input_tokens) to formatTokens(stats.currentCachedTokens),
                                stringResource(R.string.stats_page_cache_rate) to formatCacheRate(
                                    stats.currentCachedTokens,
                                    stats.currentPromptTokens
                                ),
                                stringResource(R.string.stats_page_message_count) to formatCount(stats.currentMessageCount.toLong()),
                                stringResource(R.string.stats_page_model_calls) to formatCount(stats.currentModelCalls.toLong()),
                            )
                        )
                    }
                }

                item {
                    StatsCard(
                        title = stringResource(R.string.stats_page_history_token_stats),
                        icon = HugeIcons.TransactionHistory,
                    ) {
                        StatGrid(
                            items = listOf(
                                stringResource(R.string.stats_page_input_tokens) to formatTokens(stats.totalPromptTokens),
                                stringResource(R.string.stats_page_output_tokens) to formatTokens(stats.totalCompletionTokens),
                                stringResource(R.string.stats_page_cached_input_tokens) to formatTokens(stats.totalCachedTokens),
                                stringResource(R.string.stats_page_total_conversations) to formatCount(stats.totalConversations.toLong()),
                                stringResource(R.string.stats_page_total_model_calls) to formatCount(stats.totalModelCalls.toLong()),
                            )
                        )
                    }
                }

                item {
                    StatsCard(
                        title = stringResource(R.string.stats_page_daily_token_usage),
                        icon = HugeIcons.ChartColumn,
                    ) {
                        DailyTokenBars(tokensPerDay = stats.tokensPerDay)
                    }
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CustomColors.cardColorsOnSurfaceContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = HugeIcons.Rocket01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.stats_page_launch_count),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = formatCount(stats.launchCount.toLong()),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            content()
        }
    }
}

@Composable
private fun StatGrid(items: List<Pair<String, String>>) {
    Column {
        items.chunked(2).forEachIndexed { index, rowItems ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowItems.forEach { (label, value) ->
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if (rowItems.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

private data class TokenBucket(
    val start: LocalDate,
    val end: LocalDate,
    val tokens: Long,
)

// 近 7 天逐日柱状，避免 30 天图把高峰挤到角落
private fun tokenBuckets(
    tokensPerDay: Map<LocalDate, Long>,
    today: LocalDate = LocalDate.now(),
): List<TokenBucket> {
    val days = 7
    return (0 until days).map { index ->
        val day = today.minusDays((days - 1 - index).toLong())
        TokenBucket(day, day, tokensPerDay[day] ?: 0L)
    }
}

@Composable
private fun DailyTokenBars(tokensPerDay: Map<LocalDate, Long>) {
    val buckets = tokenBuckets(tokensPerDay)
    val maxValue = buckets.maxOf { it.tokens }.coerceAtLeast(1L)
    val chartHeight = 96.dp
    val dateFormat = DateTimeFormatter.ofPattern("MM/dd")
    val primary = MaterialTheme.colorScheme.primary
    val emptyBar = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(
                modifier = Modifier
                    .width(36.dp)
                    .height(chartHeight),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = formatAxisTokens(maxValue),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                )
                Text(
                    text = formatAxisTokens(maxValue / 2),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                )
                Text(
                    text = "0",
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                )
            }
            Spacer(Modifier.width(8.dp))
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                buckets.forEach { bucket ->
                    val fraction = bucket.tokens.toFloat() / maxValue.toFloat()
                    val barHeight = if (bucket.tokens > 0) {
                        (chartHeight * fraction).coerceAtLeast(8.dp)
                    } else {
                        6.dp
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = if (bucket.tokens > 0) formatTokens(bucket.tokens) else " ",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = if (bucket.tokens > 0) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                labelColor
                            },
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .widthIn(max = 28.dp)
                                .fillMaxWidth()
                                .height(barHeight)
                                .clip(RoundedCornerShape(7.dp))
                                .background(
                                    if (bucket.tokens > 0) {
                                        Brush.verticalGradient(
                                            listOf(primary, primary.copy(alpha = 0.45f))
                                        )
                                    } else {
                                        Brush.verticalGradient(listOf(emptyBar, emptyBar))
                                    }
                                ),
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 44.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            buckets.forEach { bucket ->
                Text(
                    text = bucket.end.format(dateFormat),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun formatCacheRate(cachedTokens: Long, promptTokens: Long): String {
    val base = cachedTokens + promptTokens
    if (base <= 0L) return "0.00%"
    val rate = cachedTokens.toDouble() / base.toDouble() * 100.0
    return "%.2f%%".format(rate)
}

private fun formatCount(count: Long): String = when {
    count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(count / 1_000.0)
    else -> count.toString()
}

private fun formatTokens(count: Long): String = when {
    count >= 1_000_000_000 -> "%.2fB".format(count / 1_000_000_000.0)
    count >= 1_000_000 -> "%.2fM".format(count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(count / 1_000.0)
    else -> count.toString()
}

private fun formatAxisTokens(count: Long): String {
    val m = count / 1_000_000.0
    return if (m >= 0.1) {
        "%.1fM".format(m)
    } else if (count >= 1_000) {
        "%.0fK".format(count / 1_000.0)
    } else {
        count.toString()
    }
}
