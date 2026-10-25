package me.yui.yuihub.ui.pages.stats

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ChartLineData01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.TransactionHistory
import me.yui.yuihub.R
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.theme.CustomColors
import me.yui.yuihub.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

@Composable
fun StatsPage(vm: StatsVM = koinViewModel()) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    val inputColor = MaterialTheme.colorScheme.primary
    val outputColor = MaterialTheme.colorScheme.tertiary
    val cacheColor = MaterialTheme.colorScheme.secondary

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_page_history_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = vm::refresh, enabled = !stats.isLoading) {
                        Icon(
                            imageVector = HugeIcons.Refresh01,
                            contentDescription = stringResource(R.string.stats_page_refresh),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        if (stats.isLoading && stats.totals.requests == 0 && stats.daily.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding + PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (stats.isLoading) {
                    item {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                item {
                    HistorySummaryCard(stats = stats)
                }
                item {
                    FilterCard(
                        stats = stats,
                        onProvider = vm::setProvider,
                        onModel = vm::setModel,
                        onRange = vm::setRange,
                    )
                }
                item {
                    TrendCard(
                        stats = stats,
                        inputColor = inputColor,
                        outputColor = outputColor,
                        cacheColor = cacheColor,
                    )
                }
                item {
                    BreakdownCard(
                        stats = stats,
                        inputColor = inputColor,
                        outputColor = outputColor,
                        cacheColor = cacheColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun HistorySummaryCard(stats: AppStats) {
    val totals = stats.totals
    val total = totals.totalTokens
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = HugeIcons.TransactionHistory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.stats_page_filtered_total),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = formatTokens(total),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(
                    R.string.stats_page_history_caption,
                    formatTokens(stats.lifetimeTotals.totalTokens),
                    formatCount(totals.requests.toLong()),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryMetric(
                    label = stringResource(R.string.stats_page_input_tokens),
                    value = formatTokens(totals.promptTokens),
                    modifier = Modifier.weight(1f),
                )
                SummaryMetric(
                    label = stringResource(R.string.stats_page_output_tokens),
                    value = formatTokens(totals.completionTokens),
                    modifier = Modifier.weight(1f),
                )
                SummaryMetric(
                    label = stringResource(R.string.stats_page_cached_input_tokens),
                    value = formatTokens(totals.cachedTokens),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryMetric(
                    label = stringResource(R.string.stats_page_total_model_calls),
                    value = formatCount(totals.requests.toLong()),
                    modifier = Modifier.weight(1f),
                )
                SummaryMetric(
                    label = stringResource(R.string.stats_page_total_conversations),
                    value = formatCount(totals.conversations.toLong()),
                    modifier = Modifier.weight(1f),
                )
                SummaryMetric(
                    label = stringResource(R.string.stats_page_cache_rate),
                    value = formatCacheRate(totals.cachedTokens, totals.promptTokens),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SummaryMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun FilterCard(
    stats: AppStats,
    onProvider: (String?) -> Unit,
    onModel: (String?) -> Unit,
    onRange: (StatsRange) -> Unit,
) {
    val selectedProvider = stats.providers.find { it.id == stats.providerId }
    val availableModels = stats.models.filter {
        stats.providerId == null || it.providerId == stats.providerId
    }
    val selectedModel = availableModels.find { it.id == stats.modelId }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterDropdown(
                    label = selectedProvider?.name ?: stringResource(R.string.stats_page_all_providers),
                    allLabel = stringResource(R.string.stats_page_all_providers),
                    options = stats.providers.map { it.id to it.name },
                    selectedId = stats.providerId,
                    onSelect = onProvider,
                )
                FilterDropdown(
                    label = selectedModel?.name ?: stringResource(R.string.stats_page_all_models),
                    allLabel = stringResource(R.string.stats_page_all_models),
                    options = availableModels.map { it.id to it.name },
                    selectedId = stats.modelId,
                    onSelect = onModel,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatsRange.entries.forEach { range ->
                    FilterChip(
                        selected = stats.range == range,
                        onClick = { onRange(range) },
                        label = { Text(rangeLabel(range)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterDropdown(
    label: String,
    allLabel: String,
    options: List<Pair<String, String>>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selectedId != null,
            onClick = { expanded = true },
            label = {
                Text(
                    text = label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp),
                )
            },
            trailingIcon = {
                Icon(
                    imageVector = HugeIcons.ArrowDown01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(allLabel) },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSelect(id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun TrendCard(
    stats: AppStats,
    inputColor: Color,
    outputColor: Color,
    cacheColor: Color,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = HugeIcons.ChartLineData01,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.stats_page_token_trend),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = rangeLabel(stats.range),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (stats.daily.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.stats_page_no_data),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                TokenLineChart(
                    points = stats.daily,
                    inputColor = inputColor,
                    outputColor = outputColor,
                    cacheColor = cacheColor,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LegendDot(stringResource(R.string.stats_page_input_tokens), inputColor)
                    LegendDot(stringResource(R.string.stats_page_output_tokens), outputColor)
                    LegendDot(stringResource(R.string.stats_page_cached_input_tokens), cacheColor)
                }
            }
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TokenLineChart(
    points: List<StatsDailyPoint>,
    inputColor: Color,
    outputColor: Color,
    cacheColor: Color,
) {
    var selectedIndex by remember(points) { mutableStateOf<Int?>(null) }
    LaunchedEffect(points) { selectedIndex = null }

    val maxValue = maxOf(
        points.maxOf { it.inputTokens },
        points.maxOf { it.outputTokens },
        points.maxOf { it.cachedTokens },
        2L,
    )
    val cacheIsDominant = points.any { it.cachedTokens > 0 }
    val areaValues = if (cacheIsDominant) {
        points.map { it.cachedTokens }
    } else {
        points.map { it.inputTokens }
    }
    val areaColor = if (cacheIsDominant) cacheColor else inputColor
    val selected = selectedIndex?.let { points.getOrNull(it) }
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val density = LocalDensity.current
    val labelTextSize = with(density) { 10.sp.toPx() }
    val labelPaint = remember(labelColor, labelTextSize) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor.toArgb()
            textSize = labelTextSize
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
    }
    val chartHeight = 232.dp
    val leftMargin = with(density) {
        (labelPaint.measureText(formatAxisTokens(maxValue)) + 10.dp.toPx()).toDp()
    }.coerceIn(40.dp, 64.dp)
    val rightMargin = 4.dp
    val topMargin = 8.dp
    val bottomMargin = 30.dp

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (selected != null) {
            Text(
                text = buildString {
                    append(selected.label)
                    append("   ")
                    append(stringResource(R.string.stats_page_input_tokens))
                    append(' ')
                    append(formatTokens(selected.inputTokens))
                    append("   ")
                    append(stringResource(R.string.stats_page_output_tokens))
                    append(' ')
                    append(formatTokens(selected.outputTokens))
                    append("   ")
                    append(stringResource(R.string.stats_page_cached_input_tokens))
                    append(' ')
                    append(formatTokens(selected.cachedTokens))
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Text(
                text = stringResource(R.string.stats_page_chart_hint),
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
            )
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val availableWidth = (maxWidth - leftMargin - rightMargin).coerceAtLeast(1.dp)
            val maxTicks = (availableWidth.value / 42f).toInt().coerceIn(3, 6)
            val tickIndices = remember(points.size, maxTicks) { xTickIndices(points.size, maxTicks) }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(chartHeight)
                    .pointerInput(points, leftMargin) {
                        detectTapGestures { offset ->
                            if (points.isEmpty()) return@detectTapGestures
                            val left = leftMargin.toPx()
                            val right = size.width - rightMargin.toPx()
                            if (offset.x < left || offset.x > right) return@detectTapGestures
                            val ratio = if (points.size == 1) {
                                0f
                            } else {
                                ((offset.x - left) / (right - left)).coerceIn(0f, 1f)
                            }
                            selectedIndex = (ratio * (points.size - 1)).roundToInt()
                                .coerceIn(0, points.lastIndex)
                        }
                    },
            ) {
            val left = leftMargin.toPx()
            val right = size.width - rightMargin.toPx()
            val top = topMargin.toPx()
            val bottom = size.height - bottomMargin.toPx()
            val plotWidth = (right - left).coerceAtLeast(1f)
            val plotHeight = (bottom - top).coerceAtLeast(1f)
            val dash = PathEffect.dashPathEffect(
                intervals = floatArrayOf(4.dp.toPx(), 4.dp.toPx()),
                phase = 0f,
            )
            fun x(index: Int): Float =
                if (points.size == 1) left + plotWidth / 2
                else left + plotWidth * index / (points.size - 1)

            fun y(value: Long): Float =
                bottom - plotHeight * (value.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)

            // Horizontal and vertical dashed grid, matching the selected range ticks.
            listOf(0f, 0.5f, 1f).forEach { fraction ->
                val lineY = bottom - plotHeight * fraction
                drawLine(
                    color = gridColor,
                    start = Offset(left, lineY),
                    end = Offset(right, lineY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dash,
                )
            }
            tickIndices.forEach { index ->
                val lineX = x(index)
                drawLine(
                    color = gridColor,
                    start = Offset(lineX, top),
                    end = Offset(lineX, bottom),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dash,
                )
            }
            // Solid axes.
            drawLine(
                color = axisColor,
                start = Offset(left, top),
                end = Offset(left, bottom),
                strokeWidth = 1.dp.toPx(),
            )
            drawLine(
                color = axisColor,
                start = Offset(left, bottom),
                end = Offset(right, bottom),
                strokeWidth = 1.dp.toPx(),
            )

            selectedIndex?.let { index ->
                drawLine(
                    color = selectionColor,
                    start = Offset(x(index), top),
                    end = Offset(x(index), bottom),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            if (points.size > 1 && areaValues.any { it > 0 }) {
                val area = Path().apply {
                    areaValues.forEachIndexed { index, value ->
                        if (index == 0) moveTo(x(index), y(value))
                        else lineTo(x(index), y(value))
                    }
                    lineTo(x(points.lastIndex), bottom)
                    lineTo(x(0), bottom)
                    close()
                }
                drawPath(path = area, color = areaColor.copy(alpha = 0.12f))
            }

            fun drawSeries(values: List<Long>, color: Color) {
                if (values.isEmpty()) return
                val line = Path()
                values.forEachIndexed { index, value ->
                    if (index == 0) line.moveTo(x(index), y(value))
                    else line.lineTo(x(index), y(value))
                }
                drawPath(
                    path = line,
                    color = color,
                    style = Stroke(
                        width = 2.2.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
                if (values.size == 1) {
                    drawCircle(
                        color = color,
                        radius = 3.5.dp.toPx(),
                        center = Offset(x(0), y(values.first())),
                    )
                }
                selectedIndex?.let { index ->
                    val point = values.getOrNull(index) ?: return
                    drawCircle(
                        color = color,
                        radius = 3.5.dp.toPx(),
                        center = Offset(x(index), y(point)),
                    )
                }
            }

            drawSeries(points.map { it.inputTokens }, inputColor)
            drawSeries(points.map { it.outputTokens }, outputColor)
            drawSeries(points.map { it.cachedTokens }, cacheColor)

            val xLabelBaseline = size.height - 7.dp.toPx()
            tickIndices.forEach { index ->
                labelPaint.textAlign = when {
                    index == 0 -> Paint.Align.LEFT
                    index == points.lastIndex -> Paint.Align.RIGHT
                    else -> Paint.Align.CENTER
                }
                val drawX = when (labelPaint.textAlign) {
                    Paint.Align.LEFT -> left
                    Paint.Align.RIGHT -> right
                    else -> x(index)
                }
                drawContext.canvas.nativeCanvas.drawText(
                    points[index].label,
                    drawX,
                    xLabelBaseline,
                    labelPaint,
                )
            }

            labelPaint.textAlign = Paint.Align.RIGHT
            listOf(0f, 0.5f, 1f).forEach { fraction ->
                val value = (maxValue * fraction).toLong()
                val lineY = bottom - plotHeight * fraction
                val baseline = (lineY + labelTextSize / 3f)
                    .coerceIn(top + labelTextSize, bottom)
                drawContext.canvas.nativeCanvas.drawText(
                    formatAxisTokens(value),
                    left - 6.dp.toPx(),
                    baseline,
                    labelPaint,
                )
            }
            }
        }
    }
}

@Composable
private fun BreakdownCard(
    stats: AppStats,
    inputColor: Color,
    outputColor: Color,
    cacheColor: Color,
) {
    val totals = stats.totals
    val total = totals.totalTokens.coerceAtLeast(1)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.stats_page_breakdown),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            BreakdownRow(
                label = stringResource(R.string.stats_page_input_tokens),
                value = totals.promptTokens,
                total = total,
                color = inputColor,
            )
            BreakdownRow(
                label = stringResource(R.string.stats_page_output_tokens),
                value = totals.completionTokens,
                total = total,
                color = outputColor,
            )
            BreakdownRow(
                label = stringResource(R.string.stats_page_cached_input_tokens),
                value = totals.cachedTokens,
                total = total,
                color = cacheColor,
            )
            val compression = stats.sources.firstOrNull { it.source == "compress" }
            if (compression != null && compression.requests > 0) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.stats_page_compression_cost),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(
                            R.string.stats_page_compression_cost_value,
                            formatTokens(compression.totalTokens),
                            compression.requests,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BreakdownRow(
    label: String,
    value: Long,
    total: Long,
    color: Color,
) {
    val fraction = (value.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${formatTokens(value)}  ${"%.1f%%".format(fraction * 100)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
        }
    }
}

@Composable
private fun rangeLabel(range: StatsRange): String = when (range) {
    StatsRange.TODAY -> stringResource(R.string.stats_page_range_today)
    StatsRange.LAST_7 -> stringResource(R.string.stats_page_range_7d)
    StatsRange.LAST_30 -> stringResource(R.string.stats_page_range_30d)
    StatsRange.LAST_90 -> stringResource(R.string.stats_page_range_90d)
    StatsRange.ALL -> stringResource(R.string.stats_page_range_all)
}

private fun xTickIndices(size: Int, maxTicks: Int): List<Int> {
    if (size <= 1) return listOf(0)
    if (size <= maxTicks) return (0 until size).toList()
    if (size == 24) return listOf(0, 6, 12, 18, 23)
    val ticks = maxTicks.coerceAtLeast(3)
    return (0 until ticks)
        .map { it * (size - 1) / (ticks - 1) }
        .distinct()
}

private fun formatCacheRate(cachedTokens: Long, promptTokens: Long): String {
    if (promptTokens <= 0L) return "0.00%"
    return "%.2f%%".format((cachedTokens.toDouble() / promptTokens.toDouble() * 100.0).coerceAtMost(100.0))
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
