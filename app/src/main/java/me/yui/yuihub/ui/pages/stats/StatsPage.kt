package me.yui.yuihub.ui.pages.stats

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Rocket01
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
import kotlin.math.roundToLong

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
                    StatsCard(title = stringResource(R.string.stats_page_current_conversation)) {
                        Text(
                            text = stats.conversationTitle.ifBlank {
                                stringResource(R.string.stats_page_current_conversation)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
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
                    StatsCard(title = stringResource(R.string.stats_page_history_token_stats)) {
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
                    StatsCard(title = stringResource(R.string.stats_page_daily_token_usage)) {
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
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun StatGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                rowItems.forEach { (label, value) ->
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.titleSmall,
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

@Composable
private fun DailyTokenBars(tokensPerDay: Map<LocalDate, Long>) {
    val today = LocalDate.now()
    val days = (29 downTo 0).map { today.minusDays(it.toLong()) }
    val values = days.map { tokensPerDay[it] ?: 0L }
    val maxValue = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val chartHeight = 120.dp

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Column(
                modifier = Modifier
                    .width(46.dp)
                    .height(chartHeight),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = formatAxisTokens(maxValue),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatAxisTokens(maxValue / 2),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "0",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(6.dp))
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(chartHeight),
                verticalAlignment = Alignment.Bottom,
            ) {
                values.forEach { value ->
                    val fraction = value.toFloat() / maxValue.toFloat()
                    val barHeight = (chartHeight * fraction).coerceAtLeast(3.dp)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 1.5.dp)
                            .height(barHeight)
                            .clip(
                                RoundedCornerShape(
                                    topStart = 3.dp,
                                    topEnd = 3.dp,
                                    bottomStart = 0.dp,
                                    bottomEnd = 0.dp,
                                )
                            )
                            .background(
                                MaterialTheme.colorScheme.primary.copy(
                                    alpha = if (value > 0) 0.75f else 0.15f
                                )
                            ),
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 52.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = days.first().format(DateTimeFormatter.ofPattern("MM/dd")),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = days.last().format(DateTimeFormatter.ofPattern("MM/dd")),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
